package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.util.LanguageCode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * بند 6-7: أنظمة العدّ الهندية خارج العربية-الهندية/اللاتينية لم تعُد تُنسب
 * حصراً للغة الأرقام؛ الديفاناغارية (१२३) تُنسب للسكربت فيُعنونَ للهندية
 * عبر خريطة اللغة، والبنغالية/التاميلية تُنسب لسكربتها فتنزل على لغة السقوط
 * المتاحة بدل نطقها بأصوات الأرقام العربية/الإنجليزية رغم سياقها.
 */
class LanguageSegmenterIndicDigitsTest {

    private val segmenter = LanguageSegmenter()

    @Test
    fun devanagariDigits_areScriptNotNumberLanguage() {
        val segments = segmenter.segment(
            "\u0967\u0968\u0969", // १२३ — أرقام ديفاناغارية
            fallbackLanguage = LanguageCode.AR.tag,
            secondaryLanguage = LanguageCode.EN.tag,
            numberLanguage = LanguageCode.AR.tag
        )
        assertEquals(1, segments.size)
        assertEquals("hi", segments.single().languageTag)
    }

    @Test
    fun arabicIndicDigits_remainNumberLanguage() {
        val segments = segmenter.segment(
            "\u0661\u0662\u0663", // ١٢٣ — عربية هندية
            fallbackLanguage = LanguageCode.AR.tag,
            secondaryLanguage = LanguageCode.EN.tag,
            numberLanguage = LanguageCode.EN.tag
        )
        assertEquals(1, segments.size)
        assertEquals(LanguageCode.EN.tag, segments.single().languageTag)
    }

    @Test
    fun latinDigits_remainNumberLanguage() {
        val segments = segmenter.segment(
            "123",
            fallbackLanguage = LanguageCode.AR.tag,
            secondaryLanguage = LanguageCode.EN.tag,
            numberLanguage = LanguageCode.EN.tag
        )
        assertEquals(1, segments.size)
        assertEquals(LanguageCode.EN.tag, segments.single().languageTag)
    }

    @Test
    fun bengaliDigits_fallToScriptLanguageNotArabic() {
        // ০৯৪৭ (بنغالية): سكربت بنغالي بلا خريطة لغة — ينزل على لغة السقوط
        // (secondary=en ضمن طلب عربي) لا لغة الأرقام العربية الافتراضية.
        val segments = segmenter.segment(
            "\u09E6\u09EF\u09EA\u09ED", // ০৯৪৭ (بنغالية)
            fallbackLanguage = LanguageCode.AR.tag,
            secondaryLanguage = LanguageCode.EN.tag,
            numberLanguage = LanguageCode.AR.tag
        )
        assertEquals(1, segments.size)
        assertEquals(LanguageCode.EN.tag, segments.single().languageTag)
    }
}