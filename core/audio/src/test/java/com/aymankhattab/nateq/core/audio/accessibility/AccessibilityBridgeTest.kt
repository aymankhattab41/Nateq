package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
