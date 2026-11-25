package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات بوابة تثبيط أحداث البلوتوث وحفظ الحالة النهائية الحقيقية.
 */
class BluetoothEventGateTest {

    private val gate = BluetoothEventGate(debounceMs = 3_000L)

    @Test
    fun firstEventForDevice_announcesImmediately() {
        val action = gate.evaluate("AA:BB", connected = true, nowMs = 1_000L)
        assertTrue(action is BluetoothEventGate.Action.AnnounceImmediate)
        assertEquals(true, gate.getLastAnnouncedState("AA:BB"))
        assertFalse(gate.hasPendingTrailing("AA:BB"))
    }

    @Test
    fun connectAt1000_thenDisconnectAt2000_preservesFinalDisconnect() {
        // اتصال عند 1000ms ⟹ إعلان فوري
        val a1 = gate.evaluate("AA:BB", connected = true, nowMs = 1_000L)
        assertTrue(a1 is BluetoothEventGate.Action.AnnounceImmediate)
        assertEquals(true, gate.getLastAnnouncedState("AA:BB"))

        // فصل عند 2000ms ⟹ جدولة متأخرة بعد المتبقي من النافذة (2000ms)
        val a2 = gate.evaluate("AA:BB", connected = false, nowMs = 2_000L)
        assertTrue(a2 is BluetoothEventGate.Action.ScheduleTrailing)
        val schedule = a2 as BluetoothEventGate.Action.ScheduleTrailing
        assertEquals(2_000L, schedule.delayMs)
        assertTrue(gate.hasPendingTrailing("AA:BB"))
        assertEquals(false, gate.getPendingState("AA:BB"))

        // عند 4000ms: استحقاق الإعلان المتأخر ⟹ يُعلن الفصل ويبقى
        val trailing = gate.onTrailingDue("AA:BB", nowMs = 4_000L)
        assertEquals(false, trailing)
        assertEquals(false, gate.getLastAnnouncedState("AA:BB"))
        assertFalse(gate.hasPendingTrailing("AA:BB"))
    }

    @Test
    fun disconnectAt1000_thenConnectAt2000_preservesFinalConnect() {
        // فصل عند 1000ms ⟹ إعلان فوري
        val a1 = gate.evaluate("AA:BB", connected = false, nowMs = 1_000L)
        assertTrue(a1 is BluetoothEventGate.Action.AnnounceImmediate)
        assertEquals(false, gate.getLastAnnouncedState("AA:BB"))

        // اتصال عند 2000ms ⟹ جدولة متأخرة
        val a2 = gate.evaluate("AA:BB", connected = true, nowMs = 2_000L)
        assertTrue(a2 is BluetoothEventGate.Action.ScheduleTrailing)
        val schedule = a2 as BluetoothEventGate.Action.ScheduleTrailing
        assertEquals(2_000L, schedule.delayMs)
        assertTrue(gate.hasPendingTrailing("AA:BB"))

        // عند 4000ms: استحقاق الإعلان المتأخر ⟹ يُعلن الاتصال ويبقى
        val trailing = gate.onTrailingDue("AA:BB", nowMs = 4_000L)
        assertEquals(true, trailing)
        assertEquals(true, gate.getLastAnnouncedState("AA:BB"))
        assertFalse(gate.hasPendingTrailing("AA:BB"))
    }

    @Test
    fun quickDisconnectThenReconnect_cancelsTrailingSafely() {
        // اتصال عند 1000ms
        gate.evaluate("AA:BB", connected = true, nowMs = 1_000L)

        // فصل عند 2000ms ⟹ مجدول
        val a2 = gate.evaluate("AA:BB", connected = false, nowMs = 2_000L)
        assertTrue(a2 is BluetoothEventGate.Action.ScheduleTrailing)
        assertTrue(gate.hasPendingTrailing("AA:BB"))

        // اتصال مجدداً عند 2500ms ⟹ إلغاء آمن للإعلان المتأخر
        val a3 = gate.evaluate("AA:BB", connected = true, nowMs = 2_500L)
        assertTrue(a3 is BluetoothEventGate.Action.CancelTrailing)
        assertFalse(gate.hasPendingTrailing("AA:BB"))

        // عند 4000ms: لا شيء مستحق لأن الإعلان أُلغي
        val trailing = gate.onTrailingDue("AA:BB", nowMs = 4_000L)
        assertNull(trailing)
        assertEquals(true, gate.getLastAnnouncedState("AA:BB"))
    }

