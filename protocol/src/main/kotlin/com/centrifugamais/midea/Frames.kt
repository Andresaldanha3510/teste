package com.centrifugamais.midea

import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Builds the 0x5A5A envelope that wraps every command sent to the appliance. */
object PacketBuilder {

    fun build(deviceId: Long, command: ByteArray, security: LocalSecurity, time: ByteArray = packetTime()): ByteArray {
        val packet = header(deviceId, time)
        val full = packet + security.aesEncrypt(command)
        return finalize(full, security)
    }

    fun heartbeat(deviceId: Long, security: LocalSecurity, time: ByteArray = packetTime()): ByteArray {
        val packet = header(deviceId, time)
        packet[3] = 0x10
        packet[6] = 0x7B
        return finalize(packet, security)
    }

    private fun header(deviceId: Long, time: ByteArray): ByteArray {
        val p = ByteArray(40)
        p[0] = 0x5A; p[1] = 0x5A; p[2] = 0x01; p[3] = 0x11; p[6] = 0x20
        time.copyInto(p, 12)
        for (i in 0 until 8) p[20 + i] = ((deviceId shr (8 * i)) and 0xFF).toByte()
        return p
    }

    private fun finalize(packet: ByteArray, security: LocalSecurity): ByteArray {
        val len = packet.size + 16
        packet[4] = (len and 0xFF).toByte()
        packet[5] = ((len shr 8) and 0xFF).toByte()
        return packet + security.encode32(packet)
    }

    /** "YYYYMMDDHHMMSSff" split into 8 decimal pairs, stored in reverse order. */
    fun packetTime(now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): ByteArray {
        val t = "%04d%02d%02d%02d%02d%02d%02d".format(
            now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second, now.nano / 10_000_000,
        )
        return ByteArray(8) { i -> t.substring(14 - 2 * i, 16 - 2 * i).toInt().toByte() }
    }
}

/** Midea "0xAA" serial frame: header + body + checksum. */
object AaFrame {
    const val TYPE_SET = 0x02
    const val TYPE_QUERY = 0x03
    const val TYPE_NOTIFY1 = 0x04
    const val TYPE_QUERY_APPLIANCE = 0xA0

    fun build(deviceType: Int, protocolVersion: Int, messageType: Int, body: ByteArray): ByteArray {
        val length = 10 + body.size
        val header = bytesOf(
            0xAA, minOf(length, 0xFF), deviceType, 0, 0, 0, 0, 0, protocolVersion, messageType,
        )
        val stream = header + body
        var sum = 0
        for (i in 1 until stream.size) sum += stream.u(i)
        return stream + byteArrayOf(((sum.inv() + 1) and 0xFF).toByte())
    }

    fun queryAppliance(deviceType: Int): ByteArray = build(deviceType, 0, TYPE_QUERY_APPLIANCE, ByteArray(19))

    data class Parsed(val deviceType: Int, val protocolVersion: Int, val messageType: Int, val body: ByteArray)

    fun parse(frame: ByteArray): Parsed? {
        if (frame.size < 12 || frame.u(0) != 0xAA) return null
        return Parsed(frame.u(2), frame.u(8), frame.u(9), frame.copyOfRange(10, frame.size - 1))
    }
}
