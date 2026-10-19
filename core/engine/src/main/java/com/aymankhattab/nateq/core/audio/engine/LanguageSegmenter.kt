package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.util.LanguageCode

/**
 * مقطع لغوي واحد ضمن نصٍ مختلط الكتابات.
 */
data class Segment(
    val text: String,
    val languageTag: String,
    val role: SpeechRole = SpeechRole.TEXT
)

/**
 * مقطع لغوي مع إحداثيات البداية والنهاية في النص الأصلي (الأمر 1).
 */
data class SegmentWithOffset(
    val text: String,
    val languageTag: String,
    val role: SpeechRole = SpeechRole.TEXT,
    val start: Int = 0,
    val end: Int = text.length
)

fun Segment.isNumericOnly(): Boolean {
    var digitCount = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        if (Character.isDigit(cp)) {
            digitCount++
            continue
        }
        if (Character.isLetter(cp)) return false
    }
    return digitCount > 0
}

/**
 * مقسم النصوص واللغات الذكي — يقسم النص إلى مقاطع لغوية مع إحداثياتها
 * ويدعم قفل لغة القاموس والأرقام التلقائية وضوابط المحايدات (الأوامر 1 و3).
 */
class LanguageSegmenter(
    private val numberLanguageProvider: (() -> String)? = null
) {

    companion object {
        val EN_FALLBACK: String get() = LanguageCode.EN.tag

        /** تفعيل تشخيص اللغات في Logcat (الأمر 3). */
        @Volatile
        var diagnosticsEnabled: Boolean = false

        /** رمز وضع الأرقام التلقائي التابع للغة الجملة (الأمر 3). */
        const val NUMBER_LANG_AUTO = "auto"

        /** علامات قفل لغة إدخال القاموس الخاصة (الأمر 3). */
        const val LOCK_START = '\uE010'
        const val LOCK_TAG_END = '\uE011'
        const val LOCK_END = '\uE012'

        private const val LATIN_PLACEHOLDER = "\u0000latin"

        private val SCRIPT_LANGUAGE_TAGS: Map<Character.UnicodeScript, String> =
            mapOf(
                Character.UnicodeScript.CYRILLIC to "ru",
                Character.UnicodeScript.HEBREW to "he",
                Character.UnicodeScript.GREEK to "el",
                Character.UnicodeScript.HAN to "zh",
                Character.UnicodeScript.HIRAGANA to "ja",
                Character.UnicodeScript.KATAKANA to "ja",
                Character.UnicodeScript.HANGUL to "ko",
                Character.UnicodeScript.THAI to "th",
                Character.UnicodeScript.DEVANAGARI to "hi",
                Character.UnicodeScript.ETHIOPIC to "am"
            )

        private val ARABIC_RANGES = arrayOf(
            0x0600..0x06FF,
            0x0750..0x077F,
            0x0870..0x089F,
            0x08A0..0x08FF,
            0xFB50..0xFDFF,
            0xFE70..0xFEFC,
            0x10EC0..0x10EFF,
            0x1EE00..0x1EEFF
        )

        private val NEUTRAL_CATEGORY_MASK: Long =
            listOf(
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
                Character.MATH_SYMBOL.toInt(),
                Character.CURRENCY_SYMBOL.toInt(),
                Character.MODIFIER_SYMBOL.toInt(),
                Character.OTHER_SYMBOL.toInt(),
                Character.SPACE_SEPARATOR.toInt(),
                Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt()
            ).fold(0L) { mask, category -> mask or (1L shl category) }

        private val CLOSING_PUNCTUATION = setOf(
            ')', ']', '}', '”', '»', '’', '›'
        )

        /** إزالة علامات قفل اللغة قبل النطق لأي مسار لا يمر بالمقسم. */
        fun stripLockMarkers(text: String): String {
            if (!text.contains(LOCK_START) && !text.contains(LOCK_END)) {
                return text
            }
            val sb = java.lang.StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                if (text[i] == LOCK_START) {
                    val tagEnd = text.indexOf(LOCK_TAG_END, i + 1)
                    if (tagEnd != -1) {
                        val end = text.indexOf(LOCK_END, tagEnd + 1)
                        if (end != -1) {
                            sb.append(text, tagEnd + 1, end)
                            i = end + 1
                            continue
                        }
                    }
                }
                if (text[i] != LOCK_START && text[i] != LOCK_TAG_END &&
                    text[i] != LOCK_END
                ) {
                    sb.append(text[i])
                }
                i++
            }
            return sb.toString()
        }
    }

    private enum class Kind { SCRIPT, NUMBER, NEUTRAL }

    private class Run(
        val kind: Kind,
        val script: Character.UnicodeScript?,
        val start: Int
    )

    fun segment(
        text: String,
        fallbackLanguage: String = LanguageCode.AR.tag,
        secondaryLanguage: String = EN_FALLBACK,
        numberLanguage: String = numberLanguageProvider?.invoke()
            ?: LanguageCode.AR.tag
    ): List<Segment> = segmentWithOffsets(
        text, fallbackLanguage, secondaryLanguage, numberLanguage
    ).map { Segment(it.text, it.languageTag, it.role) }

    /**
     * تقسيم النص مع حساب إحداثيات البداية والنهاية لكل مقطع (الأمر 1).
     */
    fun segmentWithOffsets(
        text: String,
        fallbackLanguage: String = LanguageCode.AR.tag,
        secondaryLanguage: String = EN_FALLBACK,
        numberLanguage: String = numberLanguageProvider?.invoke()
            ?: LanguageCode.AR.tag
    ): List<SegmentWithOffset> {
        if (text.isEmpty()) {
            return listOf(
                SegmentWithOffset(
                    "",
                    neutralFallback(fallbackLanguage),
                    SpeechRole.TEXT,
                    0,
                    0
                )
            )
        }

        // معالجة مقاطع قفل اللغة المشفرة بـ \uE010..\uE012
        if (text.contains(LOCK_START)) {
            return segmentWithLockedSections(
                text, fallbackLanguage, secondaryLanguage, numberLanguage
            )
        }

        return merge(
            text,
            scriptFallback(fallbackLanguage, secondaryLanguage),
            neutralFallback(fallbackLanguage),
            numberLanguage,
            fallbackLanguage,
            0
        )
    }

    private fun segmentWithLockedSections(
        text: String,
        fallbackLanguage: String,
        secondaryLanguage: String,
        numberLanguage: String
    ): List<SegmentWithOffset> {
        val results = ArrayList<SegmentWithOffset>()
        var cursor = 0
        val sFallback = scriptFallback(fallbackLanguage, secondaryLanguage)
        val nFallback = neutralFallback(fallbackLanguage)

        while (cursor < text.length) {
            val startLock = text.indexOf(LOCK_START, cursor)
            if (startLock == -1) {
                if (cursor < text.length) {
                    val sub = text.substring(cursor)
                    val unclosed = merge(
                        sub, sFallback, nFallback, numberLanguage,
                        fallbackLanguage, cursor
                    )
                    results.addAll(unclosed)
                }
                break
            }
            if (startLock > cursor) {
                val sub = text.substring(cursor, startLock)
                val plainSegments = merge(
                    sub, sFallback, nFallback, numberLanguage,
                    fallbackLanguage, cursor
                )
                results.addAll(plainSegments)
            }
            val tagEnd = text.indexOf(LOCK_TAG_END, startLock + 1)
            val endLock = if (tagEnd != -1) {
                text.indexOf(LOCK_END, tagEnd + 1)
            } else -1

            if (tagEnd != -1 && endLock != -1) {
                val lockedTag = text.substring(startLock + 1, tagEnd)
                val lockedValue = text.substring(tagEnd + 1, endLock)
                if (diagnosticsEnabled) {
                    android.util.Log.d(
                        "NateqLang",
                        "نص: '$lockedValue' -> لغة: $lockedTag، " +
                            "السبب: علامة قفل"
                    )
                }
                results.add(
                    SegmentWithOffset(
                        text = lockedValue,
                        languageTag = lockedTag,
                        role = SpeechRole.TEXT,
                        start = startLock,
                        end = endLock + 1
                    )
                )
                cursor = endLock + 1
            } else {
                val sub = text.substring(startLock, startLock + 1)
                results.add(
                    SegmentWithOffset(
                        text = sub,
                        languageTag = nFallback,
                        role = SpeechRole.TEXT,
                        start = startLock,
                        end = startLock + 1
                    )
                )
                cursor = startLock + 1
            }
        }
        return results
    }

    private fun merge(
        text: String,
        scriptFallback: String,
        neutralFallback: String,
        numberLanguage: String,
        requestLanguage: String,
        baseOffset: Int
    ): List<SegmentWithOffset> {
        val runs = buildRuns(text)
        if (runs.isEmpty()) {
            return listOf(
                SegmentWithOffset(
                    text,
                    neutralFallback,
                    SpeechRole.TEXT,
                    baseOffset,
                    baseOffset + text.length
                )
            )
        }

        val segments = ArrayList<SegmentWithOffset>()
        var openStart = -1
        var openLanguage: String? = null
        var openKind: Kind? = null
        var leadingStart = -1
        val isAutoNum =
            numberLanguage.equals(NUMBER_LANG_AUTO, ignoreCase = true)

        for (runIdx in runs.indices) {
            val run = runs[runIdx]
            when (run.kind) {
                Kind.NEUTRAL -> {
                    if (openLanguage == null && leadingStart == -1) {
                        leadingStart = run.start
                    }
                }
                Kind.SCRIPT, Kind.NUMBER -> {
                    val language = if (run.kind == Kind.NUMBER) {
                        if (isAutoNum) {
                            val prevLang = runs.take(runIdx).lastOrNull {
                                it.kind == Kind.SCRIPT
                            }?.let {
                                runLanguage(
                                    it.script, scriptFallback, requestLanguage
                                )
                            }
                            val nextLang = runs.drop(runIdx + 1).firstOrNull {
                                it.kind == Kind.SCRIPT
                            }?.let {
                                runLanguage(
                                    it.script, scriptFallback, requestLanguage
                                )
                            }
                            when {
                                prevLang != null && nextLang != null &&
                                    LanguageCode.isSameFamily(
                                        prevLang, nextLang
                                    ) -> prevLang
                                prevLang != null -> prevLang
                                nextLang != null -> nextLang
                                else -> neutralFallback
                            }
                        } else {
                            numberLanguage
                        }
                    } else {
                        runLanguage(run.script, scriptFallback, requestLanguage)
                    }

                    if (openLanguage == null) {
                        openStart = if (leadingStart != -1) leadingStart
                        else run.start
                        leadingStart = -1
                        openLanguage = language
                        openKind = run.kind
                    } else if (shouldSplit(openLanguage, language)) {
                        var cutPoint = run.start
                        if (cutPoint > 0 && cutPoint > openStart) {
                            val prevChar = text[cutPoint - 1]
                            if (prevChar == '@' || prevChar == '#') {
                                cutPoint -= 1
                            }
                        }
                        addSegment(
                            segments,
                            buildSegment(
                                text, openStart, cutPoint,
                                openLanguage, scriptFallback,
                                baseOffset, openKind
                            )
                        )
                        openStart = cutPoint
                        openLanguage = language
                        openKind = run.kind
                    }
                }
            }
        }
        if (openLanguage != null) {
            addSegment(
                segments,
                buildSegment(
                    text, openStart, text.length,
                    openLanguage, scriptFallback,
                    baseOffset, openKind
                )
            )
        }
        if (segments.isEmpty()) {
            return listOf(
                SegmentWithOffset(
                    text,
                    neutralFallback,
                    SpeechRole.TEXT,
                    baseOffset,
                    baseOffset + text.length
                )
            )
        }
        return segments
    }

    private fun addSegment(
        segments: ArrayList<SegmentWithOffset>,
        newSegment: SegmentWithOffset
    ) {
        if (newSegment.text.isEmpty()) return
        val last = segments.lastOrNull()
        if (last != null &&
            LanguageCode.isSameFamily(last.languageTag, newSegment.languageTag)
        ) {
            val combinedText = last.text + newSegment.text
            val combinedRole = if (last.role == SpeechRole.NUMBER &&
                newSegment.role == SpeechRole.NUMBER
            ) {
                SpeechRole.NUMBER
            } else {
                SpeechRole.TEXT
            }
            segments[segments.size - 1] = SegmentWithOffset(
                text = combinedText,
                languageTag = last.languageTag,
                role = combinedRole,
                start = last.start,
                end = newSegment.end
            )
        } else {
            segments.add(newSegment)
        }
    }

    private fun buildSegment(
        text: String,
        start: Int,
        end: Int,
        language: String,
        scriptFallback: String,
        baseOffset: Int,
        openKind: Kind?
    ): SegmentWithOffset {
        val segText = text.substring(start, end)
        val (tag, reason) = if (language == LATIN_PLACEHOLDER) {
            val isHashtagOrHandle = (start > 0 &&
                (text[start - 1] == '@' || text[start - 1] == '#')) ||
                (end > start &&
                (text[start] == '@' || text[start] == '#'))
            if (isHashtagOrHandle) {
                scriptFallback to "وسم أو معرّف"
            } else {
                val candidate = LatinLanguageDetector.detect(segText)
                if (candidate != null) candidate to "كاشف لاتيني"
                else scriptFallback to "سقوط لاتيني محافظ"
            }
        } else {
            language to "حسب سياق النص"
        }
        if (diagnosticsEnabled) {
            android.util.Log.d(
                "NateqLang",
                "نص: '$segText' -> لغة: $tag، السبب: $reason"
            )
        }
        val role = if (openKind == Kind.NUMBER || isNumericOnlyText(segText)) {
            SpeechRole.NUMBER
        } else {
            SpeechRole.TEXT
        }
        return SegmentWithOffset(
            text = segText,
            languageTag = tag,
            role = role,
            start = baseOffset + start,
            end = baseOffset + end
        )
    }

    private fun isNumericOnlyText(str: String): Boolean {
        var digitCount = 0
        var i = 0
        while (i < str.length) {
            val cp = str.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isDigit(cp)) {
                digitCount++
                continue
            }
            if (Character.isLetter(cp)) return false
        }
        return digitCount > 0
    }

    private fun buildRuns(text: String): List<Run> {
        val runs = ArrayList<Run>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val (kind, script) = kindOf(codePoint)
            val width = Character.charCount(codePoint)
            val start = index
            index += width
            val last = runs.lastOrNull()
            if (last == null ||
                last.kind != kind || last.script != script
            ) {
                runs.add(Run(kind, script, start))
            }
        }
        return runs
    }

    private fun shouldSplit(openLang: String, newLang: String): Boolean {
        if (openLang == LATIN_PLACEHOLDER || newLang == LATIN_PLACEHOLDER) {
            return openLang != newLang
        }
        return !LanguageCode.isSameFamily(openLang, newLang)
    }

    private fun kindOf(codePoint: Int): Pair<Kind, Character.UnicodeScript?> {
        if (Character.isDigit(codePoint)) {
            val arabicFamily = codePoint in 0x0660..0x0669 ||
                codePoint in 0x06F0..0x06F9 ||
                codePoint in '0'.code..'9'.code
            return if (arabicFamily) {
                Kind.NUMBER to null
            } else {
                Kind.SCRIPT to Character.UnicodeScript.of(codePoint)
            }
        }
        if (Character.isWhitespace(codePoint)) {
            return Kind.NEUTRAL to null
        }
        if ((NEUTRAL_CATEGORY_MASK and (1L shl Character.getType(codePoint)))
            != 0L
        ) {
            return Kind.NEUTRAL to null
        }
        if (isArabic(codePoint)) {
            return Kind.SCRIPT to Character.UnicodeScript.ARABIC
        }
        if (Character.isLetter(codePoint)) {
            val script = Character.UnicodeScript.of(codePoint)
            if (script == Character.UnicodeScript.COMMON ||
                script == Character.UnicodeScript.INHERITED
            ) {
                return Kind.NEUTRAL to null
            }
            return Kind.SCRIPT to script
        }
        return Kind.NEUTRAL to null
    }

    private fun isArabic(codePoint: Int): Boolean {
        for (range in ARABIC_RANGES) {
            if (codePoint in range) return true
        }
        return false
    }

    private fun runLanguage(
        script: Character.UnicodeScript?,
        scriptFallback: String,
        requestFallback: String = ""
    ): String {
        if (script == null) return scriptFallback
        return when (script) {
            Character.UnicodeScript.ARABIC -> {
                if (LanguageCode.isArabic(requestFallback)) {
                    requestFallback
                } else {
                    LanguageCode.AR.tag
                }
            }
            Character.UnicodeScript.LATIN -> LATIN_PLACEHOLDER
            else -> SCRIPT_LANGUAGE_TAGS[script] ?: scriptFallback
        }
    }

    private fun scriptFallback(
        requestLanguage: String,
        secondaryLanguage: String
    ): String {
        val language = requestLanguage.takeWhile { it.isLetter() }
        return when {
            language.isBlank() -> EN_FALLBACK
            LanguageCode.isArabic(language) -> secondaryOrEn(secondaryLanguage)
            else -> language
        }
    }

    private fun secondaryOrEn(secondaryLanguage: String): String {
        val language = secondaryLanguage.takeWhile { it.isLetter() }
        return if (language.isBlank()) EN_FALLBACK else language
    }

    private fun neutralFallback(requestLanguage: String): String {
        val language = requestLanguage.takeWhile { it.isLetter() }
        return when {
            language.isBlank() -> EN_FALLBACK
            LanguageCode.isArabic(language) -> LanguageCode.AR.tag
            else -> language
        }
    }
}
