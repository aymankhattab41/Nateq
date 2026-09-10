package com.aymankhattab.nateq.engine

import com.aymankhattab.nateq.core.audio.engine.LanguageSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات مقسم النصوص المختلطة الكتابات (بند 17.1) — منطق نقي بلا Android. */
class LanguageSegmenterTest {

    private val segmenter = LanguageSegmenter()

    private fun textsAndTags(
        text: String,
        request: String
    ): Pair<List<String>, List<String>> {
        val segments = segmenter.segment(text, request)
        return Pair(
            segments.map { it.text },
            segments.map { it.languageTag }
        )
    }

    @Test
    fun pureArabic_singleSegment() {
        val (texts, tags) = textsAndTags("مرحبا بالعالم", "ar")
        assertEquals(listOf("مرحبا بالعالم"), texts)
        assertEquals(listOf("ar"), tags)
    }

    @Test
    fun pureEnglish_withArabicRequest_usesEnglishFallback() {
        val (texts, tags) = textsAndTags("Hello world, how are you!", "ar")
        assertEquals(listOf("Hello world, how are you!"), texts)
        assertEquals(listOf("en"), tags)
    }

    @Test
    fun pureLatin_withNonArabicRequest_usesRequestLanguage() {
        val (texts, tags) = textsAndTags("Bonjour tout le monde", "fr")
        assertEquals(listOf("Bonjour tout le monde"), texts)
        assertEquals(listOf("fr"), tags)
    }

    @Test
    fun mixedArabicEnglish_knownExample_splitsByScript() {
        val input = "يرجى فتح تطبيق WhatsApp ثم النقر على Settings"
        val (texts, tags) = textsAndTags(input, "ar")
        assertEquals(
            listOf("يرجى فتح تطبيق ", "WhatsApp ", "ثم النقر على ", "Settings"),
            texts
        )
        assertEquals(listOf("ar", "en", "ar", "en"), tags)
        assertEquals(
            "التجميع يعيد النص الأصلي حرفياً",
            input,
            texts.joinToString("")
        )
    }

    @Test
    fun leadingNeutral_attachesToNextScript() {
        val (texts, tags) = textsAndTags("  hello", "ar")
        assertEquals(listOf("  hello"), texts)
        assertEquals(listOf("en"), tags)
    }

    @Test
    fun digitsBetweenArabicWords_areNeutral() {
        val input = "العمر 30 عاما"
        val (texts, tags) = textsAndTags(input, "ar")
        // الأرقام والمسافات محايدات: تبقى داخل المقطع العربي الواحد بلا تفتيت.
        assertEquals(listOf("العمر 30 عاما"), texts)
        assertEquals(listOf("ar"), tags)
        assertEquals(input, texts.joinToString(""))
    }

    @Test
    fun arabicIndicDigits_areNeutral() {
        val input = "عندي ٣ كتب"
        val (texts, tags) = textsAndTags(input, "ar")
        assertEquals(listOf("عندي ٣ كتب"), texts)
        assertEquals(listOf("ar"), tags)
        assertEquals(input, texts.joinToString(""))
    }

    @Test
    fun neutralOnlyText_singleSegmentWithArabicRequest() {
        val (texts, tags) = textsAndTags("123 456 !", "ar")
        assertEquals(listOf("123 456 !"), texts)
        assertEquals(listOf("ar"), tags)
    }

    @Test
    fun arabicIndicDigitsOnly_withArabicRequest_usesArabic() {
        val (texts, tags) = textsAndTags("١٢٣٤٥", "ar")
        assertEquals(listOf("١٢٣٤٥"), texts)
        assertEquals(listOf("ar"), tags)
    }

    @Test
    fun neutralOnlyText_withNonArabicRequest_usesRequestLanguage() {
        val (texts, tags) = textsAndTags("123 456 !", "fr")
        assertEquals(listOf("123 456 !"), texts)
        assertEquals(listOf("fr"), tags)
    }

    @Test
    fun neutralOnlyText_withBlankRequest_usesEnglishFallback() {
        val (texts, tags) = textsAndTags("123", "")
        assertEquals(listOf("123"), texts)
        assertEquals(listOf("en"), tags)
    }

