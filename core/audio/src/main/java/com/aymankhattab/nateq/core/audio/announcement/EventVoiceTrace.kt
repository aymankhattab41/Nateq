package com.aymankhattab.nateq.core.audio.announcement

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * متتبع صوت كل إعلان حدث في التطبيق
 * (الجولة 52 - الأمر 3).
 * يسجل آخر 50 إعلاناً مع تفاصيل الفئة
 * والصوت المحفوظ والمحرك والمزود والتراجع.
 */
object EventVoiceTrace {

    private const val MAX_ENTRIES = 50

    data class TraceEntry(
        val timestampMs: Long,
        val category: String,
        val savedVoiceId: String?,
        val resolvedEngine: String?,
        val dispatchedVoiceId: String?,
        val executingProvider: String?,
        val fallbackReason: String?,
        val textSnippet: String
    ) {
        fun formatLine(): String {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
            val timeStr = sdf.format(Date(timestampMs))
            val savedStr = savedVoiceId ?: "غير محدد"
            val dispStr = dispatchedVoiceId ?: "افتراضي"
            val engStr = resolvedEngine ?: "تلقائي"
            val provStr = executingProvider ?: "غير معروف"
            val fallStr = if (!fallbackReason.isNullOrBlank()) {
                " [تراجع: $fallbackReason]"
            } else ""
            return "[$timeStr][$category] صوت: $savedStr ← مرسل: " +
                "$dispStr | محرك: $engStr | مزود: $provStr" +
                "$fallStr | \"$textSnippet\""
        }
    }

    private val entries = ConcurrentLinkedQueue<TraceEntry>()

    fun record(
        category: String,
        savedVoiceId: String?,
        resolvedEngine: String?,
        dispatchedVoiceId: String?,
        executingProvider: String? = null,
        fallbackReason: String? = null,
        textSnippet: String = ""
    ) {
        val snippet = textSnippet.take(40).replace('\n', ' ')
        val entry = TraceEntry(
            timestampMs = System.currentTimeMillis(),
            category = category,
            savedVoiceId = savedVoiceId,
            resolvedEngine = resolvedEngine,
            dispatchedVoiceId = dispatchedVoiceId,
            executingProvider = executingProvider,
            fallbackReason = fallbackReason,
            textSnippet = snippet
        )
        entries.add(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.poll()
        }
    }

    fun updateExecutingProvider(
        category: String,
        providerName: String?,
        fallbackReason: String? = null
    ) {
        val last = entries.lastOrNull { it.category == category } ?: return
        entries.remove(last)
        entries.add(
            last.copy(
                executingProvider = providerName ?: last.executingProvider,
                fallbackReason = fallbackReason ?: last.fallbackReason
            )
        )
    }

    fun getRecent(limit: Int = MAX_ENTRIES): List<TraceEntry> {
        return entries.toList().takeLast(limit)
    }

    fun formatReport(): String {
        val list = getRecent()
        if (list.isEmpty()) {
            return "لا توجد سجلات تتبع صوتية للأحداث حتى الآن."
        }
        val sb = StringBuilder()
        sb.appendLine("=== سجل تتبع صوت الأحداث (EventVoiceTrace) ===")
        list.forEachIndexed { i, entry ->
            sb.appendLine("${i + 1}. ${entry.formatLine()}")
        }
        return sb.toString().trimEnd()
    }

    fun clear() {
        entries.clear()
    }
}
