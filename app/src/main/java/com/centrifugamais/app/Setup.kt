package com.centrifugamais.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.centrifugamais.midea.CloudAppliance
import com.centrifugamais.midea.Discovery
import com.centrifugamais.midea.DiscoveredDevice
import com.centrifugamais.midea.LanConnection
import com.centrifugamais.midea.SmartHomeCloud
import com.centrifugamais.midea.WasherModel
import com.centrifugamais.midea.hexToBytes
import java.net.Inet4Address

/** One-time setup: cloud login -> find washer -> fetch token/key -> find it on Wi-Fi -> test. */
object Setup {

    fun run(
        context: Context,
        email: String,
        password: String,
        manualIp: String?,
        progress: (String) -> Unit,
    ): SavedDevice {
        progress("Entrando na conta SmartHome…")
        val cloud = SmartHomeCloud(email, password)
        cloud.login()

        progress("Buscando seus aparelhos…")
        val appliances = cloud.listAppliances()
        val washer = pickWasher(appliances)
        progress("Encontrei: ${washer.name.ifEmpty { "Lavadora" }} (tipo 0x%02X). Buscando a chave de acesso…".format(washer.type))
        val tokens = cloud.getTokens(washer.id)

        progress("Procurando a lavadora no Wi-Fi…")
        val found = findOnLan(context, washer, manualIp)
        progress("Lavadora em ${found.ip}:${found.port} (protocolo V${found.protocol}). Testando a conexão…")

        if (found.protocol != 3) {
            testConnection(found, null, null)
            return found.toSaved(washer, "", "")
        }
        var lastError: Exception? = null
        for (tk in tokens) {
            try {
                testConnection(found, tk.token, tk.key)
                return found.toSaved(washer, tk.token, tk.key)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw Exception("A lavadora recusou as chaves da nuvem: ${lastError?.message}")
    }

    private fun pickWasher(appliances: List<CloudAppliance>): CloudAppliance {
        val washers = appliances.filter { WasherModel.forType(it.type) != null }
        if (washers.isEmpty()) {
            val types = appliances.joinToString { "${it.name} (0x%02X)".format(it.type) }
            throw Exception(
                if (appliances.isEmpty()) "Nenhum aparelho nesta conta. A lavadora foi compartilhada com ela?"
                else "Nenhuma lavadora compatível na conta. Aparelhos encontrados: $types",
            )
        }
        return washers.sortedByDescending { it.online }.first()
    }

    private fun findOnLan(context: Context, washer: CloudAppliance, manualIp: String?): DiscoveredDevice {
        val targets = if (!manualIp.isNullOrBlank()) listOf(manualIp.trim()) else broadcastTargets(context)
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("centrifuga-discovery").apply { setReferenceCounted(false) }
        val devices = try {
            lock.acquire()
            Discovery.discover(targets)
        } finally {
            runCatching { lock.release() }
        }
        devices.firstOrNull { it.id == washer.id }?.let { return it }
        devices.singleOrNull { it.type == washer.type }?.let { return it }
        if (!manualIp.isNullOrBlank()) {
            // The machine did not answer discovery; try the default port anyway.
            return DiscoveredDevice(washer.id, washer.type, manualIp.trim(), 6444, "", washer.serial, 3)
        }
        throw Exception(
            "Não achei a lavadora no Wi-Fi. Confira se o celular está na mesma rede que ela " +
                "(${devices.size} aparelho(s) Midea responderam). Você também pode digitar o IP dela.",
        )
    }

    fun testConnection(d: DiscoveredDevice, token: String?, key: String?) {
        val c = LanConnection(d.ip, d.port, d.id, d.protocol, token?.hexToBytes(), key?.hexToBytes())
        try {
            c.connect()
        } finally {
            c.close()
        }
    }

    private fun DiscoveredDevice.toSaved(w: CloudAppliance, token: String, key: String) =
        SavedDevice(id, w.name.ifEmpty { "Lavadora" }, type, ip, port, protocol, token, key)

    /** Subnet broadcast address of the Wi-Fi network, plus the global one. */
    fun broadcastTargets(context: Context): List<String> {
        val result = LinkedHashSet<String>()
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp = cm.getLinkProperties(network) ?: continue
                for (la in lp.linkAddresses) {
                    val addr = la.address as? Inet4Address ?: continue
                    val prefix = la.prefixLength
                    val ip = addr.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                    val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                    val bcast = (ip and mask) or (mask.inv() and 0xFFFFFFFFL)
                    result.add(listOf(24, 16, 8, 0).joinToString(".") { ((bcast shr it) and 0xFF).toString() })
                }
            }
        }
        result.add("255.255.255.255")
        return result.toList()
    }
}