    @Test
    fun arabicRequest_digitsOnlyArabic_butLatinWordsStillEnglish() {
        val (texts, tags) = textsAndTags("Status 123", "ar")
        assertEquals(listOf("Status 123"), texts)
        assertEquals(listOf("en"), tags)
    }

    @Test
    fun emptyText_singleEmptySegment_usesRequestLanguage() {
        val (texts, tags) = textsAndTags("", "ar")
        assertEquals(listOf(""), texts)
        assertEquals(listOf("ar"), tags)
    }

    @Test
    fun tashkeel_staysWithArabic() {
        val (texts, tags) = textsAndTags("السَّلامُ عليكم", "ar")
        assertEquals(listOf("السَّلامُ عليكم"), texts)
        assertEquals(listOf("ar"), tags)
    }

    @Test
    fun arabicExtendedBlocks_classifiedAsArabic() {
        // يغطي النطاقات العربية الموسّعة الناقصة (البند): العربية الموسّعة-ب
        // (0x0870..0x089F)، والعربية الموسّعة-ج (0x10EC0..0x10EFF)، ورموز
        // الرياضيات العربية (0x1EE00..0x1EEFF) — نصٌّ منها ضمن طلبٍ عربي يجب
        // أن يُصنف كله عربياً لا أجنبياً.
        val extendedB = "\u0870\u089F"
        val extendedC = "\uD803\uDEC0\uD803\uDEFF"
        val mathSymbols = "\uD83B\uDE00\uD83B\uDEFF"
        val combined = "قاعدة " + extendedB + " " + extendedC +
            " " + mathSymbols + " نهاية"
        val (texts, tags) = textsAndTags(combined, "ar")
        assertEquals(
            "النصوص الموسّعة تُصنَّف كلها عربية",
            listOf(combined),
            texts
        )
        assertEquals(listOf("ar"), tags)
        assertEquals(combined, texts.joinToString(""))
    }

    @Test
    fun cyrillic_withArabicRequest_usesRussian() {
        val (texts, tags) = textsAndTags("Привет мир", "ar")
        // السكربت السيريلي يُحدد لغته الروسية حتماً مهما كان طلب النص
        // (بند التحويل التلقائي): لا يُمرَّر لمحركٍ إنجليزي كما كان.
        assertEquals(listOf("Привет мир"), texts)
        assertEquals(listOf("ru"), tags)
    }

    @Test
    fun cyrillic_withSameRequest_usesRequest() {
        val (texts, tags) = textsAndTags("Привет мир", "ru")
        assertEquals(listOf("Привет мир"), texts)
        assertEquals(listOf("ru"), tags)
    }

    @Test
    fun hebrew_withArabicRequest_usesHebrew() {
        val (texts, tags) = textsAndTags("שלום עולם", "ar")
        assertEquals(listOf("שלום עולם"), texts)
        assertEquals(listOf("he"), tags)
    }

    @Test
    fun greek_withArabicRequest_usesGreek() {
        val (texts, tags) = textsAndTags("Καλημέρα κόσμε", "ar")
        assertEquals(listOf("Καλημέρα κόσμε"), texts)
        assertEquals(listOf("el"), tags)
    }

    @Test
    fun thai_withArabicRequest_usesThai() {
        val (texts, tags) = textsAndTags("สวัสดี", "ar")
        assertEquals(listOf("สวัสดี"), texts)
        assertEquals(listOf("th"), tags)
    }

    @Test
    fun devanagari_withArabicRequest_usesHindi() {
        val (texts, tags) = textsAndTags("नमस्ते दुनिया", "ar")
        assertEquals(listOf("नमस्ते दुनिया"), texts)
        assertEquals(listOf("hi"), tags)
    }

    @Test
    fun mixedCjkAndLatinAndArabic_splitsByScript() {
        val input = "مرحبا Hello 世界"
        val (texts, tags) = textsAndTags(input, "ar")
        // اللاتينية تُنسب لسقوط الطلب (الإنجليزية)، والهان للصينية:
        // كل سكربتٍ محددُ اللغةِ الآن يصبح مقطعه الخاص.
        assertEquals(listOf("مرحبا ", "Hello ", "世界"), texts)
        assertEquals(listOf("ar", "en", "zh"), tags)
        assertEquals(input, texts.joinToString(""))
    }

