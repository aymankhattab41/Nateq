package com.aymankhattab.nateq.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticLogSettingsTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository.create(context)
        settings.resetAllToDefault()
    }

    @Test
    fun testVocalizerNotAvailable_clearsVocalizerFromNumbers() {
        // حالة السجل: vocalizer_embedded غير مثبت على الجهاز
        settings.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            "vocalizer_embedded"
        )
        settings.setPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            "ara-SAU-Leila",
            "vocalizer_embedded"
        )

        // استدعاء الإصلاح بدون vocalizer_embedded في المحركات المتاحة
        val installedEngines = listOf(
            "com.samsung.SMT",
            "org.nobody.multitts"
        )
        EngineLanguageConsistency.repairAllInconsistentVoices(
            settings = settings,
            catalog = null,
            availableEngines = installedEngines
        )

        val repairedEngine = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )
        val repairedVoice = settings.getPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )

        // يجب تفريغ المحرك والصوت غير المتاحين حتى لا يفشل نطق الأرقام
        assertNull(
            "يجب تفريغ المحرك غير المثبت",
            repairedEngine
        )
        assertNull(
            "يجب تفريغ الصوت غير المثبت",
            repairedVoice
        )
    }

    @Test
    fun testVocalizerNotAvailable_doesNotRestoreToVocalizer() {
        // فئة أرقام بلا صوت ومحرك
        settings.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            null
        )
        settings.setPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            ""
        )

        val installedEngines = listOf(
            "com.samsung.SMT",
            "org.nobody.multitts"
        )
        EngineLanguageConsistency.repairAllInconsistentVoices(
            settings = settings,
            catalog = null,
            availableEngines = installedEngines
        )

        val repairedEngine = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )
        // يجب ألا يُجبر على vocalizer_embedded عند عدم توفره
        assertNull(repairedEngine)
    }
}
