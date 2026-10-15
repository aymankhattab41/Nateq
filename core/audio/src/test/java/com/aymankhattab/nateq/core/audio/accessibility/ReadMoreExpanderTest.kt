package com.aymankhattab.nateq.core.audio.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات موسّع «قراءة المزيد» الذكي (ع٤٣-1).
 * يتحقق من الاستراتيجيات الأربع، القائمة الصارمة، والقائمة السوداء.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReadMoreExpanderTest {

    @Test
    fun tryExpand_nullNode_returnsFalse() {
        assertFalse(ReadMoreExpander.tryExpand(null))
    }

    @Test
    fun tryExpand_strategy1_nodeItselfIsClickableTrigger_succeeds() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.text = "قراءة المزيد"
            node.isClickable = true
            assertTrue(ReadMoreExpander.tryExpand(node))
        } finally {
            SafeNodeWalker.safeRecycle(node)
        }
    }

    @Test
    fun tryExpand_strategy1_nodeHasExpandAction_succeeds() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.text = "رسالة طويلة مقتطعة…"
            node.addAction(AccessibilityNodeInfo.ACTION_EXPAND)
            assertTrue(ReadMoreExpander.tryExpand(node))
        } finally {
            SafeNodeWalker.safeRecycle(node)
        }
    }

    @Test
    fun tryExpand_blacklistedTrigger_doesNotExpand() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.text = "More options"
            node.isClickable = true
            assertFalse(ReadMoreExpander.tryExpand(node))
        } finally {
            SafeNodeWalker.safeRecycle(node)
        }
    }

    @Test
    fun tryExpand_normalTextWithoutTrigger_returnsFalse() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.text = "رسالة عادية لا تحوي أي زر توسيع"
            node.isClickable = false
            assertFalse(ReadMoreExpander.tryExpand(node))
        } finally {
            SafeNodeWalker.safeRecycle(node)
        }
    }

    @Test
    fun tryExpand_strategy1_clickableTruncatedMessage_succeeds() {
        // ع٤٣-1: رسائل تلجرام القابلة للنقر مباشرة وتصل مقتطعة
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.text = "تحديث جديد للنظام... قراءة المزيد"
            node.isClickable = true
            assertTrue(ReadMoreExpander.tryExpand(node))
        } finally {
            SafeNodeWalker.safeRecycle(node)
        }
    }
}
