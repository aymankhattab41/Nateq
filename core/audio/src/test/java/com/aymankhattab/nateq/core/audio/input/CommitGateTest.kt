package com.aymankhattab.nateq.core.audio.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitGateTest {

    @Test
    fun liftMode_dwellUnder40ms_rejected() {
        val gate = CommitGate { "lift" }
        gate.onHoverEnter(1000L)
        val commit = gate.shouldCommit(
            key = "A",
            source = Source.HOVER_EXIT,
            nowMs = 1030L,
            exitedInsideKey = true
        )
        assertFalse(commit)
    }

    @Test
    fun liftMode_dwell40msOrMore_accepted() {
        val gate = CommitGate { "lift" }
        gate.onHoverEnter(1000L)
        val commit = gate.shouldCommit(
            key = "A",
            source = Source.HOVER_EXIT,
            nowMs = 1045L,
            exitedInsideKey = true
        )
        assertTrue(commit)
    }

    @Test
    fun liftMode_rapidSuccessiveNewTouchCycles_acceptedForSameKey() {
        // اختبار الأمر 17: enter1000/up1050 ثم enter1100/up1150 للحرف نفسه
        val gate = CommitGate { "lift" }

        // الدورة الأولى: دخول عند 1000ms ورفع عند 1050ms
        gate.onHoverEnter(1000L)
        assertTrue(
            gate.shouldCommit("A", Source.RAW_UP, 1050L, true)
        )

        // الدورة الثانية الجديدة: دخول جديد عند 1100ms ورفع عند 1150ms
        gate.onHoverEnter(1100L)
        assertTrue(
            gate.shouldCommit("A", Source.RAW_UP, 1150L, true)
        )
    }

    @Test
    fun liftMode_sameCycleDuplicate_rejected() {
        // اختبار الأمر 17: أحداث hover exit مع accessibility click لنفس الدورة
        val gate = CommitGate { "lift" }
        gate.onHoverEnter(1000L)

        // تأكيد أولي عبر HOVER_EXIT في نفس الدورة
        assertTrue(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1050L, true)
        )

        // حدث accessibility click لنفس الدورة دون دخول جديد -> يُرفض
        assertFalse(
            gate.shouldCommit("A", Source.ACCESSIBILITY_CLICK, 1060L, true)
        )

        // حدث RAW_UP مكرر لنفس الدورة دون دخول جديد -> يُرفض
        assertFalse(
            gate.shouldCommit("A", Source.RAW_UP, 1070L, true)
        )
    }

    @Test
    fun liftMode_multiTouch_rejected() {
        val gate = CommitGate { "lift" }
        gate.onHoverEnter(1000L)
        gate.setMultiTouchActive(true)
        assertFalse(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1050L, true)
        )
    }

    @Test
    fun liftMode_exitedOutsideKey_rejected() {
        val gate = CommitGate { "lift" }
        gate.onHoverEnter(1000L)
        assertFalse(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1050L, false)
        )
    }

    @Test
    fun liftMode_accessibilityClickWithoutNewEnter_suppressedWithin700ms() {
        val gate = CommitGate { "lift" }
        gate.onHoverEnter(1000L)
        assertTrue(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1050L, true)
        )
        // عند غياب onHoverEnter جديد، تُمنع النقرات ضمن نافذة 700ms
        assertFalse(
            gate.shouldCommit("A", Source.ACCESSIBILITY_CLICK, 1300L, true)
        )
        // بعد انقضاء 700ms يُسمح به
        assertTrue(
            gate.shouldCommit("A", Source.ACCESSIBILITY_CLICK, 1800L, true)
        )
    }

    @Test
    fun doubleTapMode_hoverExitAndRawUpAlwaysRejected() {
        val gate = CommitGate { "double_tap" }
        gate.onHoverEnter(1000L)
        assertFalse(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1100L, true)
        )
        assertFalse(
            gate.shouldCommit("A", Source.RAW_UP, 1150L, true)
        )
    }

    @Test
    fun doubleTapMode_touchTypingSuppressionWithin120msOfHoverExit() {
        val gate = CommitGate { "double_tap" }
        gate.shouldCommit("A", Source.HOVER_EXIT, 1000L, true)
        // نقر ضمن 120ms من الخروج العابر للاستكشاف -> يُرفض
        assertFalse(
            gate.shouldCommit("A", Source.ACCESSIBILITY_CLICK, 1080L, true)
        )
        // بعد 150ms -> يُقبل
        assertTrue(
            gate.shouldCommit("A", Source.ACCESSIBILITY_CLICK, 1200L, true)
        )
    }

    @Test
    fun doubleTapMode_multiTouch_rejected() {
        val gate = CommitGate { "double_tap" }
        gate.setMultiTouchActive(true)
        assertFalse(
            gate.shouldCommit("A", Source.ACCESSIBILITY_CLICK, 1000L, true)
        )
    }

    @Test
    fun resetState_clearsHistoryWhenRequested() {
        val gate = CommitGate { "lift" }
        gate.onHoverEnter(1000L)
        assertTrue(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1050L, true)
        )
        // حدث في نفس الدورة يُرفض
        assertFalse(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1060L, true)
        )
        // تصفير الحالة
        gate.resetState(clearHistory = true)
        gate.onHoverEnter(1200L)
        assertTrue(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1250L, true)
        )
    }
}
