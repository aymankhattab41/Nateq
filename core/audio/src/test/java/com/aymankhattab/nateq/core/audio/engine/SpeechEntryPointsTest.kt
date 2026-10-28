package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.accessibility.RepeatSpellController
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.engine.PronunciationDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات الجولة 53 — الأمر 4:
 * توحيد مسار النطق عبر [SpeechEntryPoints] وهجاء الحروف المفردة
 * وحماية «جُمادى» من استبدال حرف الجيم بالقاموس.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SpeechEntryPointsTest {

    private lateinit var context: Context
    private lateinit var dict: PronunciationDictionary
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dict = PronunciationDictionary.shared(context)
        dict.clear()
        dict.setStandaloneMaxLength(1)
        dict.addEntry(
            abbreviation = "ج",
            pronunciation = "جنيه",
            languageTag = "ar",
            matchMode = "AUTO"
        )
        settings = SettingsRepository(context)
        settings.setSingleLetterMode("name")
    }

    @Test
    fun jumadaMonth_fromAllSpeechEntrySources_doesNotBecomeGenieh() {
        val testText = "جُمادى الأولى"

        for (source in SpeechEntryPoints.Source.values()) {
            val result = SpeechEntryPoints.prepare(testText, source, context)
            assertFalse(
                "المصدر ${source.name} يجب ألا يستبدل ج " +
                    "في جُمادى بجنيه: $result",
                result.contains("جنيه")
            )
        }
    }

    @Test
    fun jumadaMonth_withInvisibleAndDirectionChars_doesNotBecomeGenieh() {
        val variations = listOf(
            "ج\u200Bُمادى الأولى",
            "ج\u2060ُمادى الأولى",
            "ج\u200Fُمادى الأولى",
            "ج\u061Cُمادى الأولى",
            "جُمادى\u00A0الأولى",
            "جُمادى\u202Fالأولى"
        )

        for (source in SpeechEntryPoints.Source.values()) {
            for (text in variations) {
                val result = SpeechEntryPoints.prepare(text, source, context)
                assertFalse(
                    "المصدر ${source.name} مع $text لا يجب أن يحوي جنيه: " +
                        result,
                    result.contains("جنيه")
                )
            }
        }
    }

    @Test
    fun singleJeemLetter_inSpellLast_isSpokenAsJeem() {
        val result = SpeechEntryPoints.prepare(
            rawText = "ج",
            source = SpeechEntryPoints.Source.SPELL_LAST,
            context = context
        )
        assertEquals("جيم", result)
    }

    @Test
    fun repeatSpellController_spellText_spellsCharNamesNotDictionary() {
        val spelled = RepeatSpellController.spellText("ج")
        assertEquals("جيم", spelled)
    }

    @Test
    fun englishDate_inEnglishContext_isSpokenInEnglish() {
        val result = SpeechEntryPoints.prepare(
            rawText = "Date is 2024-03-15",
            source = SpeechEntryPoints.Source.SCREEN_READER,
            context = context,
            languageTag = "en"
        )
        assertTrue(
            "يجب نطق التاريخ بالإنجليزية في السياق الإنجليزي: $result",
            result.contains("March") && result.contains("fifteenth")
        )
    }

    @Test
    fun englishNumbers_withoutCommasBetweenThem() {
        val result = SpeechEntryPoints.prepare(
            rawText = "1, 2, 3",
            source = SpeechEntryPoints.Source.SCREEN_READER,
            context = context,
            languageTag = "en"
        )
        assertFalse(
            "لا يجب وجود فواصل بين الأرقام: $result",
            result.contains(",") || result.contains("،")
        )
    }
}
