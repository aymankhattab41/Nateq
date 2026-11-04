package com.aymankhattab.nateq.engine.pipeline

import java.util.regex.Pattern

/**
 * معالجة الاختصارات التقنية الشائعة (تقرير ب.txt بند 2.6-2): تُنطق
 * بنطقها الصوتي العربي المعروف قبل تقسيم اللغة («SMS» ⟶ «إس إم إس»)
 * فلا تقفز الكلمة اللاتينية القصيرة لمحركٍ إنجليزي ثم تعود — إرهاق
 * سمعيًّا وتأخير تبديل لكل اختصار. تُطبَّق خطوةً عربيةً بعد عملة/وحدات
 * (فلا تُكسَر «1500 USD» التي سبق وهضمها [CurrencyStep]) وبحدود كلمات
 * تامة (لا تمس "PDFs" المركّب ولا الصيغ الصغيرة) — اختصاراتٌ مختارة
 * للشهرة وعدم الالتباس دون تمدد انتهازي.
 */
internal object AcronymStep : TextProcessingStep {

    /** الاختصار ⟶ نطقه العربي الشائع. */
    private val ACRONYMS = mapOf(
        "SMS" to "إس إم إس",
        "PDF" to "بي دي إف",
        "RAM" to "رام",
        "OK" to "أو كي",
        "USB" to "يو إس بي",
        "GPS" to "جي بي إس",
        "م.ب." to "ميجابايت",
        "م.ب" to "ميجابايت",
        "ك.ب." to "كيلوبايت",
        "ك.ب" to "كيلوبايت",
        "ج.ب." to "جيجابايت",
        "ج.ب" to "جيجابايت",
        "ت.ب." to "تيرابايت",
        "ت.ب" to "تيرابايت",
        "MB" to "ميجابايت",
        "GB" to "جيجابايت",
        "KB" to "كيلوبايت",
        "TB" to "تيرابايت"
    )

    private val ACRONYM_PATTERNS = ACRONYMS.map { (acronym, _) ->
        // حدود كلمات تامة: تدعم الحروف اللاتينية والعربية والنقاط الختامية
        Pattern.compile(
            """(?<![\p{L}\p{N}_])""" + Pattern.quote(acronym) +
                """(?![\p{L}\p{N}_])"""
        ) to acronym
    }

    private val ACRONYM_ANY_PATTERN = Pattern.compile(
        ACRONYM_PATTERNS.joinToString("|") { "(" + it.first.pattern() + ")" }
    )

    override fun apply(input: String): String {
        if (!ACRONYM_ANY_PATTERN.matcher(input).find()) return input
        var result = input
        for ((pattern, acronym) in ACRONYM_PATTERNS) {
            val replacement = ACRONYMS[acronym]!!
            val matcher = pattern.matcher(result)
            val buffer = StringBuffer()
            while (matcher.find()) {
                matcher.appendReplacement(
                    buffer,
                    java.util.regex.Matcher.quoteReplacement(replacement)
                )
            }
            matcher.appendTail(buffer)
            result = buffer.toString()
        }
        return result
    }
}