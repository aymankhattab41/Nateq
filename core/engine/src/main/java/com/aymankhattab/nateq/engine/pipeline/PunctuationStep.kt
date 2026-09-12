package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.core.engine.PunctuationLevels
import java.util.regex.Pattern

/**
 * نطق علامات الترقيم والرموز وفق مستوى مُختار (لا شيء / البعض / الكل):
 * تُقرأ إعدادات المستوى لحظياً عبر [levelProvider] عند كل تطبيق فلا يلزم
 * إعادة بناء الخطوة عند تغير الإعداد أثناء التشغيل.
 *
 * - «لا شيء»: لا تنطق أسماء رموز الترقيم إطلاقاً (تبقى وقفات طبيعية).
 * - «البعض» (الافتراضي): @ المعزولة (عند)، # (رقم)، % (بالمئة)، & (و)،
 *   و/ (شرطة مائلة) و+ (زائد) و= (يساوي) المعزولة عن الأرقام — تحافظ على
 *   نطق الرموز الشائعة كما كان في سلسلة LORD السابقة.
 * - «الكل»: يضيف فوق ذلك الأقواس () و[] و{} والفاصلة المنقوطة (؛ و;)
 *   والشرطات (- و– و—) وعلامة النقاط (…) بأسمائها الصريحة.
 *
 * تُنفذ هذه الخطوة بعد خطوات الحماية (الروابط/التواريخ/الأوقات/العملات/
 * الوحدات/الهواتف) فترى نصاً نُظّف من تلك السياقات، ولا تمس الحساب
 * بين رقمين («5+3») الذي يبقى حصراً في [SymbolStep].
 */
internal class PunctuationStep(
    private val levelProvider: () -> Int =
        { PunctuationLevels.SOME }
) : TextProcessingStep {

    /** الرموز المُنطوقة عند مستوى «البعض» — الأنماط الأطول أولاً. */
    private val someEntries: List<Pair<Pattern, String>> = listOf(
        "@" to " عند "
    ).flatMap { (symbol, word) ->
        // @ لا تُنطق داخل بريد إلكتروني (حرف/رقم على طرفيها)، بل فقط
        // حين تكون معزولة (مثل «نلتقي @ 5»).
        if (symbol == "@") {
            listOf(
                Pattern.compile(
                    "(?<!\\p{L})(?<![0-9])@(?![0-9])(?!\\p{L})"
                ) to word
            )
        } else {
            listOf(Pattern.compile(Pattern.quote(symbol)) to word)
        }
    } + listOf(
        "#" to " رقم ",
        "&" to " و ",
        "%" to " بالمئة ",
        "٪" to " بالمئة "
    ).map { (symbol, word) ->
        Pattern.compile(Pattern.quote(symbol)) to word
    } + listOf(
        "/" to " شرطة مائلة ",
        "+" to " زائد ",
        "=" to " يساوي "
    ).map { (symbol, word) ->
        // المعزولة فقط: الحساب بين رقمين («5/2») من مسؤولية SymbolStep،
        // والروابط تحميها UrlStep سابقاً فلا تصل «//» هنا.
        Pattern.compile("(?<!\\d)\\s*\\Q$symbol\\E\\s*(?!\\d)") to word
    }

    /** الإضافات عند مستوى «الكل» فوق أنماط «البعض». */
    private val allEntries: List<Pair<Pattern, String>> = listOf(
        "(" to " قوس افتتاح ",
        ")" to " قوس إقفال ",
        "[" to " قوس مربع افتتاح ",
        "]" to " قوس مربع إقفال ",
        "{" to " قوس مجعد افتتاح ",
        "}" to " قوس مجعد إقفال ",
        "؛" to " فاصلة منقوطة ",
        ";" to " فاصلة منقوطة ",
        "…" to " نقاط ",
        "-" to " شرطة ",
        "–" to " شرطة ",
        "—" to " شرطة "
    ).map { (symbol, word) ->
        Pattern.compile(Pattern.quote(symbol)) to word
    }

    /** بوابة عدم التطابق للمستوى الحالي:
     * إن لم يطابق شيئاً أُعيد النص كما هو.
     * الأنماط المركّبة تُجمَّع مرة واحدة (lazy) ولا في كل تطبيق — كانت
     * تكلفة compile تُدفع لكل فقرة في المسار الثقيل (تحسين أداء). */
    private val someUnion: Pattern by lazy {
        compileUnion(someEntries)
    }
    private val allUnion: Pattern by lazy {
        compileUnion(someEntries + allEntries)
    }

    private fun compileUnion(
        entries: List<Pair<Pattern, String>>
    ): Pattern = Pattern.compile(
        entries.joinToString("|") { "(" + it.first.pattern() + ")" }
    )

    private fun unionFor(level: Int): Pattern =
        if (level >= PunctuationLevels.ALL) allUnion else someUnion

    override fun apply(input: String): String {
        val level = levelProvider()
        if (level <= PunctuationLevels.NONE) return input
        if (!unionFor(level).matcher(input).find()) return input
        var result = input
        val entries = if (level >= PunctuationLevels.ALL) {
            someEntries + allEntries
        } else {
            someEntries
        }
        for ((pattern, replacement) in entries) {
            result = pattern.matcher(result).replaceAll(replacement)
        }
        return result
    }
}