package com.aymankhattab.nateq.core.audio.engine

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * تشخيص استدعاءات محرك SpeechFlow (in.visionHacks.eloquencePlus).
 * يوثق هل يُستدعى، وما الاستجابة، وهل تصل عينات صوت، وأي استثناء يحدث.
 */
object SpeechFlowDiagnostics {

    private const val TAG = "SpeechFlowDiag"
    const val PACKAGE_NAME = "in.visionHacks.eloquencePlus"

    private val called = AtomicBoolean(false)
    private val callCounter = AtomicInteger(0)
    private val lastCallEpoch = AtomicLong(0L)
    private val totalBytes = AtomicLong(0L)
    private val totalSamples = AtomicLong(0L)

    @Volatile
    private var lastResponseStatus: Int? = null

    @Volatile
    private var lastStatusText: String? = null

    @Volatile
    private var lastExceptionText: String? = null

    @Volatile
    private var lastUtteranceText: String? = null

    fun isCalled(): Boolean = called.get()

    fun recordCall(text: String) {
        called.set(true)
        callCounter.incrementAndGet()
        lastCallEpoch.set(System.currentTimeMillis())
        lastUtteranceText = text
        Log.i(TAG, "استدعاء محرك SpeechFlow: \"$text\"")
    }

    fun recordResponse(status: Int) {
        lastResponseStatus = status
        Log.i(TAG, "استجابة محرك SpeechFlow: status=$status")
    }

    fun recordAudioChunk(bytes: Int, sampleRate: Int = 22050) {
        if (bytes > 0) {
            totalBytes.addAndGet(bytes.toLong())
            val samples = bytes / 2L
            totalSamples.addAndGet(samples)
        }
    }

    fun recordCompletion(status: String, exception: Throwable? = null) {
        lastStatusText = status
        if (exception != null) {
            lastExceptionText = "${exception.javaClass.simpleName}: " +
                "${exception.message ?: ""}"
            Log.w(TAG, "اكتمل نداء SpeechFlow بخطأ: $status", exception)
        } else {
            Log.i(TAG, "اكتمل نداء SpeechFlow بنجاح: $status")
        }
    }

    fun buildReport(): String = buildString {
        appendLine("--- تشخيص محرك SpeechFlow ($PACKAGE_NAME) ---")
        val wasCalled = called.get()
        appendLine("  - هل يُستدعى: ${if (wasCalled) "نعم" else "لا"}")
        if (wasCalled) {
            appendLine("  - عدد مرات الاستدعاء: ${callCounter.get()}")
            val dateStr = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US
            ).format(Date(lastCallEpoch.get()))
            appendLine("  - وقت آخر استدعاء: $dateStr")
            appendLine(
                "  - آخر نص مطلوب: \"${lastUtteranceText ?: "(فارغ)"}\""
            )
            appendLine(
                "  - رمز استجابة synthesizeToFile: " +
                    "${lastResponseStatus ?: "لم يُسجل"}"
            )
            appendLine(
                "  - حالة الإتمام: ${lastStatusText ?: "قيد المعالجة"}"
            )
            val bytes = totalBytes.get()
            val samples = totalSamples.get()
            val hasAudio = bytes > 0
            appendLine(
                "  - هل تصل عينات صوت: ${if (hasAudio) "نعم" else "لا"} " +
                    "($bytes بايت، $samples عينة)"
            )
            appendLine(
                "  - الاستثناءات: " +
                    (lastExceptionText ?: "لا توجد استثناءات مسجلة")
            )
        } else {
            appendLine(
                "  - تنبيه: لم يُسجَّل أي نداء موجه لمحرك SpeechFlow حتى الآن"
            )
        }
    }

    fun clear() {
        called.set(false)
        callCounter.set(0)
        lastCallEpoch.set(0L)
        totalBytes.set(0L)
        totalSamples.set(0L)
        lastResponseStatus = null
        lastStatusText = null
        lastExceptionText = null
        lastUtteranceText = null
    }
}
