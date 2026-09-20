package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.NumberSpeech
import java.util.regex.Pattern

/** معالجة الأوقات: 14:30 → «الثانية والنصف ظهراً». */
internal object TimeStep : TextProcessingStep {

    private val PATTERN_TIME = Pattern.compile(
        """(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([AaPp]\.?[Mm]\.?|صباحاً?|مساءً?)?"""
    )

    override fun apply(input: String): String {
        val matcher = PATTERN_TIME.matcher(input)
        if (!matcher.find()) return input
        matcher.reset()
        val buffer = StringBuffer()

        while (matcher.find()) {
            var hour = matcher.group(1)!!.toInt()
            val minute = matcher.group(2)!!.toInt()
            val suffix = matcher.group(4)
            if (!suffix.isNullOrBlank()) {
                val lower = suffix.lowercase(java.util.Locale.ROOT)
                val isPm = lower.contains("p") || lower.contains("م")
                if (isPm) {
                    if (hour < 12) hour += 12
                } else {
                    if (hour == 12) hour = 0
                }
            }
            val timeText = formatTime(hour, minute)
            matcher.appendReplacement(
                buffer, java.util.regex.Matcher.quoteReplacement(timeText)
            )
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /** تنسيق الوقت بالعربية */
    private fun formatTime(hour: Int, minute: Int): String {
        val hour12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        // بند 3.3: الفترة تُحسب من الساعة المعروضة في العبارة (تُقرَّب
        // للأعلى عند الدقائق 45+ بنمط «إلا ربع»): 11:45 «الثانية عشرة
        // إلا ربع ظهراً» لا صباحاً، و00:xx «بعد منتصف الليل».
        val roundedHour = if (minute >= 45) hour + 1 else hour
        val period = when {
            roundedHour == 0 -> "بعد منتصف الليل"
            roundedHour == 12 -> "ظهراً"
            roundedHour <= 11 -> "صباحاً"
            else -> "مساءً"
        }
        // الساعة تُنطق بالصيغة الترتيبية المؤنثة المعرّفة بأل:
        // «الثانية والنصف مساءً» لا «اثنان والنصف مساءً».
        val hourText = NumberSpeech.toOrdinalHourWord(hour12)

        fun minutesPart(count: Int): String = when (count) {
            1 -> "دقيقة واحدة"
            2 -> "دقيقتان"
            in 3..10 -> "${NumberSpeech.toArabicWords(count)} دقائق"
            else -> "${NumberSpeech.toArabicWords(count)} دقيقة"
        }

        // صيغة دقائق سياق «إلا» (منصوبة): «إلا خمس دقائق»،
        // «إلا دقيقة واحدة»، «إلا دقيقتين»
        fun minutesOmissionPart(count: Int): String = when (count) {
            1 -> "دقيقة واحدة"
            2 -> "دقيقتين"
            in 3..10 -> "${NumberSpeech.toArabicWords(count)} دقائق"
            else -> "${NumberSpeech.toArabicWords(count)} دقيقة"
        }

        return when (minute) {
            0 -> "$hourText $period"
            15 -> "$hourText والربع $period"
            30 -> "$hourText والنصف $period"
            45 -> {
                val nextHour = if (hour12 == 12) 1 else hour12 + 1
                val nextHourText = NumberSpeech.toOrdinalHourWord(nextHour)
                "$nextHourText إلا ربع $period"
            }
            in 1..29 -> "$hourText و ${minutesPart(minute)} $period"
            in 31..44 -> "$hourText و ${minutesPart(minute)} $period"
            in 46..59 -> {
                val remaining = 60 - minute
                val nextHour = if (hour12 == 12) 1 else hour12 + 1
                val nextHourText = NumberSpeech.toOrdinalHourWord(nextHour)
                "$nextHourText إلا ${minutesOmissionPart(remaining)} $period"
            }
            else -> "$hourText $period"
        }
    }
}