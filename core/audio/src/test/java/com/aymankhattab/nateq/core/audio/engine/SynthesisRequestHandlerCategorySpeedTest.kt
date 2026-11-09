package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارس عقد تطبيق سرعات ونبرات ومستويات فئات الأصوات
 * في [SynthesisRequestHandler] و[NateqTtsService].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SynthesisRequestHandlerCategorySpeedTest {

    private lateinit var settings: SettingsRepository
    private lateinit var handler: SynthesisRequestHandler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = SettingsRepository.create(context)
        settings.resetAllToDefault()
        val catalog = VoiceCatalog(emptyList())
        handler = SynthesisRequestHandler(catalog, settings)
    }

    @Test
    fun categorySpeechRate_explicitCategoryRateTakesPrecedence() {
        // فئة أحداث LORD تم ضبط سرعتها صراحة على 1.8x
        settings.setSpeechRateForCategory(
            SettingsRepository.VOICE_CATEGORY_LORD_SYSTEM,
            1.8f
        )
        // السرعة العامة 1.0x
        settings.setDefaultSpeechRate(1.0f)

        val rate = handler.getSpeechRateFor(
            "ar",
            SettingsRepository.VOICE_CATEGORY_LORD_SYSTEM
        )
        assertEquals(1.8f, rate, 0.001f)
    }

    @Test
    fun categorySpeechRate_fallsBackToLanguageExplicitWhenCategoryNotSet() {
        // لغة صريحة تم تعيين سرعتها 1.4x
        settings.setSpeechRate("en", 1.4f)

        val rate = handler.getSpeechRateFor(
            "en",
            SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
        )
        // بما أن الإشعارات لم تُعيّن صراحة، تؤخذ سرعة اللغة
        assertEquals(1.4f, rate, 0.001f)
    }

    @Test
    fun categorySpeechRate_fallsBackToDefaultRateWhenNeitherIsSet() {
        settings.setDefaultSpeechRate(1.25f)

        val rate = handler.getSpeechRateFor(
            "fr",
            SettingsRepository.VOICE_CATEGORY_TIME
        )
        // لم يُعيّن صريح للوقت ولا للفرنسية ⇒ يرث الافتراضية
        assertEquals(1.25f, rate, 0.001f)
    }

    @Test
    fun categoryPitchAndVolume_explicitCategoryTakesPrecedence() {
        settings.setPitchForCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY,
            1.5f
        )
        settings.setVolumeForCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY,
            0.7f
        )

        val pitch = handler.getPitchFor(
            "ar",
            SettingsRepository.VOICE_CATEGORY_BATTERY
        )
        val volume = handler.getVolumeFor(
            "ar",
            SettingsRepository.VOICE_CATEGORY_BATTERY
        )

        assertEquals(1.5f, pitch, 0.001f)
        assertEquals(0.7f, volume, 0.001f)
    }
}
