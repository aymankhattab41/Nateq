package com.aymankhattab.nateq.core.audio.engine

import android.os.SystemClock
import java.util.Collections
import java.util.UUID

/**
 * متتبع شامل ودقيق لزمن استجابة نطق سلمى من لحظة طلب المستخدم حتى أول صوت
 * مسموع حقيقي.
 *
 * الخصائص:
 *  1. قياس أحادي الرتابة (monotonic) باستخدام [SystemClock.elapsedRealtime].
 *  2. ربط المراحل بمعرّف طلب موحد (correlation ID) عبر العمليات.
 *  3. التفريق بين: أول بايت PCM أصلي، وتسليمه للمشغل، وبدء تقدم رأس
 *     التشغيل (playback head) لسماع الصوت الفعلي.
 *  4. شمول الحالات الملغاة والفاشلة وغير المقيسة في إحصائيات المقام.
 *  5. فصل القياسات بين البارد (COLD) والدافئ (WARM) والتشخيصي (DIAGNOSTIC)
 *     والعادي (REGULAR) مع حساب p95 والمتوسط.
 *  6. متوافق ومدمج مع [UnifiedSpeechTracker].
 */
object SalmaSpeechTracker {

    enum class Category {
        COLD,
        WARM,
        WARMUP,
        DIAGNOSTIC,
        REGULAR
    }

    enum class Status {
        SUCCESS,
        CANCELLED,
        FAILED,
        UNMEASURED
    }

    data class Record(
        val correlationId: String,
        val requestStartTimeMs: Long,
        val category: Category,
        val status: Status,
        val textLength: Int,
        val voiceName: String,
        val queueWaitMs: Long,
        val voiceResolveMs: Long,
        val discoveryVerifyMs: Long,
        val hostBindMs: Long,
        val engineInitMs: Long,
        val warmupMs: Long,
        val speakCallMs: Long,
        val firstPcmMs: Long,
        val firstTrackWriteMs: Long,
        val playbackHeadStartMs: Long,
        val totalDurationMs: Long,
        val failureReason: String? = null
    )

