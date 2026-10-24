package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.engine.SynthesisRequestHandler
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.audio.providers.SystemVoiceProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.engine.EngineMarkers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات الجولة 53 — الأمر 5:
 * نطق «مساءً/صباحاً/ظهراً» مع محرك vocalizer_embedded:
 * كسر السياق النحوي بفاصلة في غير الساعة التامة، والنون الصريحة،
 * ونمط «في المساء/في الصباح/بعد الظهر».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TimeAnnouncementRound53Test {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var manager: TimeAnnouncementManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository(context)
        val providers = listOf(SystemVoiceProvider(context, settings))
        val catalog = VoiceCatalog(providers)
        val handler = SynthesisRequestHandler(catalog, settings)
        manager = TimeAnnouncementManager(
            context, settings, catalog, handler
        )
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val method = TimeAnnouncementManager::class.java.getDeclaredMethod(
            "formatArabicNaturalTime",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(manager, hour, minute) as String
    }

    @Test
    fun vocalizerEmbedded_explicitNunComma_matchesCommand5Spec() {
        settings.setDayPeriodStyle(
            SettingsRepository.DAY_PERIOD_STYLE_EXPLICIT_NUN_COMMA
        )

        val minutes = listOf(0, 15, 30, 45, 7, 20)
        val hours = listOf(10, 12, 17) // صباحاً، ظهراً، مساءً

        for (h in hours) {
            for (m in minutes) {
                val formatted = formatTime(h, m)

                // المعالجة لمحرك vocalizer_embedded
                val finalSpeech = ArabicSpeechNormalizer.prepareArabicForEngine(
                    text = formatted,
                    languageTag = "ar",
                    enginePackage = "vocalizer_embedded",
                    explicitNunForDayPeriod = true
                )

                // 1. يحتوي على النون الصريحة
                val hasExplicitNun =
                    finalSpeech.contains(ArabicSpeechNormalizer.SABA_AN) ||
                        finalSpeech.contains(ArabicSpeechNormalizer.DHUHR_AN) ||
                        finalSpeech.contains(ArabicSpeechNormalizer.MASA_AN)
                assertTrue(
                    "يجب أن يحوي نوناً صريحة لـ $h:$m: $finalSpeech",
                    hasExplicitNun
                )

                // 2. فاصلة قبل الفترة في غير الساعة التامة
                if (m != 0) {
                    assertTrue(
                        "يجب وجود فاصلة قبل الفترة في غير التامة لـ $h:$m: " +
                            finalSpeech,
                        finalSpeech.contains("، ")
                    )
                }

                // 3. لا علامة خاصة للتنوين
                assertFalse(
                    "يجب ألا يحوي علامة التنوين الخاصة: $finalSpeech",
                    finalSpeech.contains(EngineMarkers.TANWEEN_MARKER)
                )
            }
        }
    }

    @Test
    fun vocalizerEmbedded_prepositionalStyle_noTanween() {
        settings.setDayPeriodStyle(
            SettingsRepository.DAY_PERIOD_STYLE_PREPOSITIONAL
        )

        val minutes = listOf(0, 15, 30, 45, 7, 20)
        val hours = listOf(10, 12, 17)

        for (h in hours) {
            for (m in minutes) {
                val formatted = formatTime(h, m)
                val finalSpeech = ArabicSpeechNormalizer.prepareArabicForEngine(
                    text = formatted,
                    languageTag = "ar",
                    enginePackage = "vocalizer_embedded",
                    explicitNunForDayPeriod = false
                )

                // لا تنوين ولا نون صريحة
                assertFalse(
                    "نمط في المساء يجب ألا يحوي تنويناً لـ $h:$m: $finalSpeech",
                    finalSpeech.contains("ً")
                )
                assertFalse(
                    finalSpeech.contains(ArabicSpeechNormalizer.MASA_AN)
                )

                // يحتوي على الصيغة المجرورة المناسبة
                val hasPrep = finalSpeech.contains("في الصباح") ||
                    finalSpeech.contains("بعد الظهر") ||
                    finalSpeech.contains("في المساء")
                assertTrue(
                    "يجب أن يحوي صيغة مجرورة لـ $h:$m: $finalSpeech",
                    hasPrep
                )
            }
        }

        // إعادة الوضع الافتراضي
        settings.setDayPeriodStyle(
            SettingsRepository.DAY_PERIOD_STYLE_EXPLICIT_NUN_COMMA
        )
    }
}