    @Test
    fun repeatedSameState_isDroppedWithoutDuplicateAction() {
        // اتصال أولي
        val a1 = gate.evaluate("AA:BB", connected = true, nowMs = 1_000L)
        assertTrue(a1 is BluetoothEventGate.Action.AnnounceImmediate)

        // اتصال مكرر داخل النافذة
        val a2 = gate.evaluate("AA:BB", connected = true, nowMs = 1_500L)
        assertTrue(a2 is BluetoothEventGate.Action.Drop)

        // اتصال مكرر بعد النافذة (الحالة الحالية معلنة أصلاً)
        val a3 = gate.evaluate("AA:BB", connected = true, nowMs = 5_000L)
        assertTrue(a3 is BluetoothEventGate.Action.Drop)
    }

    @Test
    fun eventBeyondWindow_announcesImmediatelyWithoutDelay() {
        // اتصال عند 1000ms
        gate.evaluate("AA:BB", connected = true, nowMs = 1_000L)

        // فصل بعد انقضاء نافذة 3 ثوانٍ (عند 4500ms) ⟹ إعلان فوري
        val a2 = gate.evaluate("AA:BB", connected = false, nowMs = 4_500L)
        assertTrue(a2 is BluetoothEventGate.Action.AnnounceImmediate)
        assertEquals(false, gate.getLastAnnouncedState("AA:BB"))
        assertFalse(gate.hasPendingTrailing("AA:BB"))
    }

    @Test
    fun distinctDevices_operateIndependently() {
        // جهاز 1 يتصل عند 1000ms
        val a1 = gate.evaluate("AA:BB", connected = true, nowMs = 1_000L)
        assertTrue(a1 is BluetoothEventGate.Action.AnnounceImmediate)

        // جهاز 2 يتصل عند 1200ms
        val a2 = gate.evaluate("CC:DD", connected = true, nowMs = 1_200L)
        assertTrue(a2 is BluetoothEventGate.Action.AnnounceImmediate)

        // جهاز 1 ينفصل عند 2000ms ⟹ مجدول لجهاز 1 فقط
        val a3 = gate.evaluate("AA:BB", connected = false, nowMs = 2_000L)
        assertTrue(a3 is BluetoothEventGate.Action.ScheduleTrailing)
        assertTrue(gate.hasPendingTrailing("AA:BB"))
        assertFalse(gate.hasPendingTrailing("CC:DD"))

        assertEquals(true, gate.getLastAnnouncedState("CC:DD"))
    }

    @Test
    fun clear_resetsAllStateAndCancelsPending() {
        gate.evaluate("AA:BB", connected = true, nowMs = 1_000L)
        gate.evaluate("AA:BB", connected = false, nowMs = 2_000L)
        assertTrue(gate.hasPendingTrailing("AA:BB"))

        // إطفاء الميزة أو إغلاق الخدمة
        gate.clear()

        assertFalse(gate.hasPendingTrailing("AA:BB"))
        assertNull(gate.getLastAnnouncedState("AA:BB"))
        assertNull(gate.onTrailingDue("AA:BB", nowMs = 4_000L))

        // حدث لاحق بعد المسح يُعامل كحدث أول
        val a = gate.evaluate("AA:BB", connected = true, nowMs = 5_000L)
        assertTrue(a is BluetoothEventGate.Action.AnnounceImmediate)
    }

    @Test
    fun shouldAnnounceCompatibility_returnsTrueOnlyForImmediate() {
        assertTrue(
            gate.shouldAnnounce("AA:BB", connected = true, nowMs = 1_000L)
        )
        assertFalse(
            gate.shouldAnnounce("AA:BB", connected = false, nowMs = 2_000L)
        )
        assertTrue(gate.hasPendingTrailing("AA:BB"))
    }
}
