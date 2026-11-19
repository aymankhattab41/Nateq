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
 * حارس هوية الرسالة المفوكسة بعد التوسيع:
 * النص الموسّع يُقبل فقط إن كان استمراراً لنفس الرسالة (هوية+بادئة)،
 * ويُرفض عند تغيّر الرسالة/الهوية أو عند نص لا يطابق استمرار البادئة.
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

    private fun identityOf(snapshot: NodeView, prefix: String): String {
        val method = NateqAccessibilityService::class.java
            .getDeclaredMethod(
                "messageIdentity",
                NodeView::class.java,
                String::class.java
            )
        method.isAccessible = true
        return method.invoke(service, snapshot, prefix) as String
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
        // نص رسالة مختلفة (لا يكمل البادئة) يُرفض ولو على نفس العقدة.
        val node = truncatedNode()
        val prefix = node.text.toString()
        val otherMessage = "اجتماع مجلس الإدارة غدا ... المزيد"
        assertFalse(
            isTrusted(
                node, otherMessage, prefix, identityOf(node, prefix)
            )
        )
    }

    @Test
    fun expandedTextMatchesIdentity_focusChanged_rejectsSameExpandedText() {
        // تغيّر هوية الرسالة (موضع/معرّف مختلف) يعني رسالة أخرى حتى لو
        // بدا النص مكمّلاً للبادئة.
        val before = truncatedNode()
        val after = before.copy(
            viewIdResourceName = "another_message_body",
            boundsInScreen = android.graphics.Rect(0, 400, 300, 500)
        )
        val expandedText = "مرحبا كيف حالك اليوم بخير ... المزيد"
        assertFalse(
            isTrusted(
                after,
                expandedText,
                before.text.toString(),
                identityOf(before, before.text.toString())
            )
        )
    }

    @Test
    fun expandedTextMatchesIdentity_sameNodeAndContinuation_isTrusted() {
        val node = truncatedNode()
        val prefix = node.text.toString()
        val expandedText = "مرحبا كيف حالك اليوم بخير ... المزيد"
        assertTrue(
            isTrusted(
                node,
                expandedText,
                prefix,
                identityOf(node, prefix)
            )
        )
    }
}