    class ActiveRequest internal constructor(
        val correlationId: String,
        val category: Category,
        val voiceName: String,
        val textLength: Int,
        val requestStartRealtimeMs: Long
    ) {
        var queueWaitMs: Long = 0L
        var voiceResolveMs: Long = 0L
        var discoveryVerifyMs: Long = 0L
        var hostBindMs: Long = 0L
        var engineInitMs: Long = 0L
        var warmupMs: Long = 0L
        var speakCallMs: Long = 0L

        var firstPcmMs: Long = -1L
        var firstTrackWriteMs: Long = -1L
        var playbackHeadStartMs: Long = -1L

        private val isCompleted =
            java.util.concurrent.atomic.AtomicBoolean(false)
        val isCompletedRequest: Boolean get() = isCompleted.get()

        private var queueStart: Long = -1L
        private var resolveStart: Long = -1L
        private var verifyStart: Long = -1L
        private var hostBindStart: Long = -1L
        private var engineInitStart: Long = -1L
        private var warmupStart: Long = -1L
        private var speakCallStart: Long = -1L

        fun markQueueWaitStart() {
            queueStart = SystemClock.elapsedRealtime()
        }

        fun markQueueWaitEnd() {
            if (queueStart > 0L) {
                queueWaitMs = SystemClock.elapsedRealtime() - queueStart
                AcapelaPerformanceCounters.recordQueueWait(queueWaitMs)
            }
        }

        fun markVoiceResolveStart() {
            resolveStart = SystemClock.elapsedRealtime()
        }

        fun markVoiceResolveEnd() {
            if (resolveStart > 0L) {
                voiceResolveMs = SystemClock.elapsedRealtime() - resolveStart
            }
        }

        fun markDiscoveryVerifyStart() {
            verifyStart = SystemClock.elapsedRealtime()
        }

        fun markDiscoveryVerifyEnd() {
            if (verifyStart > 0L) {
                discoveryVerifyMs = SystemClock.elapsedRealtime() - verifyStart
            }
        }

        fun markHostBindStart() {
            hostBindStart = SystemClock.elapsedRealtime()
        }

        fun markHostBindEnd() {
            if (hostBindStart > 0L) {
                hostBindMs = SystemClock.elapsedRealtime() - hostBindStart
            }
        }

        fun markEngineInitStart() {
            engineInitStart = SystemClock.elapsedRealtime()
        }

        fun markEngineInitEnd() {
            if (engineInitStart > 0L) {
                engineInitMs = SystemClock.elapsedRealtime() - engineInitStart
            }
        }

        fun markWarmupStart() {
            warmupStart = SystemClock.elapsedRealtime()
        }

        fun markWarmupEnd() {
            if (warmupStart > 0L) {
                warmupMs = SystemClock.elapsedRealtime() - warmupStart
            }
        }

        fun markSpeakCallStart() {
            speakCallStart = SystemClock.elapsedRealtime()
        }

        fun markSpeakCallEnd() {
            if (speakCallStart > 0L) {
                speakCallMs = SystemClock.elapsedRealtime() - speakCallStart
            }
        }

        fun markFirstPcm() {
            if (firstPcmMs < 0L) {
                firstPcmMs =
                    SystemClock.elapsedRealtime() - requestStartRealtimeMs
            }
        }

        fun markFirstTrackWrite() {
            if (firstTrackWriteMs < 0L) {
                firstTrackWriteMs =
                    SystemClock.elapsedRealtime() - requestStartRealtimeMs
            }
        }

        fun markPlaybackHeadStarted() {
            if (playbackHeadStartMs < 0L) {
                playbackHeadStartMs =
                    SystemClock.elapsedRealtime() - requestStartRealtimeMs
            }
        }

        fun complete(
            status: Status = Status.SUCCESS,
            reason: String? = null
        ): Record? {
            if (!isCompleted.compareAndSet(false, true)) {
                return null
            }
            val total =
                SystemClock.elapsedRealtime() - requestStartRealtimeMs
            val finalStatus = if (status == Status.SUCCESS &&
                firstPcmMs < 0L
            ) {
                Status.UNMEASURED
            } else {
                status
            }
            val record = Record(
                correlationId = correlationId,
                requestStartTimeMs = requestStartRealtimeMs,
                category = category,
                status = finalStatus,
                textLength = textLength,
                voiceName = voiceName,
                queueWaitMs = queueWaitMs,
                voiceResolveMs = voiceResolveMs,
                discoveryVerifyMs = discoveryVerifyMs,
                hostBindMs = hostBindMs,
                engineInitMs = engineInitMs,
                warmupMs = warmupMs,
                speakCallMs = speakCallMs,
                firstPcmMs = firstPcmMs,
                firstTrackWriteMs = firstTrackWriteMs,
                playbackHeadStartMs = playbackHeadStartMs,
                totalDurationMs = total,
                failureReason = reason
            )
            recordCall(record)
            mirrorToUnifiedTracker(record)
            return record
        }

        private fun mirrorToUnifiedTracker(rec: Record) {
            val uCat = when (rec.category) {
                Category.COLD -> UnifiedSpeechTracker.Category.COLD
                Category.WARM -> UnifiedSpeechTracker.Category.WARM
                Category.WARMUP -> UnifiedSpeechTracker.Category.WARMUP
                Category.DIAGNOSTIC -> UnifiedSpeechTracker.Category.DIAGNOSTIC
                Category.REGULAR -> UnifiedSpeechTracker.Category.REGULAR
            }
            val uStat = when (rec.status) {
                Status.SUCCESS -> UnifiedSpeechTracker.Status.SUCCESS
                Status.CANCELLED -> UnifiedSpeechTracker.Status.CANCELLED
                Status.FAILED -> UnifiedSpeechTracker.Status.FAILED
                Status.UNMEASURED -> UnifiedSpeechTracker.Status.UNMEASURED
            }
            UnifiedSpeechTracker.recordCall(
                UnifiedSpeechTracker.Record(
                    correlationId = rec.correlationId,
                    engine = UnifiedSpeechTracker.Engine.ACAPELA,
                    requestStartTimeMs = rec.requestStartTimeMs,
                    category = uCat,
                    status = uStat,
                    textLength = rec.textLength,
                    voiceName = rec.voiceName,
                    queueWaitMs = rec.queueWaitMs,
                    voiceResolveMs = rec.voiceResolveMs,
                    discoveryVerifyMs = rec.discoveryVerifyMs,
                    hostBindMs = rec.hostBindMs,
                    engineInitMs = rec.engineInitMs,
                    warmupMs = rec.warmupMs,
                    speakCallMs = rec.speakCallMs,
                    firstPcmMs = rec.firstPcmMs,
                    firstTrackWriteMs = rec.firstTrackWriteMs,
                    playbackHeadStartMs = rec.playbackHeadStartMs,
                    totalDurationMs = rec.totalDurationMs,
                    failureReason = rec.failureReason
                )
            )
        }
    }

