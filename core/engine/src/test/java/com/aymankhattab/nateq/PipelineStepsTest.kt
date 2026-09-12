package com.aymankhattab.nateq

import com.aymankhattab.nateq.core.engine.PunctuationLevels
import com.aymankhattab.nateq.engine.EmojiSpeech
import com.aymankhattab.nateq.engine.SpeechPart
import com.aymankhattab.nateq.engine.pipeline.AmountParser
import com.aymankhattab.nateq.engine.pipeline.CleanupStep
import com.aymankhattab.nateq.engine.pipeline.CurrencyStep
import com.aymankhattab.nateq.engine.pipeline.DateStep
import com.aymankhattab.nateq.engine.pipeline.EmojiStripStep
import com.aymankhattab.nateq.engine.pipeline.NumberStep
import com.aymankhattab.nateq.engine.pipeline.NumberWordsConverter
import com.aymankhattab.nateq.engine.pipeline.PhoneNumberStep
import com.aymankhattab.nateq.engine.pipeline.PunctuationStep
import com.aymankhattab.nateq.engine.pipeline.RomanNumeralStep
import com.aymankhattab.nateq.engine.pipeline.SymbolStep
import com.aymankhattab.nateq.engine.pipeline.UnitStep
import com.aymankhattab.nateq.engine.pipeline.UrlStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات خطوات المعالجة ككائنات مستقلة (internal objects) — بدون Context أو
 * Robolectric — لتغطية حالات الحافة التي لا تصلها اختبارات TextProcessor
 * عبر المعالج الكامل.
 */
class PipelineStepsTest {

    // ═══════════════════════ UnitStep ═══════════════════════

    @Test
    fun unit_1_masculine() {
        assertEquals("متر واحد", UnitStep.apply("1 م"))
    }

    @Test
    fun unit_1_feminine() {
        assertEquals("دقيقة واحدة", UnitStep.apply("1 د"))
    }

    @Test
    fun unit_2_dual() {
        assertEquals("كيلومتران", UnitStep.apply("2 km"))
    }

    @Test
    fun unit_plural_masculine() {
        assertEquals("خمسة كيلومترات", UnitStep.apply("5 km"))
    }

    @Test
    fun unit_plural_feminine() {
        assertEquals("ثلاث دقائق", UnitStep.apply("3 د"))
    }

    @Test
    fun unit_teens_masculine() {
        assertEquals("أحد عشر كيلوغرام", UnitStep.apply("11 kg"))
    }

    @Test
    fun unit_teens_feminine() {
        assertEquals("اثنتا عشرة ساعة", UnitStep.apply("12 س"))
    }

    @Test
    fun unit_compound_feminine() {
        assertEquals("سبع وثلاثون ساعة", UnitStep.apply("37 س"))
    }

    @Test
    fun unit_compound_feminine_25() {
        assertEquals("خمس وعشرون سنة", UnitStep.apply("25 سنة"))
    }

    @Test
    fun unit_compound_feminine_14() {
        assertEquals("أربع عشرة سنة", UnitStep.apply("14 سنة"))
    }

    @Test
    fun unit_decimal_feminine() {
        assertEquals("واحد ونصف درجة مئوية", UnitStep.apply("1.5 °C"))
    }

    @Test
    fun unit_zero() {
        assertEquals("صفر متر", UnitStep.apply("0 م"))
    }

    @Test
    fun unit_hundred() {
        assertEquals("مائة كيلومتر", UnitStep.apply("100 km"))
    }

    @Test
    fun unit_speed() {
        assertEquals("ثمانون كيلومتر في الساعة", UnitStep.apply("80 كم/س"))
    }

    // ═══════════════════════ CurrencyStep ═══════════════════════

    @Test
    fun currency_dollar_1() {
        assertEquals("دولار واحد", CurrencyStep.apply("$1"))
    }

    @Test
    fun currency_dollar_2() {
        assertEquals("دولاران", CurrencyStep.apply("$2"))
    }

    @Test
    fun currency_dollar_3() {
        assertEquals("ثلاثة دولارات", CurrencyStep.apply("$3"))
    }

    @Test
    fun currency_dollar_100() {
        assertEquals("مائة دولار", CurrencyStep.apply("$100"))
    }

    @Test
    fun currency_dollar_200() {
        assertEquals("مائتان دولار", CurrencyStep.apply("$200"))
    }

