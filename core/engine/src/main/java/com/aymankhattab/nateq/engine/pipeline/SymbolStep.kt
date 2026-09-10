package com.aymankhattab.nateq.engine.pipeline

import java.util.regex.Pattern

/**
 * معالجة الرموز الشائعة (درجات الحرارة، المقارنات، الحساب بين رقمين…).
 * @/#/٪/&/@ المعزولة انتقلت إلى [PunctuationStep] لأن نطقها يتبع مستوى
 * علامات الترقيم المختار («لا شيء» يمنعها)، بينما بقي هنا كل ما هو
 * دلالي ثابت المعنى دون مستوى قراءة.
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
    // "ملاحظة ناقص هام"، ولا تعارَض كلمة عادية معها). الكسر 1/2 يُنطق
    // «واحد على اثنين» كما في العربية السياقية. (البديل المعزول من هذه
    // الرموز عند غياب رقم على طرفيه أصبح من مسؤولية PunctuationStep).
    private val SYMBOL_ARITHMETIC = mapOf(
        "+" to " زائد ",
        "-" to " ناقص ",
        "×" to " في ",
        "÷" to " على ",
        "/" to " على ",
        "*" to " في ",
        "=" to " يساوي "
    )

    /** أنماط منتهية تجمع الرموز العامة (الأطول أولاً لضمان °C قبل °) ثم
     *  الحسابية المقيدة بين الرقمين. */
    private val SYMBOL_PATTERNS: List<Pair<Pattern, String>> = buildList {
        addAll(
            SYMBOL_NAMES_GENERAL.entries.sortedByDescending { it.key.length }
                .map {
                    Pattern.compile(Pattern.quote(it.key)) to " ${it.value} "
                }
        )
        addAll(
            SYMBOL_ARITHMETIC.map { (op, word) ->
                // \Q..\E لإبعاد الرموز الخاصة (كـ * و /) عن المعنى النمطي
                Pattern.compile("(?<=\\d)\\s*\\Q$op\\E\\s*(?=\\d)") to word
            }
        )
    }

    // بوابة عدم التطابق: دمج OR صريح لجميع أنماط الخطوة. إن لم يطابق شيئاً
    // أُعيد النص كما هو (نفس المرجع) دون ممرّات فردية؛ بدائل الخطوة عربية
    // بلا أرقام/رموز فلا يخلق استبدالٌ تطابقاً لاحقاً جديداً، فالسلوك مطابق.
    private val SYMBOL_ANY_PATTERN = Pattern.compile(
        SYMBOL_PATTERNS
            .joinToString("|") { "(" + it.first.pattern() + ")" }
    )

    override fun apply(input: String): String {
        if (!SYMBOL_ANY_PATTERN.matcher(input).find()) return input
        var result = input
        for ((pattern, replacement) in SYMBOL_PATTERNS) {
            result = pattern.matcher(result).replaceAll(replacement)
        }
        return result
    }
}