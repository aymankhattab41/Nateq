package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس ع٤١-ب8: كاشف أوامر المكالمة بالمفاتيح وتنفيذها.
 */
class CallKeyDetectorTest {

    private val detector = CallKeyDetector(longPressMs = 700L)

    @Test
    fun down_outsideRinging_isNotConsumed() {
        assertFalse(detector.onDown(VolumeKey.UP, ringing = false, 0L))
    }

    @Test
    fun down_whileRinging_isConsumed() {
        assertTrue(detector.onDown(VolumeKey.UP, ringing = true, 0L))
    }

    @Test
    fun longPress_whileRinging_upAnswers() {
        detector.onDown(VolumeKey.UP, ringing = true, 1_000L)
        val action = detector.onUp(VolumeKey.UP, true, 1_800L)
        assertEquals(CallAction.ANSWER, action)
    }

    @Test
    fun longPress_whileRinging_downRejects() {
        detector.onDown(VolumeKey.DOWN, ringing = true, 1_000L)
        val action = detector.onUp(VolumeKey.DOWN, true, 1_800L)
        assertEquals(CallAction.REJECT, action)
    }

    @Test
    fun shortPress_whileRinging_isIgnored() {
        detector.onDown(VolumeKey.UP, ringing = true, 1_000L)
        assertNull(detector.onUp(VolumeKey.UP, true, 1_300L))
    }

    @Test
    fun up_withoutDown_isIgnored() {
        assertNull(detector.onUp(VolumeKey.UP, true, 5_000L))
    }

    @Test
    fun swapped_mapsUpToReject() {
        detector.onDown(VolumeKey.UP, ringing = true, 1_000L)
        assertEquals(
            CallAction.REJECT,
            detector.onUp(VolumeKey.UP, true, 1_800L, swapped = true)
        )
        detector.onDown(VolumeKey.DOWN, ringing = true, 1_000L)
        assertEquals(
            CallAction.ANSWER,
            detector.onUp(VolumeKey.DOWN, true, 1_800L, swapped = true)
        )
    }

    @Test
    fun reset_forgetsPendingPress() {
        detector.onDown(VolumeKey.UP, ringing = true, 1_000L)
        detector.reset()
        assertNull(detector.onUp(VolumeKey.UP, true, 1_800L))
    }

    @Test
    fun invoker_callsGateway() {
        val gateway = RecordingGateway()
        assertTrue(CallActionInvoker.invoke(CallAction.ANSWER, gateway))
        assertTrue(gateway.accepted)
        assertFalse(gateway.ended)

        assertTrue(CallActionInvoker.invoke(CallAction.REJECT, gateway))
        assertTrue(gateway.ended)
    }

    @Test
    fun invoker_confirmationTexts() {
        assertEquals(
            "تم الرد",
            CallActionInvoker.confirmationText(CallAction.ANSWER)
        )
        assertEquals(
            "تم الرفض",
            CallActionInvoker.confirmationText(CallAction.REJECT)
        )
    }

    private class RecordingGateway : CallGateway {
        var accepted = false
        var ended = false
        override fun acceptRingingCall(): Boolean {
            accepted = true
            return true
        }

        override fun endCall(): Boolean {
            ended = true
            return true
        }
    }
}
