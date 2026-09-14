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

    // أنماط الرموز قبل المبلغ: «$100» مع مسافة اختيارية بين الرمز والمبلغ.
    private val CURRENCY_PATTERNS_BEFORE =
        CURRENCY_SYMBOLS.map { (symbol, info) ->
            val source = Pattern.quote(symbol) + "\\s*(" + AMOUNT_REGEX + ")\\b"
            Pattern.compile(source) to info
        }
    // أنماط الرموز بعد المبلغ: «100$» مع مسافة اختيارية بين المبلغ والرمز.
    // الحارس السالب للعدد يشمل الإشارة نفسها: لا تُلتقط «-2$» كجزء من
    // رقم أطول/رقمٍ سالبٍ سابق («12-2$» تُترك كما هي).
    private val CURRENCY_PATTERNS_AFTER =
        CURRENCY_SYMBOLS.map { (symbol, info) ->
            val source = "(?<![-\\d])(" + AMOUNT_REGEX + ")\\s*" +
                Pattern.quote(symbol)
            Pattern.compile(source) to info
        }

    // بوابة عدم التطابق: دمج OR صريح لكل أنماط العملة (قبل/بعد/كود/المبلغ
    // قبل الكود). إن لم يطابق شيئاً أُعيد النص كما هو بلا 38 ممراً وتخصيص
    // سلسلة؛ بدائل العملة عربية بلا أرقام فلا يُنشئ استبدالٌ تطابقاً جديداً،
    // فالسلوك مطابق تماماً.
    private val CURRENCY_ANY_PATTERN = Pattern.compile(
        (CURRENCY_PATTERNS_BEFORE + CURRENCY_PATTERNS_AFTER)
            .joinToString("|") { "(" + it.first.pattern() + ")" } +
            "|(" + PATTERN_CURRENCY_CODE.pattern() + ")" +
            "|(" + PATTERN_AMOUNT_CODE.pattern() + ")"
    )

    override fun apply(input: String): String {
        if (!CURRENCY_ANY_PATTERN.matcher(input).find()) return input
        var result = input

        // رموز قبل المبلغ: $100
        for ((pattern, info) in CURRENCY_PATTERNS_BEFORE) {
            val matcher = pattern.matcher(result)
            val buffer = StringBuffer()
            while (matcher.find()) {
                val amount = AmountParser.parseAmount(matcher.group(1)!!)
                val amountText = currencyAmountPhrase(amount, info)
                matcher.appendReplacement(
                    buffer,
                    Matcher.quoteReplacement(amountText)
                )
            }
            matcher.appendTail(buffer)
            result = buffer.toString()
        }

        // رموز بعد المبلغ: 100$
        for ((pattern, info) in CURRENCY_PATTERNS_AFTER) {
            val matcher = pattern.matcher(result)
            val buffer = StringBuffer()
            while (matcher.find()) {
                val amount = AmountParser.parseAmount(matcher.group(1)!!)
                val amountText = currencyAmountPhrase(amount, info)
                matcher.appendReplacement(
                    buffer,
                    Matcher.quoteReplacement(amountText)
                )
            }
            matcher.appendTail(buffer)
            result = buffer.toString()
        }

        // أكواد العملة: USD 100 (الكود ثم المبلغ)
        result = replaceCurrencyCodes(
            result, PATTERN_CURRENCY_CODE, 1, 2
        )
        // أكواد العملة: 1500 USD (المبلغ ثم الكود) — مهم للنصوص المختلطة
        // التي يُفصل عنها الرمز لو نُسب «USD» إلى مقطعٍ إنجليزي منفصل.
        result = replaceCurrencyCodes(
            result, PATTERN_AMOUNT_CODE, 2, 1
        )

        return result
    }

    /** استبدال أكواد العملة بـ «codeGroup» (رقم مجموعة الكود) و«amountGroup»
     *  (رقم مجموعة المبلغ) في نمطٍ محدد — تُوحَّد حلقةُ الاستبدال للنمطين
     *  (كودٌ قبل مبلغه أو بعده) فلا يتكرر منطقُ التطابق والإخراج. */
    private fun replaceCurrencyCodes(
        input: String,
        pattern: Pattern,
        codeGroup: Int,
        amountGroup: Int
    ): String {
        val matcher = pattern.matcher(input)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val code = matcher.group(codeGroup)!!
            val amount = AmountParser.parseAmount(matcher.group(amountGroup)!!)
            val info = CURRENCY_CODE_INFO[code]
            if (info == null) {
                val amountText = NumberWordsConverter.numberToWords(amount)
                val fallback = "$amountText $code"
                matcher.appendReplacement(
                    buffer,
                    Matcher.quoteReplacement(fallback)
                )
            } else {
                val amountPhrase = currencyAmountPhrase(amount, info)
                matcher.appendReplacement(
                    buffer,
                    Matcher.quoteReplacement(amountPhrase)
                )
            }
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

    /** صيغة النصب للكلمة (تنوين نصب): ة→ةً، سواها→اً. */
    private fun accusativeForm(word: String): String {
        if (word.endsWith("ة")) return "${word}ً"
        return "${word}اً"
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