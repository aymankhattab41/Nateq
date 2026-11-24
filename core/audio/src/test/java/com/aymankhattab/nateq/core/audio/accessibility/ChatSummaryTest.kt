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

    @Test
    fun extractConversationTitle_fromKnownIds() {
        val views = listOf(
            NodeView(
                text = "مجموعة العمل",
                viewIdResourceName = "com.whatsapp:id/conversation_contact_name"
            ),
            messageNode("مرحباً")
        )
        val title = ChatSnapshotReader.extractConversationTitle(views)
        assertEquals("مجموعة العمل", title)
    }

    @Test
    fun extractConversationTitle_unidentified_returnsNull() {
        val views = listOf(
            messageNode("مرحباً")
        )
        val title = ChatSnapshotReader.extractConversationTitle(views)
        assertNull(title)
    }

    @Test
    fun tracker_messagesDuringThrottle_areNotLostForever() {
        // البند 16: رسالة تصل أثناء التثبيط لا تضيع بل تُعلن بعد انتهاء المهلة
        val tracker = ChatSummaryTracker(throttleMs = 30_000L)
        val chatKey = "com.whatsapp#Conversation#أحمد"

        val first = tracker.onVisit(
            chatKey,
            listOf(chat("a", "أحمد")),
            nowMs = 1_000L
        )
        assertEquals(1, first?.messageCount)

        val second = tracker.onVisit(
            chatKey,
            listOf(chat("a", "أحمد"), chat("b", "أحمد")),
            nowMs = 2_000L
        )
        assertNull(second)

        val third = tracker.onVisit(
            chatKey,
            listOf(chat("a", "أحمد"), chat("b", "أحمد")),
            nowMs = 40_000L
        )
        assertEquals(1, third?.messageCount)
        assertEquals(listOf("أحمد"), third?.senders)
    }

    @Test
    fun tracker_multipleMessagesDuringThrottle_allAnnouncedWhenUnthrottled() {
        val tracker = ChatSummaryTracker(throttleMs = 30_000L)
        val chatKey = "com.whatsapp#Conversation#أحمد"

        tracker.onVisit(chatKey, listOf(chat("a")), nowMs = 1_000L)
        tracker.onVisit(chatKey, listOf(chat("a"), chat("b")), nowMs = 5_000L)
        tracker.onVisit(
            chatKey,
            listOf(chat("a"), chat("b"), chat("c")),
            nowMs = 10_000L
        )

        val summary = tracker.onVisit(
            chatKey,
            listOf(chat("a"), chat("b"), chat("c")),
            nowMs = 40_000L
        )
        assertEquals(2, summary?.messageCount)
    }

    @Test
    fun tracker_independentChats_doNotThrottleEachOther() {
        // البند 15 و16: انتقال بين محادثتين بنفس Activity
        val tracker = ChatSummaryTracker(throttleMs = 30_000L)
        val chatAhmed = "com.whatsapp#Conversation#أحمد"
        val chatSarah = "com.whatsapp#Conversation#سارة"

        val firstAhmed = tracker.onVisit(
            chatAhmed,
            listOf(chat("مرحباً من أحمد")),
            nowMs = 1_000L
        )
        assertEquals(1, firstAhmed?.messageCount)

        val firstSarah = tracker.onVisit(
            chatSarah,
            listOf(chat("أهلاً من سارة")),
            nowMs = 2_000L
        )
        assertEquals(1, firstSarah?.messageCount)
    }

    @Test
    fun tracker_rollback_allowsReannouncementOnFailure() {
        val tracker = ChatSummaryTracker(throttleMs = 30_000L)
        val chatKey = "c1"
        val summary = tracker.onVisit(
            chatKey,
            listOf(chat("msg1")),
            nowMs = 1_000L
        )
        assertEquals(1, summary?.messageCount)

        tracker.rollback(chatKey, summary!!)

        val retry = tracker.onVisit(
            chatKey,
            listOf(chat("msg1")),
            nowMs = 2_000L
        )
        assertEquals(1, retry?.messageCount)
    }
}
