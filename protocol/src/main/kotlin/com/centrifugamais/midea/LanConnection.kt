package com.centrifugamais.midea

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * TCP connection to an appliance (normally port 6444). Blocking API: call from a
 * background thread. Decoded 0xAA frames are delivered to [onFrame].
 */
class LanConnection(
    private val ip: String,
    private val port: Int,
    private val deviceId: Long,
    private val protocol: Int,
    private val token: ByteArray?,
    private val key: ByteArray?,
) {
    private val security = LocalSecurity()
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var buffer = ByteArray(0)
    private val writeLock = Any()

    @Volatile var closed = false
        private set

    fun connect() {
        val s = Socket()
        s.connect(InetSocketAddress(ip, port), 8000)
        s.soTimeout = 10_000
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
        if (protocol == 3) authenticate()
        // Short timeout afterwards so the read loop stays responsive.
        s.soTimeout = 1000
    }

    private fun authenticate() {
        val t = token ?: throw MideaProtocolException("Esta máquina precisa de token/key")
        val k = key ?: throw MideaProtocolException("Esta máquina precisa de token/key")
        val out = output!!
        out.write(security.encode8370(t, LocalSecurity.MSGTYPE_HANDSHAKE_REQUEST))
        out.flush()
        val header = readExactly(8)
        val length = (header.u(2) shl 8) or header.u(3)
        if (length != 64 || header.u(5) != LocalSecurity.MSGTYPE_HANDSHAKE_RESPONSE) {
            throw MideaProtocolException("A máquina recusou o token/key")
        }
        val response = header + readExactly(length)
        val (packets, _) = security.decode8370(response)
        security.deriveTcpKey(packets.first(), k)
    }

    private fun readExactly(n: Int): ByteArray {
        val result = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input!!.read(result, off, n - off)
            if (r < 0) throw MideaProtocolException("Conexão fechada pela máquina")
            off += r
        }
        return result
    }

    fun sendFrame(aaFrame: ByteArray) = sendRaw(PacketBuilder.build(deviceId, aaFrame, security))

    fun sendHeartbeat() = sendRaw(PacketBuilder.heartbeat(deviceId, security))

    private fun sendRaw(packet: ByteArray) {
        synchronized(writeLock) {
            val data = if (protocol == 3) security.encode8370(packet, LocalSecurity.MSGTYPE_ENCRYPTED_REQUEST) else packet
            val out = output ?: throw MideaProtocolException("Não conectado")
            out.write(data)
            out.flush()
        }
    }

    /**
     * Reads once (up to the socket timeout) and returns any complete 0xAA frames.
     * Returns an empty list on timeout.
     */
    fun readFrames(): List<ByteArray> {
        val chunk = ByteArray(4096)
        val n = try {
            input!!.read(chunk)
        } catch (e: SocketTimeoutException) {
            return emptyList()
        }
        if (n < 0) throw MideaProtocolException("Conexão fechada pela máquina")
        buffer += chunk.copyOfRange(0, n)
        val messages: List<ByteArray>
        if (protocol == 3) {
            val (packets, rest) = security.decode8370(buffer)
            messages = packets
            buffer = rest
        } else {
            val (packets, rest) = splitV2(buffer)
            messages = packets
            buffer = rest
        }
        val frames = ArrayList<ByteArray>()
        for (m in messages) {
            if (m.contentEquals("ERROR".toByteArray())) throw MideaProtocolException("A máquina respondeu ERROR")
            if (m.size <= 56) continue
            val payloadType = m.u(2) + (m.u(3) shl 8)
            if (payloadType == 0x1001 || payloadType == 0x0001) continue
            val payloadLen = m.u(4) + (m.u(5) shl 8) - 56
            if (payloadLen % 16 != 0) continue
            val decrypted = security.aesDecrypt(m.copyOfRange(40, m.size - 16))
            if (decrypted.isNotEmpty()) frames.add(decrypted)
        }
        return frames
    }

    private fun splitV2(data: ByteArray): Pair<List<ByteArray>, ByteArray> {
        val result = ArrayList<ByteArray>()
        var rest = data
        while (rest.size >= 6) {
            val len = rest.u(4) + (rest.u(5) shl 8)
            if (len < 56) throw MideaProtocolException("Pacote inválido")
            if (rest.size < len) break
            result.add(rest.copyOfRange(0, len))
            rest = rest.copyOfRange(len, rest.size)
        }
        return result to rest
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
    }
}
