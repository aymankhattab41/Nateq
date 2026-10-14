package com.aymankhattab.nateq.core.audio.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// `AccessibilityNodeInfo.obtain()` مُهمَل في API 36، لكن البديل (المُنشئ)
// متاح من API 30 فقط، وتشمل هذه الفئة sdk=24، لذا يلزم الإبقاء عليه.
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class SafeNodeWalkerTest {

    @Test
    fun `null root returns empty list`() {
        val result = SafeNodeWalker.walk(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extractNodeView extracts text and bounds cleanly`() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.text = "مرحبا بالعالم"
            node.contentDescription = "زر تجريبي"
            node.className = "android.widget.Button"
            node.isVisibleToUser = true

            val view = SafeNodeWalker.extractNodeView(node, 0)
            assertEquals("مرحبا بالعالم", view.text)
            assertEquals("زر تجريبي", view.contentDescription)
            assertEquals("android.widget.Button", view.className)
            assertEquals("زر تجريبي مرحبا بالعالم", view.readableText())
        } finally {
            SafeNodeWalker.safeRecycle(node)
        }
    }

    @Test
    fun `skips password and invisible nodes`() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.text = "سري للغاية"
            node.isPassword = true
            node.isVisibleToUser = true

            val result = SafeNodeWalker.walk(node)
            assertTrue(result.isEmpty())
        } finally {
            SafeNodeWalker.safeRecycle(node)
        }
    }
}
