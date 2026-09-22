package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.NumberSpeech
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.abs

/** معالجة العملات: $100 → «مائة دولار»، $1 → «دولار واحد»،
 *  $3 → «ثلاثة دولارات»، $1.50 → «دولار واحد وخمسون سنتاً»،
 *  50€ → «خمسون يورو». */
internal object CurrencyStep : TextProcessingStep {

    private val CURRENCY_SYMBOLS = mapOf<String, CurrencyInfo>(
        "$" to CurrencyInfo(
            "دولار",
            "دولارات",
            "دولاران",
            false,
            "سنت",
            "سنتات",
            false
        ),
        "€" to CurrencyInfo(
            "يورو",
            "يورو",
            "يوروان",
            false,
            "سنت",
            "سنتات",
            false
        ),
        "£" to CurrencyInfo(
            "جنيه استرليني",
            "جنيهات استرلينية",
            "جنيهان استرلينيان",
            false,
            "بنس",
            "بنسات",
            false
        ),
        "¥" to CurrencyInfo(
            "ين ياباني",
            "ين ياباني",
            "ينان يابانيان",
            false,
            "سن",
            "سنات",
            false
        ),
        "₹" to CurrencyInfo(
            "روبية هندية",
            "روبيات هندية",
            "روبيتان هنديتان",
            true,
            "بيسة",
            "بيسات",
            true
        ),
        "₽" to CurrencyInfo(
            "روبل روسي",
            "روبلات روسية",
            "روبلان روسيان",
            false,
            "كوبيك",
            "كوبيكات",
            false
        ),
        "₩" to CurrencyInfo(
            "وون كوري",
            "وون كوري",
            "وونان كوريان",
            false,
            "جون",
            "جونات",
            false
        ),
        "﷼" to CurrencyInfo(
            "ريال",
            "ريالات",
            "ريالان",
            false,
            "هللة",
            "هللات",
            true
        ),
        "د.إ" to CurrencyInfo(
            "درهم إماراتي",
            "دراهم إماراتية",
            "درهمان إماراتيان",
            false,
            "فلس",
            "فلوس",
            false
        ),
        "ر.س" to CurrencyInfo(
            "ريال سعودي",
            "ريالات سعودية",
            "ريالان سعوديان",
            false,
            "هللة",
            "هللات",
            true
        ),
        "د.ك" to CurrencyInfo(
            "دينار كويتي",
            "دنانير كويتية",
            "ديناران كويتيان",
            false,
            "فلس",
            "فلوس",
            false,
            subunitsPerUnit = 1000
        ),
        "ر.ق" to CurrencyInfo(
            "ريال قطري",
            "ريالات قطرية",
            "ريالان قطريان",
            false,
            "درهم",
            "دراهم",
            false
        ),
        "ر.ع" to CurrencyInfo(
            "ريال عماني",
            "ريالات عمانية",
            "ريالان عمانيان",
            false,
            "بيسة",
            "بيسات",
            true,
            subunitsPerUnit = 1000
        ),
        "د.ب" to CurrencyInfo(
            "دينار بحريني",
            "دنانير بحرينية",
            "ديناران بحرينيان",
            false,
            "فلس",
            "فلوس",
            false,
            subunitsPerUnit = 1000
        ),
        "ج.م" to CurrencyInfo(
            "جنيه مصري",
            "جنيهات مصرية",
            "جنيهان مصريان",
            false,
            "قرش",
            "قروش",
            false
        ),
        "د.ت" to CurrencyInfo(
            "دينار تونسي",
            "دنانير تونسية",
            "ديناران تونسيان",
            false,
            "مليم",
            "مليمات",
            false,
            subunitsPerUnit = 1000
        ),
        "د.ج" to CurrencyInfo(
            "دينار جزائري",
            "دنانير جزائرية",
            "ديناران جزائريان",
            false,
            "سنتيم",
            "سنتيمات",
            false
        ),
        "ر.م" to CurrencyInfo(
            "ريال مغربي",
            "ريالات مغربية",
            "ريالان مغربيان",
            false,
            "سنتيم",
            "سنتيمات",
            false
        )
    )

