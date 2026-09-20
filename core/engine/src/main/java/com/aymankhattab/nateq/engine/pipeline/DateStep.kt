package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.NumberSpeech
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * معالجة التواريخ الرقمية: 2024-03-15 → «خمسة عشر مارس ألفان وأربعة وعشرون»،
 * مع دعم صيغ YYYY-MM-DD / DD-MM-YYYY / DD.MM.YYYY بالتقويم الميلادي فقط
 * كمسار وحيد بلا تفرّع.
 */
internal class DateStep : TextProcessingStep {

    private companion object {
        // أنماط التواريخ: حدود الكلمات (\\b) في الطرفين تمنع التقاط تاريخ
        // داخل متوالية أرقام لاصقة («x12.05.2024y» أو نهاية عنوان IP).
        val PATTERN_DATE_YMD = Pattern.compile(
            """\b(\d{4})[-/](\d{1,2})[-/](\d{1,2})\b"""
        )
        val PATTERN_DATE_DMY = Pattern.compile(
            """\b(\d{1,2})[-/](\d{1,2})[-/](\d{4})\b"""
        )
        val PATTERN_DATE_DOTY = Pattern.compile(
            """\b(\d{1,2})\.(\d{1,2})\.(\d{4})\b"""
        )

        // الأشهر الميلادية بالإنجليزية (النسخة الإنجليزية).
        val MONTHS_EN = arrayOf(
            "", "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )
    }

    override fun apply(input: String): String = process(input, english = false)

    /** النسخة الإنجليزية: «2024-03-15» → «March fifteenth two thousand
     *  twenty four» بالتقويم الميلادي فقط. */
    override fun applyEnglish(input: String): String =
        process(input, english = true)

    private fun process(input: String, english: Boolean): String {
        // isYMD=true → group1=year,group2=month,group3=day. العكس للـ DMY/DOTY.
        val patterns = listOf(
            PATTERN_DATE_YMD to true,   // YYYY-MM-DD أو YYYY/MM/DD
            PATTERN_DATE_DMY to false,  // DD-MM-YYYY أو DD/MM/YYYY
            PATTERN_DATE_DOTY to false  // DD.MM.YYYY
        )

        var result = input
        for ((pattern, isYMD) in patterns) {
            val matcher = pattern.matcher(result)
            val buffer = StringBuffer()
            while (matcher.find()) {
                val day: String
                val month: String
                val year: String
                if (isYMD) {
                    year = matcher.group(1)!!
                    month = matcher.group(2)!!
                    day = matcher.group(3)!!
                } else {
                    day = matcher.group(1)!!
                    month = matcher.group(2)!!
                    year = matcher.group(3)!!
                }
                // حماية من تاريخ شاذ (شهر 13+ أو يوم 32+) تُسقط مصفوفة الأشهر.
                val dayNum = day.toInt()
                val monthNum = month.toInt()
                if (monthNum !in 1..12 || dayNum !in 1..31) {
                    matcher.appendReplacement(
                        buffer,
                        Matcher.quoteReplacement(matcher.group(0)!!)
                    )
                    continue
                }
                val dateText = if (english) {
                    formatDateEnglish(dayNum, monthNum, year.toInt())
                } else {
                    formatDate(dayNum, monthNum, year.toInt())
                }
                matcher.appendReplacement(
                    buffer,
                    Matcher.quoteReplacement(dateText)
                )
            }
            matcher.appendTail(buffer)
            result = buffer.toString()
        }
        return result
    }

    /** تنسيق التاريخ بالعربية بالتقويم الميلادي فقط. */
    private fun formatDate(day: Int, month: Int, year: Int): String {
        if (month !in 1..12) return "التاريخ غير صالح"
        if (day !in 1..31) return "التاريخ غير صالح"
        val months = arrayOf(
            "", "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
            "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
        )
        val dayText = NumberWordsConverter.numberToWords(day.toLong())
        val yearText = NumberWordsConverter.numberToWords(year.toLong())
        return "$dayText ${months[month]} $yearText"
    }

    /** تنسيق التاريخ بالإنجليزية: شهر أولاً ثم اليوم الترتيبي ثم السنة
     *  («March fifteenth two thousand twenty four»). */
    private fun formatDateEnglish(day: Int, month: Int, year: Int): String {
        if (month !in 1..12 || day !in 1..31) return "invalid date"
        val dayText = englishOrdinal(day)
        val yearText = NumberSpeech.toEnglishWords(year)
        return "${MONTHS_EN[month]} $dayText $yearText"
    }

    /** اليوم بالصيغة الترتيبية («fifteenth»، «twenty first») — يُحوَّل آخر
     *  مكوّن من كلمات العدد الإنجليزية لا تُلحق به اللاحقة عشوائياً
     *  (كان «twenty one» + «st» ينتج «twenty onest»). */
    private fun englishOrdinal(day: Int): String {
        val parts = NumberSpeech.toEnglishWords(day).split(" ")
        val last = parts.last()
        val ordinalLast = when (last) {
            "one" -> "first"
            "two" -> "second"
            "three" -> "third"
            "five" -> "fifth"
            "eight" -> "eighth"
            "nine" -> "ninth"
            "twelve" -> "twelfth"
            "twenty" -> "twentieth"
            "thirty" -> "thirtieth"
            "forty" -> "fortieth"
            "fifty" -> "fiftieth"
            "sixty" -> "sixtieth"
            "seventy" -> "seventieth"
            "eighty" -> "eightieth"
            "ninety" -> "ninetieth"
            else -> "${last}th"
        }
        return (parts.dropLast(1) + ordinalLast).joinToString(" ")
    }

}