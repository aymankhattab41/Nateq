package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class AccessibilityBridgeTest {

    @Test
    fun `foreground package updates and returns cleanly`() {
        AccessibilityBridge.updateForeground(
            "com.whatsapp",
            "com.whatsapp.HomeActivity"
        )
        assertEquals("com.whatsapp", AccessibilityBridge.foregroundPackage())
        assertEquals(
            "com.whatsapp.HomeActivity",
            AccessibilityBridge.foregroundActivity()
        )
    }

    @Test
    fun `focus snapshot returns null when no service connected`() {
        assertNull(AccessibilityBridge.focusSnapshot())
    }

    @Test
    fun `screen texts buffer updates and retrieves safely`() {
        val texts = listOf("نص 1", "نص 2", "نص 3")
        AccessibilityBridge.updateLastScreenTexts(texts)
        assertEquals(texts, AccessibilityBridge.lastScreenTexts())
    }

    @Test
    fun `prepareContentChangeSignal and notifyContentChanged wake await`() =
        runBlocking {
            val signal = AccessibilityBridge.prepareContentChangeSignal()
            AccessibilityBridge.notifyContentChanged()
            val received = AccessibilityBridge.awaitContentChange(
                signal,
                timeoutMs = 100L
            )
            assertTrue(received)
        }

    @Test
    fun `awaitContentChange times out if no notification sent`() =
        runBlocking {
            val signal = AccessibilityBridge.prepareContentChangeSignal()
            val received = AccessibilityBridge.awaitContentChange(
                signal,
                timeoutMs = 50L
            )
            assertFalse(received)
        }

    @Test
    fun `external read-more timeout exceeds internal budget with margin`() {
        // بند ١٤: كان 200ms أقل من مجموع المسار الداخلي
        // (50 إعادة محاولة + 250 انتظار إشارة + 60 تعليق = 360ms)،
        // فتُلغى المهلة قبل ظهور النص الموسَّع. الحارس يثبت أن المهلة
        // الخارجية الآن أكبر من المجموع بهامش صريح.
        val internalSum =
            AccessibilityBridge.READ_MORE_FOCUS_RETRY_MS +
                AccessibilityBridge.READ_MORE_CONTENT_CHANGE_MS +
                AccessibilityBridge.READ_MORE_GRACE_MS
        assertEquals(
            "budget must equal the sum of the three internal stages",
            internalSum,
            AccessibilityBridge.READ_MORE_INTERNAL_BUDGET_MS
        )
        assertTrue(
            "external timeout must exceed internal budget " +
                "($internalSum ms), was " +
                "${AccessibilityBridge.READ_MORE_EXTERNAL_TIMEOUT_MS}",
            AccessibilityBridge.READ_MORE_EXTERNAL_TIMEOUT_MS > internalSum
        )
        assertEquals(
            "external timeout must keep an explicit safety margin",
            AccessibilityBridge.READ_MORE_EXTERNAL_MARGIN_MS,
            AccessibilityBridge.READ_MORE_EXTERNAL_TIMEOUT_MS - internalSum
        )
        assertTrue(
            "legacy 200ms gap that caused the bug must be gone",
            200L < AccessibilityBridge.READ_MORE_INTERNAL_BUDGET_MS
        )
    }
}