    @Test
    fun currency_dollar_fraction() {
        assertEquals("دولار واحد وخمسون سنت", CurrencyStep.apply("$1.50"))
    }

    @Test
    fun currency_euro_2() {
        assertEquals("يوروان", CurrencyStep.apply("2€"))
    }

    @Test
    fun currency_euro_afterNumber() {
        assertEquals("خمسون يورو", CurrencyStep.apply("50€"))
    }

    @Test
    fun currency_riyalSaudi() {
        assertEquals("خمسمائة ريال سعودي", CurrencyStep.apply("ر.س 500"))
    }

    @Test
    fun currency_omani_dual() {
        assertEquals("ريالان عمانيان", CurrencyStep.apply("ر.ع 2"))
    }

    @Test
    fun currency_code_usd() {
        assertEquals("مائة دولار أمريكي", CurrencyStep.apply("USD 100"))
    }

    @Test
    fun currency_code_afterAmount() {
        // بند: «1500 USD» (المبلغ قبل الكود) كان يُقسم إلى مقطع عربي وآخر
        // إنجليزي فيفقد خطُّ العملة مبلغَه المكسور؛ يُستبدل الآن عربياً كاملاً.
        assertEquals(
            "ألف وخمسمائة دولار أمريكي",
            CurrencyStep.apply("1500 USD")
        )
        assertEquals(
            "خمسمائة ريال سعودي",
            CurrencyStep.apply("500 SAR")
        )
        assertEquals(
            "يوروان",
            CurrencyStep.apply("2 EUR")
        )
    }

    @Test
    fun currency_code_afterAmount_withinArabicText() {
        assertEquals(
            "سعر ألف وخمسمائة دولار أمريكي",
            CurrencyStep.apply("سعر 1500 USD")
        )
    }

    @Test
    fun currency_thousands_withFraction() {
        assertEquals(
            "ألف دولار وخمسة وسبعون سنت",
            CurrencyStep.apply("$1,000.75")
        )
    }

    @Test
    fun currency_zero() {
        assertEquals("صفر دولار", CurrencyStep.apply("$0"))
    }

    @Test
    fun currency_fractionDual_feminineTaaMarbuta() {
        // الوحدات الفرعية المؤنثة المنتهية بتاء مربوطة تُفتح تاؤها:
        // «هللة» → «هللتان» و«بيسة» → «بيستان» لا «هللةتان»/«بيسةتان».
        assertEquals("هللتان", CurrencyStep.apply("0.02 ر.س"))
        // الريال العماني = 1000 بيسة، فـ«0.002 ر.ع» = بيستان ← «بيستان».
        assertEquals("بيستان", CurrencyStep.apply("0.002 ر.ع"))
        assertEquals("عشرون بيسة", CurrencyStep.apply("0.02 ر.ع"))
        // غير المنتهية بتاء مربوطة تبقى على مثناها القديم («سنت» → «سنتان»).
        assertEquals("سنتان", CurrencyStep.apply("0.02$"))
    }

    @Test
    fun currency_threeDecimal_subunits() {
        // د.ك/د.ب/ر.ع/د.ت = 1000 وحدة فرعية (فلس/بيسة/مليم) لكل وحدة رئيسية:
        // كان الضرب الثابت في 100 ينطق «1.500 د.ك» «وخمسون فلس» بدل
        // «وخمسمائة فلس»، ويُفقد الجزء الكسري من «2.005 د.ت» كلياً.
        assertEquals(
            "دينار كويتي واحد وخمسمائة فلس",
            CurrencyStep.apply("1.500 د.ك")
        )
        assertEquals(
            "دينار بحريني واحد ومائتان وخمسون فلس",
            CurrencyStep.apply("1.250 د.ب")
        )
        assertEquals("سبعمائة وخمسون بيسة", CurrencyStep.apply("0.750 ر.ع"))
        assertEquals(
            "ديناران تونسيان وخمسة مليمات",
            CurrencyStep.apply("2.005 د.ت")
        )
        // العملات ثنائية الخانات لا تتأثر بالتغيير:
        assertEquals("دولار واحد وخمسون سنت", CurrencyStep.apply("1.50$"))
    }

    @Test
    fun currency_threeDecimal_code() {
        assertEquals(
            "دينار كويتي واحد وخمسمائة فلس",
            CurrencyStep.apply("KWD 1.500")
        )
        assertEquals("سبعمائة وخمسون بيسة", CurrencyStep.apply("OMR 0.750"))
    }