    // أكواد العملات العالمية مع بياناتها النحوية الكاملة
    private val CURRENCY_CODE_INFO = mapOf<String, CurrencyInfo>(
        "USD" to CurrencyInfo(
            "دولار أمريكي",
            "دولارات أمريكية",
            "دولاران أمريكيان",
            false,
            "سنت",
            "سنتات",
            false
        ),
        "EUR" to CurrencyInfo(
            "يورو",
            "يورو",
            "يوروان",
            false,
            "سنت",
            "سنتات",
            false
        ),
        "GBP" to CurrencyInfo(
            "جنيه استرليني",
            "جنيهات استرلينية",
            "جنيهان استرلينيان",
            false,
            "بنس",
            "بنسات",
            false
        ),
        "SAR" to CurrencyInfo(
            "ريال سعودي",
            "ريالات سعودية",
            "ريالان سعوديان",
            false,
            "هللة",
            "هللات",
            true
        ),
        "AED" to CurrencyInfo(
            "درهم إماراتي",
            "دراهم إماراتية",
            "درهمان إماراتيان",
            false,
            "فلس",
            "فلوس",
            false
        ),
        "KWD" to CurrencyInfo(
            "دينار كويتي",
            "دنانير كويتية",
            "ديناران كويتيان",
            false,
            "فلس",
            "فلوس",
            false,
            subunitsPerUnit = 1000
        ),
        "QAR" to CurrencyInfo(
            "ريال قطري",
            "ريالات قطرية",
            "ريالان قطريان",
            false,
            "درهم",
            "دراهم",
            false
        ),
        "OMR" to CurrencyInfo(
            "ريال عماني",
            "ريالات عمانية",
            "ريالان عمانيان",
            false,
            "بيسة",
            "بيسات",
            true,
            subunitsPerUnit = 1000
        ),
        "BHD" to CurrencyInfo(
            "دينار بحريني",
            "دنانير بحرينية",
            "ديناران بحرينيان",
            false,
            "فلس",
            "فلوس",
            false,
            subunitsPerUnit = 1000
        ),
        "EGP" to CurrencyInfo(
            "جنيه مصري",
            "جنيهات مصرية",
            "جنيهان مصريان",
            false,
            "قرش",
            "قروش",
            false
        ),
        "TND" to CurrencyInfo(
            "دينار تونسي",
            "دنانير تونسية",
            "ديناران تونسيان",
            false,
            "مليم",
            "مليمات",
            false,
            subunitsPerUnit = 1000
        ),
        "DZD" to CurrencyInfo(
            "دينار جزائري",
            "دنانير جزائرية",
            "ديناران جزائريان",
            false,
            "سنتيم",
            "سنتيمات",
            false
        ),
        "MAD" to CurrencyInfo(
            "درهم مغربي",
            "دراهم مغربية",
            "درهمان مغربيان",
            false,
            "سنتيم",
            "سنتيمات",
            false
        ),
        "JPY" to CurrencyInfo(
            "ين ياباني",
            "ين ياباني",
            "ينان يابانيان",
            false,
            "سن",
            "سنات",
            false
        ),
        "CNY" to CurrencyInfo(
            "يوان صيني",
            "يوانات صينية",
            "يوانان صينيان",
            false,
            "فن",
            "فنات",
            false
        ),
        "INR" to CurrencyInfo(
            "روبية هندية",
            "روبيات هندية",
            "روبيتان هنديتان",
            true,
            "بيسة",
            "بيسات",
            true
        ),
        "KRW" to CurrencyInfo(
            "وون كوري",
            "وون كوري",
            "وونان كوريان",
            false,
            "جون",
            "جونات",
            false
        ),
        "RUB" to CurrencyInfo(
            "روبل روسي",
            "روبلات روسية",
            "روبلان روسيان",
            false,
            "كوبيك",
            "كوبيكات",
            false
        )
    )

