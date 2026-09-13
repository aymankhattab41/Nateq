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
    fun `short snippet below minimum words stays unresolved`() {
        // «Merci beaucoup» حرفان فقط: اللّبس فوق طاقة الحكم القاطع.
        assertNull(LatinLanguageDetector.detect("Merci beaucoup"))
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
}