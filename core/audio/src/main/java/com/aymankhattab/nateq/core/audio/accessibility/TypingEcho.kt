package com.aymankhattab.nateq.core.audio.accessibility

/**
 * صدى الكتابة (ع٢٧-31).
 * ينطق الحروف أو الكلمات المكتوبة أثناء تفاعل المستخدم مع لوحة المفاتيح.
 * - يدعم أوضاع: OFF، CHARACTERS، WORDS، BOTH.
 * - يعتمد fromIndex وaddedCount وremovedCount مع فحص الحدود.
 * - يستخدم مطابقة البادئة واللاحقة المشتركة كمسار احتياطي آمن.
 * - ينطق الحرف المدخل بدقة أينما أُدرج (بداية، وسط، نهاية، استبدال، لصق).
 * - يعالج التشكيل العربي والرموز المركبة (البند 14).
 */
object TypingEcho {

    enum class EchoMode {
        OFF,
        CHARACTERS,
        WORDS,
        BOTH
    }

    /**
     * حساب النص المراد نطقه عند إضافة أو تعديل حرف.
     *
     * @param oldText النص السابق قبل التعديل.
     * @param newText النص الحالي بعد التعديل.
     * @param mode وضع صدى الكتابة المطلوب.
     * @param fromIndex بداية التغيير من الحدث إن توفر (-1 افتراضياً).
     * @param addedCount عدد المحارف المضافة إن توفر (-1 افتراضياً).
     * @param removedCount عدد المحارف المحذوفة إن توفر (-1 افتراضياً).
     */
    fun getEchoText(
        oldText: CharSequence?,
        newText: CharSequence?,
        mode: EchoMode,
        fromIndex: Int = -1,
        addedCount: Int = -1,
        removedCount: Int = -1
    ): String? {
        if (mode == EchoMode.OFF || newText.isNullOrEmpty()) return null
        val oldStr = oldText?.toString().orEmpty()
        val newStr = newText.toString()

        val added = extractAddedText(
            oldStr = oldStr,
            newStr = newStr,
            fromIndex = fromIndex,
            addedCount = addedCount,
            removedCount = removedCount
        ) ?: return null

        if (added.isEmpty()) return null

        return when (mode) {
            EchoMode.OFF -> null
            EchoMode.CHARACTERS -> added
            EchoMode.WORDS -> {
                if (containsWordDelimiter(added)) {
                    extractCompletedWord(
                        newStr = newStr,
                        fromIndex = fromIndex,
                        added = added
                    )
                } else {
                    null
                }
            }
            EchoMode.BOTH -> {
                if (containsWordDelimiter(added)) {
                    extractCompletedWord(
                        newStr = newStr,
                        fromIndex = fromIndex,
                        added = added
                    )
                } else {
                    added
                }
            }
        }
    }

    private fun extractAddedText(
        oldStr: String,
        newStr: String,
        fromIndex: Int,
        addedCount: Int,
        removedCount: Int
    ): String? {
        if (fromIndex >= 0 && addedCount >= 0) {
            if (addedCount == 0) return null
            if (fromIndex <= newStr.length &&
                fromIndex + addedCount <= newStr.length
            ) {
                return newStr.substring(fromIndex, fromIndex + addedCount)
            }
        }
        return extractAddedByDiff(oldStr, newStr)
    }

    internal fun extractAddedByDiff(oldStr: String, newStr: String): String? {
        if (oldStr == newStr) return null

        var prefixLen = 0
        val maxPrefix = minOf(oldStr.length, newStr.length)
        while (prefixLen < maxPrefix &&
            oldStr[prefixLen] == newStr[prefixLen]
        ) {
            prefixLen++
        }

        var oldSuffixIdx = oldStr.length - 1
        var newSuffixIdx = newStr.length - 1
        while (oldSuffixIdx >= prefixLen &&
            newSuffixIdx >= prefixLen &&
            oldStr[oldSuffixIdx] == newStr[newSuffixIdx]
        ) {
            oldSuffixIdx--
            newSuffixIdx--
        }

        if (newSuffixIdx < prefixLen) return null
        val added = newStr.substring(prefixLen, newSuffixIdx + 1)
        return added.ifEmpty { null }
    }

    private fun containsWordDelimiter(text: String): Boolean {
        return text.any { it.isWhitespace() || isDelimiterChar(it) }
    }

    private fun isDelimiterChar(c: Char): Boolean {
        return c in " \t\n\r.,!?:;،؛؟()[]{}<>\"'\\/`~@#$%^&*-_=+|"
    }

    private fun extractCompletedWord(
        newStr: String,
        fromIndex: Int,
        added: String
    ): String? {
        val delimOffset = added.indexOfFirst {
            it.isWhitespace() || isDelimiterChar(it)
        }
        val delimPos = if (fromIndex in 0..newStr.length && delimOffset >= 0) {
            minOf(fromIndex + delimOffset, newStr.length)
        } else {
            val idx = newStr.lastIndexOf(added)
            if (idx >= 0 && delimOffset >= 0) {
                minOf(idx + delimOffset, newStr.length)
            } else {
                newStr.length
            }
        }

        var wordEnd = delimPos
        while (wordEnd > 0 &&
            (newStr[wordEnd - 1].isWhitespace() ||
                isDelimiterChar(newStr[wordEnd - 1]))
        ) {
            wordEnd--
        }
        if (wordEnd <= 0) return null

        var wordStart = wordEnd
        while (wordStart > 0 &&
            !newStr[wordStart - 1].isWhitespace() &&
            !isDelimiterChar(newStr[wordStart - 1])
        ) {
            wordStart--
        }

        val word = newStr.substring(wordStart, wordEnd).trim()
        return word.ifEmpty { null }
    }
}
