package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس عقد كاشف إيماءات مفاتيح الصوت (ع٤١-ب1).
 * النمط الحاكم: يُستهلك المفتاح فقط عند تحقّق إيماءةٍ مربوطة بفعل.
 */
class ShortcutGestureDetectorTest {

    private fun detector(
        bound: Set<ShortcutGesture> = setOf(
            ShortcutGesture.VOL_UP_LONG,
            ShortcutGesture.VOL_DOWN_LONG,
            ShortcutGesture.VOL_UP_DOUBLE,
            ShortcutGesture.VOL_DOWN_TRIPLE
        )
    ): ShortcutGestureDetector =
        ShortcutGestureDetector(isGestureBound = { it in bound })

    @Test
    fun longPressBound_firesOnRepeatDownAndConsumesUp() {
        val d = detector()
        val first = d.onKeyDown(VolumeKey.UP, 1000L, false, 0)
        assertNull(first.gesture)
        assertFalse(first.consume)
        val repeated = d.onKeyDown(VolumeKey.UP, 1500L, false, 1)
        assertEquals(ShortcutGesture.VOL_UP_LONG, repeated.gesture)
        assertTrue(repeated.consume)
        assertTrue(d.onKeyUp(VolumeKey.UP, 1600L).consume)
    }

    @Test
    fun longPressUnbound_isNotConsumed() {
        val d = detector(bound = emptySet())
        d.onKeyDown(VolumeKey.UP, 1000L, false, 0)
        val repeated = d.onKeyDown(VolumeKey.UP, 1500L, false, 1)
        assertNull(repeated.gesture)
        assertFalse(repeated.consume)
        assertFalse(d.onKeyUp(VolumeKey.UP, 1500L).consume)
    }

    @Test
    fun longPressByDuration_firesOnRelease() {
        val d = detector()
        val down = d.onKeyDown(VolumeKey.UP, 1000L, false, 0)
        assertNull(down.gesture)
        val up = d.onKeyUp(VolumeKey.UP, 1700L)
        assertEquals(ShortcutGesture.VOL_UP_LONG, up.gesture)
        assertTrue(up.consume)
    }

    @Test
    fun shortPressByDuration_doesNotFireLong() {
        val d = detector()
        d.onKeyDown(VolumeKey.UP, 1000L, false, 0)
        val up = d.onKeyUp(VolumeKey.UP, 1200L)
        assertNull(up.gesture)
        assertFalse(up.consume)
    }

    @Test
    fun longPressDuration_thenTap_doesNotFireLong() {
        val d = detector()
        d.onKeyDown(VolumeKey.UP, 1000L, false, 0)
        assertTrue(d.onKeyUp(VolumeKey.UP, 1800L).consume)
        val tap = d.onKeyDown(VolumeKey.UP, 2500L, false, 0)
        assertNull(tap.gesture)
    }

    @Test
    fun bothHeldRelease_doesNotFireLong() {
        val d = detector()
        d.onKeyDown(VolumeKey.UP, 1000L, false, 0)
        d.onKeyDown(VolumeKey.DOWN, 1010L, false, 0)
        assertNull(d.onKeyUp(VolumeKey.UP, 2500L).gesture)
        assertNull(d.onKeyUp(VolumeKey.DOWN, 2600L).gesture)
    }

    @Test
    fun doublePressWithinWindow_firesOnSecondDown() {
        val d = detector()
        val first = d.onKeyDown(VolumeKey.UP, 1000L, false, 0)
        assertFalse(first.consume)
        val second = d.onKeyDown(VolumeKey.UP, 1200L, false, 0)
        assertEquals(ShortcutGesture.VOL_UP_DOUBLE, second.gesture)
        assertTrue(second.consume)
    }

    @Test
    fun doublePressOutsideWindow_doesNotFire() {
        val d = detector()
        d.onKeyDown(VolumeKey.UP, 1000L, false, 0)
        val second = d.onKeyDown(VolumeKey.UP, 2000L, false, 0)
        assertNull(second.gesture)
        assertFalse(second.consume)
    }

    @Test
    fun triplePress_firesOnThirdDownWhenDoubleIsUnbound() {
        val d = detector()
        d.onKeyDown(VolumeKey.DOWN, 1000L, false, 0)
        val second = d.onKeyDown(VolumeKey.DOWN, 1100L, false, 0)
        assertNull(second.gesture)
        val third = d.onKeyDown(VolumeKey.DOWN, 1200L, false, 0)
        assertEquals(ShortcutGesture.VOL_DOWN_TRIPLE, third.gesture)
        assertTrue(third.consume)
    }

    @Test
    fun bothKeysHeldLongPress_defersToEmergencyTimer() {
        val d = detector()
        d.onKeyDown(VolumeKey.UP, 1000L, false, 0)
        d.onKeyDown(VolumeKey.DOWN, 1010L, false, 0)
        val repeated = d.onKeyDown(VolumeKey.UP, 1500L, false, 1)
        assertNull(repeated.gesture)
        assertFalse(repeated.consume)
    }

    @Test
    fun reset_clearsPendingPressCount() {
        val d = detector()
        d.onKeyDown(VolumeKey.UP, 1000L, false, 0)
        d.reset()
        val second = d.onKeyDown(VolumeKey.UP, 1200L, false, 0)
        assertNull(second.gesture)
    }
}
