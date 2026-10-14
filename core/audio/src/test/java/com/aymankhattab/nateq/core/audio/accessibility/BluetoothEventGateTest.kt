package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ع٤١-ب9: حارس بوّابة تثبيط أحداث البلوتوث. */
class BluetoothEventGateTest {

    private val gate = BluetoothEventGate(debounceMs = 3_000L)

    @Test
    fun firstEventForDevice_announces() {
        assertTrue(gate.shouldAnnounce("AA:BB", connected = true, nowMs = 0L))
    }

    @Test
    fun repeatedSameStateWithinWindow_isSilenced() {
        assertTrue(gate.shouldAnnounce("AA:BB", true, 0L))
        assertFalse(gate.shouldAnnounce("AA:BB", true, 1_000L))
        assertFalse(gate.shouldAnnounce("AA:BB", true, 2_999L))
    }

    @Test
    fun quickDisconnectThenConnect_isMergedIntoSingle() {
        assertTrue(gate.shouldAnnounce("AA:BB", true, 0L))
        // فصل ثم اتصال سريعان (خلال ٣ ثوانٍ) ⇒ يُكبَت الثاني
        assertFalse(gate.shouldAnnounce("AA:BB", false, 1_000L))
        assertFalse(gate.shouldAnnounce("AA:BB", true, 2_000L))
    }

    @Test
    fun eventBeyondWindow_announces() {
        assertTrue(gate.shouldAnnounce("AA:BB", true, 0L))
        assertTrue(gate.shouldAnnounce("AA:BB", false, 3_000L))
    }

    @Test
    fun distinctDevices_areIndependent() {
        assertTrue(gate.shouldAnnounce("AA:BB", true, 0L))
        assertTrue(gate.shouldAnnounce("CC:DD", true, 100L))
    }

    @Test
    fun clear_resetsAllState() {
        assertTrue(gate.shouldAnnounce("AA:BB", true, 0L))
        gate.clear()
        assertTrue(gate.shouldAnnounce("AA:BB", true, 100L))
    }
}
