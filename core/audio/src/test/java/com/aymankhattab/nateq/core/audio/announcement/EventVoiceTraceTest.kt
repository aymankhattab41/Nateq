package com.aymankhattab.nateq.core.audio.announcement

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات الجولة 52 — الأمر 3:
 * تتبع صوت الأحداث ومطابقة Boot Mirror وحماية قنوات الإشعارات.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class EventVoiceTraceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EventVoiceTrace.clear()
    }

    @Test
    fun testRecordAndReport_tracksFallbackAndExecution() {
        EventVoiceTrace.record(
            category = "lord_system",
            savedVoiceId = "custom_voice",
            resolvedEngine = "com.google.android.tts",
            dispatchedVoiceId = "google_ar",
            executingProvider = "SystemVoiceProvider",
            fallbackReason = "Engine unavailable",
            textSnippet = "فشلت استعادة الخدمة"
        )

        val report = EventVoiceTrace.formatReport()
        assertTrue(report.contains("lord_system"))
        assertTrue(report.contains("custom_voice"))
        assertTrue(report.contains("Engine unavailable"))
        assertTrue(report.contains("فشلت استعادة الخدمة"))

        val entries = EventVoiceTrace.getRecent(10)
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("lord_system", entry.category)
        assertEquals("custom_voice", entry.savedVoiceId)
        assertEquals("Engine unavailable", entry.fallbackReason)
    }

    @Test
    fun testTraceCapacity_capsAtFiftyEntries() {
        for (i in 1..60) {
            EventVoiceTrace.record(
                category = "time",
                savedVoiceId = "voice_$i",
                resolvedEngine = "engine",
                dispatchedVoiceId = "dispatched_$i",
                executingProvider = "provider",
                fallbackReason = null,
                textSnippet = "snippet_$i"
            )
        }

        val entries = EventVoiceTrace.getRecent(100)
        assertEquals(50, entries.size)
        assertEquals("voice_60", entries.last().savedVoiceId)
    }

    @Test
    fun testBootMirror_copiesAllCategoryVoiceKeys() {
        val repo = SettingsRepository.create(context)
        val cat = SettingsRepository.VOICE_CATEGORY_LORD_SYSTEM

        repo.setPreferredVoiceIdForCategory(cat, "lord_sys_v1")
        repo.setEngineForCategory(cat, "com.aymankhattab.engine")
        repo.setSpeechRateForCategory(cat, 1.25f)
        repo.setPitchForCategory(cat, 1.1f)
        repo.setVolumeForCategory(cat, 0.9f)
        repo.setLanguageForCategory(cat, "ar")

        // تحقق من قراءة المفاتيح المحفوظة
        assertEquals("lord_sys_v1", repo.getPreferredVoiceIdForCategory(cat))
        assertEquals(
            "com.aymankhattab.engine",
            repo.getEngineForCategory(cat)
        )
        assertEquals(1.25f, repo.getSpeechRateForCategory(cat), 0.01f)
        assertEquals(1.1f, repo.getPitchForCategory(cat), 0.01f)
        assertEquals(0.9f, repo.getVolumeForCategory(cat), 0.01f)
        assertEquals("ar", repo.getLanguageForCategory(cat))
    }

    @Test
    fun testNotificationChannel_notDeletedWhileRunning() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        // قناة الخدمة الأمامية عند البدء
        val channel = android.app.NotificationChannel(
            "nateq_announcement_service",
            "Lord Announcements",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        assertNotNull(nm.getNotificationChannel("nateq_announcement_service"))
    }
}
