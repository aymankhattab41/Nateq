package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** اختبارات مخزن الإعدادات عبر Robolectric (SharedPreferences حقيقي). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsRepositoryTest {

    private lateinit var repo: SettingsRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        repo = SettingsRepository(context)
    }

    @Test
    fun defaults_correctValues() {
        assertEquals(1, repo.getNumberReadingMode())
        assertTrue(repo.isTimeAnnouncementEnabled())
        assertEquals(30, repo.getTimeAnnouncementInterval())
        assertFalse(repo.isTime24Hour())
        assertFalse(repo.isHijriDateEnabled())
        assertEquals("arabic_natural", repo.getTimeAnnouncementFormat())
        assertNull(repo.getSelectedEnginePackage())
    }

    @Test
    fun numberReadingMode_roundTrip() {
        repo.setNumberReadingMode(6)
        assertEquals(6, repo.getNumberReadingMode())
        repo.setNumberReadingMode(1)
        assertEquals(1, repo.getNumberReadingMode())
    }

    @Test
    fun time24Hour_roundTrip() {
        assertFalse(repo.isTime24Hour())
        repo.setTime24Hour(true)
        assertTrue(repo.isTime24Hour())
    }

    @Test
    fun hijriDateEnabled_roundTrip() {
        assertFalse(repo.isHijriDateEnabled())
        repo.setHijriDateEnabled(true)
        assertTrue(repo.isHijriDateEnabled())
    }

    @Test
    fun timeAnnouncementConfig_roundTrip() {
        repo.setTimeAnnouncementInterval(15)
        assertEquals(15, repo.getTimeAnnouncementInterval())
        repo.setTime24Hour(true)
        assertTrue(repo.isTime24Hour())
    }

    @Test
    fun appLanguage_roundTrip() {
        assertNull(repo.getAppLanguage())
        repo.setAppLanguage("en")
        assertEquals("en", repo.getAppLanguage())
    }

    @Test
    fun numberReadingMode_invalidMode_clampedByUIButStored() {
        // يخزّن ما يُمرَّر (الضبط على 1..8 يتم في طبقة الواجهة) — تحقق من سلامة القراءة
        repo.setNumberReadingMode(99)
        assertEquals(99, repo.getNumberReadingMode())
    }

    @Test
    fun preferredVoiceId_normalizesOldIds() {
        repo.setPreferredVoiceId("ar", "nateq-ar-1")
        assertEquals("ar-EG", repo.getPreferredVoiceId("ar"))
        repo.setPreferredVoiceId("en", "nateq-en-1")
        assertEquals("en-US", repo.getPreferredVoiceId("en"))
        repo.setPreferredVoiceId("ar", "ar-local")
        assertEquals("ar-EG", repo.getPreferredVoiceId("ar"))
    }

    @Test
    fun resetAllToDefault_restoresDefaults() {
        repo.setNumberReadingMode(5)
        repo.setTime24Hour(true)
        repo.setHijriDateEnabled(true)
        repo.resetAllToDefault()
        assertEquals(1, repo.getNumberReadingMode())
        assertFalse(repo.isTime24Hour())
        assertFalse(repo.isHijriDateEnabled())
    }
}