    // جداول العملات الإنجليزية (اللغة الثانية) — نفس مفاتيح الرموز/الأكواد
    // العربية بأسماء إنجليزية. لو غاب رمزٌ عن إحداهما لسقط من المسار
    // المقابل، فتبقى المفاتيح متطابقة دائماً.
    private val CURRENCY_SYMBOLS_EN = mapOf<String, CurrencyInfoEn>(
        "$" to CurrencyInfoEn("dollar", "dollars", "cent", "cents"),
        "€" to CurrencyInfoEn("euro", "euros", "cent", "cents"),
        "£" to CurrencyInfoEn("pound", "pounds", "penny", "pence"),
        "¥" to CurrencyInfoEn("yen", "yen", "sen", "sen"),
        "₹" to CurrencyInfoEn("rupee", "rupees", "paise", "paise"),
        "₽" to CurrencyInfoEn("ruble", "rubles", "kopeck", "kopecks"),
        "₩" to CurrencyInfoEn("won", "won", "jeon", "jeon"),
        "﷼" to CurrencyInfoEn("riyal", "riyals", "halala", "halalas"),
        "د.إ" to CurrencyInfoEn("dirham", "dirhams", "fils", "fils"),
        "ر.س" to CurrencyInfoEn("riyal", "riyals", "halala", "halalas"),
        "د.ك" to CurrencyInfoEn("dinar", "dinars", "fils", "fils", 1000),
        "ر.ق" to CurrencyInfoEn("riyal", "riyals", "dirham", "dirhams"),
        "ر.ع" to CurrencyInfoEn("riyal", "riyals", "baisa", "baisa", 1000),
        "د.ب" to CurrencyInfoEn("dinar", "dinars", "fils", "fils", 1000),
        "ج.م" to CurrencyInfoEn("pound", "pounds", "piastre", "piastres"),
        "د.ت" to CurrencyInfoEn(
            "dinar", "dinars", "millime", "millimes", 1000
        ),
        "د.ج" to CurrencyInfoEn("dinar", "dinars", "centime", "centimes"),
        "ر.م" to CurrencyInfoEn("dirham", "dirhams", "centime", "centimes")
    )

    private val CURRENCY_CODE_INFO_EN = mapOf<String, CurrencyInfoEn>(
        "USD" to CurrencyInfoEn(
            "US dollar", "US dollars", "cent", "cents"
        ),
        "EUR" to CurrencyInfoEn("euro", "euros", "cent", "cents"),
        "GBP" to CurrencyInfoEn(
            "pound sterling", "pounds sterling", "penny", "pence"
        ),
        "SAR" to CurrencyInfoEn(
            "Saudi riyal", "Saudi riyals", "halala", "halalas"
        ),
        "AED" to CurrencyInfoEn("dirham", "dirhams", "fils", "fils"),
        "KWD" to CurrencyInfoEn(
            "Kuwaiti dinar", "Kuwaiti dinars", "fils", "fils", 1000
        ),
        "QAR" to CurrencyInfoEn(
            "Qatari riyal", "Qatari riyals", "dirham", "dirhams"
        ),
        "OMR" to CurrencyInfoEn(
            "Omani riyal", "Omani riyals", "baisa", "baisa", 1000
        ),
        "BHD" to CurrencyInfoEn(
            "Bahraini dinar", "Bahraini dinars", "fils", "fils", 1000
        ),
        "EGP" to CurrencyInfoEn(
            "Egyptian pound", "Egyptian pounds", "piastre", "piastres"
        ),
        "TND" to CurrencyInfoEn(
            "Tunisian dinar", "Tunisian dinars", "millime", "millimes", 1000
        ),
        "DZD" to CurrencyInfoEn(
            "Algerian dinar", "Algerian dinars", "centime", "centimes"
        ),
        "MAD" to CurrencyInfoEn(
            "Moroccan dirham", "Moroccan dirhams", "centime", "centimes"
        ),
        "JPY" to CurrencyInfoEn("yen", "yen", "sen", "sen"),
        "CNY" to CurrencyInfoEn("yuan", "yuan", "fen", "fen"),
        "INR" to CurrencyInfoEn("rupee", "rupees", "paise", "paise"),
        "KRW" to CurrencyInfoEn("won", "won", "jeon", "jeon"),
        "RUB" to CurrencyInfoEn("ruble", "rubles", "kopeck", "kopecks")
    )

