package com.aymankhattab.nateq.engine.pipeline

import java.util.regex.Pattern

/**
 * معالجة الأرقام الرومانية: «III» كرقم ساعة → «ثلاثة»، «XIV» → «أربعة عشر».
 * يُتحقق من الصحة النحوية (نقصان/زيادة) قبل التحويل؛ إن كانت متوالية
 * رومانية غير صحيحة (مثل تاريخ «MMXXIV») تُترك كما هي للتواريخ.
 */
internal object RomanNumeralStep : TextProcessingStep {

    // الأرقام الرومانية (ساعات كبند/فصول/قوائم): تُنطق ككلمات أو أرقام عادية.
    // يعترف فقط بالملييئة وإن كانت كبيرة (IVXLCDM) بحدود كلمات حقيقية؛
    // والسياق (مؤشر صريح أو نطاق 1–12) يُقرَّر في المعالجة.
    private val PATTERN_ROMAN = Pattern.compile("""(?<![\p{Alpha}])[IVXLCDM]{1,8}(?![\p{Alpha}])""")

    // مؤشرات صريحة قبل رقم روماني («الفصل III»، «الجزء II») تُرجّح أنه رقم
    // تسلسلي وليس كلمة إنجليزية مكتوبة بحروف رومانية.
    private val ROMAN_INDICATORS = listOf(
        "الفصل", "الجزء", "الباب", "القسم", "المقدمة", "الملحق", "الفقرة",
        "المادة", "السورة", "المجلد",
        "chapter", "part", "section", "volume", "book", "unit", "lesson", "act"
    )

    // كلمات إنجليزية شائعة مكوّنة من حروف رومانية ظاهرياً (I، DID، MIX، MID،
    // CD…) تُستبعد دائماً من تحويل الأرقام الرومانية.
    private val ENGLISH_ROMAN_WORDS = setOf("I", "ID", "DID", "MIX", "MID", "CD")

    override fun apply(input: String): String {
        val matcher = PATTERN_ROMAN.matcher(input)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val rom = matcher.group(0)!!
            val value = romanToInt(rom) ?: run {
                // غير صالح/غير معترف → لا نلمسه (ربما تاريخ أو اختصار)
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(rom))
                continue
            }
            // كلمات إنجليزية شائعة من حروف رومانية (DID/MIX/MID/CD/I) تُستبعد
            // دائماً مهما كان السياق.
            if (rom in ENGLISH_ROMAN_WORDS) {
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(rom))
                continue
            }
            // دون مؤشر صريح لا نُحوّل إلا الأرقام التسلسلية 1–12 (ساعات/قوائم)؛
            // ما عداها تُرك — لا نجعل «الفصل MCMXCV» تفقّد سياقها ولا نجعل كلمة
            // إنجليزية عابرة رقمَ ساعة.
            if (!hasRomanIndicatorBefore(input, matcher.start()) && value !in 1..12) {
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(rom))
                continue
            }
            val spoken = NumberWordsConverter.numberToWords(value.toLong())
            matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(spoken))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /** هل يسبق التطابق مؤشر صريح («الفصل»، «الجزء»، «chapter»…)؟ */
    private fun hasRomanIndicatorBefore(text: String, start: Int): Boolean {
        var before = text.substring(0, start).trimEnd()
        while (before.isNotEmpty() && !before.last().isLetterOrDigit()) before = before.dropLast(1)
        if (before.isEmpty()) return false
        val lower = before.lowercase()
        return ROMAN_INDICATORS.any { ind ->
            val indLower = ind.lowercase()
            if (!lower.endsWith(indLower)) return@any false
            val prefixLen = lower.length - indLower.length
            prefixLen == 0 || !lower[prefixLen - 1].isLetterOrDigit()
        }
    }

    /** تحويل رقم روماني إلى Int، أو null عند تركيبة غير صالحة. */
    private fun romanToInt(s: String): Int? {
        val map = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var prev = 0
        for (c in s.reversed()) {
            val v = map[c] ?: return null
            if (v < prev) total -= v else total += v
            prev = v
        }
        // تحقق من الصحة: لا تكرار لأكثر من 3 لـ I/X/C، ولا 4 لـ V/L/D.
        val repeats = listOf('I', 'X', 'C', 'M').any { ch -> s.filter { it == ch }.length > 3 }
            || listOf('V', 'L', 'D').any { ch -> s.filter { it == ch }.length > 1 }
        // قيمة معقولة كرقم ترتيبي (تجنب تحويل CC/DD/MM التواريخ إلى أرقام)
        if (repeats || total > 3999) return null
        return total
    }
}