    private const val MAX_HISTORY = 100
    private val history = Collections.synchronizedList(mutableListOf<Record>())

    fun startRequest(
        correlationId: String? = null,
        category: Category = Category.REGULAR,
        voiceName: String = "salma",
        textLength: Int = 0
    ): ActiveRequest {
        val cid = correlationId?.takeIf { it.isNotBlank() }
            ?: "salma_${SystemClock.elapsedRealtime()}_" +
                UUID.randomUUID().toString().take(6)
        return ActiveRequest(
            correlationId = cid,
            category = category,
            voiceName = voiceName,
            textLength = textLength,
            requestStartRealtimeMs = SystemClock.elapsedRealtime()
        )
    }

    private fun recordCall(record: Record) {
        synchronized(history) {
            if (history.size >= MAX_HISTORY) {
                history.removeAt(0)
            }
            history.add(record)
        }
    }

    data class CategoryStats(
        val totalCount: Int,
        val successCount: Int,
        val cancelledCount: Int,
        val failedCount: Int,
        val unmeasuredCount: Int,
        val avgFirstPcmMs: Double,
        val p95FirstPcmMs: Double,
        val avgFirstTrackWriteMs: Double,
        val p95FirstTrackWriteMs: Double,
        val avgPlaybackHeadMs: Double,
        val p95PlaybackHeadMs: Double,
        val avgTotalDurationMs: Double,
        val p95TotalDurationMs: Double,
        val pcmMeasuredCount: Int = 0,
        val pcmUnmeasuredCount: Int = 0,
        val trackWriteMeasuredCount: Int = 0,
        val trackWriteUnmeasuredCount: Int = 0,
        val playbackHeadMeasuredCount: Int = 0,
        val playbackHeadUnmeasuredCount: Int = 0
    ) {
        fun formatMetric(valueMs: Double): String =
            if (valueMs < 0.0) "غير مقاس" else "${"%.1f".format(valueMs)}ms"
    }

    fun getStats(category: Category? = null): CategoryStats {
        val subset = synchronized(history) {
            if (category == null) {
                history.toList()
            } else {
                history.filter { it.category == category }
            }
        }
        val total = subset.size
        if (total == 0) {
            return CategoryStats(
                0, 0, 0, 0, 0,
                -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, -1.0
            )
        }

        val success = subset.count { it.status == Status.SUCCESS }
        val cancelled = subset.count { it.status == Status.CANCELLED }
        val failed = subset.count { it.status == Status.FAILED }
        val unmeasured = subset.count { it.status == Status.UNMEASURED }

        val pcmValues =
            subset.map { it.firstPcmMs }.filter { it >= 0L }
        val writeValues =
            subset.map { it.firstTrackWriteMs }.filter { it >= 0L }
        val headValues =
            subset.map { it.playbackHeadStartMs }.filter { it >= 0L }
        val durValues =
            subset.map { it.totalDurationMs }.filter { it >= 0L }

        val avgPcm =
            if (pcmValues.isNotEmpty()) pcmValues.average() else -1.0
        val p95Pcm =
            if (pcmValues.isNotEmpty()) {
                percentile95(pcmValues)
            } else -1.0

        val avgWrite =
            if (writeValues.isNotEmpty()) writeValues.average() else -1.0
        val p95Write =
            if (writeValues.isNotEmpty()) {
                percentile95(writeValues)
            } else -1.0

        val avgHead =
            if (headValues.isNotEmpty()) headValues.average() else -1.0
        val p95Head =
            if (headValues.isNotEmpty()) {
                percentile95(headValues)
            } else -1.0

        val avgDur =
            if (durValues.isNotEmpty()) durValues.average() else -1.0
        val p95Dur =
            if (durValues.isNotEmpty()) {
                percentile95(durValues)
            } else -1.0

        return CategoryStats(
            totalCount = total,
            successCount = success,
            cancelledCount = cancelled,
            failedCount = failed,
            unmeasuredCount = unmeasured,
            avgFirstPcmMs = avgPcm,
            p95FirstPcmMs = p95Pcm,
            avgFirstTrackWriteMs = avgWrite,
            p95FirstTrackWriteMs = p95Write,
            avgPlaybackHeadMs = avgHead,
            p95PlaybackHeadMs = p95Head,
            avgTotalDurationMs = avgDur,
            p95TotalDurationMs = p95Dur,
            pcmMeasuredCount = pcmValues.size,
            pcmUnmeasuredCount = total - pcmValues.size,
            trackWriteMeasuredCount = writeValues.size,
            trackWriteUnmeasuredCount = total - writeValues.size,
            playbackHeadMeasuredCount = headValues.size,
            playbackHeadUnmeasuredCount = total - headValues.size
        )
    }