    // نمط المبلغ الرقمي داخل العملات: فواصل آلاف اختيارية + فاصلة عشرية.
    // نمط المبلغ مع إشارة سالبة اختيارية (بند 3.1): «-2$» / «$-2» تُنطق
    // «ناقص دولاران» — كانت السالبة خارج النمط فتسقط الإشارة وتُتلف
    // الترتيب النحوي.
    private val AMOUNT_REGEX = "-?\\d+(?:[.,]\\d{3})*(?:[.,]\\d+)?"
    // نمط كود العملة مسبوقاً بالمبلغ: «USD 100».
    private val PATTERN_CURRENCY_CODE = Pattern.compile(
        """\b(USD|EUR|GBP|SAR|AED|KWD|QAR|OMR|BHD|EGP|TND|""" +
            """DZD|MAD|JPY|CNY|INR|KRW|RUB)\s+(""" + AMOUNT_REGEX + """)\b"""
    )
    // نمط الكود المسبوق بمبلغه: «1500 USD» — يمنع انفصال «USD» مقطعاً
    // إنجليزياً في النص المختلط (تُطبَّق خطوة العملة قبل تقسيم اللغة).
    private val PATTERN_AMOUNT_CODE = Pattern.compile(
        """\b(""" + AMOUNT_REGEX + """)\s+(USD|EUR|GBP|SAR|AED|KWD|QAR|""" +
            """OMR|BHD|EGP|TND|DZD|MAD|JPY|CNY|INR|KRW|RUB)\b"""
    )

    /** أنماط لغةِ عملة: استبدالات «الرمز قبل المبلغ» و«الرمز بعده» + بوابة
     *  عدم التطابق (تتخطى الـ38 ممراً عند غياب أي عملة). */
    private class Patterns<T>(
        val before: List<Pair<Pattern, T>>,
        val after: List<Pair<Pattern, T>>,
        val any: Pattern
    )

    /** يبني أنماط لغةٍ من جدول رموزها — البنية نفسها (نمط المبلغ/الأكواد)
     *  للغتين، والمختلف مفرداتُ الاستبدال فقط. */
    private fun <T> compilePatterns(infos: Map<String, T>): Patterns<T> {
        // أنماط الرموز قبل المبلغ: «$100» مع مسافة اختيارية بين الرمز والمبلغ.
        val before = infos.map { (symbol, info) ->
            val source = Pattern.quote(symbol) + "\\s*(" +
                AMOUNT_REGEX + ")\\b"
            Pattern.compile(source) to info
        }
        // أنماط الرموز بعد المبلغ: «100$» مع مسافة اختيارية بين المبلغ
        // والرمز. الحارس السالب للعدد يشمل الإشارة نفسها: لا تُلتقط «-2$»
        // كجزء من رقم أطول/رقمٍ سالبٍ سابق («12-2$» تُترك كما هي).
        val after = infos.map { (symbol, info) ->
            val source = "(?<![-\\d])(" + AMOUNT_REGEX + ")\\s*" +
                Pattern.quote(symbol)
            Pattern.compile(source) to info
        }
        // بوابة عدم التطابق: دمج OR صريح لكل أنماط العملة (قبل/بعد/كود/
        // المبلغ قبل الكود). إن لم يطابق شيئاً أُعيد النص كما هو بلا 38 ممراً
        // وتخصيص سلسلة؛ بدائل العملة بلا أرقام فلا يُنشئ استبدالٌ تطابقاً.
        val any = Pattern.compile(
            (before + after)
                .joinToString("|") { "(" + it.first.pattern() + ")" } +
                "|(" + PATTERN_CURRENCY_CODE.pattern() + ")" +
                "|(" + PATTERN_AMOUNT_CODE.pattern() + ")"
        )
        return Patterns(before, after, any)
    }

    private val arabicPatterns = compilePatterns(CURRENCY_SYMBOLS)
    private val englishPatterns = compilePatterns(CURRENCY_SYMBOLS_EN)

    override fun apply(input: String): String = process(
        input,
        arabicPatterns,
        CURRENCY_CODE_INFO,
        phrase = { amount, info -> currencyAmountPhrase(amount, info) },
        fallback = { amount -> NumberWordsConverter.numberToWords(amount) }
    )

