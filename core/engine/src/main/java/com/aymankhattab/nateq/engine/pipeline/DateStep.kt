package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.NumberSpeech
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * معالجة التواريخ الرقمية: 2024-03-15 → «خمسة عشر مارس ألفان وأربعة وعشرون»،
 * مع دعم صيغ YYYY-MM-DD / DD-MM-YYYY / DD.MM.YYYY بالتقويم الميلادي فقط
 * كمسار وحيد بلا تفرّع.
 */
internal class DateStep(
    private val markNumbers: Boolean = false
) : TextProcessingStep {

    companion object : TextProcessingStep {
        private val defaultInstance = DateStep()
        override fun apply(input: String): String =
            defaultInstance.apply(input)
        override fun applyEnglish(input: String): String =
            defaultInstance.applyEnglish(input)
        override fun apply(input: String, languageTag: String?): String =
            defaultInstance.apply(input, languageTag)
        // أنماط التواريخ: حدود الكلمات أو تأكيدات عدم الالتصاق بحروف/أرقام
        // تمنع التقاط تاريخ داخل متوالية أرقام لاصقة («x12.05.2024y» أو IP).
        val PATTERN_DATE_YMD = Pattern.compile(
            """(?<![\p{L}\p{N}_])(\d{4})[-/.](\d{1,2})[-/.]""" +
                """(\d{1,2})(?![\p{L}\p{N}_])"""
        )
        val PATTERN_DATE_DMY = Pattern.compile(
            """(?<![\p{L}\p{N}_])(\d{1,2})[-/](\d{1,2})[-/]""" +
                """(\d{4})(?![\p{L}\p{N}_])"""
        )
        val PATTERN_DATE_DOTY = Pattern.compile(
            """(?<![\p{L}\p{N}_])(\d{1,2})\.(\d{1,2})\.""" +
                """(\d{4})(?![\p{L}\p{N}_])"""
        )

        // الأشهر الميلادية بالإنجليزية (النسخة الإنجليزية).
        val MONTHS_EN = arrayOf(
            "", "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )

        // الأشهر الميلادية بالعربية.
        val MONTHS_AR = arrayOf(
            "", "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
            "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
        )

        // isYMD=true → group1=year,group2=month,group3=day. العكس للـ DMY/DOTY.
        val DATE_PATTERNS = listOf(
            // YYYY-MM-DD أو YYYY/MM/DD أو YYYY.MM.DD
            PATTERN_DATE_YMD to true,
            PATTERN_DATE_DMY to false,  // DD-MM-YYYY أو DD/MM/YYYY
            PATTERN_DATE_DOTY to false  // DD.MM.YYYY
        )

        fun isLeapYear(year: Int): Boolean =
            (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

        fun maxDaysInMonth(year: Int, month: Int): Int = when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(year)) 29 else 28
            else -> 0
        }

        fun isValidDate(year: Int, month: Int, day: Int): Boolean {
            if (year <= 0 || month !in 1..12) return false
            return day in 1..maxDaysInMonth(year, month)
        }
    }

    override fun apply(input: String): String = process(input, english = false)

    /** النسخة الإنجليزية: «2024-03-15» → «March fifteenth two thousand
     *  twenty four» بالتقويم الميلادي فقط. */
    override fun applyEnglish(input: String): String =
        process(input, english = true)

    private fun process(input: String, english: Boolean): String {
        var result = input
        for ((pattern, isYMD) in DATE_PATTERNS) {
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
                // التحقق من صحة التاريخ الميلادي كاملاً
                // (أيام الشهور والسنة الكبيسة).
                val dayNum = day.toInt()
                val monthNum = month.toInt()
                val yearNum = year.toInt()
                if (!isValidDate(yearNum, monthNum, dayNum)) {
                    matcher.appendReplacement(
                        buffer,
                        Matcher.quoteReplacement(matcher.group(0)!!)
                    )
                    continue
                }
                val dateText = if (english) {
                    formatDateEnglish(dayNum, monthNum, yearNum)
                } else {
                    formatDate(dayNum, monthNum, yearNum)
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
        if (!isValidDate(year, month, day)) return "التاريخ غير صالح"
        val dayText = NumberWordsConverter.numberToWords(
            day.toLong(), markNumbers = markNumbers
        )
        val yearText = NumberWordsConverter.numberToWords(
            year.toLong(), markNumbers = markNumbers
        )
        return "$dayText ${MONTHS_AR[month]} $yearText"
    }

    /** تنسيق التاريخ بالإنجليزية: شهر أولاً ثم اليوم الترتيبي ثم السنة
     *  («March fifteenth two thousand twenty four»). */
    private fun formatDateEnglish(day: Int, month: Int, year: Int): String {
        if (!isValidDate(year, month, day)) return "invalid date"
        val dayText = englishOrdinal(day, markNumbers = markNumbers)
        val yearText = NumberSpeech.toEnglishWords(
            year, markNumbers = markNumbers
        )
        return "${MONTHS_EN[month]} $dayText $yearText"
    }

    /** اليوم بالصيغة الترتيبية («fifteenth»، «twenty first») — يُحوَّل آخر
     *  مكوّن من كلمات العدد الإنجليزية لا تُلحق به اللاحقة عشوائياً
     *  (كان «twenty one» + «st» ينتج «twenty onest»). */
    private fun englishOrdinal(
        day: Int,
        markNumbers: Boolean = false
    ): String {
        val parts = NumberSpeech.toEnglishWords(day, markNumbers = false)
            .split(" ")
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
        val joined = (parts.dropLast(1) + ordinalLast).joinToString(" ")
        return NumberSpeech.wrapNumber(joined, markNumbers)
    }

}