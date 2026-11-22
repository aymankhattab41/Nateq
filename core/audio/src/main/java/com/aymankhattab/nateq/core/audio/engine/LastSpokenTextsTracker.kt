package com.aymankhattab.nateq.core.audio.engine

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * متتبع آخر النصوص المنطوقة في سجل دائري لآخر 20 نصاً.
 * يطبق سياسة خصوصية صارمة:
 * 1. عدم حفظ المكالمات والرموز وكلمات المرور افتراضياً.
 * 2. سقوف لحجم كل عبارة وإجمالي الذاكرة.
 * 3. آلية مسح واضحة.
 */
object LastSpokenTextsTracker {

    private const val MAX_ENTRIES = 20
    private const val MAX_ENTRY_CHARS = 256
    private const val MAX_TOTAL_CHARS = 4096

    private val SENSITIVE_SOURCE_KEYWORDS = setOf(
        "caller", "متصل", "مكالمة", "call", "password", "كلمة مرور",
        "pin", "رمز", "token", "otp", "secret", "سر"
    )

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

    fun record(
        rawText: String,
        source: Any? = null,
        isSensitive: Boolean = false
    ) {
        if (rawText.isBlank()) return
        if (isSensitive) return

        val srcLabel = when (source) {
            is SpeechEntryPoints.Source -> {
                if (source == SpeechEntryPoints.Source.CALLER) return
                source.labelAr
            }
            is String -> {
                val lower = source.lowercase()
                if (SENSITIVE_SOURCE_KEYWORDS.any { lower.contains(it) }) {
                    return
                }
                source
            }
            null -> "قارئ الشاشة"
            else -> {
                val s = source.toString()
                val lower = s.lowercase()
                if (SENSITIVE_SOURCE_KEYWORDS.any { lower.contains(it) }) {
                    return
                }
                s
            }
        }

        // قص العبارة بالسقف المحدد
        val boundedText = if (rawText.length > MAX_ENTRY_CHARS) {
            rawText.take(MAX_ENTRY_CHARS)
        } else {
            rawText
        }

        val hexChars = extractNonStandardHex(boundedText)
        buffer.add(
            SpokenEntry(
                rawText = boundedText,
                source = srcLabel,
                hexInfo = hexChars
            )
        )

        // تقليص بالعدد وسقف إجمالي الحروف
        while (buffer.size > MAX_ENTRIES ||
            calculateTotalChars() > MAX_TOTAL_CHARS
        ) {
            buffer.poll()
        }
    }

    private fun calculateTotalChars(): Int =
        buffer.sumOf { it.rawText.length }

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
