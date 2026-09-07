package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.engine.TextProcessor
import com.aymankhattab.nateq.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** اختبارات معالج النصوص الذكي (TextProcessor). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextProcessorTest {

    private lateinit var processor: TextProcessor

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        processor = TextProcessor(context)
    }

    @Test
    fun blankText_isReturned() {
        assertEquals("", processor.process("", "ar"))
        assertEquals("   ", processor.process("   ", "ar"))
    }

    @Test
    fun nonArabicText_isUnchanged() {
        assertEquals("hello world 123", processor.process("hello world 123", "en"))
        assertEquals("the number 42", processor.process("the number 42", "en-US"))
    }

    @Test
    fun plainArabic_isUnchanged() {
        // نص عربي صافٍ بلا أرقام يمر عبر fast-path دون تحويلات
        assertEquals("مرحبا بالعالم", processor.process("مرحبا بالعالم", "ar"))
    }

    @Test
    fun hijriDateConversion_disabled_usesGregorian() {
        // عند تعطيل الهجري: تاريخ ميلادي عادي
        val out = processor.process("15/06/2024", "ar")
        assertEquals("خمسة عشر يونيو ألفان وأربعة وعشرون", out)
    }

    @Test
    fun gregorianDate_ymdFormat() {
        val out = processor.process("2024/01/15", "ar")
        assertEquals("خمسة عشر يناير ألفان وأربعة وعشرون", out)
    }

    @Test
    fun time_arabicNatural() {
        // 14:30 → الثانية عشرة محوَّلة لـ 2 والنصف مساءً
        val out = processor.process("14:30", "ar")
        assertEquals("اثنان والنصف مساءً", out)
    }

    @Test
    fun phoneNumber_arabicContext_spokenDigitByDigit() {
        // السياق عربي (languageTag=ar) فتُنطق رقماً رقماً عربياً حتى لو كان النص أرقاماً فقط
        val out = processor.process("01001234567", "ar")
        assertEquals(
            "صفر واحد صفر صفر واحد اثنان ثلاثة أربعة خمسة ستة سبعة",
            out
        )
    }

    @Test
    fun phoneNumber_mixedArabicText_spokenDigitByDigit() {
        // حتى بوجود نص عربي حول الرقم
        val out = processor.process("اتصل بـ 01001234567", "ar")
        assertTrue(out.contains("صفر واحد صفر صفر واحد اثنان ثلاثة أربعة خمسة ستة سبعة"))
    }

    @Test
    fun phoneNumber_englishContext_leftForSpeechEngine() {
        // النص الإنجليزي يُعاد كما هو من process() دون تحويل أو نطق عربي
        val out = processor.process("Call 01001234567 now", "en-US")
        assertEquals("Call 01001234567 now", out)
    }

    @Test
    fun currencyAndNumber_arabic() {
        // القاموس الشخصي افتراضياً فارغ (يبنيه المستخدم بالإضافة/الاستيراد)،
        // فكلمة «جنيه» لا تُتوسَّع تلقائياً، والأرقام تُنطق طبيعياً.
        val out = processor.process("100 جنيه", "ar")
        assertEquals("مائة جنيه", out)
    }

    @Test
    fun importedTashkeel_removedInFastPath() {
        // التشكيل يُجرّد في fast-path وأنماط المطابقة
        val out = processor.process("كَيْفَ", "ar")
        assertEquals("كيف", out)
    }

    @Test
    fun urduText_withoutExtendedMarks_unchanged() {
        // نص أوردو عادي (لا يحمل رموز النطاق الممتد) — لا يتأثر بالتوسيع الجديد
        val input = "\u067E\u0627\u06A9\u0633\u062A\u0627\u0646\u06CC " + // پاکستانی
            "\u0645\u06CC\u0631\u06D2 " + // میرے
            "\u062F\u0648\u0633\u062A " + // دوست
            "\u06C1\u06CC\u06BA" // ہیں
        assertEquals(input, processor.process(input, "ar"))
    }

    @Test
    fun urduText_withExtendedTashkeel_stripped() {
        // نص أوردو مشكول بعلامة من نطاق التشكيل العربي الممتد
        // (U+08A0–U+08FF، هنا تعني الضمة الأوردية U+08EE). تُجرّد العلامة لكن
        // تبقى الحروف الأوردية نفسها (پ ک ی ے ہ ں) سالمة تماماً.
        val marked = "\u067E\u0627\u06A9\u08EE\u0633\u062A\u0627\u0646\u06CC " + // پاکستانی (ضمة ممتدة)
            "\u0645\u06CC\u0631\u06D2 " + // میرے
            "\u062F\u0648\u0633\u062A\u08F0 " + // دوست (فتحتان مفتوحتان U+08F0)
            "\u06C1\u06CC\u06BA" // ہیں
        val expected = "پاکستانی میرے دوست ہیں"
        assertEquals(expected, processor.process(marked, "ar"))
    }

    @Test
    fun emoji_spokenByName_arabic() {
        // نطق الإيموجي مفعّل افتراضياً: 😊 تُنطق باسمها لا حذفها
        val out = processor.process("مرحبا 😊", "ar")
        assertEquals("مرحبا وجه مبتسم بعينين مبتسمتين", out)
    }

    @Test
    fun emoji_spokenByName_english() {
        val out = processor.process("Great 😀 job 👍", "en")
        assertEquals("Great beaming face job thumbs up", out)
    }

    @Test
    fun emoji_compoundFamily_spokenOnce() {
        // إيموجي مركّب بعائلة (👨‍👩‍👧‍👦 عبر ZWJ) يُنطق باسم أول مكوّن مرة
        // واحدة ولا يبقى أي ZWJ أو وحدات نصية محجرة في الناتج.
        val input = "مرحبا \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        val out = processor.process(input, "ar")
        assertEquals("مرحبا رجل", out)
        assertTrue(!out.contains("\u200D"))
    }

    @Test
    fun emoji_countryFlag_spokenByCountry() {
        // علم سعودية 🇸🇦 (زوج مؤشرَي منطقة) يُنطق «علم السعودية»
        val input = "مرحبا \uD83C\uDDF8\uD83C\uDDE6"
        val out = processor.process(input, "ar")
        assertEquals("مرحبا علم السعودية", out)
        assertTrue(!out.contains("\uD83C"))
    }

    @Test
    fun emoji_unmapped_fallbackWord() {
        // إيموجي غير موجود في قاموس الأسماء يُنطق بالكلمة العامة الثابتة
        val out = processor.process("\uD83D\uDC00 ok", "ar")
        assertEquals("إيموجي ok", out)
    }

    @Test
    fun emoji_disabled_removedFromArabicText() {
        // عند إيقاف «نطق الإيموجي»: يُحذف الإيموجي من العربية (السلوك السابق)
        // واللغة الإنجليزية تُعاد كما هي بلا حذف ولا نطق.
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsRepository(ctx)
        settings.setEmojiPronunciationEnabled(false)
        val processorOff = TextProcessor(ctx, settings)
        assertEquals("مرحبا", processorOff.process("مرحبا 😊", "ar"))
        assertEquals("Great 😀 job", processorOff.process("Great 😀 job", "en"))
    }

    @Test
    fun asciiEmoticon_arabicSpoken() {
        assertEquals("أخبارك حزين", processor.process("أخبارك :(", "ar"))
        assertEquals("أحبك قلب", processor.process("أحبك <3", "ar"))
    }

    @Test
    fun asciiEmoticon_englishSpoken() {
        assertEquals("hello smile", processor.process("hello :)", "en"))
        assertEquals("oh laughing", processor.process("oh :D", "en"))
    }
}