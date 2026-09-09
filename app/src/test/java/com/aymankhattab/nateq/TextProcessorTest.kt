package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.engine.TextProcessor
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // الساعة تُنطق بالصيغة الترتيبية المؤنثة المعرّفة بأل:
        // 14:30 → «الثانية والنصف مساءً» لا «اثنان والنصف مساءً»
        assertEquals("الثانية والنصف مساءً", processor.process("14:30", "ar"))
        // 01:00 → «الواحدة صباحاً» لا «واحد صباحاً»
        assertEquals("الواحدة صباحاً", processor.process("01:00", "ar"))
        // 02:15 → «الثانية والربع صباحاً»
        assertEquals("الثانية والربع صباحاً", processor.process("02:15", "ar"))
        // 12:00 → «الثانية عشرة ظهراً» لا «مساءً»
        assertEquals("الثانية عشرة ظهراً", processor.process("12:00", "ar"))
        // 10:45 → «الحادية عشرة إلا ربع صباحاً»
        assertEquals("الحادية عشرة إلا ربع صباحاً", processor.process("10:45", "ar"))
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

    @Test
    fun urlContainingDate_isKeptWhole() {
        // تاريخ داخل الرابط (2026-03-09) لا يُفسَّر كتاريخ مستقل: UrlStep تعمل
        // أولاً فتحمي الرابط ومساراته الرقمية من خطوات التاريخ/الوقت/العملة
        // اللاحقة، فيُنطق اسم النطاق ويختفي باقي المسار بصمت (لا كلمات تاريخ).
        val out = processor.process("راجع https://site.com/news/2026-03-09/post", "ar")
        assertEquals("راجع موقع site", out)
        assertTrue(!out.contains("مارس"))
        assertTrue(!out.contains("post"))
    }

    // ===== الإصلاحات: معالجة النصوص العربية والحالات الشاذة =====

    @Test
    fun tashkeel_extendedA_lettersPreserved_marksStripped() {
        // U+08A0 (باء ذات نقطة سفلية من العربية الممتدة A — حرف هجائي أصلي)
        // يجب أن يبقى لا يُمسح كنطاق تشكيل خاطئ.
        assertTrue(processor.process("\u08A0 كلمة", "ar").contains("\u08A0"))
        // حركة خالصة (الضمة الأوردية U+08EE) تُجرَّد كما كانت.
        assertFalse(processor.process("\u0628\u08EE", "ar").contains("\u08EE"))
    }

    @Test
    fun arithmeticOperations_spokenBetweenNumbers() {
        // الرموز الحسابية تُعالج قبل الأرقام فلا تتجمد (كانت أرقامُها تتحول
        // كلمات أولاً فيفشل نمط (?<=\d)…(?=\d)).
        assertEquals("خمسة زائد ثلاثة", processor.process("5 + 3", "ar"))
        assertEquals("عشرة ناقص أربعة", processor.process("10 - 4", "ar"))
        // الكسر 1/2 يُنطق «واحد على اثنان» (numberToWords يستخدم «اثنان» المرفوعة).
        assertEquals("واحد على اثنان", processor.process("1/2", "ar"))
        // الادعاء: العملية الطويلة (8 خانات) لا تُنطق هاتفاً رقماً رقماً
        assertEquals("ألف ناقص ألفان", processor.process("1000 - 2000", "ar"))
        // الادعاء: «%» يُستبدل مفصولاً بمسافات فلا تلتصق «خمسونبالمئة»
        assertEquals("خمسون بالمئة", processor.process("خمسون%", "ar"))
    }

    @Test
    fun decimalNumber_threeFractionDigits_notCorrupted() {
        // 3.141 عدد عشري لا فاصلة آلاف — لا يتحول إلى 3141.
        assertEquals("ثلاثة فاصلة واحد أربعة واحد", processor.process("3.141", "ar"))
    }

    @Test
    fun thousandsSeparators_stillRemoved() {
        assertEquals("ألف ومائتان وأربعة وثلاثون", processor.process("1,234", "ar"))
        assertEquals("مليون ومائتان وأربعة وثلاثون ألفاً وخمسمائة وسبعة وستون",
            processor.process("1,234,567", "ar"))
    }

    @Test
    fun grammatical_alafScale_constructGenitive() {
        // تمييز الآلاف بالإضافة المجرورة لا النصب:
        assertEquals("مائة ألف", processor.process("100,000", "ar"))
        // حذف نون المثنى عند الإضافة:
        assertEquals("مائتا ألف", processor.process("200,000", "ar"))
        // آحاد 5 بعد مئة → جمع آلاف:
        assertEquals("مائة وخمسة آلاف", processor.process("105,000", "ar"))
        // تمييز الملايين بالإضافة كذلك:
        assertEquals("مليون ومائتا ألف وخمسمائة", processor.process("1,200,500", "ar"))
        assertEquals("مائة مليون", processor.process("100,000,000", "ar"))
        assertEquals("مائتا مليون", processor.process("200,000,000", "ar"))
        // مئات مضبوطة:
        assertEquals("ثلاثمائة ألف", processor.process("300,000", "ar"))
    }

    @Test
    fun longDigits_precisionPreserved() {
        // 16 خانة (بطاقة مصرفية/رمز طويل) تفوق دقة Double (2^53) — تُنطق
        // عبر Long بدقة كاملة؛ 9999999999999999 لا تُقرَّب إلى خطأ.
        val out = processor.process("9999999999999999", "ar")
        assertTrue(out.startsWith("تسعة كوادريليونات"))
        assertFalse(out.contains("عشرة كوادريليون"))
    }

    @Test
    fun grammatical_feminineCounted_compound() {
        // آحاد العدد المركّب تلزم بالمؤنث مع المعدود المؤنث:
        assertEquals("خمس وعشرون سنة", processor.process("25 سنة", "ar"))
        assertEquals("أربع عشرة سنة", processor.process("14 سنة", "ar"))
        assertEquals("سبع وثلاثون ساعة", processor.process("37 س", "ar"))
        assertEquals("خمس وثلاثون دقيقة", processor.process("35 د", "ar"))
    }

    @Test
    fun grammatical_currency_singularPluralAndFractions() {
        // 1 ← المال قبل العدد، 3 ← جمع، والكسور باسم وحدتها الفرعية:
        assertEquals("دولار واحد", processor.process("""$1""", "ar"))
        assertEquals("دولاران", processor.process("""$2""", "ar"))
        assertEquals("ثلاثة دولارات", processor.process("""$3""", "ar"))
        assertEquals("دولار واحد وخمسون سنت", processor.process("""$1.50""", "ar"))
        assertEquals("خمسون سنت", processor.process("""0.50$""", "ar"))
        assertEquals("عشرة ملايين دولار", processor.process("""$10,000,000""", "ar"))
    }

    @Test
    fun networkIp_leftUnchanged() {
        // عنوان IP معرّف شبكة لا يُقرأ عدّاً في مسار المعالجة الكامل
        // (كان «192.168.1» يُشوّه إلى «مائة واثنان وتسعون ألفاً …»).
        assertEquals(
            "الخادم 192.168.1.1 يعمل",
            processor.process("الخادم 192.168.1.1 يعمل", "ar")
        )
        assertEquals("10.20.30.40", processor.process("10.20.30.40", "ar"))
    }

    @Test
    fun europeanDecimal_separatedDigits() {
        // التنسيق الأوروبي 1.234,56 = 1234.56.
        assertEquals("ألف ومائتان وأربعة وثلاثون فاصلة خمسة ستة", processor.process("1.234,56", "ar"))
    }

    @Test
    fun romanNumerals_englishWords_keptAsIs() {
        assertEquals("DID", processor.process("DID", "ar"))
        assertEquals("MIX", processor.process("MIX", "ar"))
        assertEquals("MID", processor.process("MID", "ar"))
        assertEquals("I", processor.process("I", "ar"))
        assertEquals("قرص CD", processor.process("قرص CD", "ar"))
    }

    @Test
    fun romanNumerals_afterIndicator_orSequential_converted() {
        assertEquals("الفصل ثلاثة", processor.process("الفصل III", "ar"))
        assertEquals("اثنا عشر", processor.process("XII", "ar"))
    }

    @Test
    fun longBareNumber_notPhone_spokenAsNumber() {
        // المبالغ الطويلة بلا فواصل لا تُعامل هواتف ولا تُنطق رقماً رقماً.
        assertEquals("المبلغ عشرة ملايين", processor.process("المبلغ 10000000", "ar"))
        assertEquals("خمسة ملايين", processor.process("5000000", "ar"))
    }

    @Test
    fun unit_ArabicSymbols_matchThroughUnicodeBoundaries() {
        // \b بلا (?U) لا يعترف بحروف عربية ككلمات؛ كانت «5 م» و«10 سم» و
        // «80 كم/س» لا تُطابق إطلاقاً.
        assertEquals("خمسة أمتار", processor.process("5 م", "ar"))
        assertEquals("عشرة سنتيمترات", processor.process("10 سم", "ar"))
        assertEquals("ثمانون كيلومتر في الساعة", processor.process("80 كم/س", "ar"))
    }

    @Test
    fun numberToWords_specialValues_noCrash() {
        assertTrue(processor.numberToWords(Double.NaN).isNotBlank())
        assertEquals("ما لا نهاية", processor.numberToWords(Double.POSITIVE_INFINITY))
        assertEquals("ناقص ما لا نهاية", processor.numberToWords(Double.NEGATIVE_INFINITY))
        // Long.MIN_VALUE لا يفيض ولا يغرق في حلقة (StackOverflow) ولو مسبوق بالسالب.
        val minWords = processor.numberToWords(Long.MIN_VALUE)
        assertTrue(minWords.startsWith("ناقص"))
        assertTrue(minWords.contains("كوينتيليون"))
    }

    @Test
    fun numberToWords_largePositiveLong_supported() {
        // حتى الكوينتيليون (10^18) — أقصى مدى Long سليم دون صفر يعيد كلمات ناقصة.
        assertTrue(processor.numberToWords(Long.MAX_VALUE).isNotBlank())
    }
}