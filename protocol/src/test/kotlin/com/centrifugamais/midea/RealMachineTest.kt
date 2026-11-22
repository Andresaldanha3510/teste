package com.centrifugamais.midea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Status bodies captured from a real Midea front-load washer (0xDB). */
class RealMachineTest {

    private val standby = "0301010000052005030202000040000020b00001db35494c0000006e0a000000ffffff00ffffffffffffffffffffff01ffffffffffff0000ffff0dffff"
    private val spinning = "02010200090000000600020000400000010c0000db35494c000000000a000008ffffff09ffffffffffffffffffffff01ffffffffffff0000ffff0dffff"

    @Test fun standbyCotton() {
        val s = FrontLoadWasher.parse(standby.hexToBytes())!!
        assertEquals("Em espera", s.statusText)
        assertEquals("Algodão", s.programName)
        assertEquals(false, s.running)
        assertEquals(2, s.spinTimeValue)
        assertEquals(10, s.spinMinutes)
        assertEquals(110, s.washMinutes)
    }

    @Test fun spinOnlyRunning() {
        val s = FrontLoadWasher.parse(spinning.hexToBytes())!!
        assertTrue(s.running)
        assertEquals("Só centrifugar", s.programName)
        assertEquals("Centrifugando", s.stageName)
        assertEquals("1400 rpm", s.spinSpeedName)
        assertEquals(12, s.timeRemainingMin)
        assertEquals(10, s.spinMinutes)
    }

    @Test fun spinCommandKeepsAcceptedFields() {
        // On the real machine the frame with program 0x09 + speed 6 started a 1400 rpm spin.
        val status = FrontLoadWasher.parse(standby.hexToBytes())!!
        val frame = AaFrame.build(0xDB, 0, AaFrame.TYPE_SET, FrontLoadWasher.spinOnlyBody(status, 6)).toHex()
        assertEquals("aa1adb0000000000000202ff010009ffffff06ffffffffffffff02", frame)
    }
}
