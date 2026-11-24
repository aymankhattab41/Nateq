package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.NumberSpeech
import java.util.regex.Pattern

/** معالجة الأرقام العادية: 1234 → «ألف ومائتان وأربعة وثلاثون» (عربية)
 *  أو «one thousand two hundred thirty four» (إنجليزية). */
internal class NumberStep(
    private val modeProvider: () -> Int = { 1 },
    private val languageProvider: () -> String? = { null },
    private val markNumbers: Boolean = false
) : TextProcessingStep {

    constructor(
        languageProvider: () -> String?
    ) : this(
        modeProvider = { 1 },
        languageProvider = languageProvider,
        markNumbers = false
    )

    companion object : TextProcessingStep {
        private val defaultInstance = NumberStep()
        private val COMMA_REGEX = Regex("""[,،]""")
        override fun apply(input: String): String =
            defaultInstance.apply(input)
        override fun applyEnglish(input: String): String =
            defaultInstance.applyEnglish(input)

        operator fun invoke(
            languageProvider: () -> String?
        ): NumberStep = NumberStep(languageProvider)
    }

    // أنماط الأرقام: حدود واضحة تمنع شطر الأرقام وتسمح بالحرف العربي
    // الملاصق (مثل 50ج أو 5م أو 3أيام) دون الاعتماد على \b (الأمر 3).
    private val PATTERN_NUMBER = Pattern.compile(
        """(?<![\p{N}_])(\d+(?:[.,]\d+)*)(?![\p{N}_])"""
    )

    // عناوين IP: أربع مجموعات من 1–3 أرقام مفصولة بنقاط (192.168.1.1). معرّف
    // شبكة لا مبلغ يُلفظ، فتُترك كما هي كاملةً من دون قراءتها عدّاً.
    private val PATTERN_IPV4 = Pattern.compile("""\d{1,3}(?:\.\d{1,3}){3}""")

    // أرقام الإصدارات (1.6.1 / 1.6.164 / 10.20.300): تُنطق خانةً خانة مع
    // «فاصلة» بين الأجزاء. تدعم أجزاء ذات أطوال مختلفة وسياقات الإصدارات.
    private fun hasVersionContext(input: String, start: Int): Boolean {
        if (start <= 0) return false
        val before = input.substring(0, start).trimEnd()
        val regex = Regex(
            """(?i)(?:version|ver|v|release|build|إصدار|الإصدار|""" +
                """نسخة|النسخة|تحديث|التحديث)[\s:#_-]*$"""
        )
        return regex.containsMatchIn(before)
    }

    private fun isVersion(
        numberStr: String,
        hasVerContext: Boolean
    ): Boolean {
        if (!numberStr.contains('.') || numberStr.contains(',')) return false
        val parts = numberStr.split('.')
        if (parts.size < 2) return false
        if (!parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            return false
        }
        if (hasVerContext) return true
        if (parts.size < 3) return false
        val isThousandsFormat = parts[0].length in 1..3 &&
            parts.drop(1).all { it.length == 3 }
        return !isThousandsFormat
    }

    // رموز العملات التي تحمي الأرقام المحيطة بها من النطق الرقمي،
    // مُعرّفة مرة واحدة لا داخل حلقة المطابقات لكل رقم.
    private val CURRENCY_SYMBOLS = setOf('$', '€', '£', '¥', '₹', '₽', '₩', '﷼')

    override fun apply(input: String): String =
        process(input, isEnglish(defaultForPipeline = false))

    /** النسخة الإنجليزية (اللغة الثانية): تُنطق الأعداد كلماتٍ إنجليزية
     *  («one hundred twenty three»)، والكسر العشري «point» رقماً رقماً
     *  («three point one four one») بدل «فاصلة». */
    override fun applyEnglish(input: String): String =
        process(input, isEnglish(defaultForPipeline = true))

    private fun isEnglish(defaultForPipeline: Boolean): Boolean {
        val configured = languageProvider()
        return if (configured != null) {
            configured == "en"
        } else {
            defaultForPipeline
        }
    }

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
            val preEnd = if (minus) start - 1 else start
            val pre = input.substring(cursor, preEnd)
            // إزالة الفواصل وواو العطف بين الأرقام عربي أو إنجليزي
            val trimmedPre = pre.trim()
            val cleanedPre = if (cursor > 0 &&
                (trimmedPre == "و" || trimmedPre == "," ||
                    trimmedPre == "،" || trimmedPre.equals("and", true))
            ) {
                " "
            } else {
                pre.replace(COMMA_REGEX, " ")
            }
            sb.append(cleanedPre)
            sb.append(if (minus) minusWord(english) else "")
            sb.append(
                parseNumberText(
                    numberStr,
                    english,
                    markNumbers,
                    hasVersionContext(input, start)
                )
            )
            if (end < input.length && isLetterOrMark(input[end])) {
                sb.append(' ')
            }
            cursor = end
        }
        val tail = input.substring(cursor)
        sb.append(tail.replace(COMMA_REGEX, " "))
        return sb.toString()
    }

    private fun isLetterOrMark(c: Char): Boolean =
        c.isLetter() ||
            Character.getType(c) == Character.NON_SPACING_MARK.toInt()

    /** كلمة السالب حسب اللغة («ناقص»/«minus»). */
    private fun minusWord(english: Boolean): String =
        if (english) "minus " else "ناقص "

    /**
     * يفصل بين فواصل الآلاف والفاصلة العشرية:
     *  - 1,234 → 1234 (فاصلة آلاف)
     *  - 1,234.56 → 1234.56 (فاصلة آلاف + فاصلة عشرية)
     *  - 3.14 → 3.14 (عشري)
     */
    private fun parseNumberText(
        numberStr: String,
        english: Boolean,
        markNumbers: Boolean = false,
        hasVerContext: Boolean = false
    ): String {
        // **رقم إصدار قبل أي تطبيع:** يُعامل خانةً خانة أو كأجزاء منفصلة.
        if (isVersion(numberStr, hasVerContext)) {
            return versionWords(numberStr, english, markNumbers)
        }
        // الفصل بين فواصل الآلاف والفاصلة العشرية يتم عبر sanitizeNumerals
        // الذي لا يُهلك الأعداد العشرية ثلاثية الخانات (3.141 تبقى عشرية).
        val cleaned = AmountParser.sanitizeNumerals(numberStr)
        // الأعداد الصحيحة تُحول عبر Long (حتى 19 خانة) للحفاظ على الدقة:
        // Double يتجاوز دقته 2^53 (≈9.007×10^15) فيشوّه البطاقات/الرموز الطويلة
        // (مثل 9999999999999999 التي كانت تنطق «عشرة كوادريليون» خطأً).
        if (cleaned.indexOf('.') < 0) {
            // أرقام تبدأ بـ 0 ولها 7 إلى 15 خانة تُعامل حسب نمط قراءة الأرقام
            if (cleaned.startsWith("0") && cleaned.length in 7..15) {
                val mode = modeProvider().coerceIn(1, 8)
                return NumberSpeech.formatByMode(
                    mode, cleaned, english, markNumbers
                )
            }
            val longValue = cleaned.toLongOrNull()
            if (longValue != null) {
                return if (english) {
                    NumberSpeech.toEnglishWords(
                        longValue, markNumbers = markNumbers
                    )
                } else {
                    NumberWordsConverter.numberToWords(
                        longValue, markNumbers = markNumbers
                    )
                }
            }
            // **بند 3.6:** سلسلة رقمية أعرض من Long (رقم وطني/حساب بنكي/
            // تسلسل 20+ خانة): toLongOrNull تعيد null فكانت تنتقل إلى
            // Double فتتشوه القيمة (دقته 2^53 فقط) — نقرأ الخانات حسب النمط.
            val mode = modeProvider().coerceIn(1, 8)
            return NumberSpeech.formatByMode(
                mode, cleaned, english, markNumbers
            )
        }
        if (english) return englishDecimalWords(cleaned, markNumbers)
        if (cleaned.toDoubleOrNull() == null) {
            return NumberSpeech.wrapNumber(numberStr, markNumbers)
        }
        return NumberWordsConverter.decimalStringToWords(
            cleaned, markNumbers = markNumbers
        )
    }

    /** كسر عشري إنجليزي: «3.141» → «three point one four one»،
     *  و«0.05» → «zero point zero five» (بلا «فاصلة» العربية). */
    private fun englishDecimalWords(
        cleaned: String,
        markNumbers: Boolean = false
    ): String {
        val isNeg = cleaned.startsWith('-') || cleaned.startsWith('\u2212')
        val unsigned = if (isNeg) cleaned.substring(1) else cleaned
        val dot = unsigned.indexOf('.')
        val intPart = unsigned.substring(0, dot)
        val fracPart = unsigned.substring(dot + 1)
        val intWords = intPart.toLongOrNull()
            ?.let { NumberSpeech.toEnglishWords(it, markNumbers = false) }
            ?: englishSpokenDigits(intPart)
        val prefix = if (isNeg) "minus " else ""
        val result = "$prefix$intWords point ${englishSpokenDigits(fracPart)}"
        return NumberSpeech.wrapNumber(result, markNumbers)
    }

    /** خانات رقمية إنجليزية متتالية («05» → «zero five»). */
    private fun englishSpokenDigits(digits: String): String = digits
        .map { NumberSpeech.toEnglishWords(it.digitToInt()) }
        .joinToString(" ")

    /** رقم إصدار خانةً خانة: «1.6.1» ← «واحد فاصلة ستة فاصلة واحد»،
     *  و«one point six point one» بالإنجليزية. الخانة الواحدة بأولها
     *  تُلفظ «واحد» لا «رقم واحد» لتبقى الصياغة سليمة. */
    private fun versionWords(
        version: String,
        english: Boolean,
        markNumbers: Boolean = false
    ): String {
        val separator = if (english) " point " else " فاصلة "
        val result = version.split('.').joinToString(separator) { part ->
            part.toLongOrNull()?.let {
                if (english) {
                    NumberSpeech.toEnglishWords(it, markNumbers = false)
                } else {
                    NumberWordsConverter.numberToWords(it, markNumbers = false)
                }
            } ?: englishSpokenDigits(part)
        }
        return NumberSpeech.wrapNumber(result, markNumbers)
    }
}