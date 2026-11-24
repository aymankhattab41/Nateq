package com.aymankhattab.nateq.engine.pipeline

import java.util.regex.Pattern

/**
 * معالجة الأرقام الرومانية: «III» كرقم ساعة → «ثلاثة»، «XIV» → «أربعة عشر».
 * يُتحقق من الصحة النحوية (نقصان/زيادة) قبل التحويل؛ إن كانت متوالية
 * رومانية غير صحيحة (مثل تاريخ «MMXXIV») تُترك كما هي للتواريخ.
 */
internal object RomanNumeralStep : TextProcessingStep {

    // الأرقام الرومانية (ساعات كبند/فصول/قوائم): تُنطق ككلمات أو أرقام عادية.
    // **بند 3.5:** يعترف بالحروف الصغيرة والكبيرة (الفصل iii / III) بشرط
    // حدود كلمات حقيقية؛ والسياق (مؤشر صريح أو نطاق 1–12) يُقرَّر في المعالجة.
    private val PATTERN_ROMAN = Pattern.compile(
        """(?<![\p{Alpha}])[IVXLCDMivxlcdm]{1,8}(?![\p{Alpha}])"""
    )

    // مؤشرات صريحة قبل رقم روماني («الفصل III»، «الجزء II») تُرجّح أنه رقم
    // تسلسلي وليس كلمة إنجليزية مكتوبة بحروف رومانية.
    private val ROMAN_INDICATORS = listOf(
        "الفصل", "الجزء", "الباب", "القسم", "المقدمة", "الملحق", "الفقرة",
        "المادة", "السورة", "المجلد",
        "chapter", "part", "section", "volume", "book", "unit", "lesson", "act"
    )

    // كلمات إنجليزية شائعة مكوّنة من حروف رومانية ظاهرياً (I، DID، MIX، MID،
    // CD…) تُستبعد عند غياب المؤشر الصريح.
    private val ENGLISH_ROMAN_WORDS =
        setOf("I", "ID", "DID", "MIX", "MID", "CD")

    // نمط التحقق من صحة الرقم الروماني القياسي (قواعد الترتيب والطرح والتكرار)
    private val PATTERN_CANONICAL_ROMAN = Pattern.compile(
        """^M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})$"""
    )

    override fun apply(input: String): String {
        val matcher = PATTERN_ROMAN.matcher(input)
        if (!matcher.find()) return input
        matcher.reset()
        val buffer = StringBuffer()
        while (matcher.find()) {
            val rom = matcher.group(0)!!
            val value = romanToInt(rom) ?: run {
                // غير صالح/غير معترف → لا نلمسه (مثل IL أو IC أو اختصار)
                matcher.appendReplacement(
                    buffer, java.util.regex.Matcher.quoteReplacement(rom)
                )
                continue
            }
            val hasIndicator = hasRomanIndicatorBefore(input, matcher.start())
            val upper = rom.uppercase()

            if (!hasIndicator) {
                // دون مؤشر صريح: كلمات إنجليزية شائعة تُستبعد
                if (upper in ENGLISH_ROMAN_WORDS) {
                    matcher.appendReplacement(
                        buffer, java.util.regex.Matcher.quoteReplacement(rom)
                    )
                    continue
                }
                // ولا نُحوّل إلا متواليات 1–12 غير المفردة (ساعات/قوائم)
                val singleLetter = rom.length == 1
                val ambiguousTwoLetter = rom.length == 2 &&
                    upper in setOf("XI", "VI", "IV", "DI", "MI")
                if (singleLetter || ambiguousTwoLetter || value !in 1..12) {
                    matcher.appendReplacement(
                        buffer, java.util.regex.Matcher.quoteReplacement(rom)
                    )
                    continue
                }
            } else {
                // مع مؤشر صريح (الفصل/chapter): I يُعامل كرقم روماني 1،
                // لكن نستبعد الكلمات التي لا تكون أرقاماً إطلاقاً
                if (upper in setOf("ID", "DID", "MID")) {
                    matcher.appendReplacement(
                        buffer, java.util.regex.Matcher.quoteReplacement(rom)
                    )
                    continue
                }
            }
            val spoken = NumberWordsConverter.numberToWords(value.toLong())
            matcher.appendReplacement(
                buffer, java.util.regex.Matcher.quoteReplacement(spoken)
            )
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /** هل يسبق التطابق مؤشر صريح («الفصل»، «الجزء»، «chapter»…)؟ */
    private fun hasRomanIndicatorBefore(text: String, start: Int): Boolean {
        var before = text.substring(0, start).trimEnd()
        while (before.isNotEmpty() &&
            !before.last().isLetterOrDigit()
        ) before = before.dropLast(1)
        if (before.isEmpty()) return false
        val lower = before.lowercase()
        return ROMAN_INDICATORS.any { ind ->
            val indLower = ind.lowercase()
            if (!lower.endsWith(indLower)) return@any false
            val prefixLen = lower.length - indLower.length
            prefixLen == 0 || !lower[prefixLen - 1].isLetterOrDigit()
        }
    }

    /** تحويل رقم روماني إلى Int، مع التحقق الصارم من قواعد الترتيب والطرح. */
    private fun romanToInt(s: String): Int? {
        if (s.isEmpty()) return null
        val upper = s.uppercase()
        if (!PATTERN_CANONICAL_ROMAN.matcher(upper).matches()) return null

        val map = mapOf(
            'I' to 1, 'V' to 5, 'X' to 10, 'L' to 50,
            'C' to 100, 'D' to 500, 'M' to 1000
        )
        var total = 0
        var prev = 0
        for (c in upper.reversed()) {
            val v = map[c] ?: return null
            if (v < prev) total -= v else total += v
            prev = v
        }
        if (total <= 0 || total > 3999) return null
        return total
    }
}