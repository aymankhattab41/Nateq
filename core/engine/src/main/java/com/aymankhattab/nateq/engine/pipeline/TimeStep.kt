package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.NumberSpeech
import java.util.regex.Pattern

/** معالجة الأوقات: 14:30 → «الثانية والنصف ظهراً». */
internal object TimeStep : TextProcessingStep {

    private val PATTERN_TIME = Pattern.compile(
        """(\d{1,2}):(\d{2})(?::(\d{2}))?\s*""" +
            """([AaPp]\.?[Mm]\.?|صباح(?:اً|ا)?|""" +
            """مساء(?:ً|اً|ا)?|ظهر(?:اً|ا)?|[صم](?!\p{L}))?"""
    )

    override fun apply(input: String): String {
        val matcher = PATTERN_TIME.matcher(input)
        if (!matcher.find()) return input
        matcher.reset()
        val buffer = StringBuffer()

        while (matcher.find()) {
            val rawHour = matcher.group(1)!!.toInt()
            val minute = matcher.group(2)!!.toInt()
            val suffix = matcher.group(4)
            val isPm: Boolean? = if (!suffix.isNullOrBlank()) {
                val lower = suffix.lowercase(java.util.Locale.ROOT)
                when {
                    lower.contains("p") ||
                        lower.contains("مساء") ||
                        lower.contains("ظهر") -> true
                    lower.contains("a") ||
                        lower.contains("صباح") -> false
                    lower.startsWith("م") -> true
                    lower.startsWith("ص") -> false
                    else -> null
                }
            } else {
                null
            }
            val timeText = formatTime(rawHour, minute, isPm, suffix)
            matcher.appendReplacement(
                buffer, java.util.regex.Matcher.quoteReplacement(timeText)
            )
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /** تنسيق الوقت بالعربية */
    private fun formatTime(
        rawHour: Int,
        minute: Int,
        isPm: Boolean? = null,
        suffix: String? = null
    ): String {
        // الساعة المعروضة بصيغة 12 ساعة (1..12) مستقلة عن اللاحقة
        val hour12 = when {
            rawHour == 0 || rawHour == 12 -> 12
            rawHour > 12 -> rawHour - 12
            else -> rawHour
        }

        // كلمة الفترة تُشتق حصراً ومباشرة من isPm عند وجود لاحقة،
        // وتُحسب من الساعة المقرّبة للأعلى فقط عند غياب اللاحقة (24 ساعة).
        val period = when {
            isPm == false -> "صباحاً"
            isPm == true -> {
                if (suffix?.contains("ظهر") == true) "ظهراً" else "مساءاً"
            }
            else -> {
                val roundedHour = if (minute >= 45) rawHour + 1 else rawHour
                when {
                    roundedHour == 0 -> "بعد منتصف الليل"
                    roundedHour == 12 -> "ظهراً"
                    roundedHour <= 11 -> "صباحاً"
                    else -> "مساءاً"
                }
            }
        }

        // الساعة تُنطق بالصيغة الترتيبية المؤنثة المعرّفة بأل:
        // «الثانية والنصف مساءاً» لا «اثنان والنصف مساءاً».
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