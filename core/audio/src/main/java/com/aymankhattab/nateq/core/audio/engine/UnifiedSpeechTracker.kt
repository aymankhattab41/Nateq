package com.aymankhattab.nateq.core.audio.engine

import android.os.SystemClock
import java.util.Collections
import java.util.UUID

/**
 * متتبع شامل وموحد لزمن استجابة النطق عبر كافة المحركات المدمجة
 * (Acapela، Vocalizer، Microsoft، والنظام الافتراضي).
 *
 * الخصائص:
 *  1. قياس أحادي الرتابة (monotonic) باستخدام [SystemClock.elapsedRealtime].
 *  2. ربط المراحل بمعرّف طلب موحد (correlation ID) عبر العمليات.
 *  3. تسجيل دقيق لأول بايت PCM، وتسليمه للمشغل، وبدء تقدم رأس التشغيل.
 *  4. شمول الحالات الملغاة والفاشلة وغير المقيسة في إحصائيات المقام.
 *  5. فصل القياسات بين البارد (COLD) والدافئ (WARM) والتبديل (SWITCH)
 *     والإحماء (WARMUP) والتشخيصي (DIAGNOSTIC) والعادي (REGULAR).
 *  6. توفير إحصاءات p50 و p95 والمتوسط لكل محرك.
 */
object UnifiedSpeechTracker {

    enum class Engine {
        ACAPELA,
        VOCALIZER,
        MICROSOFT,
        SYSTEM,
        UNKNOWN
    }

    enum class Category {
        COLD,
        WARM,
        SWITCH,
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
        val engine: Engine,
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
        val engine: Engine,
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
                when (engine) {
                    Engine.ACAPELA ->
                        AcapelaPerformanceCounters.recordQueueWait(queueWaitMs)
                    Engine.VOCALIZER ->
                        EnginePerformanceCounters.vocalizer.queueWaitMs
                            .addAndGet(queueWaitMs)
                    Engine.MICROSOFT ->
                        EnginePerformanceCounters.microsoft.queueWaitMs
                            .addAndGet(queueWaitMs)
                    else -> {}
                }
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
            val finalStatus = if (status == Status.SUCCESS && firstPcmMs < 0L) {
                Status.UNMEASURED
            } else {
                status
            }
            val record = Record(
                correlationId = correlationId,
                engine = engine,
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
            return record
        }
    }

    private const val MAX_HISTORY = 200
    private val history = Collections.synchronizedList(mutableListOf<Record>())

    fun startRequest(
        engine: Engine = Engine.UNKNOWN,
        correlationId: String? = null,
        category: Category = Category.REGULAR,
        voiceName: String = "",
        textLength: Int = 0
    ): ActiveRequest {
        val prefix = engine.name.lowercase()
        val cid = correlationId?.takeIf { it.isNotBlank() }
            ?: "${prefix}_${SystemClock.elapsedRealtime()}_" +
                UUID.randomUUID().toString().take(6)
        return ActiveRequest(
            correlationId = cid,
            engine = engine,
            category = category,
            voiceName = voiceName,
            textLength = textLength,
            requestStartRealtimeMs = SystemClock.elapsedRealtime()
        )
    }

    internal fun recordCall(record: Record) {
        synchronized(history) {
            if (history.size >= MAX_HISTORY) {
                history.removeAt(0)
            }
            history.add(record)
        }
    }

    data class EngineStats(
        val totalCount: Int,
        val successCount: Int,
        val cancelledCount: Int,
        val failedCount: Int,
        val unmeasuredCount: Int,
        val avgFirstPcmMs: Double,
        val p50FirstPcmMs: Double,
        val p95FirstPcmMs: Double,
        val avgFirstTrackWriteMs: Double,
        val p50FirstTrackWriteMs: Double,
        val p95FirstTrackWriteMs: Double,
        val avgPlaybackHeadMs: Double,
        val p50PlaybackHeadMs: Double,
        val p95PlaybackHeadMs: Double,
        val avgTotalDurationMs: Double,
        val p50TotalDurationMs: Double,
        val p95TotalDurationMs: Double
    )

    fun getStats(
        engine: Engine? = null,
        category: Category? = null
    ): EngineStats {
        val subset = synchronized(history) {
            history.filter { r ->
                (engine == null || r.engine == engine) &&
                    (category == null || r.category == category)
            }
        }
        val total = subset.size
        if (total == 0) {
            return EngineStats(
                0, 0, 0, 0, 0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0
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

        return EngineStats(
            totalCount = total,
            successCount = success,
            cancelledCount = cancelled,
            failedCount = failed,
            unmeasuredCount = unmeasured,
            avgFirstPcmMs = pcmValues.average(),
            p50FirstPcmMs = percentile(pcmValues, 0.50),
            p95FirstPcmMs = percentile(pcmValues, 0.95),
            avgFirstTrackWriteMs = writeValues.average(),
            p50FirstTrackWriteMs = percentile(writeValues, 0.50),
            p95FirstTrackWriteMs = percentile(writeValues, 0.95),
            avgPlaybackHeadMs = headValues.average(),
            p50PlaybackHeadMs = percentile(headValues, 0.50),
            p95PlaybackHeadMs = percentile(headValues, 0.95),
            avgTotalDurationMs = durValues.average(),
            p50TotalDurationMs = percentile(durValues, 0.50),
            p95TotalDurationMs = percentile(durValues, 0.95)
        )
    }

    private fun percentile(values: List<Long>, pct: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val index = (pct * (sorted.size - 1)).toInt()
        return sorted[index].toDouble()
    }

    fun notePlaybackHeadAdvance(engine: Engine? = null) {
        synchronized(history) {
            val candidate = history.lastOrNull { r ->
                (engine == null || r.engine == engine) &&
                    r.playbackHeadStartMs < 0L
            } ?: return
            val advanceMs =
                SystemClock.elapsedRealtime() - candidate.requestStartTimeMs
            val idx = history.indexOf(candidate)
            if (idx >= 0) {
                history[idx] = candidate.copy(playbackHeadStartMs = advanceMs)
            }
        }
    }

    fun getAllRecords(): List<Record> =
        synchronized(history) { history.toList() }

    fun clearForTest() {
        synchronized(history) { history.clear() }
    }
}