    @Test
    fun date_noMatchInsideAttachedText() {
        // حدود الكلمات تمنع التقاط التاريخ داخل متوالية لاصقة من أرقام.
        assertEquals("x12.05.2024y", DateStep().apply("x12.05.2024y"))
        // عنوان IP لا يُعامل كتاريخ (لا سنة من 4 خانات)، والتحكم سليم:
        assertEquals(
            "اثنا عشر مايو ألفان وأربعة وعشرون",
            DateStep().apply("12.05.2024")
        )
    }

    @Test
    fun number_ipLeftUnchanged() {
        // عناوين IP معرّفات شبكة لا تُقرأ عدّاً (كانت «192.168.1» تُشوّه
        // إلى «مائة واثنان وتسعون ألفاً …»).
        assertEquals("192.168.1.1", NumberStep.apply("192.168.1.1"))
        assertEquals("10.20.30.40", NumberStep.apply("10.20.30.40"))
    }

    // ═══════════════════════ RomanNumeralStep ═══════════════════════

    @Test
    fun roman_withIndicator() {
        assertEquals("الفصل ثلاثة", RomanNumeralStep.apply("الفصل III"))
    }

    @Test
    fun roman_standalone_12() {
        assertEquals("اثنا عشر", RomanNumeralStep.apply("XII"))
    }

    @Test
    fun roman_standalone_3() {
        assertEquals("ثلاثة", RomanNumeralStep.apply("III"))
    }

    @Test
    fun roman_standalone_8() {
        assertEquals("ثمانية", RomanNumeralStep.apply("VIII"))
    }

    @Test
    fun roman_withEnglishIndicator() {
        assertEquals("chapter اثنان", RomanNumeralStep.apply("chapter II"))
    }

    @Test
    fun roman_largeKeptAsIs() {
        assertEquals("MMXXIV", RomanNumeralStep.apply("MMXXIV"))
    }

    @Test
    fun roman_englishWords_keptAsIs() {
        assertEquals("DID", RomanNumeralStep.apply("DID"))
        assertEquals("MIX", RomanNumeralStep.apply("MIX"))
        assertEquals("I", RomanNumeralStep.apply("I"))
        assertEquals("CD", RomanNumeralStep.apply("CD"))
    }

    @Test
    fun roman_singleWithoutIndicator_keptAsIs() {
        // **بند 3.9:** الحرف الروماني المنفرد بلا مؤشر صريح لا يُحوَّل —
        // كانت «X» تتحول «عشرة» فيدمّر «منصة X» و«iPhone X»، و«V» يفسد
        // الاختصارات. المؤشر (الفصل/الجزء/chapter/…) وحده يسمح بالتحويل.
        assertEquals("V", RomanNumeralStep.apply("V"))
        assertEquals("X", RomanNumeralStep.apply("X"))
        // مع المؤشر يتحول ترتيباً:
        assertEquals("الجزء عشرة", RomanNumeralStep.apply("الجزء X"))
    }

    @Test
    fun roman_withoutIndicator_above12_kept() {
        assertEquals("XIV", RomanNumeralStep.apply("XIV"))
        assertEquals("MCMXCV", RomanNumeralStep.apply("MCMXCV"))
    }

    @Test
    fun roman_invalid_rejected() {
        // تكرار أكثر من 3 لـ I → غير صالح → يُترك كما هو
        assertEquals("IIII", RomanNumeralStep.apply("IIII"))
    }

    // ═══════════════════════ NumberWordsConverter ═══════════════════════

    @Test
    fun numberToWords_half() {
        assertEquals("واحد ونصف", NumberWordsConverter.numberToWords(1.5))
    }

    @Test
    fun numberToWords_quarter() {
        assertEquals("اثنان وربع", NumberWordsConverter.numberToWords(2.25))
    }

    @Test
    fun numberToWords_threeQuarters() {
        assertEquals("ثلاثة أرباع", NumberWordsConverter.numberToWords(0.75))
    }

    @Test
    fun numberToWords_halfZero() {
        assertEquals("نصف", NumberWordsConverter.numberToWords(0.5))
    }

    @Test
    fun numberToWords_quarterZero() {
        assertEquals("ربع", NumberWordsConverter.numberToWords(0.25))
    }

    @Test
    fun numberToWords_fractionWithLeadingZeros() {
        assertEquals(
            "ثلاثة فاصلة صفر خمسة",
            NumberWordsConverter.numberToWords(3.05)
        )
    }

