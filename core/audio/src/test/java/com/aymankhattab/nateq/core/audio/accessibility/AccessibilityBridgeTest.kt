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
}
