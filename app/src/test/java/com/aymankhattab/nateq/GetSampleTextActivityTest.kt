package com.aymankhattab.nateq

import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.LocaleUtils
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * فحص GET_SAMPLE_TEXT: منطق المطابقة بين رمز اللغة ونص العيّنة —
 * كل لغة معلنة (ar/en/fr/de/es) لها نص مختلف، والرموز الغريبة
 * تسقط أماناً على العربية. اختبار نقّي بلا Robolectric لأن الموارد
 * في وحدة مكتبة (feature:settings) لا تُحمَّل في جدول موارد Robolectric
 * بشكل موثوق (معرّفات R المكتبة).
 */
class GetSampleTextActivityTest {

    private val SAMPLE_KEYS = listOf(
        "sample_text_activity_ar",
        "sample_text_activity_en",
        "sample_text_activity_fr",
        "sample_text_activity_de",
        "sample_text_activity_es"
    )

    /** يقرأ مجلد resources/values/strings.xml ويُخرج أزواج (name, value) —
     *  يعمل من وحدة :app لذا نكسر المسار إلى feature/settings. */
    private fun readSampleTexts(localeSuffix: String): Map<String, String> {
        val path = if (localeSuffix.isEmpty()) {
            "../feature/settings/src/main/res/values/strings.xml"
        } else {
            "../feature/settings/src/main/res/values-$localeSuffix/strings.xml"
        }
        val xml = File(path).readText()
        return SAMPLE_KEYS.associateWith { key ->
            val pattern = Regex(
                """<string\s+name="$key">([^<]+)</string>"""
            )
            pattern.find(xml)?.groupValues?.get(1)
                ?: error("السلاسل $key غير موجودة في $path")
        }
    }

    @Test
    fun languageRoot_mapsDeclaredLanguagesCorrectly() {
        assertEquals("ar", LocaleUtils.languageRoot("ar-EG"))
        assertEquals("ar", LocaleUtils.languageRoot("ara"))
        assertEquals("ar", LocaleUtils.languageRoot("ar"))
        assertEquals("en", LocaleUtils.languageRoot("en-US"))
        assertEquals("en", LocaleUtils.languageRoot("eng"))
        assertEquals("en", LocaleUtils.languageRoot("EN"))
        assertEquals("fr", LocaleUtils.languageRoot("fr"))
        assertEquals("fr", LocaleUtils.languageRoot("fra"))
        assertEquals("fr", LocaleUtils.languageRoot("fr-FR"))
        assertEquals("de", LocaleUtils.languageRoot("deu"))
        assertEquals("de", LocaleUtils.languageRoot("de"))
        assertEquals("de", LocaleUtils.languageRoot("de-DE"))
        assertEquals("es", LocaleUtils.languageRoot("es-ES"))
        assertEquals("es", LocaleUtils.languageRoot("es"))
        assertEquals("es", LocaleUtils.languageRoot("spa"))
        // لغة خارج الإعلان تبقى كما هي (تسقط في when → العربية)
        assertEquals("ja", LocaleUtils.languageRoot("jpn"))
    }

    @Test
    fun whenBranch_matchesCorrectSampleText() {
        // يحاكي منطق when في GetSampleTextActivity
        fun sampleForLang(lang: String): String = when (
            LocaleUtils.languageRoot(lang)
        ) {
            LanguageCode.EN.tag -> "en"
            "fr" -> "fr"
            "de" -> "de"
            "es" -> "es"
            else -> "ar"
        }
        assertEquals("en", sampleForLang("en-US"))
        assertEquals("en", sampleForLang("eng"))
        assertEquals("fr", sampleForLang("fr"))
        assertEquals("fr", sampleForLang("fra"))
        assertEquals("fr", sampleForLang("fr-FR"))
        assertEquals("de", sampleForLang("deu"))
        assertEquals("de", sampleForLang("de"))
        assertEquals("es", sampleForLang("es-ES"))
        assertEquals("es", sampleForLang("spa"))
        assertEquals("ar", sampleForLang("ar-EG"))
        assertEquals("ar", sampleForLang("zz-ZZ"))
        assertEquals("ar", sampleForLang(""))
    }

    @Test
    fun sampleTextStrings_existAndContainExpectedWords() {
        val ar = readSampleTexts("")
        val en = readSampleTexts("en")
        // كل ملف يحتوي أسماء اللغات الخمس
        for (key in SAMPLE_KEYS) {
            assertTrue("العربية: $key موجودة", ar.containsKey(key))
            assertTrue("الإنجليزية: $key موجودة", en.containsKey(key))
            assertTrue(
                "العربية: $key غير فارغة",
                ar.getValue(key).isNotBlank()
            )
        }
        assertTrue(
            "النص العربي يحتوي مرحباً",
            ar.getValue("sample_text_activity_ar").contains("مرحباً")
        )
        assertTrue(
            "النص الإنجليزي يحتوي Hello",
            en.getValue("sample_text_activity_en")
                .contains("Hello", ignoreCase = true)
        )
        assertTrue(
            "النص الفرنسي يحتوي Bonjour",
            en.getValue("sample_text_activity_fr")
                .contains("Bonjour", ignoreCase = true)
        )
        assertTrue(
            "النص الألماني يحتوي Hallo",
            en.getValue("sample_text_activity_de")
                .contains("Hallo", ignoreCase = true)
        )
        assertTrue(
            "النص الإسباني يحتوي Hola",
            en.getValue("sample_text_activity_es")
                .contains("Hola", ignoreCase = true)
        )
    }

    @Test
    fun eachSampleText_isUnique() {
        val ar = readSampleTexts("")
        val en = readSampleTexts("en")
        // النص العربي، والنصوص الأربعة غير العربية مختلفة جميعاً
        val arText = ar.getValue("sample_text_activity_ar")
        val nonArabic = listOf("en", "fr", "de", "es").map {
            en.getValue("sample_text_activity_$it")
        }
        assertTrue("النص العربي فريد", arText !in nonArabic)
        assertEquals(
            "4 نصوص غير عربية مختلفة",
            nonArabic.size,
            nonArabic.toSet().size
        )
    }
}