    @Test
    fun japaneseHiraganaKatakana_bothUseJapanese() {
        val (texts, tags) = textsAndTags("こんにちはカタカナ", "ar")
        // الهيراغانا والكاتاكانا سكربتان لكن كلاهما يابانية — يُدمجان
        // مقطعاً واحداً لأن اللغتين متطابقتان.
        assertEquals(listOf("こんにちはカタカナ"), texts)
        assertEquals(listOf("ja"), tags)
    }

    @Test
    fun hangul_withArabicRequest_usesKorean() {
        val (texts, tags) = textsAndTags("안녕하세요", "ar")
        assertEquals(listOf("안녕하세요"), texts)
        assertEquals(listOf("ko"), tags)
    }

    @Test
    fun newline_betweenArabicWords_preserved() {
        val input = "سطر\nسطر"
        val (texts, tags) = textsAndTags(input, "ar")
        assertEquals(listOf("سطر\nسطر"), texts)
        assertEquals(listOf("ar"), tags)
        assertEquals(input, texts.joinToString(""))
    }

    @Test
    fun concatenationAlwaysRestoresOriginal() {
        val samples = listOf(
            "Hello" to "ar",
            "قمة Naji وسام" to "ar",
            "أرقام ٥ و 5 داخل نص" to "ar",
            "مرحبا Привет bonjour 你好" to "ar"
        )
        for ((text, request) in samples) {
            val segments = segmenter.segment(text, request)
            assertEquals(
                "استعادة النص الأصلي: $text",
                text,
                segments.joinToString("") { it.text }
            )
            assertTrue(
                "مقطع بلا نص خالٍ: $text",
                segments.all { it.text.isNotEmpty() }
            )
        }
    }

    @Test
    fun unitWithNumber_neutralAttachesToArabicNotLatin() {
        // بند المحايد الذكي: «50» أرقام محايدةٌ تسبقُها العربية فتلتحق
        // بها (لا تُنسب للكتلة اللاتينية اللاحقة «kg»)؛ والوحدة
        // «50kg» يُحِيلها تحويلُ الوحدات في خطوةٍ لاحقة إلى العربية
        // (اختبار [TextProcessorTest]) فلا يبقى التقسيم وحده فاصلاً
        // نهائياً. هنا نتحقق من المقسّم فقط.
        val (texts, tags) = textsAndTags("الوزن 50kg", "ar")
        assertEquals(listOf("الوزن 50", "kg"), texts)
        assertEquals(listOf("ar", "en"), tags)
        assertEquals("الوزن 50kg", texts.joinToString(""))
    }

    @Test
    fun timeWithSuffix_neutralAttachesToArabicNotLatin() {
        // «10:30» محايدة تسبقها العربية فتلتحق بهَا؛ «AM» لاتينيةٌ
        // وحدها مقطعة.
        val (texts, tags) = textsAndTags("الاجتماع 10:30 AM", "ar")
        assertEquals(listOf("الاجتماع 10:30 ", "AM"), texts)
        assertEquals(listOf("ar", "en"), tags)
        assertEquals("الاجتماع 10:30 AM", texts.joinToString(""))
    }

    @Test
    fun percentWithSuffix_neutralAttachesToArabicNotLatin() {
        val (texts, tags) = textsAndTags("خصم 25% off", "ar")
        // «25%» يُلحق بالعربية السابقة (اللغة التي تسبقها مباشرة)،
        // و«off» اللاتينية وحدها مقطعة — بلا فرض افتراضية على الرقم.
        assertEquals(listOf("خصم 25% ", "off"), texts)
        assertEquals(listOf("ar", "en"), tags)
        assertEquals("خصم 25% off", texts.joinToString(""))
    }

    @Test
    fun latinBlock_withFrenchRequest_usesFrench() {
        val (texts, tags) = textsAndTags("Bonjour le monde", "fr")
        assertEquals(listOf("Bonjour le monde"), texts)
        assertEquals(listOf("fr"), tags)
    }

    @Test
    fun latinSuffix_inFrenchRequest_usesFrenchFallback() {
        // «off» لاتيني ضمن طلب فرنسي يُنسب للغة الطلب (سقوط اللاتينية)
        // لا الإنجليزية — سلوك السقوط المحافظ للاتينية.
        val (texts, tags) = textsAndTags("réduction 25% off", "fr")
        assertEquals(listOf("réduction 25% off"), texts)
        assertEquals(listOf("fr"), tags)
    }
}