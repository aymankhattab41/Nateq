package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.core.engine.PunctuationLevels
import java.util.regex.Pattern

/**
 * نطق علامات الترقيم والرموز وفق مستوى مُختار (لا شيء / البعض / الكل):
 * تُقرأ إعدادات المستوى لحظياً عبر [levelProvider] عند كل تطبيق فلا يلزم
 * إعادة بناء الخطوة عند تغير الإعداد أثناء التشغيل.
 *
 * - «لا شيء»: لا تنطق أسماء رموز الترقيم إطلاقاً (تبقى وقفات طبيعية).
 * - «البعض» (الافتراضي): @ المعزولة (عند/at)، # (رقم/number)، % (بالمئة/
 *   percent)، & (و/and)، و/ (شرطة مائلة/slash) و+ (زائد/plus) و= (يساوي/
 *   equals) المعزولة عن الأرقام — تحافظ على نطق الرموز الشائعة.
 * - «الكل»: يضيف فوق ذلك الأقواس () و[] و{} والفاصلة المنقوطة (؛ و;)
 *   والشرطات (- و– و—) وعلامة النقاط (…) بأسمائها الصريحة.
 *
 * الأسماء تُترجم حسب اللغة: [apply] للعربية و[applyEnglish] للإنجليزية —
 * الأنماط نفسها وكلمات الاستبدال تختلف فقط.
 *
 * تُنفذ هذه الخطوة بعد خطوات الحماية (الروابط/التواريخ/الأوقات/العملات/
 * الوحدات/الهواتف) فترى نصاً نُظّف من تلك السياقات، ولا تمس الحساب
 * بين رقمين («5+3») الذي يبقى حصراً في [SymbolStep].
 */
internal class PunctuationStep(
    private val levelProvider: () -> Int =
        { PunctuationLevels.SOME }
) : TextProcessingStep {

    /** كلمات الاستبدال لعلامات الترقيم في لغةٍ ما. */
    private class Words(
        val at: String,
        val hash: String,
        val percent: String,
        val and: String,
        val slash: String,
        val plus: String,
        val equals: String,
        val openParen: String,
        val closeParen: String,
        val openBracket: String,
        val closeBracket: String,
        val openBrace: String,
        val closeBrace: String,
        val semicolon: String,
        val ellipsis: String,
        val dash: String
    )

    /** أنماط لغةٍ مبنية مرة واحدة (some/all + بوابتا عدم التطابق). */
    private class Sets(
        val some: List<Pair<Pattern, String>>,
        val all: List<Pair<Pattern, String>>,
        val someUnion: Pattern,
        val allUnion: Pattern
    )

    private val arabic = buildSets(
        Words(
            at = " عند ",
            hash = " رقم ",
            percent = " بالمئة ",
            and = " و ",
            slash = " شرطة مائلة ",
            plus = " زائد ",
            equals = " يساوي ",
            openParen = " قوس افتتاح ",
            closeParen = " قوس إقفال ",
            openBracket = " قوس مربع افتتاح ",
            closeBracket = " قوس مربع إقفال ",
            openBrace = " قوس مجعد افتتاح ",
            closeBrace = " قوس مجعد إقفال ",
            semicolon = " فاصلة منقوطة ",
            ellipsis = " نقاط ",
            dash = " شرطة "
        )
    )

    private val english = buildSets(
        Words(
            at = " at ",
            hash = " number ",
            percent = " percent ",
            and = " and ",
            slash = " slash ",
            plus = " plus ",
            equals = " equals ",
            openParen = " open parenthesis ",
            closeParen = " close parenthesis ",
            openBracket = " open bracket ",
            closeBracket = " close bracket ",
            openBrace = " open brace ",
            closeBrace = " close brace ",
            semicolon = " semicolon ",
            ellipsis = " ellipsis ",
            dash = " dash "
        )
    )

    /** يبني أنماط لغةٍ من مفرداتها: «البعض» ثم «الكل» + بوابتا المطابقة. */
    private fun buildSets(w: Words): Sets {
        // @ لا تُنطق داخل بريد إلكتروني (حرف/رقم على طرفيها)، بل فقط
        // حين تكون معزولة (مثل «نلتقي @ 5»).
        val atPattern = Pattern.compile(
            "(?<!\\p{L})(?<![0-9])@(?![0-9])(?!\\p{L})"
        )
        val some = listOf(atPattern to w.at) + listOf(
            "#" to w.hash,
            "&" to w.and,
            "%" to w.percent,
            "٪" to w.percent
        ).map { (symbol, word) ->
            Pattern.compile(Pattern.quote(symbol)) to word
        } + listOf(
            "/" to w.slash,
            "+" to w.plus,
            "=" to w.equals
        ).map { (symbol, word) ->
            // المعزولة فقط: الحساب بين رقمين («5/2») من مسؤولية SymbolStep،
            // والروابط تحميها UrlStep سابقاً فلا تصل «//» هنا.
            Pattern.compile("(?<!\\d)\\s*\\Q$symbol\\E\\s*(?!\\d)") to word
        }

        val all = some + listOf(
            "(" to w.openParen,
            ")" to w.closeParen,
            "[" to w.openBracket,
            "]" to w.closeBracket,
            "{" to w.openBrace,
            "}" to w.closeBrace,
            "؛" to w.semicolon,
            ";" to w.semicolon,
            "…" to w.ellipsis
        ).map { (symbol, word) ->
            Pattern.compile(Pattern.quote(symbol)) to word
        } + listOf(
            // **بند 3.7:** الشرطة «-» المتبوعة برقم («-5» وحتى «- 5») تُترك
            // لخطوة الأرقام لتنطق «ناقص خمسة» وليس «شرطة خمسة» — كانت
            // الاستبدال السابق يسبق NumberStep فيفسد قراءة الحساب ودرجات
            // الحرارة. لا يُعوَّض تشكيل التتابع «--» إلا بنطقٍ واحد (لدى
            // المتبوعة رقماً تبقى كما هي للمعالج العددي).
            Pattern.compile("-{1,2}(?!\\s*\\d)") to w.dash,
            Pattern.compile(Pattern.quote("–")) to w.dash,
            Pattern.compile(Pattern.quote("—")) to w.dash
        )

        return Sets(some, all, compileUnion(some), compileUnion(all))
    }

    private fun compileUnion(
        entries: List<Pair<Pattern, String>>
    ): Pattern = Pattern.compile(
        entries.joinToString("|") { "(" + it.first.pattern() + ")" }
    )

    override fun apply(input: String): String = process(input, arabic)

    /** النسخة الإنجليزية: أسماء الرموز إنجليزية (at/number/percent/…). */
    override fun applyEnglish(input: String): String = process(input, english)

    private fun process(input: String, sets: Sets): String {
        val level = levelProvider()
        if (level <= PunctuationLevels.NONE) return input
        val union = if (level >= PunctuationLevels.ALL) {
            sets.allUnion
        } else {
            sets.someUnion
        }
        if (!union.matcher(input).find()) return input
        val entries = if (level >= PunctuationLevels.ALL) {
            sets.all
        } else {
            sets.some
        }
        var result = input
        for ((pattern, replacement) in entries) {
            result = pattern.matcher(result).replaceAll(replacement)
        }
        return result
    }
}