    /** النسخة الإنجليزية: أسماء العملات والكسور إنجليزية
     *  («one dollar and fifty cents»). */
    override fun applyEnglish(input: String): String = process(
        input,
        englishPatterns,
        CURRENCY_CODE_INFO_EN,
        phrase = { amount, info ->
            englishCurrencyAmountPhrase(amount, info)
        },
        fallback = { amount -> NumberSpeech.toEnglishWords(amount.toLong()) }
    )

    /** التطبيق الموحّد للغتين: بوابة → رموز قبل/بعد → أكواد قبل/بعد. */
    private fun <T> process(
        input: String,
        patterns: Patterns<T>,
        codeInfo: Map<String, T>,
        phrase: (Double, T) -> String,
        fallback: (Double) -> String
    ): String {
        if (!patterns.any.matcher(input).find()) return input
        var result = input
        for ((pattern, info) in patterns.before) {
            result = replaceAmounts(result, pattern, info, phrase)
        }
        for ((pattern, info) in patterns.after) {
            result = replaceAmounts(result, pattern, info, phrase)
        }
        // أكواد العملة: USD 100 (الكود ثم المبلغ)
        result = replaceCodes(
            result, PATTERN_CURRENCY_CODE, 1, 2, codeInfo, phrase, fallback
        )
        // أكواد العملة: 1500 USD — مهم للنصوص المختلطة التي يُفصل عنها
        // الرمز لو نُسب «USD» إلى مقطعٍ إنجليزي منفصل.
        result = replaceCodes(
            result, PATTERN_AMOUNT_CODE, 2, 1, codeInfo, phrase, fallback
        )
        return result
    }

