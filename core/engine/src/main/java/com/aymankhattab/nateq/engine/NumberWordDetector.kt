package com.aymankhattab.nateq.engine

import com.aymankhattab.nateq.core.engine.EngineMarkers
import com.aymankhattab.nateq.util.LanguageCode
import java.util.regex.Pattern

/**
 * كاشف الأعداد المكتوبة بالحروف (عربية وإنجليزية) في النص الخام.
 * يلتقط أطول تتابع من كلمات الأعداد ويغلّفها بعلامتي
 * [EngineMarkers.NUMBER_MARK_START] (\uE030) و
 * [EngineMarkers.NUMBER_MARK_END] (\uE031).
 */
object NumberWordDetector {

    /** استثناءات افتراضية لكلمات تشبه الأعداد وليست أعداداً حسابية. */
    private val DEFAULT_EXCEPTIONS = setOf(
        "الواحد",
        "عبد الواحد",
        "الله الواحد",
        "مرة واحدة",
        "واحدة",
        "no one",
        "someone",
        "anyone",
        "everyone",
        "one another"
    )

    // مفردات الأعداد العربية الأساسية (آحاد، عشرات، مئات، ألوف، ترتيبي)
    private const val AR_UNITS =
        "صفر|واحد|واحدة|إحدى|احدى|اثنان|اثنين|اثنتان|اثنتين|ثنتان|ثنتين|" +
            "ثلاثة|ثلاث|أربعة|اربعة|أربع|اربع|خمسة|خمس|ستة|ست|سبعة|سبع|" +
            "ثمانية|ثماني|ثمان|تسعة|تسع|عشرة|عشر"

    private const val AR_TENS =
        "عشرون|عشرين|ثلاثون|ثلاثين|أربعون|اربعون|أربعين|اربعين|" +
            "خمسون|خمسين|ستون|ستين|سبعون|سبعين|ثمانون|ثمانين|تسعون|تسعين"

    private const val AR_HUNDREDS =
        "مائة|مئة|مائتان|مئتان|مائتين|مئتين|مائتا|مئتا|" +
            "ثلاثمائة|ثلاثمئة|أربعمائة|اربعمائة|أربعمئة|اربعمئة|" +
            "خمسمائة|خمسمئة|ستمائة|ستمئة|سبعمائة|سبعمئة|" +
            "ثمانمائة|ثمانمئة|تسعمائة|تسعمئة"

    private const val AR_SCALES =
        "ألف|الف|ألفان|الفان|ألفين|الفين|ألفا|الفا|آلاف|الاف|ألفاً|" +
            "مليون|مليونان|مليونين|ملايين|مليوناً|" +
            "مليار|ملياران|مليارين|مليارات|ملياراً|" +
            "تريليون|تريليونان|تريليونين|تريليونات"

    private const val AR_ORDINALS =
        "الأول|الاول|الأولى|الاولى|أول|اول|أولى|اولى|" +
            "الثاني|الثانية|ثان|ثاني|ثانية|" +
            "الثالث|الثالثة|ثالث|ثالثة|" +
            "الرابع|الرابعة|رابع|رابعة|" +
            "الخامس|الخامسة|خامس|خامسة|" +
            "السادس|السادسة|سادس|سادسة|" +
            "السابع|السابعة|سابع|سابعة|" +
            "الثامن|الثامنة|ثامن|ثامنة|" +
            "التاسع|التاسعة|تاسع|تاسعة|" +
            "العاشر|العاشرة|عاشر|عاشرة|" +
            "الحادي|الحادية|حادي|حادية"

    private const val AR_ATOM =
        "(?:[وبلفك]?(?:ال)?(?:" +
            "$AR_UNITS|$AR_TENS|$AR_HUNDREDS|$AR_SCALES|$AR_ORDINALS))"

    private val PATTERN_ARABIC_NUMBERS = Pattern.compile(
        """(?<![\p{L}\p{M}])$AR_ATOM(?:\s+(?:و\s*)?$AR_ATOM)*(?![\p{L}\p{M}])"""
    )

    private const val EN_UNITS =
        "zero|one|two|three|four|five|six|seven|eight|nine|ten|" +
            "eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|" +
            "eighteen|nineteen"

    private const val EN_TENS =
        "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety"

    private const val EN_SCALES =
        "hundred|thousand|million|billion|trillion"

    private const val EN_ORDINALS =
        "first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|" +
            "eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|" +
            "seventeenth|eighteenth|nineteenth|twentieth|thirtieth|fortieth|" +
            "fiftieth|sixtieth|seventieth|eightieth|ninetieth|" +
            "hundredth|thousandth|millionth|billionth"

    private const val EN_ATOM =
        "(?:(?:$EN_TENS)-(?:$EN_UNITS)|" +
            "$EN_UNITS|$EN_TENS|$EN_SCALES|$EN_ORDINALS)"

    private val PATTERN_ENGLISH_NUMBERS = Pattern.compile(
        """(?i)(?<![\p{L}\p{M}])$EN_ATOM""" +
            """(?:\s+(?:and\s+)?$EN_ATOM)*(?![\p{L}\p{M}])"""
    )

    private val AR_CANDIDATE_ROOTS = arrayOf(
        "صفر", "واحد", "احد", "أحد", "إحد", "اثن", "إثن", "ثنت", "ثان",
        "ثلاث", "أربع", "اربع", "رابع", "خمس", "ست", "سادس", "سبع",
        "ثمان", "ثامن", "تسع", "عشر", "مائ", "مئ", "ألف", "الف",
        "آلاف", "الاف", "مليون", "ملايين", "مليار", "تريليون", "أول",
        "اول", "حادي"
    )

