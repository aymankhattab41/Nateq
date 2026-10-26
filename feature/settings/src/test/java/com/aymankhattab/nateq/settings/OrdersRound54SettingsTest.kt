package com.aymankhattab.nateq.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات الجولة 54 — الأمر 2: إصلاح تناقض المحرك والصوت وحفظ الزوج.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class OrdersRound54SettingsTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository.create(context)
        settings.resetAllToDefault()
    }

    @Test
    fun testOrder2_repairInconsistentNumbersVoiceLeila() {
        // محاكاة الحالة في السجل: أرقام بمحرك Acapela وصوت Leila
        settings.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            "com.acapelagroup.android.tts"
        )
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "preferred_voice_${SettingsRepository.VOICE_CATEGORY_NUMBERS}",
                "ara-SAU-Leila"
            )
            .commit()

        EngineLanguageConsistency.repairAllInconsistentVoices(settings)

        // الصوت يجب أن يبقى Leila والمحرك يُصلح إلى vocalizer_embedded
        val repairedVoice = settings.getPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )
        val repairedEngine = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )
        assertEquals("ara-SAU-Leila", repairedVoice)
        assertEquals("vocalizer_embedded", repairedEngine)
    }

    @Test
    fun testOrder2_repairNumbersVoiceWhenOnlyTarikInstalled() {
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "preferred_voice_${SettingsRepository.VOICE_CATEGORY_NUMBERS}",
                "ara-SAU-Leila"
            )
            .putString(
                "engine_${SettingsRepository.VOICE_CATEGORY_NUMBERS}",
                "vocalizer_embedded"
            )
            .commit()

        val tarikCatalog = EngineVoicesCatalog(
            mapOf(
                "ar" to listOf(
                    EngineVoicesRow(
                        enginePackage = "vocalizer_embedded",
                        engineLabel = "Vocalizer",
                        voices = listOf(
                            VoiceOption("ara-SAU-Tarik", "طارق")
                        )
                    )
                )
            ),
            arabicVoiceLabel = "العربية",
            englishVoiceLabel = "English"
        )

        EngineLanguageConsistency.repairAllInconsistentVoices(
            settings,
            catalog = tarikCatalog
        )

        val repairedVoice = settings.getPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )
        assertEquals("ara-SAU-Tarik", repairedVoice)
    }

    @Test
    fun testOrder2_repairEnglishVoiceKaren() {
        // محاكاة الحالة في السجل: إنجليزية بمحرك AutoTTS وصوت Karen
        settings.setPreferredVoiceId("en", "eng-USA-Karen")
        settings.setEngineForLanguage("en", "com.autotts.engine")

        EngineLanguageConsistency.repairAllInconsistentVoices(settings)

        val repairedVoice = settings.getPreferredVoiceId("en")
        val repairedEngine = settings.getEngineForLanguage("en")
        assertEquals("eng-USA-Karen", repairedVoice)
        assertEquals("vocalizer_embedded", repairedEngine)
    }

    @Test
    fun testOrder2_languageSpecificCategoryVoices() {
        settings.setTimeAnnouncementVoiceAr("ara-SAU-Tarik")
        settings.setTimeAnnouncementVoiceEn("eng-USA-Karen")

        assertEquals("ara-SAU-Tarik", settings.getTimeAnnouncementVoiceAr())
        assertEquals("eng-USA-Karen", settings.getTimeAnnouncementVoiceEn())

        settings.setNumberAnnouncementVoiceAr("ara-SAU-Leila")
        settings.setNumberAnnouncementVoiceEn("eng-USA-Karen")

        assertEquals("ara-SAU-Leila", settings.getNumberAnnouncementVoiceAr())
        assertEquals("eng-USA-Karen", settings.getNumberAnnouncementVoiceEn())
    }

    @Test
    fun testOrder2_unownedVoiceSavedToBackup() {
        settings.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_TIME,
            "com.acapelagroup.android.tts"
        )
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "preferred_voice_${SettingsRepository.VOICE_CATEGORY_TIME}",
                "ar-xa-x-sfg"
            )
            .commit()

        EngineLanguageConsistency.repairAllInconsistentVoices(settings)

        val backup = settings.getVoiceRepairBackup(
            SettingsRepository.VOICE_CATEGORY_TIME
        )
        assertNotNull(backup)
        assertTrue(backup?.contains("ar-xa-x-sfg") == true)
    }
}