    /** استبدال «الرمز قبل/بعد المبلغ» بعبارة العملة في لغةٍ ما. */
    private fun <T> replaceAmounts(
        input: String,
        pattern: Pattern,
        info: T,
        phrase: (Double, T) -> String
    ): String {
        val matcher = pattern.matcher(input)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val amount = AmountParser.parseAmount(matcher.group(1)!!)
            matcher.appendReplacement(
                buffer,
                Matcher.quoteReplacement(phrase(amount, info))
            )
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /** استبدال أكواد العملة بـ «codeGroup» (رقم مجموعة الكود) و«amountGroup»
     *  (رقم مجموعة المبلغ) في نمطٍ محدد — تُوحَّد حلقةُ الاستبدال للنمطين
     *  (كودٌ قبل مبلغه أو بعده) فلا يتكرر منطقُ التطابق والإخراج. */
    private fun <T> replaceCodes(
        input: String,
        pattern: Pattern,
        codeGroup: Int,
        amountGroup: Int,
        codeInfo: Map<String, T>,
        phrase: (Double, T) -> String,
        fallback: (Double) -> String
    ): String {
        val matcher = pattern.matcher(input)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val code = matcher.group(codeGroup)!!
            val amount = AmountParser.parseAmount(matcher.group(amountGroup)!!)
            val info = codeInfo[code]
            val replacement = if (info == null) {
                "${fallback(amount)} $code"
            } else {
                phrase(amount, info)
            }
            matcher.appendReplacement(
                buffer, Matcher.quoteReplacement(replacement)
            )
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /** نطق مبلغ عملة مع التوافق النحوي الكامل (مفرد/مثنى/جمع/كسور):
     *  1 ← «دولار واحد»، 2 ← «دولاران»، 3–10 ← «ثلاثة دولارات»،
     *  ما فوق ← «خمسة وعشرون دولاراً»، والكسور ← «وخمسون سنتاً».
     *
     *  **بند 3.2:** ترحيل الكسور المتراكمة (1.999 → دولاران)،
     *  تمييز النصب لـ 11–99، والجنس المؤنث عبر [NumberSpeech]. */
    internal fun currencyAmountPhrase(
        amount: Double,
        info: CurrencyInfo
    ): String {
        val whole = amount.toLong()
        val isNeg = whole < 0L || (whole == 0L && amount < 0.0)
        // العملات ثلاثية الخانات (د.ك/د.ب/ر.ع/د.ت) وحدتها الفرعية 1000
        // (فلس/بيسة/مليم) والبقية 100. كان الضرب الثابت في 100.0 ينطق
        // «1.500 د.ك» خطأً «وخمسون فلس» بدل «وخمسمائة فلس»، ويُفقد كسوراً
        // صغيرة («2.005 د.ت» كانت تُنطق «ديناران تونسيان» بلا جزء كسري).
        val fracSubunitsRaw = Math.round(
            Math.abs(amount - whole) * info.subunitsPerUnit
        ).toInt()
        // **بند 3.2 (ترحيل):** كسر >= الوحدة يؤدي ترحيله للوحدة الأكبر
        // (1.999$ → «دولاران»، -1.999$ → «ناقص دولاران») — كانت الكسور
        // تتجاوز الوحدة دون ترحيل فينطق «واحد دولار وتسعمائة وتسعة وتسعون
        // سنت» بدل «دولاران».
        val carry = fracSubunitsRaw / info.subunitsPerUnit
        val absWhole = abs(whole) + carry
        val fracSubunits = fracSubunitsRaw % info.subunitsPerUnit
        val fracPhrase = currencyFractionPhrase(fracSubunits, info)
        // مبلغ كسري صرف (0.50$) → «خمسون سنت» بلا «و» افتتاحية.
        if (absWhole == 0L && fracPhrase.isNotEmpty()) {
            // بند 3.6: المبلغ الكسري السلبي يحافظ على إشارته
            // («سالب خمسون سنتاً») — كانت تُسقَط دون هذه البقعة.
            return if (isNeg) "سالب $fracPhrase" else fracPhrase
        }

        // **السالب الصحيح (بند 3.1):** العدد الذهني يُحسم على القيمة المطلقة
        // («-2$» ← «دولاران»)، وإلا عاجت السالبةُ عن فروع when إلى صيغة
        // المفرد الخاطئة («ناقص اثنان دولار»). وكما يسبق المحوّلُ الرقمي
        // الأعدادَ السالبة بكلمة «ناقص » يُسبق المبلغُ بها أيضاً.
        val wholePhrase = when {
            absWhole == 0L -> "صفر ${info.name}"
            absWhole == 1L -> {
                val unit = if (info.isFeminine) "واحدة" else "واحد"
                "${info.name} $unit"
            }
            absWhole == 2L -> info.dual
            absWhole in 3..10 -> {
                val units = NumberWordsConverter.unitNumberWord(
                    absWhole.toInt(),
                    info.isFeminine
                )
                "$units ${info.plural}"
            }
            else -> {
                val words = numberToWordsForGender(
                    absWhole, info.isFeminine
                )
                val rem100 = (absWhole % 100).toInt()
                // **بند 3.2 (تنوين نصب):** العدد المركّب 11–99 يلزم
                // المعدود بالتنوين المنصوب («دولاراً» لا «دولار»).
                // وال McMaster 3–10 بالجمع («ثلاثة دولارات»).
                when {
                    rem100 in 3..10 -> "$words ${info.plural}"
                    rem100 in 11..99 ->
                        "$words ${accusativeForm(info.name)}"
                    else -> "$words ${info.name}"
                }
            }
        }
        val base = if (isNeg) "ناقص $wholePhrase" else wholePhrase
        return if (fracPhrase.isEmpty()) {
            base
        } else {
            "$base و$fracPhrase"
        }
    }

    /** نطق كسور المبلغ (أجزاء الوحدة الفرعية) باسم الوحدة الفرعية:
     *  .01 ← «سنت واحد»، .02 ← «سنتان»، .02 ر.س ← «هللتان»،
     *  .50 ← «خمسون سنت». */
    private fun currencyFractionPhrase(
        subunits: Int,
        info: CurrencyInfo
    ): String {
        if (subunits <= 0) return ""
        return when (subunits) {
            1 -> {
                val unit = if (info.subunitFeminine) "واحدة" else "واحد"
                "${info.subunit} $unit"
            }
            // مثنى الوحدة الفرعية: المؤنثة تنتهي بتاء مربوطة (هللة/بيسة)
            // فتُفرد التاء وتُفتح بألف وتاء («هللتان»/«بيستان») بدل «هللةتان».
            2 -> when {
                info.subunit.endsWith("ة") -> "${info.subunit.dropLast(1)}تان"
                info.subunitFeminine -> "${info.subunit}تان"
                else -> "${info.subunit}ان"
            }
            in 3..10 -> {
                val units = NumberWordsConverter.unitNumberWord(
                    subunits,
                    info.subunitFeminine
                )
                "$units ${info.subunitPlural}"
            }
            else -> {
                val words = NumberWordsConverter.numberToWords(
                    subunits.toDouble()
                )
                // **بند 3.2:** الوحدة الفرعية مثل الرئيسية: 11–99 منصوبة
                // («تسعة وعشرون سنتاً» لا «تسعة وعشرون سنت»).
                val rem100 = subunits % 100
                when {
                    rem100 in 3..10 -> "$words ${info.subunitPlural}"
                    rem100 in 11..99 ->
                        "$words ${accusativeForm(info.subunit)}"
                    else -> "$words ${info.subunit}"
                }
            }
        }
    }

    /** نطق مبلغ عملة بالإنجليزية: 1 ← «one dollar»، 2 ← «two dollars»،
     *  1.50 ← «one dollar and fifty cents»، 0.50 ← «fifty cents»،
     *  والسالب ← «minus …». لا جنس ولا مثنى في الإنجليزية. */
    internal fun englishCurrencyAmountPhrase(
        amount: Double,
        info: CurrencyInfoEn
    ): String {
        val whole = amount.toLong()
        val isNeg = whole < 0L || (whole == 0L && amount < 0.0)
        // ترحيل الكسور المتراكمة (1.999 → دولاران) كما في المسار العربي.
        val fracSubunitsRaw = Math.round(
            Math.abs(amount - whole) * info.subunitsPerUnit
        ).toInt()
        val carry = fracSubunitsRaw / info.subunitsPerUnit
        val absWhole = abs(whole) + carry
        val fracSubunits = fracSubunitsRaw % info.subunitsPerUnit
        val fracPhrase = englishFractionPhrase(fracSubunits, info)
        if (absWhole == 0L && fracPhrase.isNotEmpty()) {
            return if (isNeg) "minus $fracPhrase" else fracPhrase
        }
        val wholePhrase = when (absWhole) {
            0L -> "zero ${info.plural}"
            1L -> "one ${info.name}"
            else -> "${
                NumberSpeech.toEnglishWords(absWhole)
            } ${info.plural}"
        }
        val base = if (isNeg) "minus $wholePhrase" else wholePhrase
        return if (fracPhrase.isEmpty()) {
            base
        } else {
            "$base and $fracPhrase"
        }
    }

    /** نطق كسور المبلغ بالإنجليزية باسم الوحدة الفرعية: 1 ← «one cent»،
     *  .50 ← «fifty cents». */
    private fun englishFractionPhrase(
        subunits: Int,
        info: CurrencyInfoEn
    ): String {
        if (subunits <= 0) return ""
        return if (subunits == 1) {
            "one ${info.subunit}"
        } else {
            "${NumberSpeech.toEnglishWords(subunits)} ${info.subunitPlural}"
        }
    }

    /** صيغة النصب للكلمة (تنوين نصب): ة→ةً، سواها→اً. */
    private fun accusativeForm(phrase: String): String {
        return phrase.split(' ').joinToString(" ") { word ->
            if (word.endsWith("ة")) "${word}ً" else "${word}اً"
        }
    }

    /** تحويل عدد لكلمات مع مراعاة الجنس: المؤنث يذهب إلى [NumberSpeech]
     *  (يدعم حتى 99,999,999 مع صيغة المذكر/المؤنث)، والمذكر والأعداد
     *  الكبيرة تذهب إلى [NumberWordsConverter]. */
    private fun numberToWordsForGender(
        n: Long,
        isFeminine: Boolean
    ): String {
        if (isFeminine && n <= Int.MAX_VALUE) {
            return NumberSpeech.toArabicWords(n.toInt(), isFeminine)
        }
        return NumberWordsConverter.numberToWords(n.toDouble())
    }
}