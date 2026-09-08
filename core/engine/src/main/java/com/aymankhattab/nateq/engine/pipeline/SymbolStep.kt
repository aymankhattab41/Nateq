package com.aymankhattab.nateq.engine.pipeline

import java.util.regex.Pattern

/** معالجة الرموز الشائعة (بالمئة، النسبة، العملية الحسابية، @ المعزولة…). */
internal object SymbolStep : TextProcessingStep {

    // رموز تُستبدل دائماً (معناها ثابت لا يتبدل بسياق):
    private val SYMBOL_NAMES_GENERAL = mapOf(
        "%" to "بالمئة",
        "٪" to "بالمئة",
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
        "#" to "رقم",
        "&" to "و",
        "|" to "أو",
        "~" to "تقريباً",
        "_" to "شرطة سفلية",
        "\\" to "شرطة مائلة عكسية"
    )

    // رموز حسابية تُستبدل فقط بين رقمين (فلا تتحول "ملاحظة - هام" إلى
    // "ملاحظة ناقص هام"، ولا تعارَض كلمة عادية معها). الكسر 1/2 يُنطق
    // «واحد على اثنين» كما في العربية السياقية.
    private val SYMBOL_ARITHMETIC = mapOf(
        "+" to " زائد ",
        "-" to " ناقص ",
        "×" to " في ",
        "÷" to " على ",
        "/" to " على ",
        "*" to " في ",
        "=" to " يساوي "
    )

    // @: لا تُستبدل داخل بريد إلكتروني (حرف/رقم على طرفيها)، بل فقط
    // حين تكون معزولة (مثل "نلتقي @ 5").
    private val PATTERN_AT = Pattern.compile("(?<!\\p{L})(?<![0-9])@(?![0-9])(?!\\p{L})")

    /** أنماط منتهية تجمع الرموز العامة (الأطول أولاً لضمان °C قبل °) ثم
     *  الحسابية المقيدة بين الرقمين ثم @ المعزولة. */
    private val SYMBOL_PATTERNS: List<Pair<Pattern, String>> = buildList {
        addAll(
            SYMBOL_NAMES_GENERAL.entries.sortedByDescending { it.key.length }
                .map { Pattern.compile(Pattern.quote(it.key)) to it.value }
        )
        addAll(
            SYMBOL_ARITHMETIC.map { (op, word) ->
                // لاحظ: \Q..\E لإبعاد الرموز الخاصة (بما فيها * و /) عن المعنى النمطي.
                Pattern.compile("(?<=\\d)\\s*\\Q$op\\E\\s*(?=\\d)") to word
            }
        )
        add(PATTERN_AT to " عند ")
    }

    override fun apply(input: String): String {
        var result = input
        for ((pattern, replacement) in SYMBOL_PATTERNS) {
            result = pattern.matcher(result).replaceAll(replacement)
        }
        return result
    }
}