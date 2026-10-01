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

    private val expectedMasa =
        "\u0645\u064e\u0633\u064e\u0627\u0621\u064e\u0646\u0652"

    @Test
    fun masaA_isFullyVoweled() {
        val raw = "\u0645\u0633\u0627\u0621\u064b" // مساءً
        assertEquals(expectedMasa, ArabicSpeechNormalizer.normalize(raw))
        // ومَسَاءً المشكولة سلفاً بتنوين الهمزة
        val preVoweled = "\u0645\u064e\u0633\u064e\u0627\u0621\u064b"
        assertEquals(expectedMasa, ArabicSpeechNormalizer.normalize(preVoweled))
        // ومساءا (بألف بعد تجريد التنوين)
        val plainAlif = "\u0645\u0633\u0627\u0621\u0627"
        assertEquals(expectedMasa, ArabicSpeechNormalizer.normalize(plainAlif))
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
    fun sabah_bareAlefAfterStripping_isFullyVoweled() {
        // ناتج TimeStep «صباحاً» يمرّ على TashkeelStripStep فيصير «صباحا»
        // (ألف عارية بلا تنوين) — وهو ما كان يمرّ للمحرّك خاماً فيُسقط
        // اللفظ. الآن يُضبَط كما ضُبطت «مساءاً».
        val stripped = "\u0635\u0628\u0627\u062d\u0627" // صباحا
        assertEquals(
            "\u0635\u064e\u0628\u064e\u0627\u062d\u064e\u0627\u064b",
            ArabicSpeechNormalizer.normalize(stripped)
        )
        // والتنوين على الباء (صباحًا) صيغة حديثة تُضبط كذلك.
        val tanweenOnBa = "\u0635\u0628\u0627\u062d\u064b\u0627" // صباحًا
        assertEquals(
            "\u0635\u064e\u0628\u064e\u0627\u062d\u064e\u0627\u064b",
            ArabicSpeechNormalizer.normalize(tanweenOnBa)
        )
    }

    @Test
    fun dhuhr_bareAlefAfterStripping_isFullyVoweled() {
        assertEquals(
            "\u0638\u064e\u0647\u0652\u0631\u064e\u0627\u064b",
            ArabicSpeechNormalizer.normalize("\u0638\u0647\u0631\u0627") // ظهرا
        )
        assertEquals(
            "\u0638\u064e\u0647\u0652\u0631\u064e\u0627\u064b",
            ArabicSpeechNormalizer.normalize(
                "\u0638\u0647\u0631\u0627\u064b" // ظهراً
            )
        )
    }

    @Test
    fun bareAlif_doesNotTouchWordsEndingWithIt() {
        // «صباحات» (كلمة مستقلة) لا تُمسّ — القالب يشترط ألّا يتبع
        // الألفَ العاريةَ حرفٌ.
        val word = "\u0635\u0628\u0627\u062d\u0627\u062a" // صباحات
        assertEquals(word, ArabicSpeechNormalizer.normalize(word))
    }

    @Test
    fun strippedTimeAnnouncement_normalizesSaba() {
        // «الساعة الآن السادسة صباحا» بعد التجريد — يجب ألّا تصل الكلمة
        // إلى المحرك بألفٍ عارية.
        val out = ArabicSpeechNormalizer.normalize(
            "الساعة الآن السادسة \u0635\u0628\u0627\u062d\u0627" // صباحا
        )
        val expectedSaba =
            "\u0635\u064e\u0628\u064e\u0627\u062d\u064e\u0627\u064b"
        assertTrue(out.contains(expectedSaba))
    }

    @Test
    fun timeAnnouncement_normalizesStrippedMasa() {
        val rawTime = "الساعة الآن السادسة مساء"
        val out = ArabicSpeechNormalizer.normalize(rawTime)
        assertTrue(out.endsWith(expectedMasa))
    }

    @Test
    fun masaAlKhair_unTouched() {
        val greeting = "مساء الخير"
        assertEquals(greeting, ArabicSpeechNormalizer.normalize(greeting))
    }

    @Test
    fun mixedSentence_normalizesWordsOnly() {
        val raw = "الواحدة \u0648\u0627\u062d\u062f " + // واحد
            "\u0648\u064e" + // وَ
            "النصف \u0645\u0633\u0627\u0621\u064b" // مساءً
        val out = ArabicSpeechNormalizer.normalize(raw)
        assertTrue(out.contains(expectedMasa))
    }

    @Test
    fun nonArabicText_unTouched() {
        assertEquals(
            "hello 123 !",
            ArabicSpeechNormalizer.normalize("hello 123 !")
        )
    }

    @Test
    fun masaMidSentence_isNormalized() {
        // مساء المجردة بعد وقت في وسط الجملة تُضبط كما في نهايتها
        val raw = "الساعة الآن السادسة مساء وعندك موعد"
        val out = ArabicSpeechNormalizer.normalize(raw)
        assertTrue(
            "مساء في وسط الجملة لم تُطبَّق عليها مَسَاءَنْ",
            out.contains(expectedMasa)
        )
    }
}