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

    // صَبَاحَنْ — التنوين بنونٍ ساكنة صريحة (حرفٌ تقرؤه كل المحركات)،
    // لا بعلامة التنوين «ً» التي تُهمَل عند التجريد فيُفقد اللفظ.
    private val expectedSaba = "\u0635\u064e\u0628\u064e\u0627\u062d\u064e\u0646\u0652"
    private val expectedDhuhr = "\u0638\u064e\u0647\u0652\u0631\u064e\u0646\u0652"

    @Test
    fun sabaha_isFullyVoweled() {
        val raw = "\u0635\u0628\u0627\u062d\u0627\u064b" // صباحاً
        assertEquals(expectedSaba, ArabicSpeechNormalizer.normalize(raw))
    }

    @Test
    fun sabah_bareAlefAfterStripping_isFullyVoweled() {
        // ناتج TimeStep «صباحاً» يمرّ على TashkeelStripStep فيصير «صباحا»
        // (ألف عارية بلا تنوين) — وهو ما كان يمرّ للمحرّك خاماً فيُسقط
        // اللفظ. الآن يُضبَط كما ضُبطت «مساءاً».
        assertEquals(
            expectedSaba,
            ArabicSpeechNormalizer.normalize("\u0635\u0628\u0627\u062d\u0627") // صباحا
        )
        // والتنوين على الباء (صباحًا) صيغة حديثة تُضبط كذلك.
        assertEquals(
            expectedSaba,
            ArabicSpeechNormalizer.normalize("\u0635\u0628\u0627\u062d\u064b\u0627") // صباحًا
        )
    }

    @Test
    fun dhuhr_bareAlefAfterStripping_isFullyVoweled() {
        assertEquals(
            expectedDhuhr,
            ArabicSpeechNormalizer.normalize("\u0638\u0647\u0631\u0627") // ظهرا
        )
        assertEquals(
            expectedDhuhr,
            ArabicSpeechNormalizer.normalize("\u0638\u0647\u0631\u0627\u064b") // ظهراً
        )
    }

    @Test
    fun tanweenWords_carryNoTanweenMark() {
        // **جذر «غير منونة» مسموعةً:** علامة التنوين «ً» حرفٌ ليس
        // فيه — فأي محرّكٍ يجرّد الحركات يُسقطها ويُخرج الكلمة بلا نون.
        // الخيارات المُطبَّقة كلها تحمل نوناً ساكنة صريحة.
        listOf(
            "\u0635\u0628\u0627\u062d\u0627\u064b", // صباحاً
            "\u0635\u0628\u0627\u062d\u0627", // صباحا
            "\u0645\u0633\u0627\u0621\u0627\u064b", // مساءاً
            "\u0645\u0633\u0627\u0621\u0627", // مساءا
            "\u0638\u0647\u0631\u0627\u064b", // ظهراً
            "\u0638\u0647\u0631\u0627" // ظهرا
        ).forEach { raw ->
            val out = ArabicSpeechNormalizer.normalize(raw)
            assertTrue(
                "«$raw» بلا نون ساكنة: $out",
                out.endsWith("\u0646\u0652")
            )
            assertTrue(
                "«$raw» تُركت علامة تنوين: $out",
                !out.contains('\u064B')
            )
        }
    }

    @Test
    fun masaBareAlif_afterStripping_isNormalized() {
        // «الساعة السادسة مساءا» بعد التجريد — الألف العارية تُضبط
        // بالنون الساكنة كما في نظائرها المشكولة.
        assertEquals(
            expectedMasa,
            ArabicSpeechNormalizer.normalize("\u0645\u0633\u0627\u0621\u0627") // مساءا
        )
    }

    @Test
    fun bareAlif_doesNotTouchWordsEndingWithIt() {
        // «صباحات» و«مساءات» (كلمتان مستقلتان) لا تُمسّان — القالب
        // يشترط ألّا يتبع الألفَ العاريةَ حرفٌ.
        listOf(
            "\u0635\u0628\u0627\u062d\u0627\u062a", // صباحات
            "\u0645\u0633\u0627\u0621\u0627\u062a" // مساءات
        ).forEach { word ->
            assertEquals(word, ArabicSpeechNormalizer.normalize(word))
        }
    }

    @Test
    fun strippedTimeAnnouncement_normalizesSaba() {
        // «الساعة الآن السادسة صباحا» بعد التجريد — يجب ألّا تصل الكلمة
        // إلى المحرك بألفٍ عارية.
        val out = ArabicSpeechNormalizer.normalize(
            "الساعة الآن السادسة \u0635\u0628\u0627\u062d\u0627" // صباحا
        )
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