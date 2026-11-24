package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatLiveReaderDeduplicationTest {

    private lateinit var context: Context
    private lateinit var reader: ChatLiveReader

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        reader = ChatLiveReader.getInstance()
        reader.clearHistory()

        val store = A11yStateStore.getInstance(context)!!
        store.setConnected(true)
        store.setPaused(false)

        val repo = SettingsRepository.create(context)
        repo.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_CHAT_LIVE_READING,
            true
        )
        repo.setA11yAllowAllApps(true)
    }

    @Test
    fun sameMessageWithinValidityWindow_isDeduplicated() {
        val ok1 = reader.processIncomingMessage(
            context = context,
            sender = "أحمد",
            messageBody = "السلام عليكم",
            packageName = "com.whatsapp",
            conversationId = "chat_1"
        )
        assertTrue("أول رسالة يجب قبولها", ok1)

        val ok2 = reader.processIncomingMessage(
            context = context,
            sender = "أحمد",
            messageBody = "السلام عليكم",
            packageName = "com.whatsapp",
            conversationId = "chat_1"
        )
        assertFalse("الرسالة المكررة يجب رفضها", ok2)
    }

    @Test
    fun messageFromDifferentConversation_isNotDeduplicated() {
        val ok1 = reader.processIncomingMessage(
            context = context,
            sender = "أحمد",
            messageBody = "مرحبا",
            packageName = "com.whatsapp",
            conversationId = "chat_1"
        )
        assertTrue(ok1)

        val ok2 = reader.processIncomingMessage(
            context = context,
            sender = "أحمد",
            messageBody = "مرحبا",
            packageName = "com.whatsapp",
            conversationId = "chat_2"
        )
        assertTrue("محادثة مختلفة يجب قبولها", ok2)
    }

    @Test
    fun packageNameAsSender_isSanitizedToNullAndAccepted() {
        // When sender is mistakenly passed as package name, it is sanitized
        val ok = reader.processIncomingMessage(
            context = context,
            sender = "com.whatsapp",
            messageBody = "رسالة جديدة",
            packageName = "com.whatsapp",
            conversationId = "chat_1"
        )
        assertTrue(ok)
    }

    @Test
    fun bidiCharactersAreCleanedAndDeduplicatedAgainstPlain() {
        val ok1 = reader.processIncomingMessage(
            context = context,
            sender = "علي",
            messageBody = "نص عادي",
            packageName = "com.whatsapp",
            conversationId = "chat_1"
        )
        assertTrue(ok1)

        val ok2 = reader.processIncomingMessage(
            context = context,
            sender = "علي",
            messageBody = "\u200Eنص عادي\u200F",
            packageName = "com.whatsapp",
            conversationId = "chat_1"
        )
        assertFalse(
            "الرسالة التي تحتوي رموز اتجاه يجب كشف تكرارها",
            ok2
        )
    }
}
