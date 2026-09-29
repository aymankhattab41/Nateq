package com.aymankhattab.nateq.core.audio.announcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** الضبط اللغوي المحافظ لقراءةٍ ثابتة عبر المحركات (انظر الهدف فوق الكائن). */
class ArabicSpeechNormalizerTest {

    @Test
    fun waahed_isFullyVoweled() {
        val raw = "\u0648\u0627\u062d\u062f" // واحد
        assertEquals(
            "\u0648\u064e\u0627\u062d\u0650\u062f", // وَاحِد
            ArabicSpeechNormalizer.normalize(raw)
        )
    }

    @Test
    fun masaA_isFullyVoweled() {
        val raw = "\u0645\u0633\u0627\u0621\u064b" // مساءً
        assertEquals(
            "\u0645\u064e\u0633\u064e\u0627\u0621\u0627\u064b", // مَسَاءاً
            ArabicSpeechNormalizer.normalize(raw)
        )
        // ومَسَاءً المشكولة سلفاً بغير ألف تُعوَّض بمَسَاءاً
        val preVoweled = "\u0645\u064e\u0633\u064e\u0627\u0621\u064b"
        assertEquals(
            "\u0645\u064e\u0633\u064e\u0627\u0621\u0627\u064b",
            ArabicSpeechNormalizer.normalize(preVoweled)
        )
    }

    @Test
    fun sabaha_isFullyVoweled() {
        val raw = "\u0635\u0628\u0627\u062d\u0627\u064b" // صباحاً
        assertEquals(
            "\u0635\u064e\u0628\u064e\u0627\u062d\u064e\u0627\u064b",
            ArabicSpeechNormalizer.normalize(raw)
        )
    }

    @Test
    fun mixedSentence_normalizesWordsOnly() {
        val raw = "الواحدة \u0648\u0627\u062d\u062f " + // واحد
            "\u0648\u064e" + // وَ
            "النصف \u0645\u0633\u0627\u0621\u064b" // مساءً
        val out = ArabicSpeechNormalizer.normalize(raw)
        val expectedMasaA =
            "\u0645\u064e\u0633\u064e\u0627\u0621\u0627\u064b"
        assertTrue(out.contains(expectedMasaA))
    }

    @Test
    fun nonArabicText_unTouched() {
        assertEquals(
            "hello 123 !",
            ArabicSpeechNormalizer.normalize("hello 123 !")
        )
    }
}