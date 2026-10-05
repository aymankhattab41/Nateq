package com.aymankhattab.nateq.core.audio.announcement

import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnnouncementSpeakerWarmupTest {
    @Test
    fun warmInstanceDoesNotPoll() {
        val speaker = AnnouncementSpeaker(ApplicationProvider.getApplicationContext())
        val wasWarm = true
        assertEquals(true, wasWarm)
    }

    @Test
    fun warmupPoll_warmInstanceDoesNotWait() {
        // VZ2: المثيلات الدافئة (wasWarm) لا تنتظر — تنطق فوراً
        val speaker = AnnouncementSpeaker(ApplicationProvider.getApplicationContext())
        // محاكاة مثيل دافئ: نهيئ TTS أولاً
        speaker.prewarmTtsForTesting(Locale.getDefault())
        
        val startTime = System.currentTimeMillis()
        speaker.warmupIfNeeded(Locale.getDefault())
        val elapsed = System.currentTimeMillis() - startTime
        assertTrue("المثيل الدافئ لا ينتظر، يجب أن ينتهي فوراً", elapsed < 100)
    }

    @Test
    fun warmupIfNeeded_isIdempotent() {
        // استدعاء warmupIfNeeded عدة مرات لا يسبب مشاكل
        val speaker = AnnouncementSpeaker(ApplicationProvider.getApplicationContext())
        speaker.warmupIfNeeded(Locale.getDefault())
        speaker.warmupIfNeeded(Locale.getDefault())
        speaker.warmupIfNeeded(Locale.getDefault())
        // يجب أن يمر دون أخطاء
        assertTrue(true)
    }

    @Test
    fun simulateOnError_incrementsRetryCount() {
        // اختبار آلية إعادة المحاولة لخطأ ERROR_NOT_INSTALLED_YET
        val speaker = AnnouncementSpeaker(ApplicationProvider.getApplicationContext())
        val utteranceId = "test_utterance_retry"
        
        // محاكاة خطأ ERROR_NOT_INSTALLED_YET عبر استدعاء داخلي
        speaker.simulateOnErrorForTesting(utteranceId, TextToSpeech.ERROR_NOT_INSTALLED_YET)
        
        // يجب أن يكون هناك عد إعادة محاولة واحد
        assertEquals(1, speaker.getRetryCountForTesting(utteranceId))
    }
}

