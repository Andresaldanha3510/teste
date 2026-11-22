package com.centrifugamais.midea

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Vectors generated with the reference Python library (midea-local 15.2.1),
 * so the Kotlin port must match it byte for byte.
 */
class ProtocolVectorsTest {
    private val broadcast = "5a5a01114800920000000000000000000000000000000000000000000000000000000000000000007f75bd6b3e4f8b762e849c6e578d6590036e9d4342a50f1f569eb8ec918e92e5"
    private val tcp_key = "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
    private val p8370_in = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f60616263"
    private val p8370_enc = "8370008e20a6178073017f1c0d96a4edcb4812ab929d4c5f80df7944a3ce26b2e88d682ada9cb82e96bc1ad9a1de025a2d7796a84a52f3b067618ecf8626c6163de7ddda2efa5bff80cde6225d36b65462bd163842ac18b05252abe376790af52fa0972319d799880d19663a5972546a43be3613d87705b2dbd408d953b9df76ab3d882c19b9ce88527fb4d390e1a374dd7b3bdbc563"
    private val p8370_hs = "8370004020000000000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
    private val hs_key = "c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7"
    private val hs_resp = "4946f774c91ddd356784ea16a6d2f783cb7309fcc6195a3f432517e78e9f784ace43f3395c4c07e8448c723936759a5613f1dc518912267543ccac6e945ae75b"
    private val hs_tcp = "cfc1c3c1c7c1c3c1dfc1c3c1c7c1c3c1cfc1c3c1c7c1c3c1ffc1c3c1c7c1c3c1"
    private val packet = "5a5a01114800200000000000010203040506070879df0d8648700000000000000000000000000000b8119ae9856bf0e37e612f0307b24c06bd091585acac29cc26209bd66027a8c4"
    private val heartbeat = "5a5a011038007b0000000000010203040506070879df0d8648700000000000000000000000000000fa1b57a70d4da25cfe774c84daf4c76c"
    private val db_query = "aa0bdb000000000002030312"
    private val db_power = "aa20db000000000002020201ffffffffffffffffffffffffffffffffffffffff12"
    private val db_spin = "aa1adb0000000000020202ff010009ffffff06ff0cfffffffffff3"
    private val da_power = "aa0dda000000000001020201ff14"
    private val qa_db = "aa1ddb000000000000a00000000000000000000000000000000000000068"
    private val devid = "11c98d32126be3bf"
    private val udp1 = "88a5ee9342a97a1db62e5062cbeac387"
    private val udp2 = "56a721e8579c8078d1405c079c6758d4"
    private val pwd = "b91c256f01fe9e306fb1822d1d33ac10253a4d047c7a3260496c0fea68fbe639"
    private val iam = "4b0fdc256da059fc9e0d88739aa9a181dfbd66188e5a32fdfc20d043c7512b59"
    private val acct = "45fc1fa17a1af279d500e34611b7444853fc062c439d759702196933466a3f7b"
    private val sign = "c14bd371d3e09fafbcbac8900a25ee82bf2ce23c99259efe17a4251e6bec5aba"

    @Test fun broadcastMessage() = assertEquals(broadcast, Discovery.BROADCAST_MSG.toHex())

    @Test fun encode8370Encrypted() {
        val s = LocalSecurity { n -> ByteArray(n) { (it + 1).toByte() } }
        s.setTcpKeyForTest(tcp_key.hexToBytes())
        assertEquals(p8370_enc, s.encode8370(p8370_in.hexToBytes(), LocalSecurity.MSGTYPE_ENCRYPTED_REQUEST).toHex())
    }

    @Test fun decode8370RoundTrip() {
        val s = LocalSecurity()
        s.setTcpKeyForTest(tcp_key.hexToBytes())
        val (packets, rest) = s.decode8370((p8370_enc + p8370_hs).hexToBytes())
        assertEquals(2, packets.size)
        assertEquals(p8370_in, packets[0].toHex())
        assertEquals(0, rest.size)
    }

    @Test fun encode8370Handshake() {
        val s = LocalSecurity()
        assertEquals(p8370_hs, s.encode8370(ByteArray(64) { it.toByte() }, LocalSecurity.MSGTYPE_HANDSHAKE_REQUEST).toHex())
    }

    @Test fun handshakeKey() = assertEquals(hs_tcp, LocalSecurity().deriveTcpKey(hs_resp.hexToBytes(), hs_key.hexToBytes()).toHex())

    @Test fun packetBuilder() {
        val time = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        assertEquals(packet, PacketBuilder.build(123456789012345L, "aa0bdb000000000000030301".hexToBytes(), LocalSecurity(), time).toHex())
        assertEquals(heartbeat, PacketBuilder.heartbeat(123456789012345L, LocalSecurity(), time).toHex())
    }

    @Test fun frames() {
        assertEquals(db_query, AaFrame.build(0xDB, 2, AaFrame.TYPE_QUERY, FrontLoadWasher.queryBody()).toHex())
        assertEquals(db_power, AaFrame.build(0xDB, 2, AaFrame.TYPE_SET, FrontLoadWasher.powerBody(true)).toHex())
        assertEquals(da_power, AaFrame.build(0xDA, 1, AaFrame.TYPE_SET, TopLoadWasher.powerBody(true)).toHex())
        assertEquals(qa_db, AaFrame.queryAppliance(0xDB).toHex())
    }

    @Test fun frontLoadSpinOnly() {
        val status = FrontLoadWasher.parse(ByteArray(32).also { it[1] = 1 })!!
        assertEquals(db_spin, AaFrame.build(0xDB, 2, AaFrame.TYPE_SET, FrontLoadWasher.spinOnlyBody(status, 12, 6)).toHex())
    }

    @Test fun cloudHelpers() {
        assertEquals(devid, SmartHomeCloud.deviceIdFor("fulano@example.com"))
        assertEquals(udp1, SmartHomeCloud.udpId(152832116426735L, 1))
        assertEquals(udp2, SmartHomeCloud.udpId(152832116426735L, 2))
        assertEquals(pwd, SmartHomeCloud.encryptPassword("LOGINID123", "senha!23"))
        assertEquals(iam, SmartHomeCloud.encryptIamPassword("LOGINID123", "senha!23"))
        assertEquals(acct, SmartHomeCloud.encryptAccount("fulano@example.com"))
        assertEquals(sign, SmartHomeCloud.sign("{\"a\": 1}", "1700000000"))
    }

    @Test fun packetTimeFormat() {
        val t = PacketBuilder.packetTime(java.time.ZonedDateTime.of(2026, 11, 22, 19, 5, 9, 870_000_000, java.time.ZoneOffset.UTC))
        assertEquals(listOf(87, 9, 5, 19, 22, 11, 26, 20), t.map { it.toInt() })
    }

    @Test fun urlAlias() {
        assertEquals("https://x/mas/v5/app/proxy?alias=/v1/a", SmartHomeCloud.urlFor("https://x/mas/v5/app/proxy?alias=", "/v1/a"))
    }
}
