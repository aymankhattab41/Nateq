package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.NumberSpeech
import java.util.regex.Pattern

/** معالجة الأرقام العادية: 1234 → «ألف ومائتان وأربعة وثلاثون» (عربية)
 *  أو «one thousand two hundred thirty four» (إنجليزية). */
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

    // رموز العملات التي تحمي الأرقام المحيطة بها من النطق الرقمي،
    // مُعرّفة مرة واحدة لا داخل حلقة المطابقات لكل رقم.
    private val CURRENCY_SYMBOLS = setOf('$', '€', '£', '¥', '₹', '₽', '₩', '﷼')

    override fun apply(input: String): String = process(input, english = false)

    /** النسخة الإنجليزية (اللغة الثانية): تُنطق الأعداد كلماتٍ إنجليزية
     *  («one hundred twenty three»)، والكسر العشري «point» رقماً رقماً
     *  («three point one four one») بدل «فاصلة». */
    override fun applyEnglish(input: String): String =
        process(input, english = true)

    private fun process(input: String, english: Boolean): String {
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
            if (before in CURRENCY_SYMBOLS || after in CURRENCY_SYMBOLS) {
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
            sb.append(if (minus) minusWord(english) else "")
            sb.append(parseNumberText(numberStr, english))
            cursor = end
        }
        sb.append(input, cursor, input.length)
        return sb.toString()
    }

    /** كلمة السالب حسب اللغة («ناقص»/«minus»). */
    private fun minusWord(english: Boolean): String =
        if (english) "minus " else "ناقص "

    /**
     * يفصل بين فواصل الآلاف والفاصلة العشرية:
     *  - 1,234 → 1234 (فاصلة آلاف)
     *  - 1,234.56 → 1234.56 (فاصلة آلاف + فاصلة عشرية)
     *  - 3.14 → 3.14 (عشري)
     */
    private fun parseNumberText(numberStr: String, english: Boolean): String {
        // الفصل بين فواصل الآلاف والفاصلة العشرية يتم عبر sanitizeNumerals
        // الذي لا يُهلك الأعداد العشرية ثلاثية الخانات (3.141 تبقى عشرية).
        val cleaned = AmountParser.sanitizeNumerals(numberStr)
        // الأعداد الصحيحة تُحول عبر Long (حتى 19 خانة) للحفاظ على الدقة:
        // Double يتجاوز دقته 2^53 (≈9.007×10^15) فيشوّه البطاقات/الرموز الطويلة
        // (مثل 9999999999999999 التي كانت تنطق «عشرة كوادريليون» خطأً).
        if (cleaned.indexOf('.') < 0) {
            val longValue = cleaned.toLongOrNull()
            if (longValue != null) {
                return if (english) NumberSpeech.toEnglishWords(longValue)
                else NumberWordsConverter.numberToWords(longValue)
            }
            // **بند 3.6:** سلسلة رقمية أعرض من Long (رقم وطني/حساب بنكي/
            // تسلسل 20+ خانة): toLongOrNull تعيد null فكانت تنتقل إلى
            // Double فتتشوه القيمة (دقته 2^53 فقط) — نقرأ الخانات رقماً
            // رقماً بأمان عبر المحوِّل.
            return if (english) englishSpokenDigits(cleaned)
            else NumberWordsConverter.spokenDigits(cleaned)
        }
        if (english) return englishDecimalWords(cleaned)
        val number = cleaned.toDoubleOrNull() ?: return numberStr
        return NumberWordsConverter.numberToWords(number)
    }

    /** كسر عشري إنجليزي: «3.141» → «three point one four one»،
     *  و«0.05» → «zero point zero five» (بلا «فاصلة» العربية). */
    private fun englishDecimalWords(cleaned: String): String {
        val dot = cleaned.indexOf('.')
        val intPart = cleaned.substring(0, dot)
        val fracPart = cleaned.substring(dot + 1)
        val intWords = intPart.toLongOrNull()
            ?.let { NumberSpeech.toEnglishWords(it) }
            ?: englishSpokenDigits(intPart)
        return "$intWords point ${englishSpokenDigits(fracPart)}"
    }

    /** خانات رقمية إنجليزية متتالية («05» → «zero five»). */
    private fun englishSpokenDigits(digits: String): String = digits
        .map { NumberSpeech.toEnglishWords(it.digitToInt()) }
        .joinToString(" ")
}