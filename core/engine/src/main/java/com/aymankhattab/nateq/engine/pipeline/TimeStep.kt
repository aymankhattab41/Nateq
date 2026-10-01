package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.NumberSpeech
import java.util.regex.Pattern

/** معالجة الأوقات: 14:30 → «الثانية والنصف ظهراً». */
internal object TimeStep : TextProcessingStep {

    private val PATTERN_TIME = Pattern.compile(
        """(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*""" +
            """([AaPp]\.?[Mm]\.?|صباح(?:اً|ا)?|""" +
            """مساء(?:اً|ً|ا)?|ظهر(?:اً|ا)?|صم|""" +
            """(?<!\p{L})[صم](?!\p{L})))?"""
    )

    /**
     * وقتٌ بلا دقائق مع لاحقة ص/م — «الساعة 8 م»، «الساعة ٤ص».
     *
     * الصيغة الكاملة (الساعة:الدقيقة) مغطّاة بـ [PATTERN_TIME]، وهذه
     * تغطي الصيغة الثانية الشائعة في الرسائل والإشعارات: ساعةٌ مجرّدة
     * يتبعها حرف الفترة («الساعة 8 م») — فكانت تمرّ خاماً فيُنطق الحرف
     * «صاد» أو «ماء» حرفاً، أو تُقرأ «8 م» مسافةَ متر («ثمانية أمتار»).
     *
     * **حمايةُ وحدات القياس:** النمط يشترط أن يكون ما قبل الرقم فيه
     * كلمةُ وقتٍ صريحة («الساعة»/«الساعة الآن») أو ألصاقَ الحرف بالرقم
     * («8م» بلا مسافة — وهي صيغةُ الوقت لا صيغةُ المتر)، ويشترط أن
     * تكون الساعة 1..12 (صيغة 12 ساعة بSuffix ص/م). فتبقى «50 م» متراً
     * و«10 م» ساقياً كما هي (10ام = 10 صباحاً لا عشرة أمتار).
     */
    private val PATTERN_HOUR_SUFFIX = Pattern.compile(
        """(الساعة(?:\s+الآن)?\s+)?""" +
            """(?<![\p{L}\p{N}:.،])(\d{1,2})(\s*)""" +
            """(صم|(?<!\p{L})[صم](?!\p{L}))(?![\p{L}\p{N}])"""
    )

    override fun apply(input: String): String {
        return replaceTime(input) { match ->
            val rawHour = match.group(1)!!.toInt()
            val minute = match.group(2)!!.toInt()
            val secondsGroup = match.group(3)
            val seconds = secondsGroup?.toIntOrNull()
            val validSeconds = secondsGroup == null ||
                (seconds != null && seconds in 0..59)
            if (rawHour !in 0..23 || minute !in 0..59 || !validSeconds) {
                null
            } else {
                val suffix = match.group(4)
                formatTime(
                    rawHour, minute, seconds,
                    periodFromSuffix(suffix), suffix
                )
            }
        }.let { replaceHourSuffix(it) }
    }

