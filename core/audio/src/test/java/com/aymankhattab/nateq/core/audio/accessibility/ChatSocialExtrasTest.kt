package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.announcement.GroupBurstLimiter
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatSocialExtrasTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = SettingsRepository.create(context)
        AdSkipper.resetForTesting()
        ChatChangeWatcher.clearMemory()
        GroupBurstLimiter.resetForTesting()
        ContactStatusAnnouncer.resetForTesting()
    }

    // --- 1. Ad Skipper Tests ---

    @Test
    fun testAdSkipperOnlyInYouTube() {
        val nonYtNode = AccessibilityNodeInfo.obtain().apply {
            text = "تخطي الإعلانات"
            isClickable = true
        }
        assertFalse(
            AdSkipper.onWindowContentChanged(
                context,
                "com.other.app",
                nonYtNode
            )
        )
    }

    // --- 2. Voice Note Inspector Tests ---

    @Test
    fun testVoiceNoteParseDurationAndState() {
        val nodeUnplayed = AccessibilityNodeInfo.obtain().apply {
            text = "رسالة صوتية"
            contentDescription = "0:45 غير مسموعة"
        }
        val info1 = VoiceNoteInspector.parseVoiceNote(nodeUnplayed)
        assertNotNull(info1)
        assertEquals("0:45", info1!!.duration)
        assertFalse(info1.isPlayed)

        val nodePlayed = AccessibilityNodeInfo.obtain().apply {
            contentDescription = "Voice message 1:20 played"
        }
        val info2 = VoiceNoteInspector.parseVoiceNote(nodePlayed)
        assertNotNull(info2)
        assertEquals("1:20", info2!!.duration)
        assertTrue(info2.isPlayed)
    }

    // --- 3. Chat Change Watcher Tests ---

    @Test
    fun testChatChangeWatcherCapacity() {
        for (i in 1..250) {
            val node = AccessibilityNodeInfo.obtain().apply {
                text = "رسالة تجريبية رقم $i"
            }
            ChatChangeWatcher.onWindowContentChanged(
                context,
                "com.whatsapp",
                node
            )
        }
        assertTrue(ChatChangeWatcher.size() <= ChatChangeWatcher.MAX_CAPACITY)
    }

    // --- 4. Group Burst Limiter Tests ---

    @Test
    fun testGroupBurstLimiterThresholdAndMentions() {
        repo.setExtraEnabled("extra_group_limiter", true)

        val groupKey = "whatsapp_group_123"
        val title = "أصدقاء العمل"

        // أول 4 رسائل: غير مقموعة
        for (i in 1..4) {
            val suppressed = GroupBurstLimiter.shouldSuppressSpeech(
                context = context,
                groupKey = groupKey,
                groupTitle = title,
                messageText = "رسالة رقم $i",
                windowMs = 20000L,
                threshold = 4
            )
            assertFalse("Message $i should not be suppressed", suppressed)
        }

        // الرسالة الخامسة: مقموعة
        val suppressed5 = GroupBurstLimiter.shouldSuppressSpeech(
            context = context,
            groupKey = groupKey,
            groupTitle = title,
            messageText = "رسالة رقم 5",
            windowMs = 20000L,
            threshold = 4
        )
        assertTrue("Message 5 should be suppressed", suppressed5)

        // رسالة بها منشن (@): لا تُقمع رغم الازدحام
        val mentionSuppressed = GroupBurstLimiter.shouldSuppressSpeech(
            context = context,
            groupKey = groupKey,
            groupTitle = title,
            messageText = "أهلاً @أحمد كيف حالك؟",
            windowMs = 20000L,
            threshold = 4
        )
        assertFalse("Mention should bypass burst limit", mentionSuppressed)
    }

    // --- 5. Contact Status Announcer Tests ---

    @Test
    fun testContactStatusPatternMatching() {
        val nodeOnline = AccessibilityNodeInfo.obtain().apply {
            text = "متصل الآن"
        }
        val status = ContactStatusAnnouncer.findStatusText(nodeOnline)
        assertNotNull(status)
        assertTrue(status!!.contains("متصل"))

        val nodeNoStatus = AccessibilityNodeInfo.obtain().apply {
            text = "رسالة عادية"
        }
        assertNull(ContactStatusAnnouncer.findStatusText(nodeNoStatus))
    }

    // --- 6. Alt Text Reader Tests ---

    @Test
    fun testAltTextCleaningAndLength() {
        val raw = "قد تحتوي الصورة على: شخصان في حديقة خضراء مشمسة"
        val cleaned = AltTextReader.cleanAltText(raw)
        assertEquals("شخصان في حديقة خضراء مشمسة", cleaned)

        val longText = "أ".repeat(250)
        val trimmed = AltTextReader.cleanAltText(longText)
        assertEquals(200, trimmed.length)
    }

    @Test
    fun testAltTextIsSocialMediaImage() {
        val imageNode = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.ImageView"
            contentDescription = "صورة لغروب الشمس على شاطئ البحر"
            text = null
        }
        assertTrue(
            AltTextReader.isSocialMediaImage(
                "com.instagram.android",
                imageNode
            )
        )

        val textNode = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.TextView"
            text = "نص المنشور الظاهر"
            contentDescription = "نص المنشور"
        }
        assertFalse(
            AltTextReader.isSocialMediaImage(
                "com.instagram.android",
                textNode
            )
        )
    }
}
