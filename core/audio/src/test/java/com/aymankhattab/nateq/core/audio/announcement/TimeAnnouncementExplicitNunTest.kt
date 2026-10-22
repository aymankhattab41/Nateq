package com.aymankhattab.nateq.core.audio.announcement

import com.aymankhattab.nateq.core.engine.EngineMarkers
import com.aymankhattab.nateq.core.audio.engine.SpeechChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات الجولة 50 — الأمر 5:
 * نطق «صباحاً/مساءً/ظهراً» بنون صريحة أو تنوين محمي بحسب الإعداد.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TimeAnnouncementExplicitNunTest {

    private val phrases = listOf(
        "الساعة العاشرة صباحاً",
        "الساعة الواحدة ظهراً",
        "الساعة الثامنة مساءً",
        "العاشرة صباحاً وسبع دقائق",
        "الساعة العاشرة وخمس دقائق صباحاً"
    )

    @Test
    fun explicitNun_enabled_convertsAllPhrasesToExplicitNun() {
        // لمحرك يفهم التشكيل ولكن مع تفعيل day_period_explicit_nun = true
        for (phrase in phrases) {
            val result = ArabicSpeechNormalizer.prepareArabicForEngine(
                text = phrase,
                languageTag = "ar",
                enginePackage = "com.google.android.tts", // يفهم التشكيل
                explicitNunForDayPeriod = true
            )
            val hasExplicitNun =
                result.contains(ArabicSpeechNormalizer.SABA_AN) ||
                result.contains(ArabicSpeechNormalizer.DHUHR_AN) ||
                result.contains(ArabicSpeechNormalizer.MASA_AN)
            assertTrue(
                "يجب أن تحوي الجملة نوناً صريحة: $phrase -> $result",
                hasExplicitNun
            )
            assertFalse(
                "يجب ألا تحوي علامة التنوين الخاصة: $result",
                result.contains(EngineMarkers.TANWEEN_MARKER)
            )
        }
    }

    @Test
    fun explicitNun_disabled_preservesTanweenForTashkeelAwareEngine() {
        // لمحرك يفهم التشكيل مع تعطيل day_period_explicit_nun = false
        val saba = "الساعة العاشرة صباحاً${EngineMarkers.TANWEEN_MARKER}"
        val resSaba = ArabicSpeechNormalizer.prepareArabicForEngine(
            text = saba,
            languageTag = "ar",
            enginePackage = "com.google.android.tts",
            explicitNunForDayPeriod = false
        )
        assertFalse(
            "لا تحول لنون صريحة عند تعطيل الإعداد لمحرك مشكول",
            resSaba.contains(ArabicSpeechNormalizer.SABA_AN)
        )
        assertTrue(
            "تحتفظ بلفظ صباحاً بالتنوين",
            resSaba.contains("صباحاً")
        )
        assertFalse(
            "تُحذف علامة TANWEEN_MARKER",
            resSaba.contains(EngineMarkers.TANWEEN_MARKER)
        )

        val dhuhr = "الساعة الواحدة ظهراً${EngineMarkers.TANWEEN_MARKER}"
        val resDhuhr = ArabicSpeechNormalizer.prepareArabicForEngine(
            text = dhuhr,
            languageTag = "ar",
            enginePackage = "com.google.android.tts",
            explicitNunForDayPeriod = false
        )
        assertTrue(resDhuhr.contains("ظهراً"))
        assertFalse(resDhuhr.contains(ArabicSpeechNormalizer.DHUHR_AN))

        val masa = "الساعة الثامنة مساءً${EngineMarkers.TANWEEN_MARKER}"
        val resMasa = ArabicSpeechNormalizer.prepareArabicForEngine(
            text = masa,
            languageTag = "ar",
            enginePackage = "com.google.android.tts",
            explicitNunForDayPeriod = false
        )
        assertTrue(resMasa.contains("مساءً"))
        assertFalse(resMasa.contains(ArabicSpeechNormalizer.MASA_AN))
    }

    @Test
    fun nonTashkeelAwareEngine_alwaysGetsExplicitNun() {
        // لمحرك لا يفهم التشكيل (stripTashkeelFor = true)
        for (phrase in phrases) {
            val result = ArabicSpeechNormalizer.prepareArabicForEngine(
                text = phrase,
                languageTag = "ar",
                enginePackage = "com.vocalizer.tts",
                explicitNunForDayPeriod = false
            )
            val hasExplicitNun =
                result.contains(ArabicSpeechNormalizer.SABA_AN) ||
                result.contains(ArabicSpeechNormalizer.DHUHR_AN) ||
                result.contains(ArabicSpeechNormalizer.MASA_AN)
            assertTrue(
                "محرك غير مشكول يستلم نوناً صريحة دوماً: $phrase -> $result",
                hasExplicitNun
            )
        }
    }

    @Test
    fun safeSplitting_doesNotSplitAfterPeriodWordOrSeparateTanweenMarker() {
        val commaText = "الساعة العاشرة صباحاً، وسبع دقائق الآن في هذا اليوم"
        val sentences = AnnouncementSpeaker.splitIntoSentences(commaText)
        // الفاصلة التي تلي «صباحاً» مباشرة لا تقطع الجملة
        assertEquals(1, sentences.size)

        val marker = EngineMarkers.TANWEEN_MARKER
        val markerText = "الساعة العاشرة صباحاً${marker}، تم"
        val res = AnnouncementSpeaker.splitIntoSentences(markerText)
        assertEquals(1, res.size)
    }
}