    /**
     * يطبّق [PATTERN_TIME] على النص: كل مطابقة تُحوَّل بـ [transform]،
     * والإ>null يُبقي النص كما هو.
     */
    private inline fun replaceTime(
        input: String,
        transform: (java.util.regex.Matcher) -> String?
    ): String {
        val matcher = PATTERN_TIME.matcher(input)
        if (!matcher.find()) return input
        matcher.reset()
        val buffer = StringBuffer()
        while (matcher.find()) {
            val replaced = transform(matcher)
            if (replaced == null) continue
            matcher.appendReplacement(
                buffer, java.util.regex.Matcher.quoteReplacement(replaced)
            )
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /**
     * يطبّق [PATTERN_HOUR_SUFFIX] — الساعة المجرّدة بلاحقتها.
     * يتحقق في الكود من شرطي الصيغة (ساعة 1..12 + كلمةُ وقتٍ أو لصاق).
     */
    private fun replaceHourSuffix(input: String): String {
        val matcher = PATTERN_HOUR_SUFFIX.matcher(input)
        if (!matcher.find()) return input
        matcher.reset()
        val buffer = StringBuffer()
        while (matcher.find()) {
            val anchor = matcher.group(1)
            val hour = matcher.group(2)!!.toInt()
            val spaced = !matcher.group(3).isNullOrEmpty()
            // صيغة الوقت: ساعة 1..12، ومعها كلمةُ وقتٍ أو لصاقُ الحرف.
            if (hour !in 1..12 || (anchor == null && spaced)) continue
            val suffix = matcher.group(4)!!
            val timeText = formatTime(
                hour, 0, null,
                periodFromSuffix(suffix), suffix
            )
            matcher.appendReplacement(
                buffer,
                java.util.regex.Matcher.quoteReplacement(
                    (anchor ?: "") + timeText
                )
            )
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /**
     * استنتاج الفترة (صباحاً/مساءً/ظهراً) من اللاحقة المكتوبة:
     * am/ص → morning، pm/م/ظهر → evening، و«صم» → مساءً (الحرف الأخير
     * هو المؤشر)، وغيابُ اللاحقة → null (تُحسب الفترة من الساعة).
     */
    private fun periodFromSuffix(suffix: String?): Boolean? {
        if (suffix.isNullOrBlank()) return null
        val lower = suffix.lowercase(java.util.Locale.ROOT)
        return when {
            lower.contains("p") ||
                lower.contains("مساء") ||
                lower.contains("ظهر") -> true
            // «صم» لاحقة قصيرة ثنائية تُقرأ مساءً (المؤشر الأخير م)
            lower == "صم" -> true
            lower.contains("a") ||
                lower.contains("صباح") -> false
            lower.startsWith("م") -> true
            lower.startsWith("ص") -> false
            else -> null
        }
    }

    /** تنسيق الوقت بالعربية */
    private fun formatTime(
        rawHour: Int,
        minute: Int,
        seconds: Int? = null,
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
                if (suffix?.contains("ظهر") == true) "ظهراً" else "مساءً"
            }
            else -> {
                val roundedHour = if (minute >= 45) rawHour + 1 else rawHour
                when {
                    roundedHour == 0 -> "بعد منتصف الليل"
                    roundedHour == 12 -> "ظهراً"
                    roundedHour <= 11 -> "صباحاً"
                    else -> "مساءً"
                }
            }
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

        fun secondsPart(count: Int): String = when (count) {
            1 -> "ثانية واحدة"
            2 -> "ثانيتان"
            in 3..10 -> "${NumberSpeech.toArabicWords(count)} ثوانٍ"
            else -> "${NumberSpeech.toArabicWords(count)} ثانية"
        }

        val body = if (seconds == null || seconds == 0) {
            when (minute) {
                0 -> hourText
                15 -> "$hourText والربع"
                30 -> "$hourText والنصف"
                45 -> {
                    val nextHour = if (hour12 == 12) 1 else hour12 + 1
                    val nextHourText = NumberSpeech.toOrdinalHourWord(nextHour)
                    "$nextHourText إلا ربع"
                }
                in 1..29 -> "$hourText و ${minutesPart(minute)}"
                in 31..44 -> "$hourText و ${minutesPart(minute)}"
                in 46..59 -> {
                    val remaining = 60 - minute
                    val nextHour = if (hour12 == 12) 1 else hour12 + 1
                    val nextHourText = NumberSpeech.toOrdinalHourWord(nextHour)
                    "$nextHourText إلا ${minutesOmissionPart(remaining)}"
                }
                else -> hourText
            }
        } else {
            // الثواني مذكورة: الدقائق تُنطق صريحة (بلا «إلا») ثم تُلحق الثواني
            val minutesPhrase = when (minute) {
                0 -> hourText
                15 -> "$hourText والربع"
                30 -> "$hourText والنصف"
                else -> "$hourText و ${minutesPart(minute)}"
            }
            "$minutesPhrase و ${secondsPart(seconds)}"
        }
        return "$body $period"
    }
}