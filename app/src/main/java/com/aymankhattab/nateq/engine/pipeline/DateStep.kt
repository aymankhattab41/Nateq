package com.aymankhattab.nateq.engine.pipeline

import android.content.Context
import com.aymankhattab.nateq.settings.SettingsRepository
import java.util.Calendar
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * معالجة التواريخ الرقمية: 2024-03-15 → «خمسة عشر مارس ألفان وأربعة وعشرون»،
 * مع دعم صيغ YYYY-MM-DD / DD-MM-YYYY / DD.MM.YYYY والتحويل للهجري (أم القرى)
 * حسب تفضيل المستخدم إن وُجدت حقنة الإعدادات (قراءة لحظية لا أكثر).
 */
internal class DateStep(
    private val context: Context,
    private val injectedSettings: SettingsRepository? = null
) : TextProcessingStep {

    private companion object {
        // أنماط التواريخ
        val PATTERN_DATE_YMD = Pattern.compile("""(\d{4})[-/](\d{1,2})[-/](\d{1,2})""")
        val PATTERN_DATE_DMY = Pattern.compile("""(\d{1,2})[-/](\d{1,2})[-/](\d{4})""")
        val PATTERN_DATE_DOTY = Pattern.compile("""(\d{1,2})\.(\d{1,2})\.(\d{4})""")

        // أشهر السنة الهجرية (بها 12 شهراً كالميلادية)
        val HIJRI_MONTHS = arrayOf(
            "", "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى",
            "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"
        )
    }

    override fun apply(input: String): String {
        // isYMD=true → group1=year,group2=month,group3=day | isYMD=false → group1=day,group2=month,group3=year
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
                // حماية من تاريخ شاذ (شهر 13-99 أو يوم 32+) التي تُسقط `months[month]`
                val dayNum = day.toInt()
                val monthNum = month.toInt()
                if (monthNum !in 1..12 || dayNum !in 1..31) {
                    matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(0)!!))
                    continue
                }
                val dateText = formatDate(dayNum, monthNum, year.toInt())
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(dateText))
            }
            matcher.appendTail(buffer)
            result = buffer.toString()
        }
        return result
    }

    /** تنسيق التاريخ بالعربية (ميلادي أو هجري حسب إعداد المستخدم) */
    private fun formatDate(day: Int, month: Int, year: Int): String {
        if (month !in 1..12) return "التاريخ غير صالح"
        if (day !in 1..31) return "التاريخ غير صالح"
        // مسار الهجري: إن فشل التحويل (نادر) نتراجع للصيغة الميلادية الصحيحة
        // ولا نُمرر قيماً ميلادية عبر أسماء الشهور الهجرية (كان ينتج نطقاً مختلطاً
        // مثل «خمسة عشر محرم 2024»).
        if (runCatching {
                (injectedSettings ?: SettingsRepository(context)).isHijriDateEnabled()
            }.getOrDefault(false)
        ) {
            val hijri = runCatching { toHijri(day, month, year) }.getOrNull()
            if (hijri != null && hijri.second in 1..12 && hijri.first in 1..30) {
                return "${NumberWordsConverter.numberToWords(hijri.first.toLong())} ${HIJRI_MONTHS[hijri.second]} " +
                    "${NumberWordsConverter.numberToWords(hijri.third.toLong())}"
            }
        }
        val months = arrayOf(
            "", "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
            "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
        )
        val dayText = NumberWordsConverter.numberToWords(day.toLong())
        val yearText = NumberWordsConverter.numberToWords(year.toLong())
        return "$dayText ${months[month]} $yearText"
    }

    /** تحويل تاريخ ميلادي إلى هجري (تقويم أم القرى المدعوم من أندرويد 7 فما فوق) */
    private fun toHijri(day: Int, month: Int, year: Int): Triple<Int, Int, Int> {
        val gregorian = Calendar.getInstance(Locale.US).apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }
        val hijri = android.icu.util.IslamicCalendar.getInstance(
            android.icu.util.TimeZone.getDefault(),
            // وسم تقويم أم القرى: العلامة تحدد نمط الحساب تلقائياً داخل ICU
            android.icu.util.ULocale("en_US@calendar=islamic-umalqura")
        ).apply {
            time = gregorian.time
        }
        return Triple(
            hijri.get(android.icu.util.Calendar.DAY_OF_MONTH),
            hijri.get(android.icu.util.Calendar.MONTH) + 1,
            hijri.get(android.icu.util.Calendar.YEAR)
        )
    }
}