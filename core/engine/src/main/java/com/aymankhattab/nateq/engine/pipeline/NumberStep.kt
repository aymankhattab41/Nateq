package com.aymankhattab.nateq.engine.pipeline

import java.util.regex.Pattern

/** معالجة الأرقام العادية: 1234 → «ألف ومائتان وأربعة وثلاثون». */
internal object NumberStep : TextProcessingStep {

    // أنماط الأرقام: «\b» المحيط يضمن التقاط المتوالية الرقمية كاملة (المبالغ
    // الطويلة بلا فواصل مثل 10000000 تُنطق «عشرة ملايين») ويمنع شطرها
    // إلى مجموعات ثلاثية، ويجبر النمط على توسّع واحد يغطي الكسور ذات الخانات
    // الثلاث (3.14159 تُسلم للعشرية كاملة) وسلسلة النقاط كاملة — مثل عنوان
    // IP (192.168.1.1) — ليكتشفها الحارس أدناه بحدودها بدل شطرها «192.168.1».
    private val PATTERN_NUMBER = Pattern.compile("""\b(\d+(?:[.,]\d+)*)\b""")

    // عناوين IP: أربع مجموعات من 1–3 أرقام مفصولة بنقاط (192.168.1.1). معرّف
    // شبكة لا مبلغ يُلفظ، فتُترك كما هي كاملةً من دون قراءتها عدّاً.
    private val PATTERN_IPV4 = Pattern.compile("""\d{1,3}(?:\.\d{1,3}){3}""")

    override fun apply(input: String): String {
        val matcher = PATTERN_NUMBER.matcher(input)
        val sb = StringBuilder(input.length + 32)
        var cursor = 0

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
                sb.append(input, cursor, end)
                cursor = end
                continue
            }
            if (before == ':' || after == ':') {
                sb.append(input, cursor, end)
                cursor = end
                continue
            }

            // عناوين IP تُترك كما هي دون نطق: كان «192.168.1.1» يُلتقط شطراً
            // ويُقرأ «مائة واثنان وتسعون ألفاً ومائة وثمانية وستون فاصلة».
            if (PATTERN_IPV4.matcher(numberStr).matches()) {
                sb.append(input, cursor, end)
                cursor = end
                continue
            }

            // سالب ملتصق ببداية العدد منفصلاً عمّا قبله («-1.5» و«التخفيض -5»)
            // يُنطق «ناقص …» بدل ترك «-» عائمة أمام العدد المنطوق؛ والمحوِّل
            // يدعم الأعداد السالبة مباشرةً (بند 15). أما «x-5» المتلاصقة بحرف
            // فتُترك كما كانت (ليست عدداً سالباً لغوياً).
            val minus = start > 0 && input[start - 1] == '-' &&
                (start == 1 || !input[start - 2].isLetterOrDigit())
            sb.append(input, cursor, if (minus) start - 1 else start)
            sb.append(if (minus) "ناقص " else "")
            sb.append(parseNumberText(numberStr))
            cursor = end
        }
        sb.append(input, cursor, input.length)
        return sb.toString()
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
        // الأعداد الصحيحة تُحول عبر Long (حتى 19 خانة) للحفاظ على الدقة:
        // Double يتجاوز دقته 2^53 (≈9.007×10^15) فيشوّه البطاقات/الرموز الطويلة
        // (مثل 9999999999999999 التي كانت تنطق «عشرة كوادريليون» خطأً).
        if (cleaned.indexOf('.') < 0) {
            val longValue = cleaned.toLongOrNull()
            if (longValue != null) {
                return NumberWordsConverter.numberToWords(longValue)
            }
        }
        val number = cleaned.toDoubleOrNull() ?: return numberStr
        return NumberWordsConverter.numberToWords(number)
    }
}