package com.centrifugamais.midea

import java.security.SecureRandom

class MideaProtocolException(message: String) : Exception(message)

/**
 * Encryption used on the local network (port 6444), ported from the open-source
 * `midea-local` library. Not thread-safe: one instance per connection.
 */
class LocalSecurity(private val random: (Int) -> ByteArray = ::secureRandomBytes) {

    private var tcpKey: ByteArray? = null
    private var requestCount = 0

    fun aesDecrypt(raw: ByteArray): ByteArray = try {
        Aes.ecbDecrypt(raw, AES_KEY)
    } catch (e: Exception) {
        ByteArray(0)
    }

    fun aesEncrypt(raw: ByteArray): ByteArray = Aes.ecbEncrypt(raw, AES_KEY)

    fun encode32(raw: ByteArray): ByteArray = Hash.md5(raw + SALT)

    /** Derives the session key from the handshake response (V3 devices). */
    fun deriveTcpKey(response: ByteArray, key: ByteArray): ByteArray {
        if (response.contentEquals("ERROR".toByteArray())) {
            throw MideaProtocolException("A máquina recusou o token/key (autenticação falhou)")
        }
        if (response.size != 64) throw MideaProtocolException("Resposta de autenticação com tamanho inesperado")
        val payload = response.copyOfRange(0, 32)
        val sign = response.copyOfRange(32, 64)
        val plain = Aes.cbcDecrypt(payload, key, ZERO_IV, padding = false)
        if (!Hash.sha256(plain).contentEquals(sign)) throw MideaProtocolException("Assinatura da autenticação não confere")
        val result = ByteArray(32) { (plain[it].toInt() xor key[it].toInt()).toByte() }
        tcpKey = result
        requestCount = 0
        return result
    }

    fun setTcpKeyForTest(key: ByteArray) {
        tcpKey = key
    }

    fun encode8370(input: ByteArray, msgType: Int): ByteArray {
        var data = input
        var size = data.size
        var padding = 0
        val encrypted = msgType == MSGTYPE_ENCRYPTED_RESPONSE || msgType == MSGTYPE_ENCRYPTED_REQUEST
        if (encrypted && (size + 2) % 16 != 0) {
            padding = 16 - ((size + 2) and 0xF)
            size += padding + 32
            data += random(padding)
        }
        val header = bytesOf(0x83, 0x70, (size shr 8) and 0xFF, size and 0xFF, 0x20, (padding shl 4) or msgType)
        data = bytesOf((requestCount shr 8) and 0xFF, requestCount and 0xFF) + data
        requestCount++
        if (requestCount >= 0xFFFF) requestCount = 0
        if (encrypted) {
            val key = tcpKey ?: throw MideaProtocolException("Sem chave de sessão")
            val sign = Hash.sha256(header + data)
            data = Aes.cbcEncrypt(data, key, ZERO_IV, padding = false) + sign
        }
        return header + data
    }

    /** Splits a buffer into complete 8370 packets. Returns the packets and leftover bytes. */
    fun decode8370(input: ByteArray): Pair<List<ByteArray>, ByteArray> {
        val packets = ArrayList<ByteArray>()
        var data = input
        while (true) {
            if (data.size < 6) return packets to data
            if (data.u(0) != 0x83 || data.u(1) != 0x70) throw MideaProtocolException("Pacote 8370 inválido")
            val size = ((data.u(2) shl 8) or data.u(3)) + 8
            if (data.size < size) return packets to data
            val leftover = data.copyOfRange(size, data.size)
            val packet = data.copyOfRange(0, size)
            if (packet.u(4) != 0x20) throw MideaProtocolException("Pacote 8370 sem byte 4")
            val header = packet.copyOfRange(0, 6)
            val padding = packet.u(5) shr 4
            val msgType = packet.u(5) and 0xF
            var body = packet.copyOfRange(6, packet.size)
            if (msgType == MSGTYPE_ENCRYPTED_RESPONSE || msgType == MSGTYPE_ENCRYPTED_REQUEST) {
                val key = tcpKey ?: throw MideaProtocolException("Sem chave de sessão")
                val sign = body.copyOfRange(body.size - 32, body.size)
                body = Aes.cbcDecrypt(body.copyOfRange(0, body.size - 32), key, ZERO_IV, padding = false)
                if (!Hash.sha256(header + body).contentEquals(sign)) {
                    throw MideaProtocolException("Assinatura do pacote não confere")
                }
                if (padding > 0) body = body.copyOfRange(0, body.size - padding)
            }
            packets.add(body.copyOfRange(2, body.size))
            data = leftover
        }
    }

    companion object {
        const val MSGTYPE_HANDSHAKE_REQUEST = 0x0
        const val MSGTYPE_HANDSHAKE_RESPONSE = 0x1
        const val MSGTYPE_ENCRYPTED_RESPONSE = 0x3
        const val MSGTYPE_ENCRYPTED_REQUEST = 0x6

        private val AES_KEY = "6a92ef406bad2f0359baad994171ea6d".hexToBytes()
        private val SALT = "78686469776a6e6368656b6434643531326368646a783564386534633339344432443753".hexToBytes()
        private val ZERO_IV = ByteArray(16)
        private val rng = SecureRandom()

        fun secureRandomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
    }
}
