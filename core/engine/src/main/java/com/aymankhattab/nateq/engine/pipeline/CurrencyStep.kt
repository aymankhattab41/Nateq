package com.aymankhattab.nateq.engine.pipeline

import java.util.regex.Pattern

/** معالجة العملات: $100 → «مائة دولار»، $1 → «دولار واحد»، $3 → «ثلاثة دولارات»،
 *  $1.50 → «دولار واحد وخمسون سنتاً»، 50€ → «خمسون يورو» */
internal object CurrencyStep : TextProcessingStep {

    private val CURRENCY_SYMBOLS = mapOf<String, CurrencyInfo>(
        "\$" to CurrencyInfo("دولار", "دولارات", "دولاران", false, "سنت", "سنتات", false),
        "€" to CurrencyInfo("يورو", "يورو", "يوروان", false, "سنت", "سنتات", false),
        "£" to CurrencyInfo("جنيه استرليني", "جنيهات استرلينية", "جنيهان استرلينيان", false, "بنس", "بنسات", false),
        "¥" to CurrencyInfo("ين ياباني", "ين ياباني", "ينان يابانيان", false, "سن", "سنات", false),
        "₹" to CurrencyInfo("روبية هندية", "روبيات هندية", "روبيتان هنديتان", true, "بيسة", "بيسات", true),
        "₽" to CurrencyInfo("روبل روسي", "روبلات روسية", "روبلان روسيان", false, "كوبيك", "كوبيكات", false),
        "₩" to CurrencyInfo("وون كوري", "وون كوري", "وونان كوريان", false, "جون", "جونات", false),
        "﷼" to CurrencyInfo("ريال", "ريالات", "ريالان", false, "هللة", "هللات", true),
        "د.إ" to CurrencyInfo("درهم إماراتي", "دراهم إماراتية", "درهمان إماراتيان", false, "فلس", "فلوس", false),
        "ر.س" to CurrencyInfo("ريال سعودي", "ريالات سعودية", "ريالان سعوديان", false, "هللة", "هللات", true),
        "د.ك" to CurrencyInfo("دينار كويتي", "دنانير كويتية", "ديناران كويتيان", false, "فلس", "فلوس", false),
        "ر.ق" to CurrencyInfo("ريال قطري", "ريالات قطرية", "ريالان قطريان", false, "درهم", "دراهم", false),
        "ر.ع" to CurrencyInfo("ريال عماني", "ريالات عمانية", "ريالان عمانيان", false, "بيسة", "بيسات", true),
        "د.ب" to CurrencyInfo("دينار بحريني", "دنانير بحرينية", "ديناران بحرينيان", false, "فلس", "فلوس", false),
        "ج.م" to CurrencyInfo("جنيه مصري", "جنيهات مصرية", "جنيهان مصريان", false, "قرش", "قروش", false),
        "د.ت" to CurrencyInfo("دينار تونسي", "دنانير تونسية", "ديناران تونسيان", false, "مليم", "مليمات", false),
        "د.ج" to CurrencyInfo("دينار جزائري", "دنانير جزائرية", "ديناران جزائريان", false, "سنتيم", "سنتيمات", false),
        "ر.م" to CurrencyInfo("ريال مغربي", "ريالات مغربية", "ريالان مغربيان", false, "سنتيم", "سنتيمات", false)
    )

    // أكواد العملات العالمية مع بياناتها النحوية الكاملة
    private val CURRENCY_CODE_INFO = mapOf<String, CurrencyInfo>(
        "USD" to CurrencyInfo("دولار أمريكي", "دولارات أمريكية", "دولاران أمريكيان", false, "سنت", "سنتات", false),
        "EUR" to CurrencyInfo("يورو", "يورو", "يوروان", false, "سنت", "سنتات", false),
        "GBP" to CurrencyInfo("جنيه استرليني", "جنيهات استرلينية", "جنيهان استرلينيان", false, "بنس", "بنسات", false),
        "SAR" to CurrencyInfo("ريال سعودي", "ريالات سعودية", "ريالان سعوديان", false, "هللة", "هللات", true),
        "AED" to CurrencyInfo("درهم إماراتي", "دراهم إماراتية", "درهمان إماراتيان", false, "فلس", "فلوس", false),
        "KWD" to CurrencyInfo("دينار كويتي", "دنانير كويتية", "ديناران كويتيان", false, "فلس", "فلوس", false),
        "QAR" to CurrencyInfo("ريال قطري", "ريالات قطرية", "ريالان قطريان", false, "درهم", "دراهم", false),
        "OMR" to CurrencyInfo("ريال عماني", "ريالات عمانية", "ريالان عمانيان", false, "بيسة", "بيسات", true),
        "BHD" to CurrencyInfo("دينار بحريني", "دنانير بحرينية", "ديناران بحرينيان", false, "فلس", "فلوس", false),
        "EGP" to CurrencyInfo("جنيه مصري", "جنيهات مصرية", "جنيهان مصريان", false, "قرش", "قروش", false),
        "TND" to CurrencyInfo("دينار تونسي", "دنانير تونسية", "ديناران تونسيان", false, "مليم", "مليمات", false),
        "DZD" to CurrencyInfo("دينار جزائري", "دنانير جزائرية", "ديناران جزائريان", false, "سنتيم", "سنتيمات", false),
        "MAD" to CurrencyInfo("درهم مغربي", "دراهم مغربية", "درهمان مغربيان", false, "سنتيم", "سنتيمات", false),
        "JPY" to CurrencyInfo("ين ياباني", "ين ياباني", "ينان يابانيان", false, "سن", "سنات", false),
        "CNY" to CurrencyInfo("يوان صيني", "يوانات صينية", "يوانان صينيان", false, "فن", "فنات", false),
        "INR" to CurrencyInfo("روبية هندية", "روبيات هندية", "روبيتان هنديتان", true, "بيسة", "بيسات", true),
        "KRW" to CurrencyInfo("وون كوري", "وون كوري", "وونان كوريان", false, "جون", "جونات", false),
        "RUB" to CurrencyInfo("روبل روسي", "روبلات روسية", "روبلان روسيان", false, "كوبيك", "كوبيكات", false)
    )

