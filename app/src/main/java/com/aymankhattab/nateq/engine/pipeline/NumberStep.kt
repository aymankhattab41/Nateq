package com.aymankhattab.nateq.engine.pipeline

import java.util.regex.Pattern

/** معالجة الأرقام العادية: 1234 → «ألف ومائتان وأربعة وثلاثون». */
internal object NumberStep : TextProcessingStep {

    // أنماط الأرقام: «\b» المحيط يضمن التقاط المتوالية الرقمية كاملة (المبالغ
    // الطويلة بلا فواصل مثل 10000000 تُنطق «عشرة ملايين») ويمنع شطرها
    // إلى مجموعات ثلاثية، ويُجبر التوسّع ليتجاوز الكسور ذات الخانات الثلاث
    // (3.14159 تُسلم للعشرية كاملة بدل اقتطاع «3.141»).
    private val PATTERN_NUMBER = Pattern.compile("""\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\b""")

    override fun apply(input: String): String {
        val matcher = PATTERN_NUMBER.matcher(input)
        val buffer = StringBuffer()

        while (matcher.find()) {
            val numberStr = matcher.group(1)!!
            // تجاهل الأرقام التي جزء من تاريخ/وقت/عملة تمت معالجتها
            val start = matcher.start()
            val end = matcher.end()
            val before = if (start > 0) input[start - 1] else ' '
            val after = if (end < input.length) input[end] else ' '

            // إذا محاط برموز عملة أو وقت، تخطيه
            val currencySymbols = setOf('$', '€', '£', '¥', '₹', '₽', '₩', '﷼')
            if (before in currencySymbols || after in currencySymbols) {
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(numberStr))
                continue
            }
            if (before == ':' || after == ':') {
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(numberStr))
                continue
            }

            val numberText = parseNumberText(numberStr)
            matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(numberText))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /**
     * يفصل بين فواصل الآلاف والفاصلة العشرية:
     *  - 1,234 → 1234 (فاصلة آلاف)
     *  - 1,234.56 → 1234.56 (فاصلة آلاف + فاصلة عشرية)
     *  - 3.14 → 3.14 (عشري)
     */
    private fun parseNumberText(numberStr: String): String {
        // الفصل بين فواصل الآلاف والفاصلة العشرية يتم عبر sanitizeNumerals
        // الذي لا يُهلك الأعداد العشرية ثلاثية الخانات (3.141 تبقى عشرية).
        val cleaned = AmountParser.sanitizeNumerals(numberStr)
        val number = cleaned.toDoubleOrNull() ?: return numberStr
        return NumberWordsConverter.numberToWords(number)
    }
}