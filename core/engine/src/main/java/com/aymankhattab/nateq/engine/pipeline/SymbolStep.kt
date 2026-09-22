package com.aymankhattab.nateq.engine.pipeline

import java.util.regex.Pattern

/**
 * معالجة الرموز الشائعة (درجات الحرارة، المقارنات، الحساب بين رقمين…).
 * @/#/٪/&/@ المعزولة انتقلت إلى [PunctuationStep] لأن نطقها يتبع مستوى
 * علامات الترقيم المختار («لا شيء» يمنعها)، بينما بقي هنا كل ما هو
 * دلالي ثابت المعنى دون مستوى قراءة.
 *
 * الأسماء تُترجم حسب اللغة: [apply] للعربية و[applyEnglish] للإنجليزية
 * (Celsius/greater than/divided by…).
 */
internal object SymbolStep : TextProcessingStep {

    // رموز تُستبدل دائماً (معناها ثابت لا يتبدل بسياق). تُحاط بدائلها في
    // SYMBOL_PATTERNS بمسافات (« بالمئة ») فلا تلتصق الكلمات («خمسونبالمئة»)،
    // ويُضبط التباعد النهائي في CleanupStep (ضم المسافات ثم التقليم).
    private val SYMBOL_NAMES_GENERAL = mapOf(
        "°C" to "درجة مئوية",
        "°F" to "درجة فهرنهايت",
        "°" to "درجة",
        ">" to "أكبر من",
        "<" to "أصغر من",
        "≥" to "أكبر من أو يساوي",
        "≤" to "أصغر من أو يساوي",
        "≠" to "لا يساوي",
        "≈" to "تقريباً",
        "∞" to "ما لا نهاية",
        "√" to "جذر",
        "π" to "باي",
        "|" to "أو",
        "~" to "تقريباً",
        "_" to "شرطة سفلية",
        "\\" to "شرطة مائلة عكسية"
    )

    // رموز حسابية تُستبدل فقط بين رقمين (فلا تتحول "ملاحظة - هام" إلى
    // "ملاحظة ناقص هام"، ولا تعارَض كلمة عادية معها). القسمة «÷» تُنطق
    // «على»؛ أما «/» فلا تُنطق إطلاقاً في العربية وتتولاها ArabicSlashStep.
    private val SYMBOL_ARITHMETIC = mapOf(
        "+" to " زائد ",
        "-" to " ناقص ",
        "×" to " في ",
        "÷" to " على ",
        "*" to " في ",
        "=" to " يساوي "
    )

    /** الأسماء الإنجليزية المقابلة — نفس الرموز بمعانٍ إنجليزية. */
    private val SYMBOL_NAMES_GENERAL_EN = mapOf(
        "°C" to "degrees Celsius",
        "°F" to "degrees Fahrenheit",
        "°" to "degrees",
        ">" to "greater than",
        "<" to "less than",
        "≥" to "greater than or equal to",
        "≤" to "less than or equal to",
        "≠" to "not equal to",
        "≈" to "approximately",
        "∞" to "infinity",
        "√" to "square root",
        "π" to "pi",
        "|" to "or",
        "~" to "approximately",
        "_" to "underscore",
        "\\" to "backslash"
    )

    private val SYMBOL_ARITHMETIC_EN = mapOf(
        "+" to " plus ",
        "-" to " minus ",
        "×" to " times ",
        "÷" to " divided by ",
        "/" to " divided by ",
        "*" to " times ",
        "=" to " equals "
    )

    /** أنماط لغةٍ ومجموعة مطابقة (بوابة «لا تطابق»). */
    private class Compiled(
        val patterns: List<Pair<Pattern, String>>,
        val any: Pattern
    )

    /** أنماط منتهية تجمع الرموز العامة (الأطول أولاً لضمان °C قبل °) ثم
     *  الحسابية المقيدة بين الرقمين. */
    private fun compile(
        names: Map<String, String>,
        arithmetic: Map<String, String>
    ): Compiled {
        val patterns = buildList {
            addAll(
                names.entries.sortedByDescending { it.key.length }
                    .map {
                        Pattern.compile(Pattern.quote(it.key)) to
                            " ${it.value} "
                    }
            )
            addAll(
                arithmetic.map { (op, word) ->
                    // \Q..\E لإبعاد الرموز الخاصة (كـ * و /) عن المعنى النمطي
                    Pattern.compile(
                        "(?<=\\d)\\s*\\Q$op\\E\\s*(?=\\d)"
                    ) to word
                }
            )
        }
        val any = Pattern.compile(
            patterns.joinToString("|") { "(" + it.first.pattern() + ")" }
        )
        return Compiled(patterns, any)
    }

    private val arabic = compile(
        SYMBOL_NAMES_GENERAL, SYMBOL_ARITHMETIC
    )
    private val english = compile(
        SYMBOL_NAMES_GENERAL_EN, SYMBOL_ARITHMETIC_EN
    )

    override fun apply(input: String): String = process(input, arabic)

    /** النسخة الإنجليزية: أسماء الرموز إنجليزية (greater than/plus…). */
    override fun applyEnglish(input: String): String = process(input, english)

    private fun process(input: String, compiled: Compiled): String {
        if (!compiled.any.matcher(input).find()) return input
        var result = input
        for ((pattern, replacement) in compiled.patterns) {
            result = pattern.matcher(result).replaceAll(replacement)
        }
        return result
    }
}
