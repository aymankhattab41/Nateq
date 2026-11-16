package com.aymankhattab.nateq.core.audio.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * متتبع طلبات النطق والبدائل (Speech Fallback Tracker).
 * يوثق كل نداء نطق عبر Lord TTS مع المحرك والصوت المطلوبين والفعليين،
 * ويسجل صراحةً أي تحويل لصوت أو محرك بديل (Fallback) وأسباب ذلك.
 */
object SpeechFallbackTracker {

    data class UtteranceRecord(
        val timestamp: Long = System.currentTimeMillis(),
        val textSnippet: String,
        val languageTag: String,
        val requestedEngine: String,
        val requestedVoice: String,
        val actualEngine: String,
        val actualVoice: String,
        val isFallback: Boolean,
        val fallbackReason: String? = null
    ) {
        fun format(): String {
            val dateStr = SimpleDateFormat(
                "HH:mm:ss.SSS", Locale.US
            ).format(Date(timestamp))
            val fallbackStr = if (isFallback) {
                " [بديل (Fallback) بسبب: ${fallbackReason ?: "غير محدد"}]"
            } else {
                " [مباشر]"
            }
            return "$dateStr | النص: '$textSnippet' ($languageTag) | " +
                "المطلوب: $requestedVoice ($requestedEngine) | " +
                "الفعلي: $actualVoice ($actualEngine)$fallbackStr"
        }
    }

    private const val MAX_RECORDS = 50
    private val records = ConcurrentLinkedDeque<UtteranceRecord>()

    @Volatile
    var acapelaFallbackActive: Boolean = false
        private set

    @Volatile
    var acapelaFallbackVoice: String? = null
        private set

    @Volatile
    var acapelaFallbackEngine: String? = null
        private set

    @Volatile
    var acapelaFallbackReason: String? = null
        private set

    fun record(
        text: String,
        languageTag: String,
        requestedEngine: String?,
        requestedVoice: String?,
        actualEngine: String?,
        actualVoice: String?,
        isFallback: Boolean,
        fallbackReason: String? = null
    ) {
        val snippet = if (text.length > 40) {
            text.take(37) + "..."
        } else {
            text
        }
        val reqEng = requestedEngine?.ifBlank { "default" } ?: "default"
        val reqV = requestedVoice?.ifBlank { "default" } ?: "default"
        val actEng = actualEngine?.ifBlank { "default" } ?: "default"
        val actV = actualVoice?.ifBlank { "default" } ?: "default"

        val isAcapelaReq = reqEng.contains("acapela", ignoreCase = true) ||
            reqV.contains("salma", ignoreCase = true) ||
            reqV.contains("mehdi", ignoreCase = true)

        if (isAcapelaReq && (isFallback || actEng != reqEng)) {
            acapelaFallbackActive = true
            acapelaFallbackVoice = actV
            acapelaFallbackEngine = actEng
            acapelaFallbackReason = fallbackReason ?: "تعثر نطق Acapela"
        }

        val record = UtteranceRecord(
            textSnippet = snippet,
            languageTag = languageTag,
            requestedEngine = reqEng,
            requestedVoice = reqV,
            actualEngine = actEng,
            actualVoice = actV,
            isFallback = isFallback,
            fallbackReason = fallbackReason
        )
        records.addFirst(record)
        while (records.size > MAX_RECORDS) {
            records.removeLast()
        }
    }

    fun noteAcapelaFailure(
        reason: String,
        substituteVoice: String?,
        substituteEngine: String?
    ) {
        acapelaFallbackActive = true
        acapelaFallbackReason = reason
        acapelaFallbackVoice = substituteVoice
        acapelaFallbackEngine = substituteEngine
    }

    fun isAcapelaFallbackActive(): Boolean = acapelaFallbackActive

    fun getRecentFormatted(): List<String> = records.map { it.format() }

    fun clear() {
        records.clear()
        acapelaFallbackActive = false
        acapelaFallbackVoice = null
        acapelaFallbackEngine = null
        acapelaFallbackReason = null
    }
}
