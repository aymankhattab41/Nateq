package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * بند ٧: حارس هوية الرسالة المفوكسة بعد التوسيع (بند ١٥).
 * يستدعي الدالتين المساعدتين في NateqAccessibilityService بالانعكاس،
 * فلا يُعدَّل ملف الخدمة التي يعمل عليها وكلاء آخرون.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReadMoreIdentityGuardTest {

    private lateinit var service: NateqAccessibilityService

    @Before
    fun setUp() {
        service = Robolectric.buildService(
            NateqAccessibilityService::class.java
        ).create().get()
    }

    private fun identityOf(snapshot: NodeView): String {
        val method = NateqAccessibilityService::class.java
            .getDeclaredMethod("stableNodeIdentity", NodeView::class.java)
        method.isAccessible = true
        return method.invoke(service, snapshot) as String
    }

    private fun isTrusted(
        snapshot: NodeView,
        expandedText: String,
        prefix: String,
        initialIdentity: String
    ): Boolean {
        val method = NateqAccessibilityService::class.java
            .getDeclaredMethod(
                "expandedTextMatchesIdentity",
                NodeView::class.java,
                String::class.java,
                String::class.java,
                String::class.java
            )
        method.isAccessible = true
        return method.invoke(
            service,
            snapshot,
            expandedText,
            prefix,
            initialIdentity
        ) as Boolean
    }

    private fun truncatedNode(): NodeView = NodeView(
        text = "مرحبا كيف حالك اليوم ... المزيد",
        className = "android.widget.TextView",
        viewIdResourceName = "message_body",
        packageName = "com.whatsapp"
    )

    @Test
    fun expandedTextMatchesIdentity_truncatedPrefix_rejectsOtherMessage() {
        // بند ١٥: الاقتطاع كان يُسقط حارس الهوية فتُقرأ رسالة أخرى
        // تشترك معها في كلمة «المزيد» فقط.
        val node = truncatedNode()
        val prefix = node.text.toString()
        val otherMessage = "اجتماع مجلس الإدارة غدا ... المزيد"
        assertFalse(
            isTrusted(node, otherMessage, prefix, identityOf(node))
        )
    }

    @Test
    fun expandedTextMatchesIdentity_focusChanged_rejectsSameExpandedText() {
        // بند ١٥: حتى لو كان النص مكمّلاً للبادئة، تغيّر هوية العقدة
        // يعني أننا نقرأ رسالة أخرى بعد النقر.
        val before = truncatedNode()
        val after = before.copy(
            viewIdResourceName = "another_message_body"
        )
        val expandedText = "مرحبا كيف حالك اليوم بخير ... المزيد"
        assertFalse(
            isTrusted(
                after,
                expandedText,
                before.text.toString(),
                identityOf(before)
            )
        )
    }

    @Test
    fun expandedTextMatchesIdentity_sameNodeAndContinuation_isTrusted() {
        val node = truncatedNode()
        val expandedText = "مرحبا كيف حالك اليوم بخير ... المزيد"
        assertTrue(
            isTrusted(
                node,
                expandedText,
                node.text.toString(),
                identityOf(node)
            )
        )
    }
}