    @Test
    fun numberToWords_fractionThreeDigits() {
        assertEquals(
            "ثلاثة فاصلة واحد أربعة واحد",
            NumberWordsConverter.numberToWords(3.141)
        )
    }

    @Test
    fun numberToWords_fractionFiveZeroFive() {
        assertEquals(
            "ثلاثة فاصلة خمسة صفر خمسة",
            NumberWordsConverter.numberToWords(3.505)
        )
    }

    @Test
    fun numberToWords_negative() {
        assertEquals("ناقص واحد ونصف", NumberWordsConverter.numberToWords(-1.5))
    }

    @Test
    fun numberToWords_zero_integer() {
        assertEquals("صفر", NumberWordsConverter.numberToWords(0))
    }

    @Test
    fun numberToWords_nan() {
        assertEquals(
            "ليس رقماً",
            NumberWordsConverter.numberToWords(Double.NaN)
        )
    }

    @Test
    fun numberToWords_positiveInfinity() {
        assertEquals(
            "ما لا نهاية",
            NumberWordsConverter.numberToWords(Double.POSITIVE_INFINITY)
        )
    }

    @Test
    fun numberToWords_negativeInfinity() {
        assertEquals(
            "ناقص ما لا نهاية",
            NumberWordsConverter.numberToWords(Double.NEGATIVE_INFINITY)
        )
    }

    @Test
    fun numberToWords_oneHundred() {
        assertEquals("مائة", NumberWordsConverter.numberToWords(100))
    }

    @Test
    fun numberToWords_twoHundred() {
        assertEquals("مائتان", NumberWordsConverter.numberToWords(200))
    }

    @Test
    fun numberToWords_oneThousand() {
        assertEquals("ألف", NumberWordsConverter.numberToWords(1000))
    }

    @Test
    fun numberToWords_oneMillion() {
        assertEquals("مليون", NumberWordsConverter.numberToWords(1000000))
    }

    @Test
    fun numberToWords_alafCompoundHundreds_genitive() {
        // آحاد 1–2 معطوفة على مائة تُجرّ التمييز: «مائة وواحد ألف» لا ألفاً.
        assertEquals(
            "مائة وواحد ألف",
            NumberWordsConverter.numberToWords(101000)
        )
        assertEquals(
            "مائة واثنان ألف",
            NumberWordsConverter.numberToWords(102000)
        )
    }

    @Test
    fun numberToWords_millionCompoundHundreds_genitive() {
        assertEquals(
            "مائة وواحد مليون",
            NumberWordsConverter.numberToWords(101000000)
        )
        assertEquals(
            "مائة واثنان مليون",
            NumberWordsConverter.numberToWords(102000000)
        )
        assertEquals(
            "مائة مليون",
            NumberWordsConverter.numberToWords(100000000)
        )
    }

    @Test
    fun numberToWords_billionCompoundHundreds_genitive() {
        // المقاييس الأعلى (مليار/تريليون…) تتبع نفس القاعدة.
        assertEquals(
            "مائة وواحد مليار",
            NumberWordsConverter.numberToWords(101000000000)
        )
    }

    @Test
    fun numberToWords_compoundTens_remainAccusative() {
        // ما فوق العشرات يبقى منصوباً: «مائة وأحد عشر ألفاً».
        assertEquals(
            "مائة وأحد عشر ألفاً",
            NumberWordsConverter.numberToWords(111000)
        )
        assertEquals(
            "مائة وخمسة وعشرون ألفاً",
            NumberWordsConverter.numberToWords(125000)
        )
    }

    // ═══════════════════════ AmountParser ═══════════════════════

    @Test
    fun amountParser_thousandsSeparator() {
        assertEquals(1234.0, AmountParser.parseAmount("1,234"), 0.001)
    }

    @Test
    fun amountParser_decimal() {
        assertEquals(3.14, AmountParser.parseAmount("3.14"), 0.001)
    }

    @Test
    fun amountParser_europeanFormat() {
        assertEquals(1234.56, AmountParser.parseAmount("1.234,56"), 0.001)
    }

    @Test
    fun amountParser_plainInteger() {
        assertEquals(42.0, AmountParser.parseAmount("42"), 0.001)
    }

    // ══════════ بوابات «لا تطابق» (لا ممرّات/تخصيص عند غياب الرمز) ══════════

