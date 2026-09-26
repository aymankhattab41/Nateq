package com.aymankhattab.nateq.settings

import com.aymankhattab.nateq.core.audio.engine.EngineWithVoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** اختبارات المنطق النقي لحوار «إعداد جميع اللغات» المدمج (كمبو لغة/
 *  محرك/صوت + حفظ تلقائي) — نقي JVM دون Robolectric: لا تُمسّ هنا أي
 *  واجهة من android.speech.tts.Voice (أسماء الأصوات تُمرَّر كسلاسل).
 *  لا خيار «بدون محرك» في القائمة: الغيابُ يُفسَّر اختياراً تلقائياً
 *  صامتاً خارج الحوار (انظر قواعد المشروع). */
class LanguageConvertDialogLogicTest {

    private fun engine(
        packageName: String,
        label: String = packageName
    ): EngineWithVoices = EngineWithVoices(packageName, label, emptyList())

    private fun row(
        languageTag: String = "ar",
        displayName: String = languageTag,
        engines: List<EngineWithVoices> = emptyList()
    ): LanguageRow = LanguageRow(languageTag, displayName, engines)

    // ===== قائمة المحرك =====

    @Test
    fun engineChoice_emptyEngines_returnsSingleDisabledNoOptions() {
        val choice = engineChoice(row(), "لا خيارات", null)
        assertEquals(listOf("لا خيارات"), choice.labels)
        assertNull(choice.resolvedIndex)
    }

    @Test
    fun engineChoice_noSavedEngine_selectsFirstEngine() {
        val r = row(engines = listOf(engine("a"), engine("b")))
        val choice = engineChoice(r, "لا", null)
        assertEquals(listOf("a", "b"), choice.labels)
        assertEquals(0, choice.resolvedIndex)
    }

    @Test
    fun engineChoice_savedEngine_selectsItsIndex() {
        val r = row(engines = listOf(engine("a"), engine("b")))
        val choice = engineChoice(r, "لا", "b")
        assertEquals(1, choice.resolvedIndex)
    }

    @Test
    fun engineChoice_unknownSavedEngine_fallsBackToFirstEngine() {
        val r = row(engines = listOf(engine("a")))
        val choice = engineChoice(r, "لا", "zzz")
        assertEquals(0, choice.resolvedIndex)
    }

    // ===== استخراج المحرك من العنوان =====

    @Test
    fun enginePackageForLabel_blankOrNull_isNull() {
        assertNull(enginePackageForLabel(row(), null, "لا"))
        assertNull(enginePackageForLabel(row(), "   ", "لا"))
    }

    @Test
    fun enginePackageForLabel_unmatchedOrNoOptions_isNull() {
        val r = row(engines = listOf(engine("a")))
        // «بدون محرك» لم يعد خياراً محدداً: عنوان لا يطابق أي محرك → null
        assertNull(enginePackageForLabel(r, "بدون", "لا"))
        assertNull(enginePackageForLabel(r, "لا", "لا"))
    }

    @Test
    fun enginePackageForLabel_knownLabel_returnsPackage() {
        val r = row(engines = listOf(engine("a", "محرك أ"), engine("b")))
        assertEquals("a", enginePackageForLabel(r, "محرك أ", "لا"))
    }

    @Test
    fun enginePackageForLabel_unknownLabel_isNull() {
        val r = row(engines = listOf(engine("a", "محرك أ")))
        assertNull(enginePackageForLabel(r, "غريب", "لا"))
    }

    // ===== قائمة الصوت =====

    @Test
    fun voiceChoice_emptyNames_returnsDisabledNoOptions() {
        val choice = voiceChoice(emptyList(), "لا", null)
        assertEquals(listOf("لا"), choice.labels)
        assertNull(choice.resolvedIndex)
    }

    @Test
    fun voiceChoice_savedVoice_preservesSelection() {
        val names = listOf("أصفر", "أزرق")
        assertEquals(1, voiceChoice(names, "لا", "أزرق").resolvedIndex)
    }

    @Test
    fun voiceChoice_unknownSavedVoice_fallsBackToFirst() {
        assertEquals(0, voiceChoice(listOf("أصفر"), "لا", "لا-أعرف")
            .resolvedIndex)
    }

    @Test
    fun voiceNameFromLabel_nullOrNoOptions_isNull() {
        assertNull(voiceNameFromLabel(null, "لا"))
        assertNull(voiceNameFromLabel("", "لا"))
        assertNull(voiceNameFromLabel("  ", "لا"))
        assertNull(voiceNameFromLabel("لا", "لا"))
    }

    @Test
    fun voiceNameFromLabel_returnsTrimmedName() {
        assertEquals("صوت", voiceNameFromLabel("  صوت  ", "لا"))
    }

    // ===== عناوين اللغات وصفوفها =====

    @Test
    fun languageLabels_andRowForLabel_areConsistent() {
        val rows = listOf(
            row("ar", "العربية"),
            row("en", "English")
        )
        assertEquals(listOf("العربية", "English"), languageLabels(rows))
        assertEquals(
            "ar",
            languageRowForLabel(rows, "العربية")?.languageTag
        )
        assertNull(languageRowForLabel(rows, "غائبة"))
    }

    // ===== قيم الحفظ التلقائي =====

    @Test
    fun convertSaveValues_withEngineAndVoice_resolvesAll() {
        val r = row(engines = listOf(engine("pk", "محرك")))
        val v = convertSaveValues(
            r, "محرك", "صوتي", 70, 100, 200, "لا"
        )
        assertEquals("pk", v.engine)
        assertEquals("صوتي", v.voice)
        assertEquals(0.7f, v.volume, 0f)
        assertEquals(1.0f, v.pitch, 0f)
        assertEquals(2.0f, v.rate, 0f)
    }

    @Test
    fun convertSaveValues_unmatchedEngine_clearsVoiceKeepsSliders() {
        val r = row(engines = listOf(engine("pk", "محرك")))
        val v = convertSaveValues(r, "غير معروف", "صوتي", 50, 50, 50, "لا")
        assertNull(v.engine)
        assertNull(v.voice)
        assertEquals(0.5f, v.volume, 0f)
        assertEquals(0.5f, v.pitch, 0f)
    }

    @Test
    fun convertSaveValues_noOptionsLanguage_keepsSlidersOnly() {
        val r = row(engines = emptyList())
        val v = convertSaveValues(r, "لا", "لا", 100, 100, 100, "لا")
        assertNull(v.engine)
        assertNull(v.voice)
        assertEquals(1.0f, v.volume, 0f)
    }

    @Test
    fun convertSaveValues_clampsSlidersBelowMinimum() {
        val v = convertSaveValues(row(), null, null, 100, 10, 10, "لا")
        assertEquals(0.25f, v.pitch, 0f)
        assertEquals(0.25f, v.rate, 0f)
    }
}