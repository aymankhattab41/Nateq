package com.aymankhattab.nateq.settings

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** اختبارات بناء معاينة النطق المشتركة (بند الأوامر 4): القيم تُقرأ من
 *  العرض الحالي وقت الضغط (موضع السبنرا وتقدم الشرائط) لا من القيم
 *  المحفوظة القديمة — دوال نقية JVM بلا Robolectric. */
class VoicePreviewParamsTest {

    private val voiceAr = NateqVoice(
        name = "ar-voice",
        languageTag = "ar",
        displayName = "عربي",
        locale = Locale.forLanguageTag("ar")
    )
    private val voiceEn = NateqVoice(
        name = "en-voice",
        languageTag = "en-US",
        displayName = "English",
        locale = Locale.forLanguageTag("en-US")
    )
    private val voices = listOf(voiceAr, voiceEn)

    @Test
    fun sms_PicksDisplayedSliderAndVoicePositions() {
        // القيم المعروضة الآن: صوت ثانٍ + سرعة 50 + نبرة 0 + صوت 75%.
        val params = buildPreviewParams(
            voices = voices,
            voiceSelection = 1,
            enginePkg = "com.engine",
            rateProgress = 50,
            pitchProgress = 0,
            volumePercent = 75,
            sampleText = "تجربة"
        )
        assertEquals(voiceEn.name, params.voiceName)
        assertEquals("en-US", params.languageTag)
        assertEquals("com.engine", params.enginePkg)
        assertEquals(0.5f, params.speechRate, 0f)
        assertEquals(MIN_SPEED_PITCH_FACTOR, params.pitch, 0f)
        assertEquals(0.75f, params.volume, 0f)
    }

    @Test
    fun sms_OutOfRangeVoiceFallsBackToFirstAndClampsVolume() {
        val params = buildPreviewParams(
            voices = voices,
            voiceSelection = 99,
            enginePkg = null,
            rateProgress = 100,
            pitchProgress = 100,
            volumePercent = 200,
            sampleText = "x"
        )
        assertEquals(voiceAr.name, params.voiceName)
        assertEquals(1f, params.speechRate, 0f)
        assertEquals(1f, params.pitch, 0f)
        assertEquals(1f, params.volume, 0f)
    }

    @Test
    fun category_PicksSavedVoiceByIdAndClampsRates() {
        val params = buildCategoryPreviewParams(
            voices = voices,
            voiceId = voiceAr.name,
            enginePkg = "com.engine",
            rate = 0.1f,
            pitch = 2.0f,
            volume = 1.5f,
            sampleText = "إعلان"
        )
        assertEquals(voiceAr.name, params.voiceName)
        assertEquals("ar", params.languageTag)
        assertEquals(0.25f, params.speechRate, 0f)
        assertEquals(2f, params.pitch, 0f)
        assertEquals(1f, params.volume, 0f)
    }

    @Test
    fun category_UnknownVoiceFallsBackToFirst() {
        val params = buildCategoryPreviewParams(
            voices = voices,
            voiceId = "missing",
            enginePkg = null,
            rate = 1f,
            pitch = 1f,
            volume = 1f,
            sampleText = "x"
        )
        assertEquals(voiceAr.name, params.voiceName)
    }

    @Test
    fun chime_SoundNameFollowsSpinnerPositions() {
        assertEquals(CHIME_SOUND_DEFAULT, chimeSoundNameAt(0))
        assertEquals(CHIME_SOUND_DIGITAL, chimeSoundNameAt(1))
        assertEquals(CHIME_SOUND_SOFT, chimeSoundNameAt(2))
        // أي موضع خارج القائمة يعود للنغمة الكلاسيكية.
        assertEquals(CHIME_SOUND_DEFAULT, chimeSoundNameAt(7))
        assertEquals(CHIME_SOUND_DEFAULT, chimeSoundNameAt(-1))
    }

    @Test
    fun chime_VolumeMapsProgressToPointOneToOne() {
        assertEquals(0.1f, chimeVolumeFromProgress(0), 0f)
        assertEquals(0.55f, chimeVolumeFromProgress(50), 0.001f)
        assertEquals(1f, chimeVolumeFromProgress(100), 0f)
        assertEquals(1f, chimeVolumeFromProgress(150), 0f)
    }
}