    @Test
    fun currency_noCurrencySymbols_leftUnchanged() {
        // نص عربي بأرقام بلا أي رمز عملة: البوابة تعيده كما هو دون 37 ممراً.
        val input = "أرسلت 25 رسالة"
        assertEquals(input, CurrencyStep.apply(input))
    }

    @Test
    fun unit_noUnits_leftUnchanged() {
        // نص بأرقام بلا أي وحدة قياس: البوابة تعيده كما هو دون 38 ممراً.
        val input = "أرسلت 25 رسالة"
        assertEquals(input, UnitStep.apply(input))
    }

    @Test
    fun symbol_noSymbols_leftUnchanged() {
        // لا رموز عامة/حسابية ولا @ معزولة: النص يُعاد كما هو بلا ممرّات.
        assertEquals("مرحبا 7", SymbolStep.apply("مرحبا 7"))
    }

    @Test
    fun phone_arithmeticExpression_leftForNumberStep() {
        // الادعاء: «1000 - 2000» (8 خانات بفاصل مسافة وشرطة) كانت تُنطق هاتفاً
        // رقماً رقماً ويلغى المعنى الحسابي — تُترك لخطوتي الرموز والأرقام.
        assertEquals("1000 - 2000", PhoneNumberStep.apply("1000 - 2000"))
        assertEquals("5 * 7", PhoneNumberStep.apply("5 * 7"))
        assertEquals("10 / 2", PhoneNumberStep.apply("10 / 2"))
        // الهاتف المألوف بشرطة يبقى هاتفاً يُنطق رقماً رقماً ولا يُفسد
        assertEquals(
            "صفر واحد صفر واحد اثنان ثلاثة أربعة خمسة ستة سبعة ثمانية",
            PhoneNumberStep.apply("010-1234-5678")
        )
    }

    @Test
    fun phone_simpleCases_skippedByLinearScan() {
        // فصل الحالات البسيطة (بند 2-1): النص بلا أرقام أو بأرقام
        // قليلة يُعاد كما هو بالمسح الخطي قبل الـ regex نهائياً.
        assertEquals("مرحبا بك", PhoneNumberStep.apply("مرحبا بك"))
        assertEquals("الرقم 123", PhoneNumberStep.apply("الرقم 123"))
        assertEquals(
            "رقمان: 42 و 7", PhoneNumberStep.apply("رقمان: 42 و 7")
        )
        // 7 أرقام مجردة بلا بادئة لا دليلَ على كونها هاتفاً — تُترك كما هي،
        // والمسح الخطي لا يُغيّر هذا السلوك (المسار الكامل يعالجها).
        assertEquals("1234567", PhoneNumberStep.apply("1234567"))
        // مع مفتاح دولي (+) تمر النتائج من الـ regex وتُستبدل رقماً رقماً.
        assertEquals(
            "اثنان صفر اثنان ثلاثة أربعة خمسة ستة سبعة",
            PhoneNumberStep.apply("+20234567")
        )
    }

    @Test
    fun url_uppercaseSchemeAndWww_stillReadableDomain() {
        // الادعاء: الروابط بحروف كبيرة كانت تُشوَّه («HTTPS://GOOGLE.COM» ←
        // «موقع HTTPS») لأن إزالة الشعار حساسة لحالة الأحرف.
        assertEquals("موقع GOOGLE", UrlStep.apply("HTTPS://GOOGLE.COM"))
        assertEquals("موقع GOOGLE", UrlStep.apply("WWW.GOOGLE.COM/x"))
        assertEquals("موقع google", UrlStep.apply("https://www.google.com"))
        assertEquals("موقع GOOGLE", UrlStep.apply("https://WWW.GOOGLE.ORG"))
    }

    @Test
    fun symbol_percentAndComparisons_notGlued() {
        // الادعاء: استبدال «%» بلا مسافات يلصق الكلمات («خمسونبالمئة») — والجذر
        // نفسه يصيب بقية الرموز («5>3» ← «5أكبر من3»). المسافات الطرفية
        // تُنظَّف في CleanupStep في المسار الكامل. النطق بالمئة من مسؤولية
        // PunctuationStep (مستوى «البعض» الافتراضي) لا SymbolStep.
        val some = PunctuationStep { PunctuationLevels.SOME }
        val percent = CleanupStep.apply(some.apply("خمسون%"))
        assertEquals("خمسون بالمئة", percent)
        assertEquals("25 بالمئة", CleanupStep.apply(some.apply("25%")))
        assertEquals("25%", SymbolStep.apply("25%"))
        assertEquals("5 أكبر من 3", SymbolStep.apply("5>3"))
        assertEquals("37 درجة", CleanupStep.apply(SymbolStep.apply("37°")))
    }

