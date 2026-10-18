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
    fun liftMode_cooldownPerKey350ms_enforced() {
        val gate = CommitGate { "lift" }
        gate.onHoverEnter(1000L)
        assertTrue(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1050L, true)
        )
        gate.onHoverEnter(1100L)
        // 1200L - 1050L = 150ms < 350ms
        assertFalse(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1200L, true)
        )
        // Different key "B" should be accepted independently
        gate.onHoverEnter(1200L)
        assertTrue(
            gate.shouldCommit("B", Source.HOVER_EXIT, 1250L, true)
        )
        // After 350ms for "A", accepted
        gate.onHoverEnter(1400L)
        assertTrue(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1450L, true)
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
    fun liftMode_accessibilityClickSuppressedWithin700ms() {
        val gate = CommitGate { "lift" }
        gate.onHoverEnter(1000L)
        assertTrue(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1050L, true)
        )
        // Acc click within 700ms should be rejected
        assertFalse(
            gate.shouldCommit("A", Source.ACCESSIBILITY_CLICK, 1300L, true)
        )
        // After 700ms should be allowed
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
        // Simulate hover exit at 1000ms (rejected)
        gate.shouldCommit("A", Source.HOVER_EXIT, 1000L, true)
        // Acc click within 100ms of hover exit (rapid exploration)
        assertFalse(
            gate.shouldCommit("A", Source.ACCESSIBILITY_CLICK, 1080L, true)
        )
        // Acc click after 150ms of hover exit -> accepted
        assertTrue(
            gate.shouldCommit("A", Source.ACCESSIBILITY_CLICK, 1200L, true)
        )
    }

    @Test
    fun doubleTapMode_perKeyCooldown350ms() {
        val gate = CommitGate { "double_tap" }
        assertTrue(
            gate.shouldCommit("A", Source.ACCESSIBILITY_CLICK, 1000L, true)
        )
        assertFalse(
            gate.shouldCommit("A", Source.ACCESSIBILITY_CLICK, 1200L, true)
        )
        assertTrue(
            gate.shouldCommit("A", Source.ACCESSIBILITY_CLICK, 1400L, true)
        )
    }

    @Test
    fun resetState_clearsHistoryWhenRequested() {
        val gate = CommitGate { "lift" }
        gate.onHoverEnter(1000L)
        assertTrue(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1050L, true)
        )
        // Normal cooldown blocks immediate re-commit
        gate.onHoverEnter(1100L)
        assertFalse(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1150L, true)
        )
        // Reset with clearHistory = true
        gate.resetState(clearHistory = true)
        gate.onHoverEnter(1200L)
        assertTrue(
            gate.shouldCommit("A", Source.HOVER_EXIT, 1250L, true)
        )
    }
}
