package com.aymankhattab.nateq.core.audio.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * اختبارات موسّع «قراءة المزيد» الذكي وقواعد حصر النقر ومنع النقر العشوائي.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReadMoreExpanderTest {

    @Before
    fun setUp() {
        ReadMoreExpander.resetStateForTesting()
    }

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
    fun tryExpand_strategy1_nodeHasReadMoreActionLabel_succeeds() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.text = "رسالة طويلة مقتطعة..."
            val action = AccessibilityNodeInfo.AccessibilityAction(
                1001,
                "توسيع الرسالة"
            )
            node.addAction(action)
            assertTrue(ReadMoreExpander.tryExpand(node))
        } finally {
            SafeNodeWalker.safeRecycle(node)
        }
    }

    @Test
    fun tryExpand_truncatedAloneWithoutActionOrButton_doesNotClick() {
        // حظر النقر لمجرد الاقتطاع دون زر أو إجراء
        val node = AccessibilityNodeInfo.obtain()
        try {
            node.text = "تحديث جديد للنظام... قراءة المزيد"
            node.isClickable = true
            // النص ليس في القائمة الصارمة ولا يحمل إجراء توسيع
            assertFalse(ReadMoreExpander.tryExpand(node))
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
    fun tryExpand_strategy2_childIsReadMoreButton_succeeds() {
        val parent = AccessibilityNodeInfo.obtain()
        val textNode = AccessibilityNodeInfo.obtain().apply {
            text = "نص رسالة عادي"
        }
        val buttonNode = AccessibilityNodeInfo.obtain().apply {
            text = "قراءة المزيد"
            isClickable = true
        }
        shadowOf(parent).addChild(textNode)
        shadowOf(parent).addChild(buttonNode)

        try {
            assertTrue(ReadMoreExpander.tryExpand(parent))
        } finally {
            SafeNodeWalker.safeRecycle(parent)
        }
    }

    @Test
    fun tryExpand_strategy2_childHasExpandAction_succeeds() {
        val parent = AccessibilityNodeInfo.obtain()
        val child = AccessibilityNodeInfo.obtain().apply {
            text = "نص الرسالة"
            addAction(AccessibilityNodeInfo.ACTION_EXPAND)
        }
        shadowOf(parent).addChild(child)

        try {
            assertTrue(ReadMoreExpander.tryExpand(parent))
        } finally {
            SafeNodeWalker.safeRecycle(parent)
        }
    }

    @Test
    fun tryExpand_strategy3_siblingIsReadMoreButton_succeeds() {
        val parent = AccessibilityNodeInfo.obtain()
        val textNode = AccessibilityNodeInfo.obtain().apply {
            text = "نص رسالة طويل"
        }
        val buttonNode = AccessibilityNodeInfo.obtain().apply {
            text = "عرض المزيد"
            isClickable = true
        }
        shadowOf(parent).addChild(textNode)
        shadowOf(parent).addChild(buttonNode)

        try {
            assertTrue(ReadMoreExpander.tryExpand(textNode))
        } finally {
            SafeNodeWalker.safeRecycle(textNode)
        }
    }

    @Test
    fun tryExpand_strategy3_siblingHasExpandAction_succeeds() {
        val parent = AccessibilityNodeInfo.obtain()
        val textNode = AccessibilityNodeInfo.obtain().apply {
            text = "رسالة مقتطعة..."
        }
        val siblingNode = AccessibilityNodeInfo.obtain().apply {
            val action = AccessibilityNodeInfo.AccessibilityAction(
                2001,
                "توسيع"
            )
            addAction(action)
        }
        shadowOf(parent).addChild(textNode)
        shadowOf(parent).addChild(siblingNode)

        try {
            assertTrue(ReadMoreExpander.tryExpand(textNode))
        } finally {
            SafeNodeWalker.safeRecycle(textNode)
        }
    }

    @Test
    fun tryExpand_strategy4_parentHasExpandAction_succeeds() {
        val parent = AccessibilityNodeInfo.obtain().apply {
            addAction(AccessibilityNodeInfo.ACTION_EXPAND)
        }
        val textNode = AccessibilityNodeInfo.obtain().apply {
            text = "نص رسالة داخل فقاعة"
        }
        shadowOf(parent).addChild(textNode)

        try {
            assertTrue(ReadMoreExpander.tryExpand(textNode))
        } finally {
            SafeNodeWalker.safeRecycle(textNode)
        }
    }

    @Test
    fun tryExpand_clickableParentWithoutTrigger_doesNotClick() {
        val parent = AccessibilityNodeInfo.obtain().apply {
            isClickable = true
        }
        val textNode = AccessibilityNodeInfo.obtain().apply {
            text = "رسالة واتساب عادية"
        }
        shadowOf(parent).addChild(textNode)

        try {
            assertFalse(ReadMoreExpander.tryExpand(textNode))
        } finally {
            SafeNodeWalker.safeRecycle(textNode)
        }
    }

    @Test
    fun tryExpand_distantTruncatedPreviewInList_doesNotClickAnyNode() {
        // اختبار الأمر 3 البند 8: شجرة فيها معاينة مقتطعة بعيدة في قائمة
        val listRoot = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.ListView"
        }
        val item1 = AccessibilityNodeInfo.obtain().apply {
            isClickable = true
        }
        val previewText = AccessibilityNodeInfo.obtain().apply {
            text = "معاينة رسالة مقتطعة تنتهي بثلاث نقاط..."
            isClickable = true
        }
        shadowOf(item1).addChild(previewText)
        shadowOf(listRoot).addChild(item1)

        val item2 = AccessibilityNodeInfo.obtain()
        val focusedNode = AccessibilityNodeInfo.obtain().apply {
            text = "عقدة مفوكسة عادية"
        }
        shadowOf(item2).addChild(focusedNode)
        shadowOf(listRoot).addChild(item2)

        try {
            // التحقق من أن فحص العقدة المفوكسة لا يلمس المعاينة
            // البعيدة في item1
            assertFalse(ReadMoreExpander.tryExpand(focusedNode))
        } finally {
            SafeNodeWalker.safeRecycle(focusedNode)
            SafeNodeWalker.safeRecycle(listRoot)
        }
    }

    @Test
    fun tryExpand_rateLimit_secondClickWithinCooldownBlocked() {
        val node = AccessibilityNodeInfo.obtain().apply {
            text = "قراءة المزيد"
            isClickable = true
            packageName = "com.whatsapp"
        }
        try {
            assertTrue(ReadMoreExpander.tryExpand(node))
            // المحاولة الثانية مباشرة على نفس العقدة يجب أن تُحجب
            assertFalse(ReadMoreExpander.tryExpand(node))
        } finally {
            SafeNodeWalker.safeRecycle(node)
        }
    }

    @Test
    fun tryExpand_failedNodeTextDidNotIncrease_neverClickedAgain() {
        val node = AccessibilityNodeInfo.obtain().apply {
            text = "عرض المزيد"
            isClickable = true
            packageName = "org.telegram.messenger"
        }
        try {
            assertTrue(ReadMoreExpander.tryExpand(node))
            // إبلاغ بأن النص لم يزد
            ReadMoreExpander.onExpandAttemptResult(false)
            // المحاولة التالية محجوبة نهائياً
            assertFalse(ReadMoreExpander.tryExpand(node))
        } finally {
            SafeNodeWalker.safeRecycle(node)
        }
    }
}