    // أنماط كود العملة
    private val PATTERN_CURRENCY_CODE = Pattern.compile(
        """\b(USD|EUR|GBP|SAR|AED|KWD|QAR|OMR|BHD|EGP|TND|DZD|MAD|JPY|CNY|INR|KRW|RUB)\s+(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\b"""
    )

    private val CURRENCY_PATTERNS_BEFORE = CURRENCY_SYMBOLS.map { (symbol, info) ->
        Pattern.compile("""${Pattern.quote(symbol)}\s*(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\b""") to info
    }
    private val CURRENCY_PATTERNS_AFTER = CURRENCY_SYMBOLS.map { (symbol, info) ->
        Pattern.compile("""\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*${Pattern.quote(symbol)}""") to info
    }

    // بوابة عدم التطابق: دمج OR صريح لكل أنماط العملة (قبل/بعد/كود). إن لم
    // يطابق شيئاً أُعيد النص كما هو بلا 37 ممراً وتخصيص سلسلة؛ بدائل العملة
    // عربية بلا أرقام فلا يُنشئ استبدالٌ تطابقاً جديداً، فالسلوك مطابق تماماً.
    private val CURRENCY_ANY_PATTERN = Pattern.compile(
        (CURRENCY_PATTERNS_BEFORE + CURRENCY_PATTERNS_AFTER)
            .joinToString("|") { "(" + it.first.pattern() + ")" } +
            "|(" + PATTERN_CURRENCY_CODE.pattern() + ")"
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
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(amountText))
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
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(amountText))
            }
            matcher.appendTail(buffer)
            result = buffer.toString()
        }

        // أكواد العملة: USD 100
        val codeMatcher = PATTERN_CURRENCY_CODE.matcher(result)
        val codeBuffer = StringBuffer()
        while (codeMatcher.find()) {
            val code = codeMatcher.group(1)!!
            val amount = AmountParser.parseAmount(codeMatcher.group(2)!!)
            val info = CURRENCY_CODE_INFO[code]
            if (info == null) {
                val amountText = NumberWordsConverter.numberToWords(amount)
                codeMatcher.appendReplacement(codeBuffer, java.util.regex.Matcher.quoteReplacement("$amountText $code"))
            } else {
                codeMatcher.appendReplacement(codeBuffer, java.util.regex.Matcher.quoteReplacement(currencyAmountPhrase(amount, info)))
            }
        }
        codeMatcher.appendTail(codeBuffer)
        result = codeBuffer.toString()

        return result
    }

    /** نطق مبلغ عملة مع التوافق النحوي الكامل (مفرد/مثنى/جمع/كسور):
     *  1 ← «دولار واحد»، 2 ← «دولاران»، 3–10 ← «ثلاثة دولارات»،
     *  ما فوق ← «خمسة وعشرون دولاراً»، والكسور ← «وخمسون سنتاً». */
    private fun currencyAmountPhrase(amount: Double, info: CurrencyInfo): String {
        val whole = amount.toLong()
        val fracHundredths = if (amount >= 0.0) Math.round((amount - whole) * 100.0).toInt() else 0
        val fracPhrase = currencyFractionPhrase(fracHundredths, info)
        // مبلغ كسري صرف (0.50$) → «خمسون سنت» بلا «و» افتتاحية.
        if (whole == 0L && fracPhrase.isNotEmpty()) return fracPhrase

        val wholePhrase = when {
            whole == 0L -> "صفر ${info.name}"
            whole == 1L -> "${info.name} ${if (info.isFeminine) "واحدة" else "واحد"}"
            whole == 2L -> info.dual
            whole in 3..10 -> "${NumberWordsConverter.unitNumberWord(whole.toInt(), info.isFeminine)} ${info.plural}"
            else -> "${NumberWordsConverter.numberToWords(whole.toDouble())} ${info.name}"
        }
        return if (fracPhrase.isEmpty()) wholePhrase else "$wholePhrase و$fracPhrase"
    }

    /** نطق كسور المبلغ (أجزاء المئة) باسم الوحدة الفرعية:
     *  .01 ← «سنت واحد»، .02 ← «سنتان»، .50 ← «خمسون سنت». */
    private fun currencyFractionPhrase(hundredths: Int, info: CurrencyInfo): String {
        if (hundredths <= 0) return ""
        return when (hundredths) {
            1 -> "${info.subunit} ${if (info.subunitFeminine) "واحدة" else "واحد"}"
            2 -> if (info.subunitFeminine) "${info.subunit}تان" else "${info.subunit}ان"
            in 3..10 -> "${NumberWordsConverter.unitNumberWord(hundredths, info.subunitFeminine)} ${info.subunitPlural}"
            else -> "${NumberWordsConverter.numberToWords(hundredths.toDouble())} ${info.subunit}"
        }
    }
}