    private val EN_CANDIDATE_ROOTS = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven",
        "eight", "nine", "ten", "eleven", "twelv", "thir", "fif",
        "twent", "hund", "thous", "mill", "bill", "trill", "first", "second"
    )

    private fun hasArabicNumberCandidate(text: String): Boolean {
        for (root in AR_CANDIDATE_ROOTS) {
            if (text.contains(root)) return true
        }
        return false
    }

    private fun hasEnglishNumberCandidate(text: String): Boolean {
        val lower = text.lowercase()
        for (root in EN_CANDIDATE_ROOTS) {
            if (lower.contains(root)) return true
        }
        return false
    }

    /**
     * فحص سريع: هل يحوي النص أي عدد مكتوب بالحروف؟
     */
    fun hasNumberWord(text: String): Boolean {
        if (text.isEmpty()) return false
        if (hasArabicNumberCandidate(text)) {
            val m = PATTERN_ARABIC_NUMBERS.matcher(text)
            while (m.find()) {
                val match = m.group(0)
                if (match != null &&
                    !isException(text, m.start(), m.end(), match, emptySet())
                ) {
                    return true
                }
            }
        }
        if (hasEnglishNumberCandidate(text)) {
            val m = PATTERN_ENGLISH_NUMBERS.matcher(text)
            while (m.find()) {
                val match = m.group(0)
                if (match != null &&
                    !isException(text, m.start(), m.end(), match, emptySet())
                ) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * يلتقط الأعداد المكتوبة بالحروف ويغلفها بالعلامات الخاصة.
     */
    fun detectAndMark(
        text: String,
        languageTag: String = LanguageCode.AR.tag,
        enabled: Boolean = true,
        customExceptions: Set<String> = emptySet()
    ): String {
        if (!enabled || text.isEmpty()) return text

        val isAr = LanguageCode.isArabic(languageTag)
        val isEn = LanguageCode.isEnglish(languageTag)

        val hasCandidate = when {
            isAr -> hasArabicNumberCandidate(text)
            isEn -> hasEnglishNumberCandidate(text)
            else -> hasArabicNumberCandidate(text) ||
                hasEnglishNumberCandidate(text)
        }
        if (!hasCandidate) return text

        val exceptions = DEFAULT_EXCEPTIONS + customExceptions

        var result = text
        if (isAr) {
            result = markWithPattern(
                result, PATTERN_ARABIC_NUMBERS, exceptions
            )
        } else if (isEn) {
            result = markWithPattern(
                result, PATTERN_ENGLISH_NUMBERS, exceptions
            )
        } else {
            if (hasArabicNumberCandidate(result)) {
                result = markWithPattern(
                    result, PATTERN_ARABIC_NUMBERS, exceptions
                )
            }
            if (hasEnglishNumberCandidate(result)) {
                result = markWithPattern(
                    result, PATTERN_ENGLISH_NUMBERS, exceptions
                )
            }
        }
        return result
    }

    fun detectAndMark(
        text: String,
        enabled: Boolean
    ): String = detectAndMark(text, LanguageCode.AR.tag, enabled)

    private fun markWithPattern(
        text: String,
        pattern: Pattern,
        exceptions: Set<String>
    ): String {
        val matcher = pattern.matcher(text)
        if (!matcher.find()) return text
        matcher.reset()

        val sb = StringBuilder(text.length + 32)
        var cursor = 0
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val match = matcher.group(0)!!
            val trimmed = match.trim()

            // تحقق من الاستثناءات
            if (isException(text, start, end, trimmed, exceptions)) {
                sb.append(text, cursor, end)
                cursor = end
                continue
            }

            // تجنب تكرار الوسم إن كان موسوماً سلفاً
            val alreadyMarked = start > 0 &&
                text[start - 1] == EngineMarkers.NUMBER_MARK_START_CHAR &&
                end < text.length &&
                text[end] == EngineMarkers.NUMBER_MARK_END_CHAR
            if (alreadyMarked) {
                sb.append(text, cursor, end)
                cursor = end
                continue
            }

            sb.append(text, cursor, start)
            sb.append(EngineMarkers.NUMBER_MARK_START)
            sb.append(match)
            sb.append(EngineMarkers.NUMBER_MARK_END)
            cursor = end
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }

    private fun isException(
        text: String,
        start: Int,
        end: Int,
        matched: String,
        exceptions: Set<String>
    ): Boolean {
        val lower = matched.lowercase()
        if (matched in exceptions || lower in exceptions) return true

        // استثناء "عبد الواحد" و"الله الواحد" حتى لو التقط "الواحد" فقط
        if (matched == "الواحد" || matched == "واحد") {
            val prefix = text.substring(0, start).trimEnd()
            if (prefix.endsWith("عبد") || prefix.endsWith("الله")) {
                return true
            }
        }
        // استثناء "one" عند وروده في سياقات إنجليزية مركبة
        if (lower == "one") {
            val prefix = text.substring(0, start).trimEnd().lowercase()
            if (prefix.endsWith("no") || prefix.endsWith("any") ||
                prefix.endsWith("every") || prefix.endsWith("some")
            ) {
                return true
            }
            val suffix = text.substring(end).trimStart().lowercase()
            if (suffix.startsWith("another")) {
                return true
            }
        }
        return false
    }
}
