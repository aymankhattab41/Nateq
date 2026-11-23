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
        ): Record {
            val total = SystemClock.elapsedRealtime() - requestStartRealtimeMs
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
        val p95TotalDurationMs: Double
    )

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
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
            )
        }

        val success = subset.count { it.status == Status.SUCCESS }
        val cancelled = subset.count { it.status == Status.CANCELLED }
        val failed = subset.count { it.status == Status.FAILED }
        val unmeasured = subset.count { it.status == Status.UNMEASURED }

        val pcmValues = subset.map { it.firstPcmMs.coerceAtLeast(0L) }
        val writeValues = subset.map { it.firstTrackWriteMs.coerceAtLeast(0L) }
        val headValues =
            subset.map { it.playbackHeadStartMs.coerceAtLeast(0L) }
        val durValues = subset.map { it.totalDurationMs.coerceAtLeast(0L) }

        return CategoryStats(
            totalCount = total,
            successCount = success,
            cancelledCount = cancelled,
            failedCount = failed,
            unmeasuredCount = unmeasured,
            avgFirstPcmMs = pcmValues.average(),
            p95FirstPcmMs = percentile95(pcmValues),
            avgFirstTrackWriteMs = writeValues.average(),
            p95FirstTrackWriteMs = percentile95(writeValues),
            avgPlaybackHeadMs = headValues.average(),
            p95PlaybackHeadMs = percentile95(headValues),
            avgTotalDurationMs = durValues.average(),
            p95TotalDurationMs = percentile95(durValues)
        )
    }

    private fun percentile95(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val index = (0.95 * (sorted.size - 1)).toInt()
        return sorted[index].toDouble()
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

    fun getAllRecords(): List<Record> =
        synchronized(history) { history.toList() }

    fun clearForTest() {
        synchronized(history) { history.clear() }
    }
}
