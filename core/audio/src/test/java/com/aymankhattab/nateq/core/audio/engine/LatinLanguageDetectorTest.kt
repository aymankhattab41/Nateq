package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** اختبار كاشف اللغة اللاتينية القصيرة (بند ب.txt 3.6-2) — JVM نقي. */
class LatinLanguageDetectorTest {

    @Test
    fun `clear english text is detected as en`() {
        assertEquals(
            "en",
            LatinLanguageDetector.detect(
                "The quick brown fox jumps over the lazy dog"
            )
        )
    }

    @Test
    fun `french is detected even without diacritics`() {
        // بصمة الثنائيات والمفردات الوظيفية تكفي دون ç/é (فرنسية بلا تشكيل).
        assertEquals(
            "fr",
            LatinLanguageDetector.detect(
                "Bonjour et bienvenue sur cet appareil"
            )
        )
    }

    @Test
    fun `german is detected with its diacritics and particles`() {
        assertEquals(
            "de",
            LatinLanguageDetector.detect(
                "Guten Tag und vielen Dank für Ihre Unterstützung"
            )
        )
    }

    @Test
    fun `spanish is detected with its accents`() {
        assertEquals(
            "es",
            LatinLanguageDetector.detect(
                "Buenos días y muchas gracias por su ayuda"
            )
        )
    }

    @Test
    fun twoWordSnippet_withoutDecisiveCue_staysUnresolved() {
        // «Merci beaucoup» كلمتان فرنسيتان (بلغتا حدّ الكلمتين الجديد)
        // لكن بلا كلمة وظيفية ولا حرف قاطع حصري: لا يتجاوز الفائز عتبة
        // الثقة المرفوعة (1.10) — يبقى الحكمُ محافظاً وتُسلَّم الكلمتان
        // لسقوطهما العلوي (اللغة الثانية التي يختارها المستخدم).
        assertNull(LatinLanguageDetector.detect("Merci beaucoup"))
    }

    @Test
    fun twoWordSnippet_withTwoEnglishFunctionWords_isDetected() {
        // «the the» كلمتان وظيفيتان إنجليزيتان (2×0.6 = 1.2): تبلغ عتبة
        // الثقة المرفوعة إلى 1.10 فتُحسم إنجليزية دون انتظار الكلمة
        // الثالثة — ثمرة خفض حدّ الكلمات (بند اللغة الثانية).
        assertEquals("en", LatinLanguageDetector.detect("the the"))
    }

    @Test
    fun `punctation only or digits yield no decision`() {
        assertNull(LatinLanguageDetector.detect("1234 5678 90"))
        assertNull(LatinLanguageDetector.detect("!! ?? .."))
    }

    @Test
    fun `mixed case does not change the verdict`() {
        assertEquals(
            "fr",
            LatinLanguageDetector.detect(
                "BONJOUR ET BIENVENUE SUR CET APPAREIL"
            )
        )
    }

    /** بند 1.4: النص القصير (< [MIN_WORDS]) بحرف قاطع حصري يُكشف مبكراً
     *  بدل اللّبس المحافظ — «München»/«Señor»/«Café» مصطلحٌ واحد يحسم
     *  لسانه بلا تدرج. tiny 1 الكلمة-only German/French/Spanish. */
    @Test
    fun `short snippet with decisive diacritic is detected early`() {
        assertEquals("de", LatinLanguageDetector.detect("München"))
        assertEquals("es", LatinLanguageDetector.detect("¿Qué?"))
        assertEquals("fr", LatinLanguageDetector.detect("Garçon"))
    }

    @Test
    fun `short snippet without decisive diacritic stays unresolved`() {
        // لا حرفَ قاطعٌ حصري هنا — تبقى الثقة الساخنة لمنطق العتبة القديم.
        assertNull(LatinLanguageDetector.detect("hello"))
        assertNull(LatinLanguageDetector.detect("bonne"))
    }
}