    private fun percentile95(values: List<Long>): Double {
        if (values.isEmpty()) return -1.0
        val sorted = values.sorted()
        val index = (0.95 * (sorted.size - 1)).toInt()
        return sorted[index].toDouble()
    }

    fun noteFirstTrackWrite() {
        synchronized(history) {
            val last = history.lastOrNull() ?: return
            if (last.firstTrackWriteMs < 0L) {
                val writeMs =
                    SystemClock.elapsedRealtime() - last.requestStartTimeMs
                val updated = last.copy(firstTrackWriteMs = writeMs)
                history[history.size - 1] = updated
                UnifiedSpeechTracker.noteFirstTrackWrite(
                    UnifiedSpeechTracker.Engine.ACAPELA
                )
            }
        }
    }

    fun notePlaybackHeadAdvance() {
        synchronized(history) {
            val last = history.lastOrNull() ?: return
            if (last.playbackHeadStartMs < 0L) {
                val advanceMs =
                    SystemClock.elapsedRealtime() - last.requestStartTimeMs
                val updated = last.copy(playbackHeadStartMs = advanceMs)
                history[history.size - 1] = updated
                UnifiedSpeechTracker.notePlaybackHeadAdvance(
                    UnifiedSpeechTracker.Engine.ACAPELA
                )
            }
        }
    }

    fun formatReport(): String {
        val overall = getStats()
        if (overall.totalCount == 0) return "لا توجد طلبات مسجلة لسلمى"
        val sb = StringBuilder()
        sb.appendLine("=== إحصائيات زمن استجابة سلمى ===")
        sb.appendLine(
            "• الإجمالي: ${overall.totalCount} " +
                "(نجاح: ${overall.successCount}، " +
                "ملغى: ${overall.cancelledCount}، " +
                "فشل: ${overall.failedCount}، " +
                "غير مقاس: ${overall.unmeasuredCount})"
        )
        sb.appendLine(
            "• أول PCM (TTFB): " +
                "متوسط=${overall.formatMetric(overall.avgFirstPcmMs)} | " +
                "p95=${overall.formatMetric(overall.p95FirstPcmMs)} " +
                "(مقاس=${overall.pcmMeasuredCount}، " +
                "غير مقاس=${overall.pcmUnmeasuredCount})"
        )
        sb.appendLine(
            "• كتابة المشغل: " +
                "متوسط=" +
                "${overall.formatMetric(overall.avgFirstTrackWriteMs)} | " +
                "p95=${overall.formatMetric(overall.p95FirstTrackWriteMs)}"
        )
        sb.appendLine(
            "• تقدم الرأس: " +
                "متوسط=" +
                "${overall.formatMetric(overall.avgPlaybackHeadMs)} | " +
                "p95=${overall.formatMetric(overall.p95PlaybackHeadMs)}"
        )
        val warm = getStats(Category.WARM)
        if (warm.totalCount > 0) {
            sb.appendLine(
                "  [دافئ WARM (${warm.totalCount})]: TTFB " +
                    "متوسط=" +
                    "${warm.formatMetric(warm.avgFirstPcmMs)} | " +
                    "p95=${warm.formatMetric(warm.p95FirstPcmMs)}"
            )
        }
        val cold = getStats(Category.COLD)
        if (cold.totalCount > 0) {
            sb.appendLine(
                "  [بارد COLD (${cold.totalCount})]: TTFB " +
                    "متوسط=${cold.formatMetric(cold.avgFirstPcmMs)} | " +
                    "p95=${cold.formatMetric(cold.p95FirstPcmMs)}"
            )
        }
        return sb.toString().trimEnd()
    }

    fun getAllRecords(): List<Record> =
        synchronized(history) { history.toList() }

    fun clearForTest() {
        synchronized(history) { history.clear() }
    }
}
