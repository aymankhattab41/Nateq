package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.engine.TextProcessor
import com.aymankhattab.nateq.core.audio.engine.LanguageSegmenter
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.engine.PunctuationLevels
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
@Config(sdk = [24, 30, 35])
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
    fun processSemantics_currency_keptWholeForSegmentation() {
        // «1500 USD» (المبلغ ثم الكود) يبقى وحدةً واحدة بعد المعالجة الدلالية
        // المبكرة — لا ينفصل رمزُ العملة لاتينياً في مقطعٍ إنجليزي مستقل.
        val out = processor.processSemantics("1500 USD", "ar")
        assertEquals("ألف وخمسمائة دولار أمريكي", out)
        // داخل نص عربي: السطر كله يُنطق عربياً.
        assertEquals(
            "سعر ألف وخمسمائة دولار أمريكي",
            processor.processSemantics("سعر 1500 USD", "ar")
        )
    }

    @Test
    fun processSemantics_splitSegments_currencyNoLongerEnglish() {
        // بند الدمج: تقسيم النص بعد المعالجة الدلالية يعيد مقطعاً عربياً واحداً
        // لـ«سعر 1500 USD» بدل مقطعين [عربي, USD]. فيبقى العملة بلسان عربي.
        val out = processor.processSemantics("سعر 1500 USD", "ar")
        val segments = LanguageSegmenter().segment(out, "ar")
        assertEquals(listOf("ar"), segments.map { it.languageTag })
        assertEquals(
            "سعر ألف وخمسمائة دولار أمريكي",
            segments.joinToString("") {
                it.text
            }
        )
    }

    @Test
    fun processSemantics_english_convertsCurrencyEarly() {
        // المعالجة الدلالية الإنجليزية تحوّل العملة مبكراً فلا ينفصل
        // رمزها اللاتيني «USD» عن مبلغه عند تقسيم اللغة.
        assertEquals(
            "one thousand five hundred US dollars",
            processor.processSemantics("1500 USD", "en")
        )
    }

    @Test
    fun processSemantics_unit_weight_staysArabicBlock() {
        // «50kg» (الرقم ملتصق بوحدة لاتينية): المعالجة الدلالية قبل
        // تقسيم اللغة تحوّلها كتلةً عربية واحدة — لا ينفصل «50» عن
        // «kg» إلى مقطعٍ إنجليزي، ولا يبقى «kg» حروفاً لاتينية.
        val out = processor.processSemantics("الوزن 50kg", "ar")
        val segments = LanguageSegmenter().segment(out, "ar")
        assertEquals(listOf("ar"), segments.map { it.languageTag })
        assertEquals(
            "الوزن خمسون كيلوغرام",
            segments.joinToString("") { it.text }
        )
    }

    @Test
    fun processSemantics_unit_degrees_staysArabicBlock() {
        // «25°C» درجة حرارة: تُنطق عربيةً كاملةً كتلةً واحدة.
        assertEquals(
            "خمس وعشرون درجة مئوية",
            processor.processSemantics("25°C", "ar")
        )
    }

    @Test
    fun processSemantics_time_withLatinSuffix_arabicNumberBlockStays() {
        val out = processor.processSemantics("الاجتماع 10:30 AM", "ar")
        assertEquals("الاجتماع العاشرة والنصف صباحاً", out)
        
        val outPm = processor.processSemantics("الاجتماع 4:50 PM", "ar")
        assertEquals("الاجتماع الخامسة إلا عشر دقائق مساءً", outPm)
    }

    @Test
    fun englishText_convertsNumbers() {
        // المسار الإنجليزي ينطق الأرقام كلماتٍ إنجليزية لا عربية.
        assertEquals(
            "hello world one hundred twenty three",
            processor.process("hello world 123", "en")
        )
        assertEquals(
            "the number forty two",
            processor.process("the number 42", "en-US")
        )
    }

    @Test
    fun nonEnglishNonArabicText_isUnchanged() {
        // الفرنسية/غيرها تبقى كما هي (بلا تحويل أرقام إلى كلمات عربية).
        assertEquals(
            "bonjour 123",
            processor.process("bonjour 123", "fr")
        )
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
        assertEquals(
            "الحادية عشرة إلا ربع صباحاً",
            processor.process("10:45", "ar")
        )
    }

    @Test
    fun phoneNumber_arabicContext_spokenDigitByDigit() {
        // السياق عربي (languageTag=ar) فتُنطق رقماً رقماً عربياً
        // حتى لو كان النص أرقاماً فقط
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
        val expected = "صفر واحد صفر صفر واحد اثنان ثلاثة أربعة خمسة ستة سبعة"
        assertTrue(out.contains(expected))
    }

    @Test
    fun phoneNumber_englishContext_spokenDigitByDigit() {
        // السياق إنجليزي فتُنطق خانات الهاتف كلماتٍ إنجليزية رقمًا رقمًا.
        val out = processor.process("Call 01001234567 now", "en-US")
        assertEquals(
            "Call zero one zero zero one two three four five six seven now",
            out
        )
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
        // پاکستانی
        val input =
            "\u067E\u0627\u06A9\u0633\u062A\u0627\u0646\u06CC " +
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
        // پاکستانی (ضمة ممتدة)
        val marked =
            "\u067E\u0627\u06A9\u08EE\u0633\u062A\u0627\u0646\u06CC " +
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
        val input =
            "مرحبا \uD83D\uDC68\u200D\uD83D\uDC69\u200D" +
            "\uD83D\uDC67\u200D\uD83D\uDC66"
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
    fun emoji_dragonFace_spokenByDragonName() {
        // 🐲 (U+1F432) وجه تنين لا وجه نمر — في العربية والإنجليزية
        val dragon = String(Character.toChars(0x1F432))
        assertEquals("وجه تنين", processor.process(dragon, "ar"))
        assertEquals("dragon face", processor.process(dragon, "en"))
    }

    @Test
    fun quranicStopMarks_stripped() {
        // علامة ضبط مصحفي ملتصقة (شمس + U+06D8) ومنتهى آية (U+06DD) تُجرَّد
        assertEquals("شمس", processor.process("شمس\u06D8", "ar"))
        assertEquals("سورة", processor.process("سورة \u06DD", "ar"))
    }

    @Test
    fun emoji_disabled_removedFromBothLanguages() {
        // عند إيقاف «نطق الإيموجي» يُحذف الإيموجي من العربية والإنجليزية
        // (المسار الإنجليزي يستخدم خطوة الإزالة نفسها).
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsRepository(ctx)
        settings.setEmojiPronunciationEnabled(false)
        val processorOff = TextProcessor(ctx, settings)
        assertEquals("مرحبا", processorOff.process("مرحبا 😊", "ar"))
        assertEquals("Great job", processorOff.process("Great 😀 job", "en"))
    }

    @Test
    fun asciiEmoticon_arabicSpoken() {
        assertEquals("أخبارك حزين", processor.process("أخبارك :(", "ar"))
        assertEquals("أحبك قلب", processor.process("أحبك <3", "ar"))
    }

    @Test
    fun punctuationLevel_none_keepsSymbolsAsIs() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsRepository(ctx)
        settings.setPunctuationLevel(PunctuationLevels.NONE)
        val processorOff = TextProcessor(ctx, settings)
        assertEquals("خمسون%", processorOff.process("خمسون%", "ar"))
        assertEquals("نلتقي @ خمسة", processorOff.process("نلتقي @ 5", "ar"))
    }

    @Test
    fun punctuationLevel_some_defaultNamesBasicSymbols() {
        // السطر التالي يثبّت السلوك الافتراضي (SOME) مع الأرقام المكتوبة
        assertEquals("خمسون بالمئة", processor.process("خمسون%", "ar"))
        // @ المعزولة تُنطق «عند» ويُحوَّل الرقم اللاحق إلى كلمات
        assertEquals("نلتقي عند خمسة", processor.process("نلتقي @ 5", "ar"))
        // البريد الإلكتروني محمي: لا تنطق فيه @
        assertEquals("رسالتي a@b.com",
            processor.process("رسالتي a@b.com", "ar"))
    }

    @Test
    fun punctuationLevel_all_addsParensSemicolonsAndDashes() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsRepository(ctx)
        settings.setPunctuationLevel(PunctuationLevels.ALL)
        val processorAll = TextProcessor(ctx, settings)
        assertEquals(
            "قوس افتتاح ملاحظة قوس إقفال",
            processorAll.process("(ملاحظة)", "ar")
        )
        assertEquals("فاصلة منقوطة", processorAll.process("؛", "ar"))
        assertEquals(
            "جملة شرطة تفصيل",
            processorAll.process("جملة — تفصيل", "ar")
        )
        // «البعض» الأساسية تعمل أيضاً ضمن «الكل»
        assertEquals("خمسون بالمئة", processorAll.process("خمسون%", "ar"))
    }

    @Test
    fun smartSpelling_singleLetterArabicAndLatin() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsRepository(ctx)
        settings.setSmartSpellingEnabled(true)
        val processorSpelling = TextProcessor(ctx, settings)
        assertEquals("باء", processorSpelling.process("ب", "ar"))
        assertEquals("باء مفتوحة", processorSpelling.process("بَ", "ar"))
        assertEquals("Capital Alpha", processorSpelling.process("A", "en"))
        assertEquals("Alpha", processorSpelling.process("a", "en"))
        // الكلمات العادية لا تتأثر بالتهجئة
        assertEquals("مرحبا", processorSpelling.process("مرحبا", "ar"))
    }

    @Test
    fun smartSpelling_disabledByDefault_singleLetterPassesThrough() {
        // المعطّل الافتراضي: الحرف المفرد يمرّ بلا تهجئة
        assertEquals("ب", processor.process("ب", "ar"))
    }

    @Test
    fun asciiEmoticon_englishSpoken() {
        assertEquals("hello smile", processor.process("hello :)", "en"))
        assertEquals("oh laughing", processor.process("oh :D", "en"))
    }

    @Test
    fun englishDate_speaksMonthOrdinalYear() {
        assertEquals(
            "March fifteenth two thousand twenty four",
            processor.process("2024-03-15", "en")
        )
    }

    @Test
    fun englishCurrency_symbolAndCode() {
        assertEquals(
            "price one thousand five hundred US dollars",
            processor.process("price 1500 USD", "en")
        )
        assertEquals(
            "cost one dollar and fifty cents",
            processor.process("cost $1.50", "en")
        )
    }

    @Test
    fun englishPunctuationAndNumbers_combined() {
        assertEquals(
            "Done fifty percent",
            processor.process("Done 50%", "en")
        )
        assertEquals(
            "two plus two equals four",
            processor.process("2+2=4", "en")
        )
    }

    @Test
    fun englishUrl_keptWhole_notMangledByPunctuation() {
        // الرابط محجوب أثناء خطوات الترقيم فلا يصير «https: slash slash…».
        assertEquals(
            "check https://example.com/path now",
            processor.process("check https://example.com/path now", "en")
        )
    }

    @Test
    fun urlContainingDate_isKeptWhole() {
        // تاريخ داخل الرابط (2026-03-09) لا يُفسَّر كتاريخ مستقل: UrlStep تعمل
        // أولاً فتحمي الرابط ومساراته الرقمية من خطوات التاريخ/الوقت/العملة
        // اللاحقة، فيُنطق اسم النطاق ويختفي باقي المسار بصمت (لا كلمات تاريخ).
        val out = processor.process(
            "راجع https://site.com/news/2026-03-09/post",
            "ar"
        )
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
        // الكسر 1/2 يُنطق «واحد على اثنان»
        // (numberToWords يستخدم «اثنان» المرفوعة).
        assertEquals("واحد على اثنان", processor.process("1/2", "ar"))
        // الادعاء: العملية الطويلة (8 خانات) لا تُنطق هاتفاً رقماً رقماً
        assertEquals("ألف ناقص ألفان", processor.process("1000 - 2000", "ar"))
        // الادعاء: «%» يُستبدل مفصولاً بمسافات فلا تلتصق «خمسونبالمئة»
        assertEquals("خمسون بالمئة", processor.process("خمسون%", "ar"))
    }

    @Test
    fun decimalNumber_threeFractionDigits_notCorrupted() {
        // 3.141 عدد عشري لا فاصلة آلاف — لا يتحول إلى 3141.
        assertEquals(
            "ثلاثة فاصلة واحد أربعة واحد",
            processor.process("3.141", "ar")
        )
    }

    @Test
    fun thousandsSeparators_stillRemoved() {
        assertEquals(
            "ألف ومائتان وأربعة وثلاثون",
            processor.process("1,234", "ar")
        )
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
        assertEquals(
            "مليون ومائتا ألف وخمسمائة",
            processor.process("1,200,500", "ar")
        )
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
        assertEquals(
            // بند 3.2: 11–99 منصوب («سنتاً» لا «سنت»)
            "دولار واحد وخمسون سنتاً",
            processor.process("""$1.50""", "ar")
        )
        assertEquals("خمسون سنتاً", processor.process("""0.50$""", "ar"))
        assertEquals(
            "عشرة ملايين دولار",
            processor.process("""$10,000,000""", "ar")
        )
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
        assertEquals(
            "ألف ومائتان وأربعة وثلاثون فاصلة خمسة ستة",
            processor.process("1.234,56", "ar")
        )
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
        assertEquals(
            "المبلغ عشرة ملايين",
            processor.process("المبلغ 10000000", "ar")
        )
        assertEquals("خمسة ملايين", processor.process("5000000", "ar"))
    }

    @Test
    fun unit_ArabicSymbols_matchThroughUnicodeBoundaries() {
        // \b بلا (?U) لا يعترف بحروف عربية ككلمات؛ كانت «5 م» و«10 سم» و
        // «80 كم/س» لا تُطابق إطلاقاً.
        assertEquals("خمسة أمتار", processor.process("5 م", "ar"))
        assertEquals("عشرة سنتيمترات", processor.process("10 سم", "ar"))
        assertEquals(
            "ثمانون كيلومتر في الساعة",
            processor.process("80 كم/س", "ar")
        )
    }

    @Test
    fun numberToWords_specialValues_noCrash() {
        assertTrue(processor.numberToWords(Double.NaN).isNotBlank())
        assertEquals(
            "ما لا نهاية",
            processor.numberToWords(Double.POSITIVE_INFINITY)
        )
        assertEquals(
            "ناقص ما لا نهاية",
            processor.numberToWords(Double.NEGATIVE_INFINITY)
        )
        // Long.MIN_VALUE لا يفيض ولا يغرق في حلقة (StackOverflow) ولو
        // مسبوق بالسالب.
        val minWords = processor.numberToWords(Long.MIN_VALUE)
        assertTrue(minWords.startsWith("ناقص"))
        assertTrue(minWords.contains("كوينتيليون"))
    }

    @Test
    fun numberToWords_largePositiveLong_supported() {
        // حتى الكوينتيليون (10^18) — أقصى مدى Long سليم دون صفر يعيد
        // كلمات ناقصة.
        assertTrue(processor.numberToWords(Long.MAX_VALUE).isNotBlank())
    }

    // ============ بند 7: أسطر/تبويبات عربية لا تُطلق مساراً ثقيلاً
    // ============

    /** النص العربي متعدد الأسطر (بلا أرقام/رموز لاتينية) كان يفعل
     *  المسار الثقيل بلا موجب: \n و\r و\t جميعها تحت U+0600، فتُطابق
     *  شرط «حرف لاتيني» القديم. المسار السريع يكفي فلا تُمرَّر النصوص
     *  العربية متعددة الأسطر عبر مراحل regex الثقيلة بلا حاجة. */
    @Test
    fun multilineArabic_withoutNumbers_passesThroughFastPath() {
        val arabic = "السلام عليكم\nورحمة الله\nوبركاته"
        // العربية النقية بلا أرقام مرت قديماً بالمسار الثقيل لمجرد وجود
        // \n (تحت U+0600 فتُطابق «حرفاً لاتينياً»). الآن المسار السريع
        // يعالجها بلا مراحل regex ثقيلة؛ السلوك الظاهر صحيح: لا أرقامَ
        // تُحوَّل ولا رموز تُنطق، وCleanupStep يضمّ الأسطر إلى مسافات.
        assertEquals(
            "السلام عليكم ورحمة الله وبركاته",
            processor.process(arabic, "ar")
        )
        assertEquals(
            "بالتبويب المضغوط",
            processor.process("بالتبويب\u0009المضغوط", "ar")
        )
    }

    /** الصفِّة السريعة ليست «إبادةً» للمحفِّزات: سطرٌ عربي يتلوها رقمٌ
     *  لاتيني حقيقي لا يزال يحفّز المسار الثقيل فيُنطق الرقم. */
    @Test
    fun multilineArabic_withRealLatinDigit_stillConverts() {
        val mixed = "السطر الأول\nالسطر الثاني 25"
        assertEquals(
            "السطر الأول السطر الثاني خمسة وعشرون",
            processor.process(mixed, "ar")
        )
    }

    /** الإيموجي الملتصق بكلمة (بلا مسافة) يفصل اسمَه عن جاره بمسافة —
     *  لم تكن الكلمة التالية تُفصل فتَلتصق باسم الإيموجي. */
    @Test
    fun emojiStuckToWord_separatesNameFromNeighbours() {
        // U+1F600 😀 اسمه في العرب AR: «وجه مبتسم».
        assertEquals(
            "مرحبا وجه مبتسم مرحبا",
            processor.process("مرحبا\uD83D\uDE00مرحبا", "ar")
        )
        assertEquals(
            "وجه مبتسم مرحبا",
            processor.process("\uD83D\uDE00مرحبا", "ar").trim()
        )
    }

    /** idempotence للمسار الثقيل: تطبيق المعالجة الثانية لا يغيّر الأولى —
     *  أوتكات التحويل (الأرقام/العملات/التواريخ/الرموز/الهواتف) تُحوَّل
     *  مرة واحدة ولا تصير النتيجة حساسةً للتمرير المتكرر عبر خطوات regex
     *  الثقيلة (إعادةُ تمريرٍ تحدث عملياً في عملية التقسيم الدلالي). */
    @Test
    fun heavyPath_isIdempotent() {
        val probes = listOf(
            "ar" to "المبلغ 1500 دولار",
            "ar" to "السعر 45.75",
            "ar" to "الاجتماع 10:30 صباحا",
            "ar" to "رقم 0791234567",
            "ar" to "نسبة 25%",
            "ar" to "الربح 1200 - خسارة 300",
            "en" to "this is 50 percent of 30",
            "en" to "call 0791234567 now"
        )
        for ((language, probe) in probes) {
            val once = processor.process(probe, language)
            val twice = processor.process(once, language)
            assertEquals(
                "التمرير الثقيل يجب أن يكون إبدامياً لـ: $probe",
                stripDiacritics(once), stripDiacritics(twice)
            )
        }
    }

    /** يُسقط تشكيلات هذه المقالة قبل المقارنة: نطقٌ مثل «صباحاً» يولّد
     *  المسارُ الثقيلُ تنوينَه في أول مرة، وعند إعادة التمرير يزيله
     *  [preamble] (إزالةُ تشكيلٍ قبل التحويل) — فالحروف وناتجُ التحويل
     *  ثابتان والوشمُ الزخرفي يزول؛ المقارنة على الحروف لا الشكل. */
    private fun stripDiacritics(text: String): String =
        text.replace(Regex("[\u064B-\u0652\u0670\u0640]"), "")

    // ============ بند 1.7: إبقاء تشكيل النصوص العربية ============

    @Test
    fun tashkeelPreserved_disabledByDefault_diacriticsStripped() {
        // السلوك القائم: معطّل افتراضياً — التشكيل يُجرّد قبل المحرك
        // (فلا تُرسَل الحركات لمحركاتٍ لا تفهمها).
        val plain = processor.process("السَّلَامُ عَلَيْكُمْ", "ar")
        assertEquals("السلام عليكم", plain)
    }

    @Test
    fun tashkeelPreserved_enabled_unchangedWordsKeepDiacritics() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsRepository(ctx)
        settings.setTashkeelPreserved(true)
        val preserved = TextProcessor(ctx, settings)

        // النص العربي الصافي بلا أرقام يمر عبر fast-path: تُعاد كلماته
        // بتشكيلها الأصلي كاملةً (لا تحويل فيها).
        assertEquals(
            "السَّلَامُ عَلَيْكُمْ",
            preserved.process("السَّلَامُ عَلَيْكُمْ", "ar")
        )
        // كلمات مشكولة مع علامة لا تغيّر الحرف: التشكيل محفوظ.
        assertEquals(
            "أَهْلًا بِكَ",
            preserved.process("أَهْلًا بِكَ", "ar")
        )
    }

    @Test
    fun tashkeelPreserved_enabled_transformedNumbersStayWords() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsRepository(ctx)
        settings.setTashkeelPreserved(true)
        val preserved = TextProcessor(ctx, settings)

        // الرقم يبقى مطابقاً للمسار العادي (لا تنكسر التحويلات عند إبقاء
        // التشكيل) — نطق الأرقام لا يخضع لحفظ الحركات.
        assertEquals(
            "السلام عليكم والعدد خمسة",
            preserved.process("السلام عليكم والعدد 5", "ar")
        )
    }

    @Test
    fun tashkeelPreserved_enabled_arabicDigitsAlsoConvert() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsRepository(ctx)
        settings.setTashkeelPreserved(true)
        val preserved = TextProcessor(ctx, settings)

        // الأرقام الشرقية (٥) تُحوَّل للكلمات كما في المسار العادي رغم
        // إبقاء التشكيل — خطوة الأرقام تميّزها مستقلةً عن التجريد.
        assertEquals(
            "أهلا خمسة",
            preserved.process("أهلا ٥", "ar")
        )
    }

    @Test
    fun tashkeelPreserved_enabled_emojiExpansionStillWorks() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsRepository(ctx)
        settings.setTashkeelPreserved(true)
        val preserved = TextProcessor(ctx, settings)

        // توسيع الإيموجي يُحدَّث قبل حفظ النسخة المشكولة (voweledSource
        // يؤخذ بعد expansion) فلا تنكسر أسماء الإيموجي مع الحفظ.
        assertEquals(
            "أَهْلًا وجه مبتسم",
            preserved.process("أَهْلًا \uD83D\uDE00", "ar").trim()
        )
    }

    // ===== بند التشكيل الشرطي حسب محرك TTS =====

    @Test
    fun engineAware_tashkeelPreservedForGoogle() {
        // Google TTS يفهم التشكيل → تُبقي معالج النص الحركات في الناتج
        // بلا اشتراط تفضيل «حفظ التشكيل».
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsRepository(ctx)
        settings.setTashkeelPreserved(false)
        val engineAware = TextProcessor(ctx, settings)

        assertEquals(
            "السَّلَامُ عَلَيْكُمْ",
            engineAware.process(
                "السَّلَامُ عَلَيْكُمْ", "ar",
                "com.google.android.tts"
            )
        )
    }

    @Test
    fun engineAware_unknownEngineStripsTashkeel() {
        // محرك غير معروف أو null → السلوك القائم: التجريد قبل المحرك.
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsRepository(ctx)
        settings.setTashkeelPreserved(false)
        val engineAware = TextProcessor(ctx, settings)

        assertEquals(
            "السلام عليكم",
            engineAware.process("السَّلَامُ عَلَيْكُمْ", "ar")
        )
        assertEquals(
            "السلام عليكم",
            engineAware.process(
                "السَّلَامُ عَلَيْكُمْ", "ar",
                "com.samsung.android.tts"
            )
        )
    }
}

