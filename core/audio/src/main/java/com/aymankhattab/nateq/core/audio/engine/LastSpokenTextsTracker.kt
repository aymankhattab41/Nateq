package com.aymankhattab.nateq.core.audio.engine

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * متتبع آخر النصوص المنطوقة في سجل دائري لآخر 20 نصاً (الجولة 53 - الأمر 4).
 * يسجل المصدر والنص الخام والنظام السداسي للمحارف غير الاعتيادية.
 */
object LastSpokenTextsTracker {

    private const val MAX_ENTRIES = 20

    data class SpokenEntry(
        val rawText: String,
        val source: String,
        val hexInfo: String
    ) {
        fun formatDisplay(): String {
            val hex = if (hexInfo.isNotBlank()) " [سداسي: $hexInfo]" else ""
            return "[$source] $rawText$hex"
        }
    }

    private val buffer = ConcurrentLinkedQueue<SpokenEntry>()

    fun record(rawText: String, source: Any? = null) {
        if (rawText.isBlank()) return
        val srcLabel = when (source) {
            is SpeechEntryPoints.Source -> source.labelAr
            is String -> source
            null -> "قارئ الشاشة"
            else -> source.toString()
        }
        val hexChars = extractNonStandardHex(rawText)
        buffer.add(
            SpokenEntry(
                rawText = rawText,
                source = srcLabel,
                hexInfo = hexChars
            )
        )
        while (buffer.size > MAX_ENTRIES) {
            buffer.poll()
        }
    }

    fun getRecent(): List<String> = buffer.toList().reversed().map {
        it.rawText
    }

    fun getRecentEntries(): List<SpokenEntry> =
        buffer.toList().reversed()

    fun clear() {
        buffer.clear()
    }

    private fun extractNonStandardHex(text: String): String {
        val nonStandard = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val w = Character.charCount(cp)
            i += w
            if (cp == 0x200B || cp == 0x200C || cp == 0x200D ||
                cp == 0x200E || cp == 0x200F || cp == 0x2060 ||
                cp == 0x00A0 || cp == 0x202F || cp == 0x061C ||
                cp == 0xFEFF || cp == 0x00AD || Character.isISOControl(cp)
            ) {
                nonStandard.add(String.format("U+%04X", cp))
            }
        }
        return nonStandard.distinct().joinToString(" ")
    }
}
