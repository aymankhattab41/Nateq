package com.aymankhattab.nateq

import com.aymankhattab.nateq.core.engine.PunctuationLevels
import com.aymankhattab.nateq.engine.ARABIC_TASHKEEL_AWARE_ENGINES
import com.aymankhattab.nateq.engine.EmojiSpeech
import com.aymankhattab.nateq.engine.SpeechPart
import com.aymankhattab.nateq.engine.stripTashkeelFor
import com.aymankhattab.nateq.engine.pipeline.AcronymStep
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
import com.aymankhattab.nateq.engine.pipeline.TashkeelStripStep
import com.aymankhattab.nateq.engine.pipeline.TimeStep
import com.aymankhattab.nateq.engine.pipeline.UnitStep
import com.aymankhattab.nateq.engine.pipeline.UrlStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun unit_data_storage_case_insensitive_mb() {
        assertEquals("حمّل خمسون ميجابايت", UnitStep.apply("حمّل 50mb"))
        assertEquals("حمّل خمسون ميجابايت", UnitStep.apply("حمّل 50Mb"))
        assertEquals("حمّل خمسون ميجابايت", UnitStep.apply("حمّل 50MB"))
        assertEquals("حمّل خمسون ميجابايت", UnitStep.apply("حمّل 50mB"))
    }

    @Test
    fun unit_data_storage_arabic_megabyte_abbreviations() {
        // اختصارات ميجابايت العربية لا تُنطق «متر ب» بل ميجابايت
        assertEquals("خمسون ميجابايت", UnitStep.apply("50 م ب"))
        assertEquals("خمسون ميجابايت", UnitStep.apply("50 م.ب"))
        assertEquals("خمسون ميجابايت", UnitStep.apply("50 م.ب."))
        assertEquals("خمسون ميجابايت", UnitStep.apply("50 م. ب"))
        assertEquals("خمسون ميجابايت", UnitStep.apply("50 مب"))
        assertEquals("خمسون ميجابايت", UnitStep.apply("50مب"))
        assertEquals("خمسون ميجابايت", UnitStep.apply("50م ب"))
        assertEquals("خمسون ميجابايت", UnitStep.apply("50م.ب"))
        assertEquals("خمسون ميجابايت", UnitStep.apply("50 م بايت"))
        assertEquals("خمسون ميجابايت", UnitStep.apply("50 ميجابايت"))
        assertEquals("خمسون ميجابايت", UnitStep.apply("50 ميجا بايت"))
        assertEquals("ميجابايت واحد", UnitStep.apply("1 م.ب"))
        assertEquals("ميجابايتان", UnitStep.apply("2 م.ب"))
        assertEquals("خمسة ميجابايتات", UnitStep.apply("5 م.ب"))
    }

    @Test
    fun unit_data_storage_arabic_other_units_and_speed() {
        assertEquals("عشرة كيلوبايتات", UnitStep.apply("10 ك.ب"))
        assertEquals("عشرة كيلوبايتات", UnitStep.apply("10 ك ب"))
        assertEquals("خمسة جيجابايتات", UnitStep.apply("5 ج.ب"))
        assertEquals("تيرابايت واحد", UnitStep.apply("1 ت.ب"))
        assertEquals("خمسمائة بايت", UnitStep.apply("500 بايت"))
        assertEquals("خمسون متر في الثانية", UnitStep.apply("50 م/ث"))
        assertEquals("خمسون ميجابايت في الثانية", UnitStep.apply("50 م.ب/ث"))
        assertEquals("خمسون ميجابايت في الثانية", UnitStep.apply("50 MB/s"))
    }

    @Test
    fun unit_data_storage_case_insensitive_other_bytes() {
        assertEquals("عشرة كيلوبايتات", UnitStep.apply("10kb"))
        assertEquals("عشرة كيلوبايتات", UnitStep.apply("10Kb"))
        assertEquals("عشرة كيلوبايتات", UnitStep.apply("10KB"))
        assertEquals("خمسة جيجابايتات", UnitStep.apply("5gb"))
        assertEquals("خمسة جيجابايتات", UnitStep.apply("5Gb"))
        assertEquals("خمسة جيجابايتات", UnitStep.apply("5GB"))
        assertEquals("تيرابايت واحد", UnitStep.apply("1tb"))
        assertEquals("تيرابايت واحد", UnitStep.apply("1Tb"))
        assertEquals("تيرابايت واحد", UnitStep.apply("1TB"))
    }

    @Test
    fun unit_non_data_units_remain_case_sensitive() {
        // كلفن K كبير فقط لا k صغير
        assertEquals("خمسة كلفنات", UnitStep.apply("5 K"))
        assertEquals("5 k", UnitStep.apply("5 k"))
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
        // بند 3.2: 50 (11–99) منصوبٌ «سنتاً»
        assertEquals("دولار واحد وخمسون سنتاً", CurrencyStep.apply("$1.50"))
    }

    @Test
    fun currency_euro_2() {
        assertEquals("يوروان", CurrencyStep.apply("2€"))
    }

    @Test
    fun currency_euro_afterNumber() {
        // بند 3.2: 50 (11–99) منصوبٌ «يورواً»
        assertEquals("خمسون يورواً", CurrencyStep.apply("50€"))
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
            // بند 3.2: 75 (11–99) منصوب بواو الكسر الختامي
            "ألف دولار وخمسة وسبعون سنتاً",
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
        // بند 3.2: 20 (11–99) منصوبٌ «بيسةً»
        assertEquals("عشرون بيسةً", CurrencyStep.apply("0.02 ر.ع"))
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
        // بند 3.2: 250 (rem100=50 → 11–99) منصوبٌ «فلساً»
        assertEquals(
            "دينار بحريني واحد ومائتان وخمسون فلساً",
            CurrencyStep.apply("1.250 د.ب")
        )
        assertEquals("سبعمائة وخمسون بيسةً", CurrencyStep.apply("0.750 ر.ع"))
        assertEquals(
            "ديناران تونسيان وخمسة مليمات",
            CurrencyStep.apply("2.005 د.ت")
        )
        // بند 3.2: 50 (11–99) منصوب «سنتاً»
        assertEquals("دولار واحد وخمسون سنتاً", CurrencyStep.apply("1.50$"))
    }

    @Test
    fun currency_threeDecimal_code() {
        assertEquals(
            "دينار كويتي واحد وخمسمائة فلس",
            CurrencyStep.apply("KWD 1.500")
        )
        // بند 3.2: 750 (rem100=50 → 11–99) منصوبٌ «بيسةً»
        assertEquals("سبعمائة وخمسون بيسةً", CurrencyStep.apply("OMR 0.750"))
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

    // ═══════════════════════ TimeStep ═══════════════════════

    @Test
    fun time_afternoon_evening() {
        assertEquals("الثانية والنصف مساءاً", TimeStep.apply("14:30"))
    }

    @Test
    fun time_quarterTo_noon_properly() {
        // بند 3.3: 11:45 «إلا ربع ظهراً» — كانت تُحسب من الساعة الخام
        // 11 فتُنطق «صباحاً».
        assertEquals(
            "الثانية عشرة إلا ربع ظهراً", TimeStep.apply("11:45")
        )
    }

    @Test
    fun time_quarterTo_lateNight_evening() {
        assertEquals(
            "الثانية عشرة إلا ربع مساءاً", TimeStep.apply("23:45")
        )
    }

    @Test
    fun time_afterMidnight() {
        // بند 3.3: 00:xx «بعد منتصف الليل» لا «صباحاً».
        assertEquals(
            "الثانية عشرة والنصف بعد منتصف الليل", TimeStep.apply("00:30")
        )
    }

    @Test
    fun time_noon_quarterAfter() {
        assertEquals(
            "الواحدة إلا ربع مساءاً", TimeStep.apply("12:45")
        )
    }

    @Test
    fun time_morning_minutes() {
        assertEquals("العاشرة و خمس دقائق صباحاً", TimeStep.apply("10:05"))
    }

    @Test
    fun time_fullMatrix_amPmAndArabicSuffixes() {
        // مصفوفة كاملة: التحقق من فصل القيمة الرقمية عن كلمة الفترة
        // واشتقاق الفترة حصراً من اللاحقة (AM/PM وصباحاً/مساءاً).
        // حالات الحد (12 AM منتصف الليل = صباحاً، 12 PM = مساءاً)
        // وحالات 1 و11.
        assertEquals("الثانية عشرة صباحاً", TimeStep.apply("12:00 AM"))
        assertEquals("الثانية عشرة مساءاً", TimeStep.apply("12:00 PM"))
        assertEquals("الواحدة صباحاً", TimeStep.apply("1:00 AM"))
        assertEquals("الواحدة مساءاً", TimeStep.apply("1:00 PM"))
        assertEquals("الحادية عشرة صباحاً", TimeStep.apply("11:00 AM"))
        assertEquals("الحادية عشرة مساءاً", TimeStep.apply("11:00 PM"))

        // نفس المصفوفة الستّ بلاحقات عربية
        assertEquals("الثانية عشرة صباحاً", TimeStep.apply("12:00 صباحاً"))
        assertEquals("الثانية عشرة مساءاً", TimeStep.apply("12:00 مساءً"))
        assertEquals("الواحدة صباحاً", TimeStep.apply("1:00 صباحاً"))
        assertEquals("الواحدة مساءاً", TimeStep.apply("1:00 مساءً"))
        assertEquals("الحادية عشرة صباحاً", TimeStep.apply("11:00 صباحاً"))
        assertEquals("الحادية عشرة مساءاً", TimeStep.apply("11:00 مساءً"))
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
    fun numberToWords_101_102_thousandOwnUnit() {
        // **بند 3.4:** آحاد 1–2 معطوفة على مائة مع المقياس تُنطق مفرداً/
        // مثنّى بعد المقياس: «مائة ألف وألف» لا «مائة وواحد ألف».
        assertEquals(
            "مائة ألف وألف",
            NumberWordsConverter.numberToWords(101000)
        )
        assertEquals(
            "مائة ألف وألفان",
            NumberWordsConverter.numberToWords(102000)
        )
    }

    @Test
    fun numberToWords_201_302_thousandOwnUnit() {
        // «مائتا ألف وألف» بحذف نون المثنى مع المضاف، و«ثلاثمائة ألف
        // وألفان» كما هي بلا تغيير.
        assertEquals(
            "مائتا ألف وألف",
            NumberWordsConverter.numberToWords(201000)
        )
        assertEquals(
            "ثلاثمائة ألف وألفان",
            NumberWordsConverter.numberToWords(302000)
        )
    }

    @Test
    fun numberToWords_millionCompoundHundreds_ownUnit() {
        assertEquals(
            "مائة مليون ومليون",
            NumberWordsConverter.numberToWords(101000000)
        )
        assertEquals(
            "مائة مليون ومليونان",
            NumberWordsConverter.numberToWords(102000000)
        )
        assertEquals(
            "مائة مليون",
            NumberWordsConverter.numberToWords(100000000)
        )
    }

    @Test
    fun numberToWords_billionCompoundHundreds_ownUnit() {
        // المقاييس الأعلى (مليار/تريليون…) تتبع نفس القاعدة.
        assertEquals(
            "مائة مليار ومليار",
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

    @Test
    fun amountParser_leadingZeroFraction() {
        // **بند 3.2:** «0,125» فاصلةُ كسورٍ لا آلاف — قاعدة الفاصلة المنفردة
        // ذات الطرف الثلاثي كانت تظنّها آلافاً فتقرأ «مائة وخمسة وعشرون».
        assertEquals(0.125, AmountParser.parseAmount("0,125"), 0.001)
        assertEquals(0.250, AmountParser.parseAmount("0,250"), 0.001)
    }

    @Test
    fun amountParser_thousands_and_decimal_kept() {
        // الاستثناء الجديد لا يمسّ الحالتين الشرعيتين:
        assertEquals(1234.0, AmountParser.parseAmount("1,234"), 0.001)
        assertEquals(3.141, AmountParser.parseAmount("3.141"), 0.001)
    }

    @Test
    fun amountParser_negative() {
        // الإشارة السالبة تمر عبر التنظيف إلى المحلل (بند 3.1).
        assertEquals(-2.0, AmountParser.parseAmount("-2"), 0.001)
        assertEquals(-2.5, AmountParser.parseAmount("-2.5"), 0.001)
        assertEquals(-1234.0, AmountParser.parseAmount("-1,234"), 0.001)
    }

    // ═════════==== بند 3.1: المبالغ السالبة ═════════

    @Test
    fun currency_negative_dollar_after() {
        // كانت «-2$» تُترك «-دولاران» — الحسم النحوي يعجز عن السالب فيسقط
        // لفرع «الأخرى» بصيغة المفرد الخاطئة.
        assertEquals("ناقص دولاران", CurrencyStep.apply("-2$"))
        assertEquals("ناقص دولار واحد", CurrencyStep.apply("-1$"))
    }

    @Test
    fun currency_negative_dollar_before() {
        assertEquals("ناقص دولاران", CurrencyStep.apply("$-2"))
    }

    @Test
    fun currency_negative_fraction_only() {
        // بند 3.6 + 3.2: الكسر النقي السالب يحافظ على
        // «سالب »، و50 (11–99) منصوب «سنتاً»
        assertEquals("سالب خمسون سنتاً", CurrencyStep.apply("-0.50$"))
    }

    @Test
    fun currency_negative_with_fraction() {
        assertEquals(
            // بند 3.2: 50 (11–99) منصوب «سنتاً»
            "ناقص دولاران وخمسون سنتاً",
            CurrencyStep.apply("-2.50$")
        )
    }

    @Test
    fun currency_negative_dual_arabicSymbols() {
        assertEquals(
            "ناقص ديناران كويتيان",
            CurrencyStep.apply("-2 د.ك")
        )
        assertEquals(
            "ناقص ريالان سعوديان",
            CurrencyStep.apply("-2 ر.س")
        )
    }

    @Test
    fun currency_tens_withOne_masculine() {
        // بند 3.1: «العشرون» بآحاد «واحد» لا «أحد» («واحد وعشرون»).
        assertEquals(
            "واحد وعشرون",
            NumberWordsConverter.numberToWords(21.0)
        )
        // بند 3.2: المركّب 11–99 يلزم المعدود بالتنوين المنصوب.
        assertEquals(
            "واحد وعشرون دولاراً",
            CurrencyStep.apply("$21")
        )
    }

    @Test
    fun currency_carry_fraction_overflowsUnit() {
        // بند 3.2: الكسر المتراكم (> الوحدة) يُرحَّل إلى الوحدة الكبرى.
        assertEquals("دولاران", CurrencyStep.apply("$1.999"))
        assertEquals("ناقص دولاران", CurrencyStep.apply("$-1.999"))
        assertEquals("مائتان دولار", CurrencyStep.apply("$199.999"))
    }

    @Test
    fun currency_fraction_accusative_11to99() {
        // بند 3.2: 11–99 منصوبة («سنتاً»)، وآحاد العشرين «واحد»:
        assertEquals("تسعة وثلاثون سنتاً", CurrencyStep.apply("$0.39"))
        assertEquals(
            "واحد وعشرون سنتاً",
            CurrencyStep.apply("$0.21")
        )
    }

    @Test
    fun unit_largeNumber_feminineCompatible() {
        // بند 3.3: رفع الحد 9999→99,999,999 عبر NumberSpeech بصيغة المعدود
        // المؤنث («خمس» لا «خمسة») مع بقاء الوحدة مفردة.
        assertEquals(
            "مليونان وخمسمائة ألف وخمس ساعة",
            UnitStep.apply("2500005 س")
        )
    }

    @Test
    fun tashkeel_preservesQuranicMarks() {
        // بند 3.4: حروف المصحف المعجمة فوقُ الألف (U+0670) والواو/الياء
        // المصحفيّتان (U+06E5/U+06E6) تُبقى بينما تُجرَّد الحركات العادية.
        assertEquals(
            "\u0643\u0670\u062A\u0627\u0628",
            TashkeelStripStep.apply("\u0643\u0670\u062A\u064E\u0627\u0628")
        )
        assertEquals(
            "\u0628\u06E5\u06E6",
            TashkeelStripStep.apply("\u0628\u064E\u06E5\u06E6\u064E")
        )
    }

    @Test
    fun url_trailingPunctuation_preserved() {
        // بند 3.5: النقطة/القوس الختامي الملاصقان للرابط يُنطقان مع البديل
        // («موقع example.») بدل اقتصاصهما من النُّطق.
        assertEquals("موقع example.", UrlStep.apply("https://example.com."))
        assertEquals("موقع example)", UrlStep.apply("https://example.com)"))
        assertEquals(
            "موقع example).",
            UrlStep.apply("www.example.org).")
        )
    }

    // ═════════==== بند 3.3: « الساعة 5 م » ═════════

    @Test
    fun unit_announcedTime_withoutColon_stays() {
        // «الساعة 5 م» — النمط اللطيف الذي كان يبتلعه «م» (نمط الاستثناء لم
        // يُدرج «الساعة») فينطق «خمسة أمتار» بدل أن يبقى للزمن.
        assertEquals("الساعة 5 م", UnitStep.apply("الساعة 5 م"))
    }

    @Test
    fun unit_minute_afterHourWord_stillConverts() {
        // الاستثناء الجديد لا يمنع «م» الصحيحة بعد رقم: «5 م» = خمسة أمتار.
        assertEquals("خمسة أمتار", UnitStep.apply("5 م"))
    }

    // ═════════==== بند 3.5: الأرقام الرومانية الصغيرة ═════════

    @Test
    fun roman_lowercase_withIndicator() {
        // «الفصل iii» = ثلاثة (الحروف الصغيرة لم تكن في فئة النمط).
        assertEquals("الفصل ثلاثة", RomanNumeralStep.apply("الفصل iii"))
        assertEquals("الجزء عشرة", RomanNumeralStep.apply("الجزء x"))
    }

    @Test
    fun roman_lowercase_withEnglishIndicator() {
        assertEquals("chapter اثنان", RomanNumeralStep.apply("chapter ii"))
    }

    @Test
    fun roman_lowercase_standalone_12() {
        assertEquals("اثنا عشر", RomanNumeralStep.apply("xii"))
    }

    @Test
    fun roman_lowercase_englishWords_keptAsIs() {
        // «mix» و«did» كلمتان إنجليزيتان لا رقمان رومانيان — المقارنة مع
        // القائمة الكلمات تُثبَّت على الصيغة الكبيرة (بند 3.5).
        assertEquals("mix", RomanNumeralStep.apply("mix"))
        assertEquals("did", RomanNumeralStep.apply("did"))
    }

    @Test
    fun roman_lowercase_singleWithoutIndicator_keptAsIs() {
        // قاعدة بند 3.9 تمتد للصغيرة: «x» و«v» منفردتان تُتركان.
        assertEquals("x", RomanNumeralStep.apply("x"))
        assertEquals("v", RomanNumeralStep.apply("v"))
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
    fun unit_callDuration_vowelledPreposition_notTreatedAsMeters() {
        // «8مِنَ الدقائق و17مِنَ الثواني»: وجود التشكيل على «مِنَ» كان
        // يجعل «م» تطابق وحدة الأمتار (غياب \p{M} من حارس الحدود) فتُنطق
        // «8 أمتار من الدقائق»؛ تُحمى لتبقى للأرقام.
        val inputAttached = "مدة المكالمة: 8مِنَ الدقائق و17مِنَ الثواني"
        assertEquals(inputAttached, UnitStep.apply(inputAttached))

        val inputSpaced = "مدة المكالمة: 8 مِنَ الدقائق و17 مِنَ الثواني"
        assertEquals(inputSpaced, UnitStep.apply(inputSpaced))

        val outNumber = NumberStep.apply(inputAttached)
        assertEquals(
            "مدة المكالمة: ثمانية مِنَ الدقائق وسبعة عشر مِنَ الثواني",
            outNumber
        )
    }

    @Test
    fun number_attachedArabicWord_separatedWithSpace() {
        // الرقم الملتصق بكلمة عربية بعدها ينفصل بمسافة تلقائياً عند تحويله
        // إلى كلمات حتى لا تتلاصق كلمة العدد مع الاسم المعدود.
        assertEquals("ثمانية دقائق", NumberStep.apply("8دقائق"))
        assertEquals("سبعة عشر ثانية", NumberStep.apply("17ثانية"))
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
        // مع مفتاح دولي (+) تمر النتائج وتُنطق رقماً رقماً مع كلمة «زائد».
        assertEquals(
            "زائد اثنان صفر اثنان ثلاثة أربعة خمسة ستة سبعة",
            PhoneNumberStep.apply("+20234567")
        )
        assertEquals(
            "زائد اثنان صفر واحد واحد خمسة خمسة خمسة اثنان أربعة أربعة اثنان",
            PhoneNumberStep.apply("+20 11 5552442")
        )
    }

    @Test
    fun phone_decimalAmounts_notTreatedAsPhoneNumbers() {
        // مبالغ أو أرقام عشرية (مثل 30496.00) لا تُعامل كهواتف
        assertEquals(
            "المبلغ 30496.00",
            PhoneNumberStep.apply("المبلغ 30496.00")
        )
        assertEquals(
            "المبلغ 30,496.00",
            PhoneNumberStep.apply("المبلغ 30,496.00")
        )
        assertEquals(
            "المبلغ ثلاثون ألفاً وأربعمائة وستة وتسعون",
            NumberStep.apply(PhoneNumberStep.apply("المبلغ 30496.00"))
        )
    }

    @Test
    fun phone_numbersStartingWithZero_alwaysSingleDigits() {
        // أي رقم يبدأ بـ 0 وله 7-15 خانة يُعامل كهاتف ويُنطق مفردة دائماً
        assertEquals(
            "صفر اثنان اثنان ثلاثة أربعة خمسة ستة سبعة ثمانية تسعة",
            PhoneNumberStep.apply("0223456789")
        )
        assertEquals(
            "صفر اثنان اثنان ثلاثة أربعة خمسة ستة سبعة ثمانية تسعة",
            NumberStep.apply("0223456789")
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

    // ═══════════════════════ AcronymStep ═══════════════════════
    // بند ب.txt 2.6-2: الاختصارات التقنية تُنطق عربياً قبل تقسيم اللغة.

    @Test
    fun acronym_sms_in_arabic() {
        assertEquals("أرسلت إس إم إس", AcronymStep.apply("أرسلت SMS"))
    }

    @Test
    fun acronym_pdf_in_arabic() {
        assertEquals("افتح بي دي إف", AcronymStep.apply("افتح PDF"))
    }

    @Test
    fun acronym_ok_in_arabic() {
        assertEquals("موافق أو كي", AcronymStep.apply("موافق OK"))
    }

    @Test
    fun acronym_wifi_kept_as_latin() {
        // يبقى ككتابة لاتينية ليُوجّه لمحرك الصوت الأجنبي
        assertEquals(
            "اتصل Wi-Fi بجهاز",
            AcronymStep.apply("اتصل Wi-Fi بجهاز")
        )
        assertEquals("الشبكة WiFi", AcronymStep.apply("الشبكة WiFi"))
    }

    @Test
    fun acronym_usb_in_arabic() {
        assertEquals("منفذ يو إس بي", AcronymStep.apply("منفذ USB"))
    }

    @Test
    fun acronym_gps_in_arabic() {
        assertEquals("إحداثيات جي بي إس", AcronymStep.apply("إحداثيات GPS"))
    }

    @Test
    fun acronym_lowercase_untouched() {
        // الصيغة الصغيرة تُترك (قد تكون كلمة مقصودة لا اختصاراً).
        assertEquals("هذا pdf سحابة", AcronymStep.apply("هذا pdf سحابة"))
    }

    @Test
    fun acronym_embedded_plural_untouched() {
        // الملصقات المركّبة تُترك (PDFs جمع يعبّر محركُه عنها بالإنجليزية).
        assertEquals("الملفات PDFs", AcronymStep.apply("الملفات PDFs"))
    }

    @Test
    fun acronym_arabic_data_units_without_number() {
        assertEquals("الحجم ميجابايت", AcronymStep.apply("الحجم م.ب"))
        assertEquals("الحجم ميجابايت", AcronymStep.apply("الحجم م.ب."))
        assertEquals("الملف ميجابايت", AcronymStep.apply("الملف MB"))
        assertEquals("الباقة كيلوبايت", AcronymStep.apply("الباقة ك.ب"))
    }

    // ═══════════════ المسار الإنجليزي (اللغة الثانية) ═══════════════

    @Test
    fun englishNumber_integerAndDecimal() {
        assertEquals(
            "one thousand two hundred thirty four",
            NumberStep.applyEnglish("1234")
        )
        assertEquals(
            "three point one four one",
            NumberStep.applyEnglish("3.141")
        )
        assertEquals("zero point zero five", NumberStep.applyEnglish("0.05"))
    }

    @Test
    fun englishNumber_negative_spokenMinus() {
        assertEquals("minus five", NumberStep.applyEnglish("-5"))
    }

    @Test
    fun numberStep_respectsConfiguredLanguageRegardlessOfPipeline() {
        val stepEn = NumberStep { "en" }
        // جملة عربية بالكامل مع الإعداد على en:
        // الرقم يُنطق إنجليزياً
        assertEquals("عندي five كتب", stepEn.apply("عندي 5 كتب"))

        val stepAr = NumberStep { "ar" }
        // جملة إنجليزية بالكامل مع الإعداد على ar:
        // الرقم يُنطق عربياً
        val arOut = stepAr.applyEnglish("I have 5 books")
        assertEquals("I have خمسة books", arOut)
    }

    @Test
    fun englishPunctuation_someLevel_namesSymbols() {
        val some = PunctuationStep { PunctuationLevels.SOME }
        fun full(text: String) = CleanupStep.apply(some.applyEnglish(text))
        assertEquals("50 percent done", full("50% done"))
        assertEquals("meet at 5", full("meet @ 5"))
        assertEquals("a and b", full("a & b"))
        assertEquals("and slash or", full("and / or"))
        assertEquals("2 plus 2", full("2 + 2"))
    }

    @Test
    fun englishPunctuation_allLevel_addsParensAndDashes() {
        val all = PunctuationStep { PunctuationLevels.ALL }
        fun full(text: String) = CleanupStep.apply(all.applyEnglish(text))
        assertEquals(
            "note open parenthesis x close parenthesis",
            full("note (x)")
        )
        assertEquals("a semicolon b", full("a ; b"))
        assertEquals("a dash b", full("a - b"))
        assertEquals("a ellipsis b", full("a … b"))
    }

    @Test
    fun englishSymbol_generalAndArithmetic() {
        assertEquals(
            "5 plus 3",
            CleanupStep.apply(SymbolStep.applyEnglish("5+3"))
        )
        assertEquals(
            "5 greater than 3",
            CleanupStep.apply(SymbolStep.applyEnglish("5>3"))
        )
        assertEquals(
            "greater than or equal to",
            CleanupStep.apply(SymbolStep.applyEnglish("≥"))
        )
        assertEquals(
            "10 divided by 2",
            CleanupStep.apply(SymbolStep.applyEnglish("10/2"))
        )
        assertEquals(
            "backslash",
            CleanupStep.apply(SymbolStep.applyEnglish("\\"))
        )
    }

    @Test
    fun englishPhone_spokenDigitByDigit() {
        assertEquals(
            "zero one zero one two three four five six seven eight",
            PhoneNumberStep.applyEnglish("010-1234-5678")
        )
        assertEquals(
            "plus two zero two three four five six seven",
            PhoneNumberStep.applyEnglish("+20234567")
        )
        assertEquals(
            "plus two zero one one five five five two four four two",
            PhoneNumberStep.applyEnglish("+20 11 5552442")
        )
    }

    @Test
    fun englishDate_monthOrdinalYear() {
        assertEquals(
            "March fifteenth two thousand twenty four",
            DateStep().applyEnglish("2024-03-15")
        )
        assertEquals(
            "March twenty first two thousand twenty four",
            DateStep().applyEnglish("21/03/2024")
        )
    }

    @Test
    fun englishCurrency_symbolAndCode() {
        assertEquals("one dollar", CurrencyStep.applyEnglish("$1"))
        assertEquals("two dollars", CurrencyStep.applyEnglish("$2"))
        assertEquals("three dollars", CurrencyStep.applyEnglish("$3"))
        assertEquals("one hundred dollars", CurrencyStep.applyEnglish("$100"))
        assertEquals(
            "one dollar and fifty cents",
            CurrencyStep.applyEnglish("$1.50")
        )
        assertEquals("fifty cents", CurrencyStep.applyEnglish("$0.50"))
        assertEquals(
            "one thousand five hundred US dollars",
            CurrencyStep.applyEnglish("1500 USD")
        )
    }

    @Test
    fun englishCurrency_threeDecimalSubunits() {
        // الدينار الكويتي = 1000 فلس: «1.500» ← دينار واحد وخمسمائة فلس.
        assertEquals(
            "one Kuwaiti dinar and five hundred fils",
            CurrencyStep.applyEnglish("KWD 1.500")
        )
    }

    @Test
    fun englishCurrency_symbolsWithoutArabicOutput() {
        // لا يسقط رمزٌ من جدول الإنجليزية فيبقى رمزاً خاماً أو مخرَجاً
        // عربياً — تغطيةٌ لكل مفاتيح جدول الرموز.
        val samples = listOf(
            "$1", "2€", "3£", "4¥", "5₹", "6₽", "7₩",
            "ر.س 8", "د.إ 9", "د.ك 10", "ر.ع 11", "د.ب 12",
            "ج.م 13", "د.ت 14", "د.ج 15", "ر.م 16", "ر.ق 17",
            "﷼ 18"
        )
        val arabic = Regex("[\\u0600-\\u06FF]")
        for (sample in samples) {
            val out = CurrencyStep.applyEnglish(sample)
            assertTrue(
                "عربية متبقية: $sample",
                !arabic.containsMatchIn(out)
            )
        }
    }

    // ═══════════════════ بند التشكيل الشرطي ═══════════════════

    @Test
    fun tashkeelStrip_preservesForGoogleTts() {
        // Google TTS يفهم التشكيل العربي → لا نجرد له.
        assertFalse(stripTashkeelFor("com.google.android.tts"))
    }

    @Test
    fun tashkeelStrip_defaultsToStripForUnknownOrNull() {
        // الافتراضي/غير المعروف/null → تجريد (السلوك القائم).
        assertTrue(stripTashkeelFor(null))
        assertTrue(stripTashkeelFor("com.samsung.android.tts"))
        assertTrue(stripTashkeelFor(""))
    }

    @Test
    fun tashkeelStrip_aweareEnginesSetIsNonEmpty() {
        assertTrue(ARABIC_TASHKEEL_AWARE_ENGINES.isNotEmpty())
    }

    @Test
    fun phone_withLanguageProvider_respectsConfiguredLanguage() {
        val stepEn = PhoneNumberStep { "en" }
        assertEquals(
            "plus two zero one one five five five two four four two",
            stepEn.apply("+20 11 5552442")
        )
        val stepAr = PhoneNumberStep { "ar" }
        assertEquals(
            "زائد اثنان صفر واحد واحد خمسة خمسة خمسة اثنان أربعة أربعة اثنان",
            stepAr.applyEnglish("+20 11 5552442")
        )
    }
}