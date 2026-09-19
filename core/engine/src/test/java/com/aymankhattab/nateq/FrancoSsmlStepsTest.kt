package com.aymankhattab.nateq

import com.aymankhattab.nateq.engine.FrancoArabic
import com.aymankhattab.nateq.engine.SsmlStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات البندين النقيين الجديدين (بند الأوامر د.3.6 ود.3.7):
 * محول الفرانكو-آراب المتحفظ ومفسر SSML المحدود.
 */
class FrancoSsmlStepsTest {

    // ════════════════════ FrancoArabic ════════════════════

    @Test
    fun `common lexicon words are replaced fully`() {
        assertEquals("عشان", FrancoArabic.convert("3ashan"))
        assertEquals("حبيبي", FrancoArabic.convert("7abibi"))
        assertEquals("خالص", FrancoArabic.convert("5ales"))
    }

    @Test
    fun `creative conversion handles mapped digits and digraphs`() {
        assertEquals("عشان", FrancoArabic.convert("3shan"))
        assertTrue(
            "الرقم البديل يُنطق حرفه العربي",
            FrancoArabic.convert("m3a").contains("ع")
        )
    }

    @Test
    fun `intact english words and latin digits stay untouched`() {
        assertEquals("WhatsApp", FrancoArabic.convert("WhatsApp"))
        assertEquals("version 2", FrancoArabic.convert("version 2"))
        assertEquals("C3PO", FrancoArabic.convert("C3PO"))
        assertEquals("hello world", FrancoArabic.convert("hello world"))
    }

    @Test
    fun `plain arabic text passes through unchanged`() {
        val arabic = "مرحبا بالعالم"
        assertEquals(arabic, FrancoArabic.convert(arabic))
    }

    // ══════════════════════ SsmlStep ══════════════════════

    @Test
    fun `say-as characters spell out spaced letters`() {
assertEquals(
    "ا ل س ل ا م",
    SsmlStep.apply(
        "<say-as interpret-as=\"characters\">السلام</say-as>"
    )
)
    }

    @Test
    fun `break time maps to pause punctuation`() {
        assertEquals(
            "مرحبا .",
            SsmlStep.apply("مرحبا <break time=\"1s\"/>")
        )
        assertEquals(
            "مرحبا ,",
            SsmlStep.apply("مرحبا <break time=\"400ms\"/>")
        )
        assertEquals(
            "مرحبا ,",
            SsmlStep.apply("مرحبا <break time=\"100ms\"/>")
        )
    }

    @Test
    fun `unknown tags keep their inner text`() {
        assertEquals(
            "مرحبا بك",
            SsmlStep.apply("<prosody rate=\"slow\">مرحبا بك</prosody>")
        )
    }

    @Test
    fun `xml entities are unescaped`() {
        assertEquals("5 < 6 و > 3", SsmlStep.apply("5 &lt; 6 و &gt; 3"))
    }

    @Test
    fun `plain text without angle brackets is untouched`() {
        val plain = "نص عادي بلا وسوم"
        assertEquals(plain, SsmlStep.apply(plain))
        assertFalse(
            "لا تُضاف أي تغييرات على النص العادي",
            plain != SsmlStep.apply(plain)
        )
    }
}