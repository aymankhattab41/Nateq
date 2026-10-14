package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس ع٤١-ب5: لقطة الدردشة ومتتبّع ملخّص المحادثة.
 */
class ChatSummaryTest {

    private fun messageNode(
        body: String,
        id: String = "com.whatsapp:id/message_text",
        sender: String? = null
    ): NodeView = NodeView(
        text = body,
        contentDescription = sender?.let { "$it: $body" },
        viewIdResourceName = id
    )

    private fun chat(body: String, sender: String? = null): ChatMessage =
        ChatMessage(sender = sender, body = body)

    @Test
    fun messageViewId_recognizesKnownIds() {
        assertTrue(
            SelectorPacks.isMessageViewId(
                "com.whatsapp:id/message_text"
            )
        )
        assertTrue(
            SelectorPacks.isMessageViewId(
                "org.telegram.messenger:id/chat_message_text"
            )
        )
        assertFalse(SelectorPacks.isMessageViewId("com.whatsapp:id/avatar"))
        assertFalse(SelectorPacks.isMessageViewId(null))
    }

    @Test
    fun snapshot_extractsSendersAndBodies() {
        val views = listOf(
            messageNode("مرحباً", sender = "أحمد"),
            messageNode("أهلاً", sender = "سارة"),
            NodeView(text = "آخر ظهور", viewIdResourceName = "x:id/status")
        )
        val messages = ChatSnapshotReader.messagesFrom(views)
        assertEquals(2, messages.size)
        assertEquals("أحمد", messages[0].sender)
        assertEquals("مرحباً", messages[0].body)
        assertEquals("سارة", messages[1].sender)
    }

    @Test
    fun snapshot_skipsPasswordAndInvisible() {
        val views = listOf(
            messageNode("سرّي").copy(isPassword = true),
            messageNode("خفي").copy(isVisibleToUser = false),
            messageNode("ظاهر")
        )
        val messages = ChatSnapshotReader.messagesFrom(views)
        assertEquals(1, messages.size)
        assertEquals("ظاهر", messages[0].body)
    }

    @Test
    fun tracker_reportsNewMessagesOnce() {
        val tracker = ChatSummaryTracker(throttleMs = 30_000L)
        val chatKey = "com.whatsapp#Conversation"
        val first = tracker.onVisit(
            chatKey,
            listOf(
                chat("a", "أحمد"),
                chat("b", "أحمد"),
                chat("c", "سارة")
            ),
            nowMs = 1_000L
        )
        assertEquals(3, first?.messageCount)
        assertEquals(listOf("أحمد", "أحمد", "سارة"), first?.senders)

        // إعادة الزيارة السريعة بنفس الرسائل ⇒ لا شيء (تثبيط 30ث)
        val second = tracker.onVisit(
            chatKey,
            listOf(
                chat("a", "أحمد"),
                chat("b", "أحمد"),
                chat("c", "سارة")
            ),
            nowMs = 2_000L
        )
        assertNull(second)
    }

    @Test
    fun tracker_reportsOnlyFreshMessagesAfterThrottle() {
        val tracker = ChatSummaryTracker(throttleMs = 30_000L)
        val chatKey = "com.whatsapp#Conversation"
        tracker.onVisit(chatKey, listOf(chat("a")), nowMs = 1_000L)
        val summary = tracker.onVisit(
            chatKey,
            listOf(chat("a"), chat("b")),
            nowMs = 40_000L
        )
        assertEquals(1, summary?.messageCount)
        assertEquals("", ChatSummarizer.summarize(emptyList(), 0))
    }

    @Test
    fun tracker_clearForgetsState() {
        val tracker = ChatSummaryTracker(throttleMs = 30_000L)
        val chatKey = "c"
        tracker.onVisit(chatKey, listOf(chat("a")), nowMs = 1_000L)
        tracker.clear()
        val after = tracker.onVisit(
            chatKey,
            listOf(chat("a")),
            nowMs = 2_000L
        )
        assertEquals(1, after?.messageCount)
    }
}
