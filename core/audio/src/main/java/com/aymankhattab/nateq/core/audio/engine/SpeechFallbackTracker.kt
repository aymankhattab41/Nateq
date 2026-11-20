package com.aymankhattab.nateq.core.audio.engine

import android.util.Log
import com.aymankhattab.nateq.core.audio.enginehost.HostLog
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import com.aymankhattab.nateq.core.audio.enginehost.SpeechTextRedactor
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

    private const val TAG = "SpeechFallbackTracker"

    data class UtteranceRecord(
        val timestamp: Long = System.currentTimeMillis(),
        val source: String = "عام",
        val textSnippet: String,
        val fullText: String,
        val languageTag: String,
        val requestedEngine: String,
        val requestedVoice: String,
        val actualEngine: String,
        val actualVoice: String,
        val isFallback: Boolean,
        val fallbackType: String? = null,
        val fallbackReason: String? = null,
        val acapelaNSpeak: Int? = null,
        val acapelaPcmBytes: Long? = null,
        val exceptionMessage: String? = null
    ) {
        fun format(): String {
            val dateStr = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US
            ).format(Date(timestamp))
            val fallbackStr = if (isFallback) {
                " [بديل (Fallback) بسبب: ${fallbackReason ?: "غير محدد"}]"
            } else {
                " [مباشر]"
            }
            val acaStr = if (acapelaNSpeak != null ||
                acapelaPcmBytes != null
            ) {
                " | Acapela: nSpeak=${acapelaNSpeak ?: 0}، " +
                    "pcm=${acapelaPcmBytes ?: 0}"
            } else ""
            val excStr = if (!exceptionMessage.isNullOrBlank()) {
                " | استثناء: $exceptionMessage"
            } else ""
            return "[$dateStr] [$source] النص: '$textSnippet' " +
                "($fullText) | " +
                "المطلوب: $requestedVoice ($requestedEngine) -> " +
                "الفعلي: $actualVoice ($actualEngine)" +
                "$fallbackStr$acaStr$excStr"
        }
    }

    const val MAX_RECORDS = 200
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
        fallbackReason: String? = null,
        source: String = "قارئ شاشة",
        fallbackType: String? = null,
        acapelaNSpeak: Int? = null,
        acapelaPcmBytes: Long? = null,
        exception: Throwable? = null
    ) {
        if (text.isBlank()) return
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

        val snippet = if (text.length > 40) {
            text.take(37) + "..."
        } else {
            text
        }

        val record = UtteranceRecord(
            source = source,
            textSnippet = snippet,
            // البند ١٥: النص الكامل لا يُحفظ ولا يُسجَّل؛ نحتفظ ببصمة
            // مختصرة تسمح بربط السجل بالطلب من دون كشف محتواه.
            fullText = SpeechTextRedactor.describe(text),
            languageTag = languageTag,
            requestedEngine = reqEng,
            requestedVoice = reqV,
            actualEngine = actEng,
            actualVoice = actV,
            isFallback = isFallback,
            fallbackType = fallbackType,
            fallbackReason = fallbackReason,
            acapelaNSpeak = acapelaNSpeak,
            acapelaPcmBytes = acapelaPcmBytes,
            exceptionMessage = exception?.let {
                "${it.javaClass.simpleName}: ${it.message ?: ""}"
            }
        )
        records.addFirst(record)
        while (records.size > MAX_RECORDS) {
            records.removeLast()
        }

        val logLine = record.format()
        Log.i(TAG, logLine)
        val targetEngine = HostProtocol.normalizeEngineId(
            record.actualEngine ?: record.requestedEngine ?: "speech"
        )
        runCatching {
            HostLog.i(targetEngine, logLine)
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

    fun clearAcapelaFallbackState() {
        acapelaFallbackActive = false
        acapelaFallbackVoice = null
        acapelaFallbackEngine = null
        acapelaFallbackReason = null
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
