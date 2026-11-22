package com.centrifugamais.midea

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

class CloudException(message: String) : Exception(message)

data class CloudAppliance(val id: Long, val name: String, val type: Int, val serial: String, val online: Boolean)

data class TokenKey(val token: String, val key: String)

/**
 * Minimal client for the Midea "SmartHome" (MSmartHome) cloud, used only to log
 * in, list the account's appliances and fetch the LAN token/key for one of them.
 * Ported from the open-source midea-local library.
 *
 * The HTTP transport is injectable so it can be tested offline.
 */
class SmartHomeCloud(
    private val account: String,
    private val password: String,
    private val http: (url: String, headers: Map<String, String>, body: String) -> String = ::httpPost,
) {
    private var apiUrl = DEFAULT_API_URL
    private val deviceId = deviceIdFor(account)
    private var uid: String? = null
    private var accessToken: String? = null
    private var dataKey: ByteArray? = null
    private var dataIv: ByteArray? = null

    private fun generalData(): JSONObject = JSONObject().apply {
        put("src", APP_ID)
        put("format", "2")
        put("stamp", stamp())
        put("platformId", "1")
        put("deviceId", deviceId)
        put("reqId", randomHex(16))
        put("uid", uid ?: JSONObject.NULL)
        put("clientType", "1")
        put("appId", APP_ID)
        put("language", "en_US")
    }

    private fun request(endpoint: String, data: JSONObject): JSONObject? {
        if (!data.has("reqId")) data.put("reqId", randomHex(16))
        if (!data.has("stamp")) data.put("stamp", stamp())
        val random = (System.currentTimeMillis() / 1000).toString()
        val body = data.toString()
        val headers = linkedMapOf(
            "content-type" to "application/json; charset=utf-8",
            "secretVersion" to "1",
            "sign" to sign(body, random),
            "random" to random,
            "x-recipe-app" to APP_ID,
            "authorization" to "Basic " + Base64.getEncoder().encodeToString("$APP_KEY:$IOT_KEY".toByteArray()),
        )
        uid?.let { headers["uid"] = it }
        accessToken?.let { headers["accessToken"] = it }
        val raw = http(urlFor(apiUrl, endpoint), headers, body)
        val response = JSONObject(raw)
        val code = response.optString("code", "-1")
        if (code == "0") return response.optJSONObject("data")
        lastError = "${response.optString("msg")} (código $code)"
        return null
    }

    var lastError: String = ""
        private set

    private fun reRoute() {
        val d1 = generalData().apply {
            put("clientType", 8)
            put("clientSrc", 8)
            put("userType", "0")
            put("userName", encryptAccount(account))
        }
        runCatching { request("/v1/unitcenter/router/user/name", d1) }.getOrNull()
            ?.optString("masUrl")?.takeIf { it.isNotEmpty() }?.let { apiUrl = it; return }
        val d2 = generalData().apply {
            put("userType", "0")
            put("userName", account)
        }
        runCatching { request("/v1/multicloud/platform/user/route", d2) }.getOrNull()
            ?.optString("masUrl")?.takeIf { it.isNotEmpty() }?.let { apiUrl = it }
    }

    fun login() {
        reRoute()
        val idData = generalData().apply { put("loginAccount", account) }
        val loginId = request("/v1/user/login/id/get", idData)?.optString("loginId")
            ?.takeIf { it.isNotEmpty() }
            ?: throw CloudException("Conta não encontrada no SmartHome: $lastError")
        val st = stamp()
        val iot = generalData().apply {
            remove("uid")
            put("iampwd", encryptIamPassword(loginId, password))
            put("loginAccount", account)
            put("password", encryptPassword(loginId, password))
            put("stamp", st)
        }
        val data = JSONObject().apply {
            put("iotData", iot)
            put("data", JSONObject().apply {
                put("appKey", APP_KEY)
                put("deviceId", deviceId)
                put("platform", "2")
            })
            put("stamp", st)
        }
        val r = request("/mj/user/login", data) ?: throw CloudException("Login recusado: $lastError")
        uid = r.getString("uid")
        accessToken = r.getJSONObject("mdata").getString("accessToken")
        val (tmpKey, tmpIv) = tempKeyIv()
        dataKey = String(Aes.cbcDecrypt(r.getString("accessToken").hexToBytes(), tmpKey, tmpIv, true)).toByteArray()
        dataIv = String(Aes.cbcDecrypt(r.getString("randomData").hexToBytes(), tmpKey, tmpIv, true)).toByteArray()
    }

    fun listAppliances(): List<CloudAppliance> {
        val r = request("/v1/appliance/user/list/get", generalData())
            ?: throw CloudException("Não consegui listar os aparelhos: $lastError")
        val list = r.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).map { i ->
            val a = list.getJSONObject(i)
            val encSn = a.optString("sn")
            val sn = runCatching {
                if (encSn.isEmpty() || dataKey == null) "" else String(Aes.cbcDecrypt(encSn.hexToBytes(), dataKey!!, dataIv!!, true))
            }.getOrDefault("")
            CloudAppliance(
                id = a.getString("id").toLong(),
                name = a.optString("name"),
                type = a.optString("type").removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: 0,
                serial = sn,
                online = a.optString("onlineStatus") == "1",
            )
        }
    }

    /** Returns the candidate token/key pairs (one per UDP-id method) for an appliance. */
    fun getTokens(applianceId: Long): List<TokenKey> {
        val result = ArrayList<TokenKey>()
        for (method in 1..2) {
            val udpId = udpId(applianceId, method)
            val data = generalData().apply {
                put("udpid", udpId)
                put("applianceCodes", applianceId.toString())
            }
            val r = request("/v1/iot/secure/getToken", data) ?: continue
            val tokens = r.optJSONArray("tokenlist") ?: continue
            for (i in 0 until tokens.length()) {
                val t = tokens.getJSONObject(i)
                if (t.optString("udpId") == udpId) {
                    result.add(TokenKey(t.getString("token").lowercase(), t.getString("key").lowercase()))
                }
            }
        }
        if (result.isEmpty()) throw CloudException("A nuvem não devolveu token/key: $lastError")
        return result.distinct()
    }

    companion object {
        const val APP_ID = "1010"
        const val APP_KEY = "ac21b9f9cbfe4ca5a88562ef25e2b768"
        const val IOT_KEY = "meicloud"
        const val HMAC_KEY = "PROD_VnoClJI9aikS8dyy"
        const val DEFAULT_API_URL = "https://mp-prod.appsmb.com/mas/v5/app/proxy?alias="

        private val rng = SecureRandom()
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        fun stamp(): String = ZonedDateTime.now(ZoneOffset.UTC).format(STAMP)

        fun randomHex(bytes: Int): String = ByteArray(bytes).also { rng.nextBytes(it) }.toHex()

        fun deviceIdFor(account: String): String = Hash.sha256Hex("Hello, $account!").substring(0, 16)

        fun sign(body: String, random: String): String = Hash.hmacSha256Hex(HMAC_KEY, IOT_KEY + body + random)

        fun encryptPassword(loginId: String, password: String): String =
            Hash.sha256Hex(loginId + Hash.sha256Hex(password) + APP_KEY)

        fun encryptIamPassword(loginId: String, password: String): String =
            Hash.sha256Hex(loginId + Hash.md5Hex(Hash.md5Hex(password)) + APP_KEY)

        private fun tempKeyIv(): Pair<ByteArray, ByteArray> {
            val digest = Hash.sha256Hex(APP_KEY)
            return digest.substring(0, 16).toByteArray() to digest.substring(16, 32).toByteArray()
        }

        fun encryptAccount(account: String): String {
            val (k, iv) = tempKeyIv()
            return Aes.cbcEncrypt(account.toByteArray(Charsets.UTF_8), k, iv, true).toHex()
        }

        fun udpId(applianceId: Long, method: Int): String {
            val idBytes = ByteArray(6)
            for (i in 0 until 6) {
                val b = ((applianceId shr (8 * i)) and 0xFF).toByte()
                if (method == 1) idBytes[5 - i] = b else idBytes[i] = b
            }
            val h = Hash.sha256(idBytes)
            return ByteArray(16) { (h[it].toInt() xor h[it + 16].toInt()).toByte() }.toHex()
        }

        /** Replaces/sets the `alias` query parameter, like yarl's update_query. */
        fun urlFor(base: String, endpoint: String): String {
            val q = base.indexOf('?')
            val path = if (q >= 0) base.substring(0, q) else base
            val params = if (q >= 0) base.substring(q + 1).split('&').filter { it.isNotEmpty() && !it.startsWith("alias=") } else emptyList()
            return path + "?" + (params + "alias=$endpoint").joinToString("&")
        }

        fun httpPost(url: String, headers: Map<String, String>, body: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                return stream?.bufferedReader()?.use { it.readText() }
                    ?: throw CloudException("Resposta vazia da nuvem (HTTP ${conn.responseCode})")
            } finally {
                conn.disconnect()
            }
        }
    }
}
