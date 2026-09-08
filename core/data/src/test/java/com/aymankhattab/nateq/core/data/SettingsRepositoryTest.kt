package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
    fun numberReadingMode_invalidMode_clampedAtRepository() {
        // بند 9.3: الحدود تُفرض في المخزن نفسه، لا في طبقة الواجهة فقط.
        repo.setNumberReadingMode(99)
        assertEquals(8, repo.getNumberReadingMode())
        repo.setNumberReadingMode(0)
        assertEquals(1, repo.getNumberReadingMode())
    }

    @Test
    fun preferredVoiceId_normalizesOldIds() {
        repo.setPreferredVoiceId("ar", "nateq-ar-1")
        assertEquals("ar-EG", repo.getPreferredVoiceId("ar"))
        repo.setPreferredVoiceId("en", "nateq-en-1")
        assertEquals("en-US", repo.getPreferredVoiceId("en"))
        repo.setPreferredVoiceId("ar", "ar-local")
        assertEquals("ar-EG", repo.getPreferredVoiceId("ar"))
        // البديل الخاطئ الأحدث من المزوّد: nateq-<lang>-local يُطبع للصيغة الموحّدة
        repo.setPreferredVoiceId("fr", "nateq-fr-local")
        assertEquals("fr-local", repo.getPreferredVoiceId("fr"))
        // الصيغة الموحّدة الحالية تمرّ كما هي
        repo.setPreferredVoiceId("fr", "fr-local")
        assertEquals("fr-local", repo.getPreferredVoiceId("fr"))
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

    @Test
    fun languageInstallHint_defaultOffAndRoundTrip() {
        // توضيح «اللغات غير المثبتة» غير افتراضي (مخفي) حتى يفعّله المستخدم
        assertFalse(repo.isLanguageInstallHintEnabled())
        repo.setLanguageInstallHintEnabled(true)
        assertTrue(repo.isLanguageInstallHintEnabled())
        repo.setLanguageInstallHintEnabled(false)
        assertFalse(repo.isLanguageInstallHintEnabled())
    }

    @Test
    fun callerInterval_defaultAndRoundTrip() {
        assertEquals(3, repo.getCallerAnnouncementIntervalSeconds())
        repo.setCallerAnnouncementIntervalSeconds(7)
        assertEquals(7, repo.getCallerAnnouncementIntervalSeconds())
    }

    @Test
    fun emojiPronunciation_defaultOnAndRoundTrip() {
        assertTrue(repo.isEmojiPronunciationEnabled())
        repo.setEmojiPronunciationEnabled(false)
        assertFalse(repo.isEmojiPronunciationEnabled())
        repo.setEmojiPronunciationEnabled(true)
        assertTrue(repo.isEmojiPronunciationEnabled())
    }

    @Test
    fun speechRatePitchVolume_clampedPerLanguage() {
        // بند 9.3: السرعة/النبرة/الصوت لا تتجاوز حدودها في المخزن مهما أرسلت الواجهة.
        repo.setSpeechRate("ar", -5f)
        assertEquals(0f, repo.getSpeechRate("ar"), 0.0f)
        repo.setSpeechRate("ar", 10f)
        assertEquals(2f, repo.getSpeechRate("ar"), 0.0f)
        repo.setPitch("ar", 99f)
        assertEquals(2f, repo.getPitch("ar"), 0.0f)
        repo.setVolume("ar", 3f)
        assertEquals(1f, repo.getVolume("ar"), 0.0f)
    }

    @Test
    fun speechRatePitchVolumeOrNull_unsetReturnsNull_and_explicitRawPreserved() {
        // غياب التفضيل ≠ «1.0x صريح»: الفارق حاسم كي لا يهبط تفضيلٌ صريح
        // قدره 1.0x إلى القيمة العامة بدل احترام اختيار المستخدم.
        assertNull(repo.getSpeechRateOrNull("ar"))
        assertNull(repo.getPitchOrNull("ar"))
        assertNull(repo.getVolumeOrNull("ar"))
        repo.setSpeechRate("ar", 1.0f)
        repo.setPitch("ar", 1.0f)
        repo.setVolume("ar", 1.0f)
        assertEquals(1.0f, repo.getSpeechRateOrNull("ar")!!, 0.0f)
        assertEquals(1.0f, repo.getPitchOrNull("ar")!!, 0.0f)
        assertEquals(1.0f, repo.getVolumeOrNull("ar")!!, 0.0f)
    }

    @Test
    fun languagePrefs_dialectFallsBackToLanguageCode() {
        // ar-EG/en-GB بلا تفضيل خاصٍ بهما يعودان تدريجياً إلى تفضيل اللغة
        // الأم (ar/en) بدل الافتراضي الصامت.
        repo.setSpeechRate("ar", 1.25f)
        repo.setPitch("en", 1.4f)
        repo.setVolume("ar", 0.8f)
        assertEquals(1.25f, repo.getSpeechRateOrNull("ar-EG")!!, 0.0f)
        assertEquals(1.4f, repo.getPitchOrNull("en-GB")!!, 0.0f)
        assertEquals(0.8f, repo.getVolumeOrNull("ar-SA")!!, 0.0f)
        assertNull(repo.getPitchOrNull("ar-EG"))
    }

    @Test
    fun languagePrefs_dialectTagWinsOverLanguageCode() {
        // التفضيل الخاص باللهجة (الوسم الكامل) يُغلَّب على تفضيل اللغة الأم.
        repo.setSpeechRate("ar", 1.0f)
        repo.setSpeechRate("ar-EG", 1.4f)
        assertEquals(1.4f, repo.getSpeechRateOrNull("ar-EG")!!, 0.0f)
        assertEquals(1.0f, repo.getSpeechRateOrNull("ar")!!, 0.0f)
    }

    @Test
    fun categoryRatePitchVolume_clamped() {
        val cat = SettingsRepository.VOICE_CATEGORY_TIME
        repo.setSpeechRateForCategory(cat, -1f)
        assertEquals(0f, repo.getSpeechRateForCategory(cat), 0.0f)
        repo.setPitchForCategory(cat, 7f)
        assertEquals(2f, repo.getPitchForCategory(cat), 0.0f)
        repo.setVolumeForCategory(cat, 5f)
        assertEquals(1f, repo.getVolumeForCategory(cat), 0.0f)
    }

    @Test
    fun defaultRatePitchVolume_clamped() {
        repo.setDefaultSpeechRate(-2f)
        assertEquals(0f, repo.getDefaultSpeechRate(), 0.0f)
        repo.setDefaultPitch(9f)
        assertEquals(2f, repo.getDefaultPitch(), 0.0f)
        repo.setDefaultVolume(9f)
        assertEquals(1f, repo.getDefaultVolume(), 0.0f)
    }

    @Test
    fun batteryCallerSmsRateVolume_clamped() {
        repo.setBatteryAnnouncementRate(4f)
        assertEquals(2f, repo.getBatteryAnnouncementRate(), 0.0f)
        repo.setBatteryAnnouncementVolume(4f)
        assertEquals(1f, repo.getBatteryAnnouncementVolume(), 0.0f)
        repo.setCallerAnnouncementRate(4f)
        assertEquals(2f, repo.getCallerAnnouncementRate(), 0.0f)
        repo.setCallerAnnouncementVolume(4f)
        assertEquals(1f, repo.getCallerAnnouncementVolume(), 0.0f)
        repo.setSmsReadingRate(4f)
        assertEquals(2f, repo.getSmsReadingRate(), 0.0f)
        repo.setSmsReadingVolume(4f)
        assertEquals(1f, repo.getSmsReadingVolume(), 0.0f)
    }

    @Test
    fun callerRepeatAndInterval_clamped() {
        repo.setCallerAnnouncementRepeat(9)
        assertEquals(5, repo.getCallerAnnouncementRepeat())
        repo.setCallerAnnouncementRepeat(0)
        assertEquals(1, repo.getCallerAnnouncementRepeat())
        repo.setCallerAnnouncementIntervalSeconds(100)
        assertEquals(10, repo.getCallerAnnouncementIntervalSeconds())
        repo.setCallerAnnouncementIntervalSeconds(0)
        assertEquals(1, repo.getCallerAnnouncementIntervalSeconds())
    }

    @Test
    fun timeInterval_clamped15to60() {
        repo.setTimeAnnouncementInterval(5)
        assertEquals(15, repo.getTimeAnnouncementInterval())
        repo.setTimeAnnouncementInterval(120)
        assertEquals(60, repo.getTimeAnnouncementInterval())
        repo.setTimeAnnouncementInterval(45)
        assertEquals(45, repo.getTimeAnnouncementInterval())
    }

    @Test
    fun convertLegacySlotsRatePitchVolume_clamped() {
        repo.setConvertRate1(-1f)
        assertEquals(0f, repo.getConvertRate1(), 0.0f)
        repo.setConvertPitch1(9f)
        assertEquals(2f, repo.getConvertPitch1(), 0.0f)
        repo.setConvertVolume1(9f)
        assertEquals(1f, repo.getConvertVolume1(), 0.0f)
        repo.setConvertRate2(9f)
        assertEquals(2f, repo.getConvertRate2(), 0.0f)
        repo.setConvertPitch2(-3f)
        assertEquals(0f, repo.getConvertPitch2(), 0.0f)
        repo.setConvertVolume2(-3f)
        assertEquals(0f, repo.getConvertVolume2(), 0.0f)
    }

    @Test
    fun callerNames_keptInMemoryWhenSecureStoreUnavailable() {
        // بند 9.2: عند تعذر فتح التخزين المشفر (Keystore) تُحفظ الأسماء في الذاكرة
        // بلا أي حذف للملف وبلا استثناء — وتظل قابلة للقراءة في نفس الجلسة،
        // وإعادة الضبط الكاملة تمسحها مع القرص معاً.
        repo.setCustomCallerNames(
            mapOf("+20123456789" to "أحمد", "+20198765432" to "فاطمة")
        )
        val names = repo.getCustomCallerNames()
        assertEquals("أحمد", names["+20123456789"])
        assertEquals("فاطمة", names["+20198765432"])
        repo.resetAllToDefault()
        assertTrue(repo.getCustomCallerNames().isEmpty())
    }

    @Test
    fun freshRepositoryInstance_readsWritesFromDisk() {
        // مسار reload(): إعادة الفتح من القرص تُحضر آخر التعديلات المكتوبة.
        repo.setNumberReadingMode(3)
        repo.setDefaultSpeechRate(1.25f)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val repo2 = SettingsRepository(context)
        assertEquals(3, repo2.getNumberReadingMode())
        assertEquals(1.25f, repo2.getDefaultSpeechRate(), 0.0f)
    }
}