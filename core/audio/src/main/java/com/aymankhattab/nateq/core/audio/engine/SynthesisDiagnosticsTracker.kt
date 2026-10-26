package com.aymankhattab.nateq.core.audio.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * سجل تشخيصي لكل طلب تخليق (الجولة 55 - الأمر 1).
 * يحتفظ بآخر 100 طلب لتشخيص أسباب بتر الصوت أو تعطل المحركات كـ Acapela.
 */
object SynthesisDiagnosticsTracker {

    private const val MAX_RECORDS = 100

    data class DiagnosticRecord(
        val timestamp: Long = System.currentTimeMillis(),
        val engine: String,
        val voice: String,
        val textLength: Int,
        val partsCount: Int = 1,
        val status: Int,
        val timeToFirstByteMs: Long = 0L,
        val onDoneTimestamp: Long = 0L,
        val expectedUtteranceId: String,
        val receivedUtteranceId: String?,
        val fileBytes: Long,
        val declaredWavBytes: Long,
        val stallPolls: Int,
        val flushedAfterStall: Boolean,
        val durationMs: Long,
        val expectedDurationMs: Long,
        val isTruncated: Boolean,
        val retryCount: Int = 0,
        val sanitizedCharsRemoved: Int = 0
    ) {
        fun format(): String {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
            val timeStr = sdf.format(Date(timestamp))
            val truncStr = if (isTruncated) " [مبتور ⚠️]" else ""
            val retryStr = if (retryCount > 0) " (إعادة #$retryCount)" else ""
            val stallStr = if (flushedAfterStall) " [تفريغ بعد جمود]" else ""
            val sanStr = if (sanitizedCharsRemoved > 0) {
                " تنقية=$sanitizedCharsRemoved"
            } else {
                ""
            }
            return buildString {
                append("• [$timeStr] محرك=$engine صوت=$voice")
                append("$truncStr$retryStr\n")
                append("   طول_النص=$textLength أجزاء=$partsCount حالة=$status")
                append(" أول_بايت=${timeToFirstByteMs}ms$sanStr\n")
                append("   عبارة_متوقعة=$expectedUtteranceId")
                append(" مستلمة=${receivedUtteranceId ?: "لم_تصل"}\n")
                append("   ملف_فعلي=${fileBytes}B ")
                append("مصرح_WAV=${declaredWavBytes}B ")
                append("جمود=$stallPolls$stallStr\n")
                append("   مدة_صوت=${durationMs}ms ")
                append("متوقعة=${expectedDurationMs}ms")
            }
        }
    }

    private val records = ConcurrentLinkedDeque<DiagnosticRecord>()
    private val totalRequests = AtomicInteger(0)
    private val totalTruncated = AtomicInteger(0)

    fun record(entry: DiagnosticRecord) {
        totalRequests.incrementAndGet()
        if (entry.isTruncated) {
            totalTruncated.incrementAndGet()
        }
        records.addLast(entry)
        while (records.size > MAX_RECORDS) {
            records.pollFirst()
        }
    }

    fun getRecent(): List<DiagnosticRecord> = records.toList()

    fun totalRequestsCount(): Int = totalRequests.get()
    fun totalTruncatedCount(): Int = totalTruncated.get()

    fun formatReport(): String {
        val list = getRecent()
        val sb = StringBuilder()
        sb.appendLine("=== سجل التخليق التشخيصي (الجولة 55) ===")
        sb.appendLine(
            "إجمالي الطلبات: ${totalRequests.get()}  " +
                "المبتورة: ${totalTruncated.get()}  " +
                "المحفوظة: ${list.size}/$MAX_RECORDS"
        )
        sb.appendLine("")
        if (list.isEmpty()) {
            sb.appendLine("• لا يوجد طلبات تخليق مسجلة بعد.")
        } else {
            // عرض أحدث 20 طلباً بترتيب عكسي لتسهيل المراجعة السريعة
            val recent = list.takeLast(20).reversed()
            recent.forEach { rec ->
                sb.appendLine(rec.format())
            }
        }
        return sb.toString()
    }

    @androidx.annotation.VisibleForTesting
    fun resetForTesting() {
        records.clear()
        totalRequests.set(0)
        totalTruncated.set(0)
    }
}
