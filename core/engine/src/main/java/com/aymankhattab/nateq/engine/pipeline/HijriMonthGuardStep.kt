package com.aymankhattab.nateq.engine.pipeline

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * حماية أسماء الأشهر الهجرية:
 * تُحجب أسماء الأشهر الهجرية المشكولة وغير المشكولة قبل خطوات الدلالة
 * برمز \uE040<رقم>\uE041 لمنع التباس حروفها برموز العملات والوحدات
 * (مثل «13 جُمَادَى» التي كانت تلتقط «13 ج» كجنيه)، وتُستعاد في ختام
 * المعالجة بتشكيلها الأصلي الكامل.
 */
internal object HijriMonthGuardStep {

    private const val TOKEN_START = "\uE040"
    private const val TOKEN_END = "\uE041"
    private val PATTERN_UNMASK = Pattern.compile("""\uE040(\d+)\uE041""")

    // بناء تعبير يطابق الكلمة بأي تشكيل مع دعم تنويعات الألف والياء
    private fun letterRegex(c: Char): String = when (c) {
        'ا', 'أ', 'إ', 'آ', 'ٱ' -> "[اأإآٱ][\\p{M}]*"
        'ي', 'ى', 'ئ' -> "[يىئ][\\p{M}]*"
        'ة', 'ه' -> "[ةه][\\p{M}]*"
        'و', 'ؤ' -> "[وؤ][\\p{M}]*"
        ' ' -> "\\s+"
        else -> Pattern.quote(c.toString()) + "[\\p{M}]*"
    }

    private fun wordRegex(word: String): String =
        word.map { letterRegex(it) }.joinToString("")

    // قائمة أسماء الأشهر الهجرية وتفريعاتها الشائعة مرتبة تنازلياً
    private val MONTH_NAMES = listOf(
        // جمادى
        "جمادى الأولى", "جمادى الاولى", "جمادى الأول", "جمادى الاول",
        "جمادى الآخرة", "جمادى الاخرة", "جمادى الاخر", "جمادى الآخر",
        "جمادى الثانية", "جمادى ثانية", "جمادى الثاني", "جمادى ثاني",
        "الجمادى الأولى", "الجمادى الاولى", "الجمادى الآخرة",
        "الجمادى الاخرة", "الجمادى الثانية",
        "جمادى",
        // ربيع
        "ربيع الأول", "ربيع اول", "ربيع الاول",
        "الربيع الأول", "الربيع الاول",
        "ربيع الآخر", "ربيع اخر", "ربيع الاخر",
        "ربيع الثاني", "ربيع ثاني",
        "الربيع الآخر", "الربيع الاخر", "الربيع الثاني",
        "ربيع",
        // ذو القعدة
        "ذو القعدة", "ذي القعدة", "ذا القعدة",
        "ذو القعده", "ذي القعده", "ذا القعده",
        "القعدة", "القعده",
        // ذو الحجة
        "ذو الحجة", "ذي الحجة", "ذا الحجة",
        "ذو الحجه", "ذي الحجه", "ذا الحجه",
        "الحجة", "الحجه",
        // الأشهر المفردة
        "المحرم", "محرم",
        "الصفر", "صفر",
        "الرجب", "رجب",
        "الشعبان", "شعبان",
        "الرمضان", "رمضان",
        "الشوال", "شوال"
    )

    private val PATTERN_HIJRI_MONTHS = Pattern.compile(
        "(?<![\\p{L}\\p{M}])(" +
            MONTH_NAMES.joinToString("|") { wordRegex(it) } +
            ")(?![\\p{L}\\p{M}])"
    )

    fun mask(input: String, store: MutableList<String>): String {
        val matcher = PATTERN_HIJRI_MONTHS.matcher(input)
        if (!matcher.find()) return input
        matcher.reset()
        val buffer = StringBuffer()
        while (matcher.find()) {
            val matchedText = matcher.group()
            store.add(matchedText)
            val token = "$TOKEN_START${store.size - 1}$TOKEN_END"
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(token))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    fun unmask(input: String, store: List<String>): String {
        if (store.isEmpty()) return input
        val matcher = PATTERN_UNMASK.matcher(input)
        if (!matcher.find()) return input
        matcher.reset()
        val buffer = StringBuffer()
        while (matcher.find()) {
            val idx = matcher.group(1)?.toIntOrNull()
            if (idx != null && idx in store.indices) {
                matcher.appendReplacement(
                    buffer,
                    Matcher.quoteReplacement(store[idx])
                )
            }
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }
}
