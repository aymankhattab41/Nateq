package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.CategoryFailureTracker
import com.aymankhattab.nateq.core.audio.CrashGuard
import com.aymankhattab.nateq.core.audio.EmergencyVoice
import com.aymankhattab.nateq.core.audio.EmergencyVoiceConfig
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.audio.providers.VocalizerEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات صوت الطوارئ وحارس الانهيارات (الأمر 1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmergencyVoiceTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository.create(context)
        EmergencyVoice.save(
            context,
            EmergencyVoiceConfig(isSaved = false)
        )
        CrashGuard.resetForTest(context)
        CategoryFailureTracker.reset()
    }

    @After
    fun tearDown() {
        EmergencyVoice.save(
            context,
            EmergencyVoiceConfig(isSaved = false)
        )
        CrashGuard.resetForTest(context)
        CategoryFailureTracker.reset()
    }

    /**
     * (أ) الحفظ يكتب الملف المحمي بالجهاز والمرآة.
     */
    @Test
    fun save_writesToDeviceProtectedStorageAndBootMirror() {
        val config = EmergencyVoiceConfig(
            engine = "com.google.android.tts",
            voice = "ar-xa-x-ard-network",
            rate = 1.3f,
            pitch = 1.1f,
            volume = 0.9f,
            isSaved = true
        )

        settings.setEmergencyEngine(config.engine)
        settings.setEmergencyVoice(config.voice)
        settings.setEmergencyRate(config.rate)
        settings.setEmergencyPitch(config.pitch)
        settings.setEmergencyVolume(config.volume)
        EmergencyVoice.save(context, config)

        val loaded = EmergencyVoice.load(context)
        assertTrue(loaded.isSaved)
        assertEquals("com.google.android.tts", loaded.engine)
        assertEquals("ar-xa-x-ard-network", loaded.voice)
        assertEquals(1.3f, loaded.rate, 0.01f)
        assertEquals(1.1f, loaded.pitch, 0.01f)
        assertEquals(0.9f, loaded.volume, 0.01f)

        // التحقق من المرآة
        val mirrorPrefs = context.getSharedPreferences(
            "nateq_boot_mirror",
            Context.MODE_PRIVATE
        )
        assertEquals(
            "com.google.android.tts",
            mirrorPrefs.getString(EmergencyVoice.KEY_EMERGENCY_ENGINE, null)
        )
        assertEquals(
            "ar-xa-x-ard-network",
            mirrorPrefs.getString(EmergencyVoice.KEY_EMERGENCY_VOICE, null)
        )
    }

    /**
     * (ب) EmergencyVoice.load تعمل مع إعدادات رئيسية تالفة.
     */
    @Test
    fun load_worksWhenMainPrefsCorruptedOrMissing() {
        val config = EmergencyVoiceConfig(
            engine = "com.svox.pico",
            voice = "en-GB",
            rate = 1.5f,
            pitch = 0.8f,
            volume = 0.95f,
            isSaved = true
        )
        EmergencyVoice.save(context, config)

        // إتلاف أو مسح ملف الإعدادات الرئيسي
        context.getSharedPreferences(
            context.packageName + "_preferences",
            Context.MODE_PRIVATE
        ).edit().clear().commit()

        val loaded = EmergencyVoice.load(context)
        assertTrue(loaded.isSaved)
        assertEquals("com.svox.pico", loaded.engine)
        assertEquals("en-GB", loaded.voice)
        assertEquals(1.5f, loaded.rate, 0.01f)
    }

    /**
     * (ج) فشل التخليق مرتين يحوّل الفئة لصوت الطوارئ بسرعته ونبرته ومستواه.
     */
    @Test
    fun synthesisFailureTwice_triggersEmergencyModeForCategory() {
        val category = SettingsRepository.VOICE_CATEGORY_TIME
        val now = 100_000L

        assertFalse(CategoryFailureTracker.isEmergencyActive(category, now))

        // الفشل الأول
        CategoryFailureTracker.recordFailure(category, now)
        assertFalse(CategoryFailureTracker.isEmergencyActive(category, now))

        // الفشل الثاني بعد 10 ثوانٍ (ضمن نافذة 30 ثانية)
        CategoryFailureTracker.recordFailure(category, now + 10_000L)
        assertTrue(
            CategoryFailureTracker.isEmergencyActive(category, now + 10_000L)
        )

        // يبقى نشطاً لمدة 5 دقائق (300 ثانية)
        assertTrue(
            CategoryFailureTracker.isEmergencyActive(category, now + 290_000L)
        )
        // ينتهي بعد انقضاء 5 دقائق
        assertFalse(
            CategoryFailureTracker.isEmergencyActive(category, now + 320_000L)
        )
    }

    /**
     * (د) انهيار مسجَّل قبل أقل من 120 ثانية يدخل وضع الأمان 10 دقائق.
     */
    @Test
    fun recentCrash_entersSafeModeFor10Minutes() {
        val now = 200_000L
        val crashTime = now - 50_000L // قبل 50 ثانية (< 120 ثانية)

        CrashGuard.recordCrash(context, crashTime)
        val shouldAnnounce = CrashGuard.checkOnStartup(context, now)

        assertTrue(shouldAnnounce)
        assertTrue(CrashGuard.isSafeModeActive(context, now))
        // لا يزال نشطاً قبل 10 دقائق
        assertTrue(CrashGuard.isSafeModeActive(context, now + 500_000L))
        // ينتهي بعد 10 دقائق (600_000ms)
        assertFalse(CrashGuard.isSafeModeActive(context, now + 650_000L))
    }

    /**
     * (هـ) 3 انهيارات في 10 دقائق تبقيه قائماً حتى زر الخروج.
     */
    @Test
    fun tripleCrashes_entersPermanentSafeModeUntilExit() {
        val now = 500_000L
        // 3 انهيارات خلال آخر 10 دقائق
        CrashGuard.recordCrash(context, now - 400_000L)
        CrashGuard.recordCrash(context, now - 200_000L)
        CrashGuard.recordCrash(context, now - 10_000L)

        CrashGuard.checkOnStartup(context, now)
        assertTrue(CrashGuard.isPermanentEmergency(context))
        assertTrue(CrashGuard.isSafeModeActive(context, now))

        // يبقى نشطاً حتى بعد ساعة
        assertTrue(CrashGuard.isSafeModeActive(context, now + 3_600_000L))

        // الضغط على زر الخروج
        CrashGuard.exitEmergencyMode(context)
        assertFalse(CrashGuard.isPermanentEmergency(context))
        assertFalse(CrashGuard.isSafeModeActive(context, now + 3_600_000L))
    }

    /**
     * (و) المحرك المدمج لا يظهر في قائمة محركات الطوارئ.
     */
    @Test
    fun embeddedEngine_doesNotAppearInEmergencySelection() {
        val installed = EnginePicker.installedEngines(context)
        val emergencyCandidates = installed.filter {
            it.packageName != VocalizerEmbeddedVoiceProvider.PROVIDER_ID
        }

        for (eng in emergencyCandidates) {
            assertNotEquals(
                "المحرك المدمج لا يجوز أن يظهر في محركات الطوارئ",
                VocalizerEmbeddedVoiceProvider.PROVIDER_ID,
                eng.packageName
            )
            assertFalse(
                eng.packageName.contains("vocalizer", ignoreCase = true)
            )
        }
    }
}
