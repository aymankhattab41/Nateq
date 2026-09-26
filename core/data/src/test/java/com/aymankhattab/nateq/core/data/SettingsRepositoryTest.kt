package com.aymankhattab.nateq.core.data

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
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
import java.util.Calendar

/** اختبارات مخزن الإعدادات عبر Robolectric (SharedPreferences حقيقي). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
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

    // وفاءُ الصفَّ بالعقود موثقٌ في إعلان الصنف نفسه، فالفحوصات أدناه تُثبّت
// القصد دون قيمة تشغيلية (الثنائي يثبت الصدفان أعلاه).
@Suppress("USELESS_IS_CHECK")
@Test
    fun implementsDomainContracts() {
        assertTrue(repo is LanguagePrefs)
        assertTrue(repo is SynthesisPrefs)
        assertTrue(repo is CategoryVoicePrefs)
        assertTrue(repo is ReadingPrefs)
        assertTrue(repo is AnnouncementPrefs)
        assertTrue(repo is DevicePrefs)
        assertTrue(repo is ConvertPrefs)
        assertTrue(repo is CallerNamesStore)
    }

    @Test
    fun defaults_correctValues() {
        assertEquals(1, repo.getNumberReadingMode())
        assertTrue(repo.isTimeAnnouncementEnabled())
        assertEquals(30, repo.getTimeAnnouncementInterval())
        assertFalse(repo.isTime24Hour())
        assertEquals("arabic_natural", repo.getTimeAnnouncementFormat())
    }

    @Test
    fun textReading_defaultsAndRoundTrip() {
        // مستوى الترقيم الافتراضي «البعض» حفاظاً على السلوك القائم.
        assertEquals(1, repo.getPunctuationLevel())
        repo.setPunctuationLevel(0)
        assertEquals(0, repo.getPunctuationLevel())
        repo.setPunctuationLevel(2)
        assertEquals(2, repo.getPunctuationLevel())
    }

    @Test
    fun punctuationLevel_invalid_clampedAtRepository() {
        repo.setPunctuationLevel(9)
        assertEquals(2, repo.getPunctuationLevel())
        repo.setPunctuationLevel(-3)
        assertEquals(0, repo.getPunctuationLevel())
    }

    @Test
    fun audioExpansionLevel_defaultsAndClamping() {
        assertEquals(0, repo.getAudioExpansionLevel())
        repo.setAudioExpansionLevel(1)
        assertEquals(1, repo.getAudioExpansionLevel())
        repo.setAudioExpansionLevel(2)
        assertEquals(2, repo.getAudioExpansionLevel())
        repo.setAudioExpansionLevel(99)
        assertEquals(2, repo.getAudioExpansionLevel())
        repo.setAudioExpansionLevel(-5)
        assertEquals(0, repo.getAudioExpansionLevel())
    }

    @Test
    fun engineEqualizer_saveAndRetrieve() {
        assertNull(repo.getEngineEqualizerGains("com.test.engine"))
        val gains = floatArrayOf(2.0f, 1.0f, -3.0f)
        repo.saveEngineEqualizerGains("com.test.engine", gains)
        val retrieved = repo.getEngineEqualizerGains("com.test.engine")
        assertNotNull(retrieved)
        assertEquals(3, retrieved?.size)
        assertEquals(2.0f, retrieved!![0], 0.01f)
        assertEquals(1.0f, retrieved[1], 0.01f)
        assertEquals(-3.0f, retrieved[2], 0.01f)

        repo.clearEngineEqualizerGains("com.test.engine")
        assertNull(repo.getEngineEqualizerGains("com.test.engine"))
    }

    @Test
    fun numberReadingLanguage_defaultAndRoundTrip() {
        // لغة نطق الأرقام الافتراضية "ar"
        assertEquals("ar", repo.getNumberReadingLanguage())
        repo.setNumberReadingLanguage("en")
        assertEquals("en", repo.getNumberReadingLanguage())
        // فقط "ar" أو "en" — أي قيمة أخرى تُثبّت كـ "ar"
        repo.setNumberReadingLanguage("fr")
        assertEquals("ar", repo.getNumberReadingLanguage())
        repo.setNumberReadingLanguage("auto")
        assertEquals("ar", repo.getNumberReadingLanguage())
    }

    @Test
    fun instantSilence_defaultsOffAndRoundTrip() {
        // مفاتيح الإسكات الفوري (هز/تقارب) معطّلة افتراضياً
        assertFalse(repo.isShakeToStopEnabled())
        assertFalse(repo.isProximitySilenceEnabled())
        repo.setShakeToStopEnabled(true)
        repo.setProximitySilenceEnabled(true)
        assertTrue(repo.isShakeToStopEnabled())
        assertTrue(repo.isProximitySilenceEnabled())
        repo.setShakeToStopEnabled(false)
        repo.setProximitySilenceEnabled(false)
        assertFalse(repo.isShakeToStopEnabled())
        assertFalse(repo.isProximitySilenceEnabled())
    }

    @Test
    fun dayQuietEnabled_defaultsTrue_andRoundTrip() {
        // الافتراضي «مفعّل» لكل الأيام حفاظاً على السلوك السابق
        assertFalse(repo.isDayQuietEnabled(Calendar.SUNDAY))
        assertFalse(repo.isDayQuietEnabled(Calendar.SATURDAY))
        repo.setDayQuietEnabled(Calendar.SUNDAY, false)
        assertFalse(repo.isDayQuietEnabled(Calendar.SUNDAY))
        // تعطيل يوم لا يمس غيره
        assertFalse(repo.isDayQuietEnabled(Calendar.SATURDAY))
        repo.setDayQuietEnabled(Calendar.SUNDAY, true)
        assertTrue(repo.isDayQuietEnabled(Calendar.SUNDAY))
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
        // البديل الخاطئ الأحدث من المزوّد: nateq-<lang>-local
        // يُطبع للصيغة الموحّدة (اسم اللغة كـ Locale صالح)
        repo.setPreferredVoiceId("fr", "nateq-fr-local")
        assertEquals("fr", repo.getPreferredVoiceId("fr"))
        // الصيغة القديمة "<lang>-local" المعطوبة على سامسونج تُرقّى
        repo.setPreferredVoiceId("fr", "fr-local")
        assertEquals("fr", repo.getPreferredVoiceId("fr"))
        // الصيغة الموحّدة الحالية تمرّ كما هي
        repo.setPreferredVoiceId("fr", "fr")
        assertEquals("fr", repo.getPreferredVoiceId("fr"))
    }

    @Test
    fun resetAllToDefault_restoresDefaults() {
        repo.setNumberReadingMode(5)
        repo.setTime24Hour(true)
        repo.setPunctuationLevel(0)
        repo.setTashkeelPreserved(true)
        repo.setShakeToStopEnabled(true)
        repo.resetAllToDefault()
        assertEquals(1, repo.getNumberReadingMode())
        assertFalse(repo.isTime24Hour())
        assertEquals(1, repo.getPunctuationLevel())
        assertFalse(repo.isTashkeelPreserved())
        assertFalse(repo.isShakeToStopEnabled())
    }

    @Test
    fun duckAndTimeScenarioFlags_defaultsRoundTripExportReset() {
        assertTrue(repo.isDuckMediaDuringAnnouncements())
        assertFalse(repo.isAnnounceTimeDuringCalls())
        assertTrue(repo.isAnnounceTimeDuringMedia())
        assertTrue(repo.isAnnounceTimeDuringSilent())

        repo.setDuckMediaDuringAnnouncements(false)
        repo.setAnnounceTimeDuringCalls(true)
        repo.setAnnounceTimeDuringMedia(false)
        repo.setAnnounceTimeDuringSilent(false)
        assertFalse(repo.isDuckMediaDuringAnnouncements())
        assertTrue(repo.isAnnounceTimeDuringCalls())
        assertFalse(repo.isAnnounceTimeDuringMedia())
        assertFalse(repo.isAnnounceTimeDuringSilent())

        val exported = repo.exportSettings()
        assertTrue(exported.containsKey("duck_media_during_announcements"))
        assertTrue(exported.containsKey("announce_time_during_calls"))
        assertTrue(exported.containsKey("announce_time_during_media"))
        assertTrue(exported.containsKey("announce_time_during_silent"))

        repo.resetAllToDefault()
        assertTrue(repo.isDuckMediaDuringAnnouncements())
        assertFalse(repo.isAnnounceTimeDuringCalls())
        assertTrue(repo.isAnnounceTimeDuringMedia())
        assertTrue(repo.isAnnounceTimeDuringSilent())
    }

    @Test
    fun speechBoost_defaultDisabledAtNormalSpeed() {
        assertFalse(repo.isSpeechBoostEnabled())
        assertEquals(1.0f, repo.getSpeechBoostValue())
    }

    @Test
    fun speechBoost_setValue_isClampedAndReadBack() {
        repo.setSpeechBoostEnabled(true)
        assertTrue(repo.isSpeechBoostEnabled())

        repo.setSpeechBoostValue(2.0f)
        assertEquals(2.0f, repo.getSpeechBoostValue())

        // القيم خارج النطاق الآمن (1.0..2.5) تُثبَّت عند الحفظ.
        repo.setSpeechBoostValue(0.5f)
        assertEquals(1.0f, repo.getSpeechBoostValue())
        repo.setSpeechBoostValue(9.0f)
        assertEquals(2.5f, repo.getSpeechBoostValue())
    }

    @Test
    fun speechBoost_roundTripExportAndReset() {
        repo.setSpeechBoostEnabled(true)
        repo.setSpeechBoostValue(1.7f)

        val exported = repo.exportSettings()
        assertTrue(exported.containsKey("speech_boost_enabled"))
        assertTrue(exported.containsKey("speech_boost_value"))
        assertEquals(true, exported["speech_boost_enabled"])
        assertEquals(1.7f, exported["speech_boost_value"])

        repo.resetAllToDefault()
        assertFalse(repo.isSpeechBoostEnabled())
        assertEquals(1.0f, repo.getSpeechBoostValue())
    }

    @Test
    fun speechBoost_import_sanitizesValueRange() {
        // قيمة مضاعف جامحة في نسخة احتياطية خارجية تُقصّ على 2.5 لا على 2.
        repo.importSettings(mapOf("speech_boost_value" to 4.2))
        assertEquals(2.5f, repo.getSpeechBoostValue())

        // فأقل من 1.0 يُردّ إلى 1.0 (لا يُسقط تحت الطبيعة).
        repo.importSettings(mapOf("speech_boost_value" to 0.3))
        assertEquals(1.0f, repo.getSpeechBoostValue())

        // تحوّل Double من JSON/نسخة تُعالَج كـ Float وليس Int مقطوعاً.
        repo.importSettings(mapOf("speech_boost_value" to 1.9))
        assertEquals(1.9f, repo.getSpeechBoostValue())
    }

    @Test
    fun volumeBoost_defaultDisabledAtNormalLevel() {
        assertFalse(repo.isVolumeBoostEnabled())
        assertEquals(1.0f, repo.getVolumeBoostValue())
    }

    @Test
    fun volumeBoost_setValue_isClampedAndReadBack() {
        repo.setVolumeBoostEnabled(true)
        assertTrue(repo.isVolumeBoostEnabled())

        repo.setVolumeBoostValue(2.0f)
        assertEquals(2.0f, repo.getVolumeBoostValue())

        // القيم خارج النطاق الآمن (1.0..2.5) تُثبَّت عند الحفظ.
        repo.setVolumeBoostValue(0.5f)
        assertEquals(1.0f, repo.getVolumeBoostValue())
        repo.setVolumeBoostValue(9.0f)
        assertEquals(2.5f, repo.getVolumeBoostValue())
    }

    @Test
    fun volumeBoost_roundTripExportAndReset() {
        repo.setVolumeBoostEnabled(true)
        repo.setVolumeBoostValue(1.7f)

        val exported = repo.exportSettings()
        assertTrue(exported.containsKey("volume_boost_enabled"))
        assertTrue(exported.containsKey("volume_boost_value"))
        assertEquals(true, exported["volume_boost_enabled"])
        assertEquals(1.7f, exported["volume_boost_value"])

        repo.resetAllToDefault()
        assertFalse(repo.isVolumeBoostEnabled())
        assertEquals(1.0f, repo.getVolumeBoostValue())
    }

    @Test
    fun volumeBoost_import_sanitizesValueRange() {
        // قيمة مضاعف جامحة تُقصّ على 2.5 — اسمه لا يحتوي مقطع _volume
        // (الشرط العام المقطوع لمساحات الصوت) فيحتاج فرعه الخاص إلزاماً.
        repo.importSettings(mapOf("volume_boost_value" to 4.2))
        assertEquals(2.5f, repo.getVolumeBoostValue())

        // فأقل من 1.0 يُردّ إلى 1.0.
        repo.importSettings(mapOf("volume_boost_value" to 0.3))
        assertEquals(1.0f, repo.getVolumeBoostValue())

        // تحوّل Double يُعالَج كـ Float وليس Int مقطوعاً.
        repo.importSettings(mapOf("volume_boost_value" to 1.9))
        assertEquals(1.9f, repo.getVolumeBoostValue())
    }

    @Test
    fun secondaryLanguage_defaultEnglishAndRoundTrips() {
        // لغة النطق الاحتياطية (بند اللغة الثانية): إنجليزية افتراضياً
        // وتُعيَّن فرنسية وتعود إنجليزية.
        assertEquals("en", repo.getSecondaryLanguage())
        repo.setSecondaryLanguage("fr")
        assertEquals("fr", repo.getSecondaryLanguage())
        repo.setSecondaryLanguage("en")
        assertEquals("en", repo.getSecondaryLanguage())
    }

    @Test
    fun importSettings_invalidSecondaryLanguage_pinnedToEnglish() {
        // الوسم خارج اللغات المدعومة يُثبَّت على الإنجليزية عند الاستيراد
        // حتى لا تُطلب اللغة الاحتياطية بلسانٍ غير مدعوم (سقوطٌ على وسامة
        // «it» الإيطالية مثلاً).
        val imported = mapOf("secondary_language" to "it")
        assertTrue(repo.importSettings(imported))
        assertEquals("en", repo.getSecondaryLanguage())
    }

    @Test
    fun importSettings_validSecondaryLanguage_kept() {
        // اللغة الثانية الصالحة (الألمانية) تمرّ كما هي.
        val imported = mapOf("secondary_language" to "de")
        assertTrue(repo.importSettings(imported))
        assertEquals("de", repo.getSecondaryLanguage())
    }

    @Test
    fun tashkeelPreserved_defaultOffAndRoundTrip() {
        // حفظ التشكيل معطّل افتراضياً على السلوك القائم للمحركات التي
        // لا تفهم الحركات، ويُرجع بشكل صريح ثم يُعاد تعطيله.
        assertFalse(repo.isTashkeelPreserved())
        repo.setTashkeelPreserved(true)
        assertTrue(repo.isTashkeelPreserved())
        repo.setTashkeelPreserved(false)
        assertFalse(repo.isTashkeelPreserved())
    }

    @Test
    fun followReaderRate_defaultOnAndRoundTrip() {
        // اتباع سرعة قارئ الشاشة مفعّل افتراضياً (السلوك الطبيعي الجديد:
        // لا مضاعفة تفضيلات التطبيق فوق القارئ ما لم يطلب المستخدم عكس ذلك).
        assertTrue(repo.isFollowReaderRateEnabled())
        repo.setFollowReaderRateEnabled(false)
        assertFalse(repo.isFollowReaderRateEnabled())
        repo.setFollowReaderRateEnabled(true)
        assertTrue(repo.isFollowReaderRateEnabled())
    }

    @Test
    fun firstRunSetup_defaultNotCompleted_roundTrip() {
        // أول تشغيل: المعالج غير منجز فيُعرض مرة واحدة
        assertFalse(repo.isFirstRunSetupCompleted())
        repo.setFirstRunSetupCompleted(true)
        assertTrue(repo.isFirstRunSetupCompleted())
        repo.setFirstRunSetupCompleted(false)
        assertFalse(repo.isFirstRunSetupCompleted())
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
        // بند 9.3: السرعة/النبرة/الصوت لا تتجاوز حدودها في المخزن
        // مهما أرسلت الواجهة.
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
    fun speechRatePitchVolumeOrNull_unsetReturnsNull_and_explicitRawPreserved()
    {
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
    fun timeInterval_clamped5to60() {
        // بند الأوامر 3: الفاصل 5–60 بخطوة 5 — 5 دقائق قبلها تُقيّد إلى 5
        // (كان الحد الأدنى 15 فيُقيَّد 5 إلى 15).
        repo.setTimeAnnouncementInterval(5)
        assertEquals(5, repo.getTimeAnnouncementInterval())
        repo.setTimeAnnouncementInterval(120)
        assertEquals(60, repo.getTimeAnnouncementInterval())
        repo.setTimeAnnouncementInterval(45)
        assertEquals(45, repo.getTimeAnnouncementInterval())
        repo.setTimeAnnouncementInterval(0)
        assertEquals(5, repo.getTimeAnnouncementInterval())
        repo.setTimeAnnouncementInterval(3)
        assertEquals(5, repo.getTimeAnnouncementInterval())
        // بند الأوامر 3: الاستيراد يقبل قيم كل خطوة 5 (10/55) ويعيد غير
        // الخطوة إلى 30 — كان يقبل 15/30/45/60 فقط فتُقصّ 55 إلى 30.
        repo.importSettings(mapOf("time_announcement_interval" to 10))
        assertEquals(10, repo.getTimeAnnouncementInterval())
        repo.importSettings(mapOf("time_announcement_interval" to 55))
        assertEquals(55, repo.getTimeAnnouncementInterval())
        repo.importSettings(mapOf("time_announcement_interval" to 53))
        assertEquals(30, repo.getTimeAnnouncementInterval())
    }

    @Test
    fun timeAlarmMaxPrecision_defaultOffPersistsAndImports() {
        // بند الأوامر 5: مفتاح «الدقة القصوى» — افتراضياً معطّل ويُصدَّر
        // مع النسخ الاحتياطي ويُستعاد.
        assertFalse(repo.isTimeAlarmMaxPrecisionEnabled())
        repo.setTimeAlarmMaxPrecisionEnabled(true)
        assertTrue(repo.isTimeAlarmMaxPrecisionEnabled())
        assertTrue(
            repo.exportSettings().containsKey("time_alarm_max_precision")
        )
        repo.importSettings(mapOf("time_alarm_max_precision" to false))
        assertFalse(repo.isTimeAlarmMaxPrecisionEnabled())
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
        // بند 9.2: عند تعذر فتح التخزين المشفر (Keystore) تُحفظ
        // الأسماء في الذاكرة
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
        org.robolectric.Shadows.shadowOf(
            android.os.Looper.getMainLooper()
        ).idle()
        val repo2 = SettingsRepository(context)
        assertEquals(3, repo2.getNumberReadingMode())
        assertEquals(1.25f, repo2.getDefaultSpeechRate(), 0.0f)
    }

    @Test
    @Suppress("DEPRECATION")
    fun isDeviceScreenLocked_detectsSwipeLockAndScreenOff() {
        val km = context.getSystemService(
            android.app.KeyguardManager::class.java
        )!!
        val kmShadow = org.robolectric.Shadows.shadowOf(km)
        val pm = context.getSystemService(android.os.PowerManager::class.java)!!
        val pmShadow = org.robolectric.Shadows.shadowOf(pm)

        // الشاشة مفتوحة والقفل غير معروض: حجب خاطئ مرفوض
        pmShadow.setIsInteractive(true)
        kmShadow.setKeyguardLocked(false)
        kmShadow.setIsKeyguardSecure(false)
        assertFalse(repo.isDeviceScreenLocked())

        // قفل غير آمن (Swipe to unlock) والشاشة معروضة:
        // يُعدُّ مقفلاً لإخفاء الحساسيات
        kmShadow.setKeyguardLocked(true)
        kmShadow.setIsKeyguardSecure(false)
        assertTrue(repo.isDeviceScreenLocked())

        // الشاشة مطفأة تماماً: حجب حتى بلا شاشة قفل أمن معروضة
        kmShadow.setKeyguardLocked(false)
        pmShadow.setIsInteractive(false)
        assertTrue(repo.isDeviceScreenLocked())

        // الشاشة مفتوحة وقفل أمن معروض: الحالة التقليدية لـ
        // isDeviceLocked محفوظة
        pmShadow.setIsInteractive(true)
        kmShadow.setKeyguardLocked(true)
        kmShadow.setIsKeyguardSecure(true)
        assertTrue(repo.isDeviceScreenLocked())
    }

    @Test
    fun reload_refreshesFromDiskWhenFileChangedExternally() {
        repo.setNumberReadingMode(3)
        // commit على نفس الاسم يُفرض إتمام الكتابة اللامتزامنة لـ apply()
        // السابقة على القرص (توثيق SharedPreferences.Editor).
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit().putInt("probe_flush", 1).commit()
        try {
            // محاكاة كتابة من عملية أخرى: تُعدَّل بيانات الملف XML على القرص
            // مباشرةً بجوار كائن الكاش في الذاكرة. وحده MODE_MULTI_PROCESS في
            // reload() يجعل SharedPreferencesImpl يعيد قراءة الملف فعلياً —
            // لولا ذلك تبقى القيمة القديمة 3 في الذاكرة إلى الأبد.
            val xmlFile = java.io.File(
                context.applicationInfo.dataDir,
                "shared_prefs/nateq_settings.xml"
            )
            assertTrue("ملف الإعدادات كُتب على القرص", xmlFile.exists())
            assertTrue(xmlFile.readText().contains("value=\"3\""))
            val updated = xmlFile.readText().replace(
                "name=\"number_reading_mode\" value=\"3\"",
                "name=\"number_reading_mode\" value=\"7\""
            )
            xmlFile.writeText(updated)
            repo.reload()
            assertEquals(
                "reload يقرأ آخر كتابة خارجية",
                7,
                repo.getNumberReadingMode()
            )
        } finally {
            // استعادة حالة نظيفة حتى لا تتسرب القيمة 7 لبقية الاختبارات.
            context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun reload_doesNotWriteToDiskFile() {
        // بند المرحلة 6: في الماضي كان reload() يعيد كتابة المخزن كاملاً
        // (flash/apply) فيغيّر مفتاحَ الملف/طوله ويرسل sap كتغيير متكرر —
        // الآن يبدّل لقطة الذاكرة فقط بلا أي مساس بالقرص.
        repo.setNumberReadingMode(3)
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit().putInt("probe_flush", 1).commit()
        try {
            val xmlFile = java.io.File(
                context.applicationInfo.dataDir,
                "shared_prefs/nateq_settings.xml"
            )
            assertTrue("ملف الإعدادات كُتب على القرص", xmlFile.exists())
            val updated = xmlFile.readText().replace(
                "name=\"number_reading_mode\" value=\"3\"",
                "name=\"number_reading_mode\" value=\"7\""
            )
            xmlFile.writeText(updated)
            val stampBefore = xmlFile.lastModified() to xmlFile.length()
            repo.reload()
            // القراءة انعكست على اللقطة…
            assertEquals(7, repo.getNumberReadingMode())
            // …لكن الملف لم يُلمس إطلاقاً (لا flash/apply ولا sap متكرر).
            assertEquals(
                "reload لا يعيد كتابة الملف على القرص",
                stampBefore.first, xmlFile.lastModified()
            )
            assertEquals(
                "طول الملف لم يتغير بعد reload",
                stampBefore.second, xmlFile.length()
            )
        } finally {
            context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun write_publishesChangeToSettingsObserver() {
        // بند المرحلة 6: كل كتابة عبر SnapshotPrefs تُطلق notifyChange على
        // سلطان SettingsChangeProvider فيستيقظ ContentObserver العملية
        // الأخرى. نعلن مستقلباً خاصاً بنا للتأكد أن الإشعار يصله فعلاً.
        val notified = java.util.concurrent.atomic.AtomicInteger(0)
        val observer = object : ContentObserver(
            Handler(Looper.getMainLooper())
        ) {
            override fun onChange(selfChange: Boolean) {
                notified.incrementAndGet()
            }
        }
        context.contentResolver.registerContentObserver(
            SettingsChangeProvider.uri(), false, observer
        )
        try {
            repo.setNumberReadingMode(4)
            org.robolectric.Shadows.shadowOf(
                android.os.Looper.getMainLooper()
            ).idle()
            assertTrue(
                "الكتابة عبر SnapshotPrefs تُطلق إشعاراً للمراقبين",
                notified.get() > 0
            )
        } finally {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    @Test
    fun observerTriggeredReload_swapsSnapshotForOtherProcessWrite() {
        // بند المرحلة 6: كتابةٌ من العملية الأخرى (تعديل الملف مباشرة على
        // القرص) ثم إشعار الواجهة — المراقبُ فيستدعي reload تلقائياً دون
        // نداءٍ صريح من المتصل، فتتبدّل اللقطة وتُعرَف القيمة الجديدة.
        repo.setNumberReadingMode(3)
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit().putInt("probe_flush", 1).commit()
        try {
            val xmlFile = java.io.File(
                context.applicationInfo.dataDir,
                "shared_prefs/nateq_settings.xml"
            )
            assertTrue(xmlFile.exists())
            val updated = xmlFile.readText().replace(
                "name=\"number_reading_mode\" value=\"3\"",
                "name=\"number_reading_mode\" value=\"7\""
            )
            xmlFile.writeText(updated)
            // يحاكي ما تفعله العملية الأخرى: notifyChange على السلطان المشترك.
            context.contentResolver.notifyChange(
                SettingsChangeProvider.uri(), null
            )
            org.robolectric.Shadows.shadowOf(
                android.os.Looper.getMainLooper()
            ).idle()
            assertEquals(
                "المراقب يُعاود التحميل تلقائياً عند إشعار العملية الأخرى",
                7, repo.getNumberReadingMode()
            )
        } finally {
            context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun migration_pendingWhenEncryptedOldFileUnreadable() {
        val oldFile = java.io.File(
            context.applicationInfo.dataDir,
            "shared_prefs/nateq_secure_settings.xml"
        )
        try {
            context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
                .edit().clear().commit()
            oldFile.parentFile?.mkdirs()
            // «ملف مشفر» تالف ≈ عطل Keystore عابر: يجب ألا تُعلن الهجرة
            // (KEY_MIGRATED غائب) وإلا أُهملت إعدادات المستخدم بلا رجعة.
            oldFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))
            val repo2 = SettingsRepository(context)
            assertFalse(repo2.isMigrationCompleted())
        } finally {
            context.deleteSharedPreferences("nateq_secure_settings")
            context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun migration_completesWhenNoLegacyFileExists() {
        try {
            context.deleteSharedPreferences("nateq_secure_settings")
            context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
                .edit().clear().commit()
            // لا ملف قديم (تثبيت نظيف): أعن الهجرة مرة واحدة بلا بيانات.
            val repo2 = SettingsRepository(context)
            assertTrue(repo2.isMigrationCompleted())
        } finally {
            context.deleteSharedPreferences("nateq_secure_settings")
            context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun engineAndVoicePerLanguage_accessors() {
        assertNull(repo.getEngineForLanguage("ar"))
        assertNull(repo.getVoiceForLanguage("ar"))
        repo.setEnginePreferenceForLanguage(
            "ar", "org.nobody.multitts", "multitts-ar", 1.0f, 1.0f, 1.0f
        )
        assertEquals("org.nobody.multitts", repo.getEngineForLanguage("ar"))
        assertEquals("multitts-ar", repo.getVoiceForLanguage("ar"))
        // لغة أخرى بلا إعداد → null (لا تسرّب بين اللغات)
        assertNull(repo.getEngineForLanguage("en"))
    }

    @Test
    fun enginePerCategory_defaultsNull_roundTrip_removable() {
        // بلا إعداد → null (يُحسم المحرك ديناميكياً وقت النطق)
        assertNull(repo.getEngineForCategory("caller"))
        assertNull(repo.getEngineForCategory("battery"))
        assertNull(repo.getEngineForCategory("time"))
        assertNull(repo.getEngineForCategory("numbers"))
        assertNull(repo.getEngineForCategory("notifications"))

        repo.setEngineForCategory("caller", "org.nobody.multitts")
        assertNull(repo.getEngineForCategory("battery"))
        assertEquals(
            "org.nobody.multitts", repo.getEngineForCategory("caller")
        )
        repo.setEngineForCategory("caller", null)
        assertNull(repo.getEngineForCategory("caller"))
    }

    @Test
    fun callerEnginesPerLanguage_independentRoundTripNoLeak() {
        val ar = SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR
        val en = SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN
        // بلا إعداد → null للغتين (تلقائي وقت النطق)
        assertNull(repo.getEngineForCategory(ar))
        assertNull(repo.getEngineForCategory(en))

        repo.setEngineForCategory(ar, "org.arabic.speech")
        assertEquals(
            "org.arabic.speech", repo.getEngineForCategory(ar)
        )
        // لا تسرّب إلى الإنجليزية
        assertNull(repo.getEngineForCategory(en))

        repo.setEngineForCategory(en, "org.english.speech")
        assertEquals(
            "org.arabic.speech", repo.getEngineForCategory(ar)
        )
        assertEquals(
            "org.english.speech", repo.getEngineForCategory(en)
        )

        repo.setEngineForCategory(ar, null)
        assertNull(repo.getEngineForCategory(ar))
        assertEquals(
            "org.english.speech", repo.getEngineForCategory(en)
        )
    }

    @Test
    fun languageForCategory_defaultsNullRoundTripNoLeak() {
        assertNull(repo.getLanguageForCategory("time"))
        assertNull(repo.getLanguageForCategory("numbers"))
        repo.setLanguageForCategory("time", "en")
        assertEquals("en", repo.getLanguageForCategory("time"))
        // لا تسرّب بين الفئات
        assertNull(repo.getLanguageForCategory("numbers"))
        repo.setLanguageForCategory("time", "ar")
        assertEquals("ar", repo.getLanguageForCategory("time"))
    }

    @Test
    fun batteryAndSmsLanguage_defaultsNullAndRoundTrip() {
        assertNull(repo.getBatteryAnnouncementLanguage())
        assertNull(repo.getSmsReadingLanguage())
        repo.setBatteryAnnouncementLanguage("en")
        repo.setSmsReadingLanguage("en")
        assertEquals("en", repo.getBatteryAnnouncementLanguage())
        assertEquals("en", repo.getSmsReadingLanguage())
        repo.setBatteryAnnouncementLanguage("ar")
        repo.setSmsReadingLanguage("ar")
        assertEquals("ar", repo.getBatteryAnnouncementLanguage())
        assertEquals("ar", repo.getSmsReadingLanguage())
    }

    @Test
    fun languageKeys_exportAndReset() {
        repo.setLanguageForCategory("time", "en")
        repo.setBatteryAnnouncementLanguage("en")
        repo.setSmsReadingLanguage("en")
        val exported = repo.exportSettings()
        assertTrue(exported.containsKey("language_for_time"))
        assertTrue(exported.containsKey("battery_announcement_language"))
        assertTrue(exported.containsKey("sms_reading_language"))
        repo.resetAllToDefault()
        assertNull(repo.getLanguageForCategory("time"))
        assertNull(repo.getBatteryAnnouncementLanguage())
        assertNull(repo.getSmsReadingLanguage())
    }

    @Test
    fun soundCues_defaultsAndRoundTrip() {
        // افتراضيات الرنة ووضع مؤثر البطارية
        assertTrue(repo.isTimeChimeEnabled())
        assertEquals("classic_bell", repo.getTimeChimeSound())
        assertEquals(0.5f, repo.getTimeChimeVolume(), 0.0f)
        assertEquals(0, repo.getBatterySoundCueMode())

        repo.setTimeChimeEnabled(false)
        assertFalse(repo.isTimeChimeEnabled())
        repo.setTimeChimeEnabled(true)
        assertTrue(repo.isTimeChimeEnabled())

        repo.setTimeChimeSound("digital_chime")
        assertEquals("digital_chime", repo.getTimeChimeSound())
        repo.setTimeChimeSound("soft_ding")
        assertEquals("soft_ding", repo.getTimeChimeSound())

        // قيمة خارج القائمة البيضاء → classic_bell
        repo.setTimeChimeSound("alien_blast")
        assertEquals("classic_bell", repo.getTimeChimeSound())

        repo.setTimeChimeVolume(1.0f)
        assertEquals(1.0f, repo.getTimeChimeVolume(), 0.0f)
        // خارج النطاق 0.1..1 يُقيَّد
        repo.setTimeChimeVolume(0.0f)
        assertEquals(0.1f, repo.getTimeChimeVolume(), 0.0f)

        repo.setBatterySoundCueMode(1)
        assertEquals(1, repo.getBatterySoundCueMode())
        repo.setBatterySoundCueMode(2)
        assertEquals(2, repo.getBatterySoundCueMode())
        // خارج النطاق 0..2 يُقيَّد
        repo.setBatterySoundCueMode(9)
        assertEquals(2, repo.getBatterySoundCueMode())
    }

    @Test
    fun batteryCueVolume_defaultsAndClamps() {
        // افتراضياً 0.8؛ خارج 0.1..1 يُقيَّد (بند 3-3).
        assertEquals(0.8f, repo.getBatteryCueVolume(), 0.0f)
        repo.setBatteryCueVolume(0.5f)
        assertEquals(0.5f, repo.getBatteryCueVolume(), 0.0f)
        repo.setBatteryCueVolume(0.0f)
        assertEquals(0.1f, repo.getBatteryCueVolume(), 0.0f)
        repo.setBatteryCueVolume(2.0f)
        assertEquals(1.0f, repo.getBatteryCueVolume(), 0.0f)
    }

    // ===== اختبارات الاستيراد (importSettings) =====

    @Test
    fun importSettings_longValue_copiedAsLong() {
        // بند 5.1: القيم الطويلة (Long) تُستعاد putLong كما هي — كانت
        // تُسقط صامتة في `when` (لا فرعَ لـ Long) أو تتحول int فتُقتطع
        // خارج مدى Int (3_000_000_000L تتحول -1294967296!).
        val huge = 3_000_000_000L
        assertTrue(
            repo.importSettings(
                mapOf("time_chime_volume" to 0.6f, "test_long_key" to huge)
            )
        )
        assertEquals(0.6f, repo.getTimeChimeVolume(), 0.0f)
        val prefs = context.getSharedPreferences(
            "nateq_settings", Context.MODE_PRIVATE
        )
        assertEquals(huge, prefs.getLong("test_long_key", 0L))
    }

    @Test
    fun importSettings_preservesMigrationFlags() {
        // وسوم الترحيل الثلاثة تبدأ بـ _ وتُستثنى من الاستيراد، لكن لا
        // يجوز أن يمحوها clear() وإلا يتحرك الترحيل من جديد فوق نسخةٍ
        // مكتملة — تُحفظ وتُعاد بعد المسح.
        val prefs = context.getSharedPreferences(
            "nateq_settings", Context.MODE_PRIVATE
        )
        // بذر الوسوم كأن هجرةً ثلاثيةً أكملت فعلها فعلاً.
        prefs.edit()
            .putBoolean("_migrated_to_plain", true)
            .putBoolean("_quiet_days_migrated", true)
            .putBoolean("_convert_slots_migrated", true)
            .commit()
        val imported = mapOf(
            "number_reading_mode" to 6,
            "time_chime_sound" to "soft_ding"
        )
        assertTrue(repo.importSettings(imported))
        assertEquals(6, repo.getNumberReadingMode())
        assertEquals("soft_ding", repo.getTimeChimeSound())
        assertTrue(prefs.getBoolean("_migrated_to_plain", false))
        assertTrue(prefs.getBoolean("_quiet_days_migrated", false))
        assertTrue(prefs.getBoolean("_convert_slots_migrated", false))
    }

    @Test
    fun importSettings_safeDefaultFalseKeys_resetEvenIfTrue() {
        // مفاتيح المستشعرات الحساسة (الهز/التقارب) تُثبَّت false على
        // الاستيراد مهما وردت في النسخة — لا يجوز إكمالهما عند ترميناً
        // الانتقال من جهازٍ آخر مفعَّلاً عليه
        repo.setShakeToStopEnabled(true)
        repo.setProximitySilenceEnabled(true)
        val imported = mapOf(
            "shake_to_stop_enabled" to true,
            "proximity_silence_enabled" to true
        )
        assertTrue(repo.importSettings(imported))
        assertFalse(repo.isShakeToStopEnabled())
        assertFalse(repo.isProximitySilenceEnabled())
    }

    @Test
    fun rmsCalibration_exportImportClear_roundTrip() {
        // بندا د.3.3/د.3.8: معايرة RMS لكل محرك تُحفظ وتُصدَّر (المفتاح
        // لا يبدأ بشرطة سفلية فيُشمل بالنسخ الاحتياطي) وتُستورَد وتُمسح.
        assertNull(repo.getEngineRmsCalibration("com.example.tts"))
        repo.saveEngineRmsCalibration("com.example.tts", 2.5f)
        assertEquals(
            2.5f, repo.getEngineRmsCalibration("com.example.tts")!!, 0.0f
        )
        val exported = repo.exportSettings()
        assertEquals(
            2.5f,
            exported["rms_calibration_com.example.tts"] as Float,
            0.0f
        )
        // استيراد نسخةٍ بقيمةٍ جديدةٍ يستعيدها كما هي (بلا تعقيلٍ خاص).
        assertTrue(
            repo.importSettings(
                mapOf("rms_calibration_com.example.tts" to 3.0f)
            )
        )
        assertEquals(
            3.0f, repo.getEngineRmsCalibration("com.example.tts")!!, 0.0f
        )
        // المسح يزيل المفتاح (إعادة معايرة) ولا يمسّ المحركات الأخرى.
        repo.saveEngineRmsCalibration("com.other.tts", 1.5f)
        repo.clearEngineRmsCalibration("com.example.tts")
        assertNull(repo.getEngineRmsCalibration("com.example.tts"))
        assertEquals(
            1.5f, repo.getEngineRmsCalibration("com.other.tts")!!, 0.0f
        )
        // قيمة غير محدودة مرفوضة حفظاً فتبقى القراءة غائبة.
        repo.saveEngineRmsCalibration("com.example.tts", Float.NaN)
        assertNull(repo.getEngineRmsCalibration("com.example.tts"))
    }

    @Test
    fun rmsCalibration_blankEngine_usesStableFallbackKey() {
        repo.saveEngineRmsCalibration("   ", 1.25f)
        assertEquals(1.25f, repo.getEngineRmsCalibration("")!!, 0.0f)
        assertTrue(
            repo.exportSettings().containsKey("rms_calibration_unknown")
        )
    }

    @Test
    fun timeChimeQuarters_defaultsAndRoundTrip() {
        assertTrue(repo.isTimeChimeAt0Enabled())
        assertFalse(repo.isTimeChimeAt15Enabled())
        assertFalse(repo.isTimeChimeAt30Enabled())
        assertFalse(repo.isTimeChimeAt45Enabled())

        repo.setTimeChimeAt0Enabled(false)
        repo.setTimeChimeAt15Enabled(true)
        repo.setTimeChimeAt30Enabled(true)
        repo.setTimeChimeAt45Enabled(true)

        assertFalse(repo.isTimeChimeAt0Enabled())
        assertTrue(repo.isTimeChimeAt15Enabled())
        assertTrue(repo.isTimeChimeAt30Enabled())
        assertTrue(repo.isTimeChimeAt45Enabled())

        repo.resetAllToDefault()
        assertTrue(repo.isTimeChimeAt0Enabled())
        assertFalse(repo.isTimeChimeAt15Enabled())
        assertFalse(repo.isTimeChimeAt30Enabled())
        assertFalse(repo.isTimeChimeAt45Enabled())
    }

    @Test
    fun customChimeUri_saveRetrieveAndProcessRestart() {
        assertEquals("", repo.getCustomChimeUri())
        val sampleUri = "content://media/external/audio/media/42"
        repo.setCustomChimeUri(sampleUri)
        assertEquals(sampleUri, repo.getCustomChimeUri())

        // محاكاة إعادة تشغيل العملية عبر بناء مثيل جديد
        val restartedRepo = SettingsRepository.create(context)
        assertEquals(sampleUri, restartedRepo.getCustomChimeUri())

        repo.resetAllToDefault()
        assertEquals("", repo.getCustomChimeUri())
    }

    @Test
    fun quarterCustomChimeUris_saveRetrieveExportAndReset() {
        // لكل ربع مسار مستقل لا يمس مسار رأس الساعة :00.
        assertEquals("", repo.getCustomChimeUri15())
        assertEquals("", repo.getCustomChimeUri30())
        assertEquals("", repo.getCustomChimeUri45())

        val uri15 = "content://media/external/audio/media/115"
        val uri30 = "content://media/external/audio/media/130"
        val uri45 = "content://media/external/audio/media/145"
        repo.setCustomChimeUri15(uri15)
        repo.setCustomChimeUri30(uri30)
        repo.setCustomChimeUri45(uri45)

        assertEquals(uri15, repo.getCustomChimeUri15())
        assertEquals(uri30, repo.getCustomChimeUri30())
        assertEquals(uri45, repo.getCustomChimeUri45())
        assertEquals("", repo.getCustomChimeUri())

        // محاكاة إعادة تشغيل العملية عبر بناء مثيل جديد.
        val restarted = SettingsRepository.create(context)
        assertEquals(uri15, restarted.getCustomChimeUri15())
        assertEquals(uri30, restarted.getCustomChimeUri30())
        assertEquals(uri45, restarted.getCustomChimeUri45())

        // التصدير يشمل المفاتيح الثلاثة بلا بادئة _.
        val exported = repo.exportSettings()
        assertEquals(uri15, exported["custom_chime_uri_15"])
        assertEquals(uri30, exported["custom_chime_uri_30"])
        assertEquals(uri45, exported["custom_chime_uri_45"])

        repo.resetAllToDefault()
        assertEquals("", repo.getCustomChimeUri15())
        assertEquals("", repo.getCustomChimeUri30())
        assertEquals("", repo.getCustomChimeUri45())
    }
}