    @Test
    fun decimalFractions_naturalWords_includingPercent() {
        // الكسور الشائعة تُنطق طبيعياً («نصف/ربع/ثلاثة أرباع») حتى داخل
        // النسبة المئوية، والكسور الأخرى تُقرأ «فاصلة» رقماً رقماً
        // (البنود 14/31 — نطق طبيعي لا عدّي).
        val some = PunctuationStep { PunctuationLevels.SOME }
        fun full(text: String) = CleanupStep.apply(
            NumberStep.apply(some.apply(text))
        )
        assertEquals("خمسون ونصف بالمئة", full("50.5%"))
        assertEquals("ربع بالمئة", full("0.25%"))
        assertEquals("واحد وثلاثة أرباع بالمئة", full("1.75%"))
        assertEquals("ثلاثة فاصلة خمسة واحد بالمئة", full("3.51%"))
    }

    @Test
    fun emojiSpeech_keycapAndTagFlags_notSpokenAsGarbage() {
        // 1️⃣: مُقسِّم النطق يُسقط FE0F ومنفّذ keycap (U+20E3) فيبقى مقطع
        // نصّي واحد «1» بلا محارف عائمة (بند 2 اختياري).
        val keycap = EmojiSpeech.split("رمزه 1\uFE0F\u20E3", true)
        assertEquals(listOf(SpeechPart("رمزه 1", false)), keycap)
        // العَلَم ذو الوسوم 🏴󠁧󠁢󠁳󠁣󠁴󠁿: العَلَم يُنطق اسمه (نطق عام للعلم)
        // ويُسقط الوسوم E0020–E007F صامتاً.
        val tagFlag = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73" +
            "\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F"
        val parts = EmojiSpeech.split(tagFlag, true)
        assertEquals(1, parts.size)
        assertTrue(parts.single().isEmojiName)
    }

    @Test
    fun emojiStrip_fe0f_droppedSilently_notSpace() {
        // ❤️ = U+2764 + U+FE0F: عند تعطيل نطق الإيموجي يُستبدل القلب بمسافة
        // ويُحذف محرف التباين FE0F صامتاً (بلا مسافة منه) فلا تتباعد الحروف.
        val step = EmojiStripStep { false }
        val input = "أنا\u2764\uFE0Fأحبك"
        assertEquals("أنا أحبك", step.apply(input))
        assertTrue(!step.apply(input).contains("\uFE0F"))
    }

    @Test
    fun emojiStrip_keycapAndTagFlags_droppedSilently() {
        // 1️⃣ = U+0031 + U+FE0F + U+20E3: يحذف FE0F وkeycap بصمت فيبقى
        // الرقم نصاً عادياً بلا محارف عائمة (بند 2 اختياري).
        val step = EmojiStripStep { false }
        assertEquals(
            "رمز 1 متاح",
            step.apply("رمز 1\uFE0F\u20E3 متاح")
        )
        // العَلَم ذو الوسوم 🏴󠁧󠁢󠁳󠁣󠁴󠁿: وسوم U+E0020–E007F تُسقط بصمت
        // (لم تكن تُجرَّد سابقاً فتصل المحرك مشوّشةً).
        val tagFlag = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC73" +
            "\uDB40\uDC63\uDB40\uDC74\uDB40\uDC7F"
        assertEquals("علم", step.apply("علم $tagFlag"))
        assertTrue(!step.apply("علم $tagFlag").contains("\uDB40\uDC67"))
    }

    @Test
    fun cleanup_dropsBidiControlMarks_beforeSpeechEngine() {
        // علامات التحكم الاتجاهي قد تصل مجتزأةً من إشعارات/نصوص خارجية
        // (LRM RLM LRE RLE LRI…) — تُجرَّد في CleanupStep فلا يصِل المحرك
        // محارف صامتة تُعطّل اتجاه النطق (بند 8).
        val lrm = '\u200E'
        val rle = '\u202B'
        val lri = '\u2066'
        assertEquals(
            "رسالة نصية سليمة",
            CleanupStep.apply("رسالة${lrm}نصية$rle سليمة$lri")
        )
    }
}