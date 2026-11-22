package com.centrifugamais.midea

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class DiscoveredDevice(
    val id: Long,
    val type: Int,
    val ip: String,
    val port: Int,
    val model: String,
    val serial: String,
    /** 2 = sem autenticação, 3 = precisa de token/key. */
    val protocol: Int,
)

/** UDP discovery on ports 6445 / 20086, as done by the official app. */
object Discovery {

    internal val BROADCAST_MSG = (
        "5a5a01114800920000000000000000000000000000000000000000000000000000000000" +
            "000000007f75bd6b3e4f8b762e849c6e578d6590036e9d4342a50f1f569eb8ec918e92e5"
        ).hexToBytes()

    fun discover(targets: List<String>, timeoutMs: Int = 5000): List<DiscoveredDevice> {
        val found = LinkedHashMap<Long, DiscoveredDevice>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (target in targets) {
                runCatching {
                    val addr = InetAddress.getByName(target)
                    socket.send(DatagramPacket(BROADCAST_MSG, BROADCAST_MSG.size, addr, 6445))
                    socket.send(DatagramPacket(BROADCAST_MSG, BROADCAST_MSG.size, addr, 20086))
                }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(1024)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                socket.soTimeout = remaining.toInt()
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val data = packet.data.copyOfRange(0, packet.length)
                val device = runCatching { parseReply(data, packet.address.hostAddress ?: "") }.getOrNull()
                if (device != null && device.id != 0L) found.putIfAbsent(device.id, device)
            }
        }
        return found.values.toList()
    }

    fun parseReply(raw: ByteArray, ip: String): DiscoveredDevice? {
        if (raw.size < 104) return null
        val protocol: Int
        val data: ByteArray
        if (raw.u(0) == 0x5A && raw.u(1) == 0x5A) {
            protocol = 2
            data = raw
        } else if (raw.u(0) == 0x83 && raw.u(1) == 0x70 && raw.u(8) == 0x5A && raw.u(9) == 0x5A) {
            protocol = 3
            data = raw.copyOfRange(8, raw.size - 16)
        } else {
            return null
        }
        var id = 0L
        for (i in 0 until 6) id = id or (data.u(20 + i).toLong() shl (8 * i))
        val reply = LocalSecurity().aesDecrypt(data.copyOfRange(40, data.size - 16))
        if (reply.size <= 41) return null
        val ssidLen = reply.u(40)
        if (41 + ssidLen > reply.size) return null
        val ssid = String(reply, 41, ssidLen, Charsets.US_ASCII)
        val type = ssid.split("_").getOrNull(1)?.toIntOrNull(16) ?: return null
        val port = reply.u(4) or (reply.u(5) shl 8) or (reply.u(6) shl 16) or (reply.u(7) shl 24)
        val serial = String(reply, 8, 32, Charsets.US_ASCII).trim('\u0000', ' ')
        val model = String(reply, 17, 8, Charsets.US_ASCII).trim('\u0000', ' ')
        return DiscoveredDevice(id, type, ip, port, model, serial, protocol)
    }
}
