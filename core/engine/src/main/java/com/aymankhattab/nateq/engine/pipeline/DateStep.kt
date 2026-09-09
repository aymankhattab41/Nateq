package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.core.engine.SynthesisConfig
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
    private val injectedSettings: SynthesisConfig? = null
) : TextProcessingStep {

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

        // أشهر السنة الهجرية (بها 12 شهراً كالميلادية)
        val HIJRI_MONTHS = arrayOf(
            "", "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى",
            "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال",
            "ذو القعدة", "ذو الحجة"
        )
    }

    override fun apply(input: String): String {
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
                val dateText = formatDate(dayNum, monthNum, year.toInt())
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

    /** تنسيق التاريخ بالعربية (ميلادي أو هجري حسب إعداد المستخدم) */
    private fun formatDate(day: Int, month: Int, year: Int): String {
        if (month !in 1..12) return "التاريخ غير صالح"
        if (day !in 1..31) return "التاريخ غير صالح"
        // مسار الهجري: إن فشل التحويل (نادر) نتراجع للصيغة الميلادية الصحيحة
            // ولا نُمرر قيماً ميلادية عبر أسماء الشهور الهجرية (كانت تنتج
            // نطقاً مختلطاً مثل «خمسة عشر محرم 2024»).
            if (runCatching {
                    injectedSettings?.isHijriDateEnabled() == true
                }.getOrDefault(false)
            ) {
                val hijri = runCatching {
                    toHijri(day, month, year)
                }.getOrNull()
                val hijriValid = hijri != null &&
                    hijri.second in 1..12 && hijri.first in 1..30
                if (hijriValid) {
                    val dayHijri = NumberWordsConverter.numberToWords(
                        hijri.first.toLong()
                    )
                    val yearHijri = NumberWordsConverter.numberToWords(
                        hijri.third.toLong()
                    )
                    return "$dayHijri ${HIJRI_MONTHS[hijri.second]} $yearHijri"
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

    /** تحويل تاريخ ميلادي إلى هجري (تقويم أم القرى المدعوم على أندرويد) */
    private fun toHijri(
        day: Int,
        month: Int,
        year: Int
    ): Triple<Int, Int, Int> {
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