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
        val textHash: String = "00000000",
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
        val maxGapMs: Long = 0L,
        val gapCountOverThreshold: Int = 0,
        val maxGapOffsetMs: Long = 0L,
        val underrunCount: Int = 0,
        val writeWaitMs: Long = 0L,
        val failureReason: String? = null
    )

    class ActiveRequest internal constructor(
        val correlationId: String,
        val category: Category,
        val voiceName: String,
        val textLength: Int,
        val textHash: String = "00000000",
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

        var maxGapMs: Long = 0L
        var gapCountOverThreshold: Int = 0
        var maxGapOffsetMs: Long = 0L
        var underrunCount: Int = 0
        var writeWaitMs: Long = 0L

        private var lastPcmUptimeMs: Long = -1L
        private var pcmChunkCount: Int = 0

        fun onPcmChunkReceived(validLength: Int = 0) {
            val nowUptime = SystemClock.uptimeMillis()
            val nowRealtime = SystemClock.elapsedRealtime()
            if (firstPcmMs < 0L) {
                firstPcmMs = nowRealtime - requestStartRealtimeMs
            }
            pcmChunkCount++
            if (lastPcmUptimeMs > 0L) {
                val gap = nowUptime - lastPcmUptimeMs
                if (gap > maxGapMs) {
                    maxGapMs = gap
                    maxGapOffsetMs = nowRealtime - requestStartRealtimeMs
                }
                if (gap >= GAP_THRESHOLD_MS) {
                    gapCountOverThreshold++
                }
            }
            lastPcmUptimeMs = nowUptime
        }

        fun noteUnderrun(count: Int) {
            if (count > underrunCount) {
                underrunCount = count
            }
        }

        fun markFirstPcm() {
            onPcmChunkReceived(0)
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
                textHash = textHash,
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
                maxGapMs = maxGapMs,
                gapCountOverThreshold = gapCountOverThreshold,
                maxGapOffsetMs = maxGapOffsetMs,
                underrunCount = underrunCount,
                writeWaitMs = writeWaitMs,
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

    const val GAP_THRESHOLD_MS = 150L
    private const val MAX_HISTORY = 200
    private val history = Collections.synchronizedList(mutableListOf<Record>())

    @Volatile
    var currentActiveRequest: ActiveRequest? = null
        internal set

    fun computeTextHash(text: String): String {
        if (text.isEmpty()) return "00000000"
        return runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(text.toByteArray(Charsets.UTF_8))
            digest.take(4).joinToString("") { "%02x".format(it) }
        }.getOrDefault("00000000")
    }

    fun startRequest(
        correlationId: String? = null,
        category: Category = Category.REGULAR,
        voiceName: String = "salma",
        textLength: Int = 0,
        textHash: String = "00000000"
    ): ActiveRequest {
        val cid = correlationId?.takeIf { it.isNotBlank() }
            ?: "salma_${SystemClock.elapsedRealtime()}_" +
                UUID.randomUUID().toString().take(6)
        val active = ActiveRequest(
            correlationId = cid,
            category = category,
            voiceName = voiceName,
            textLength = textLength,
            textHash = textHash,
            requestStartRealtimeMs = SystemClock.elapsedRealtime()
        )
        currentActiveRequest = active
        return active
    }

    fun startRequest(
        correlationId: String? = null,
        category: Category = Category.REGULAR,
        voiceName: String = "salma",
        text: String
    ): ActiveRequest = startRequest(
        correlationId = correlationId,
        category = category,
        voiceName = voiceName,
        textLength = text.length,
        textHash = computeTextHash(text)
    )

    fun noteUnderrun(count: Int) {
        val active = currentActiveRequest
        if (active != null && !active.isCompletedRequest) {
            active.noteUnderrun(count)
        }
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

    fun getStats(
        category: Category? = null,
        voiceFilter: String? = null
    ): CategoryStats {
        val subset = synchronized(history) {
            history.filter { rec ->
                (category == null || rec.category == category) &&
                    (voiceFilter == null ||
                        rec.voiceName.equals(voiceFilter, ignoreCase = true))
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
        val active = currentActiveRequest
        if (active != null && !active.isCompletedRequest) {
            active.markFirstTrackWrite()
        }
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
        val active = currentActiveRequest
        if (active != null && !active.isCompletedRequest) {
            active.markPlaybackHeadStarted()
        }
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

    fun formatReport(voiceFilter: String? = null): String {
        val overall = getStats(category = null, voiceFilter = voiceFilter)
        val headerTitle = if (!voiceFilter.isNullOrBlank()) {
            "=== إحصائيات زمن استجابة صوت $voiceFilter ==="
        } else {
            "=== إحصائيات زمن استجابة محرك Acapela ==="
        }
        if (overall.totalCount == 0) {
            return if (!voiceFilter.isNullOrBlank()) {
                "لا توجد طلبات مسجلة لصوت $voiceFilter"
            } else {
                "لا توجد طلبات مسجلة لمحرك Acapela"
            }
        }
        val sb = StringBuilder()
        sb.appendLine(headerTitle)
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
        sb.appendLine("")
        sb.append(formatAcapelaLatencySummary())
        return sb.toString().trimEnd()
    }

    fun percentile(sorted: List<Long>, pct: Double): Long {
        if (sorted.isEmpty()) return 0L
        val idx = (pct * (sorted.size - 1)).toInt()
        return sorted[idx]
    }

    private fun formatMs(v: Double): String =
        if (v < 0.0) "غير مقاس" else "${"%.1f".format(v)}ms"

    private fun formatMs(v: Long): String =
        if (v < 0L) "غير مقاس" else "${v}ms"

    fun formatAcapelaLatencySummary(
        records: List<Record> = getAllRecords()
    ): String {
        if (records.isEmpty()) {
            return buildString {
                appendLine("=== ملخص زمن استجابة وفجوات نطق Acapela ===")
                append("لا عبارات مسجلة بعد")
            }
        }
        val sb = StringBuilder()
        sb.appendLine("=== ملخص زمن استجابة وفجوات نطق Acapela ===")
        val count = records.size
        sb.appendLine("• إجمالي العبارات المسجلة: $count (آخر $MAX_HISTORY)")

        // 1) أول PCM
        val pcmList = records.map { it.firstPcmMs }
            .filter { it >= 0L }.sorted()
        val pcmMeasured = pcmList.size
        val pcmUnmeasured = count - pcmMeasured
        val pcmAvg = if (pcmMeasured > 0) pcmList.average() else -1.0
        val pcmP50 = if (pcmMeasured > 0) percentile(pcmList, 0.50) else -1L
        val pcmP95 = if (pcmMeasured > 0) percentile(pcmList, 0.95) else -1L
        val pcmMax = if (pcmMeasured > 0) pcmList.last() else -1L
        sb.appendLine(
            "• أول PCM (TTFB): متوسط=${formatMs(pcmAvg)} | " +
                "p50=${formatMs(pcmP50)} | p95=${formatMs(pcmP95)} | " +
                "أقصى=${formatMs(pcmMax)} " +
                "(مقاس=$pcmMeasured، غير مقاس=$pcmUnmeasured)"
        )

        // 2) الزمن الكلي
        val durList = records.map { it.totalDurationMs }
            .filter { it >= 0L }.sorted()
        val durAvg = if (durList.isNotEmpty()) durList.average() else -1.0
        val durP50 = if (durList.isNotEmpty()) {
            percentile(durList, 0.50)
        } else -1L
        val durP95 = if (durList.isNotEmpty()) {
            percentile(durList, 0.95)
        } else -1L
        val durMax = if (durList.isNotEmpty()) durList.last() else -1L
        sb.appendLine(
            "• الزمن الكلي: متوسط=${formatMs(durAvg)} | " +
                "p50=${formatMs(durP50)} | p95=${formatMs(durP95)} | " +
                "أقصى=${formatMs(durMax)}"
        )

        // 3) أكبر فجوة داخلية
        val gapList = records.map { it.maxGapMs }.sorted()
        val gapAvg = if (gapList.isNotEmpty()) gapList.average() else 0.0
        val gapP50 = if (gapList.isNotEmpty()) {
            percentile(gapList, 0.50)
        } else 0L
        val gapP95 = if (gapList.isNotEmpty()) {
            percentile(gapList, 0.95)
        } else 0L
        val gapMax = if (gapList.isNotEmpty()) gapList.last() else 0L
        val totalGapsOver150 = records.sumOf { it.gapCountOverThreshold }
        val phrasesWithGapsOver150 = records.count {
            it.gapCountOverThreshold > 0
        }
        sb.appendLine(
            "• أكبر فجوة داخلية: متوسط=${formatMs(gapAvg)} | " +
                "p50=${formatMs(gapP50)} | p95=${formatMs(gapP95)} | " +
                "أقصى=${formatMs(gapMax)} " +
                "(فجوات>150ms: $totalGapsOver150 في " +
                "$phrasesWithGapsOver150 عبارة)"
        )

        // 4) الانتظار في الطابور
        val qList = records.map { it.queueWaitMs }.sorted()
        val qAvg = if (qList.isNotEmpty()) qList.average() else 0.0
        val qP50 = if (qList.isNotEmpty()) percentile(qList, 0.50) else 0L
        val qP95 = if (qList.isNotEmpty()) percentile(qList, 0.95) else 0L
        val qMax = if (qList.isNotEmpty()) qList.last() else 0L
        sb.appendLine(
            "• الانتظار في الطابور: متوسط=${formatMs(qAvg)} | " +
                "p50=${formatMs(qP50)} | p95=${formatMs(qP95)} | " +
                "أقصى=${formatMs(qMax)}"
        )

        // أبطأ 10 عبارات
        val slowest10 = records.sortedByDescending { it.totalDurationMs }
            .take(10)
        if (slowest10.isNotEmpty()) {
            sb.appendLine("• أبطأ 10 عبارات زمنياً:")
            val sdf = java.text.SimpleDateFormat(
                "HH:mm:ss", java.util.Locale.US
            )
            slowest10.forEachIndexed { idx, rec ->
                val timeStr = sdf.format(
                    java.util.Date(rec.requestStartTimeMs)
                )
                val stateStr = if (rec.category == Category.COLD) {
                    "بارد (بعد خمول)"
                } else {
                    "دافئ"
                }
                val gapExtra = if (rec.gapCountOverThreshold > 0) {
                    " (@+${rec.maxGapOffsetMs}ms، " +
                        "${rec.gapCountOverThreshold} >150ms)"
                } else if (rec.maxGapMs > 0L) {
                    " (@+${rec.maxGapOffsetMs}ms)"
                } else {
                    ""
                }
                val firstPcmStr = if (rec.firstPcmMs < 0L) {
                    "غير مقاس"
                } else {
                    "${rec.firstPcmMs}ms"
                }
                sb.appendLine(
                    "  ${idx + 1}. [$timeStr] صوت: ${rec.voiceName} | " +
                        "طول: ${rec.textLength} حرفاً | " +
                        "بصمة: #${rec.textHash} | " +
                        "إجمالي: ${rec.totalDurationMs}ms | " +
                        "أول PCM: $firstPcmStr | " +
                        "أكبر فجوة: ${rec.maxGapMs}ms$gapExtra | " +
                        "انتظار: ${rec.queueWaitMs}ms | حالة: $stateStr"
                )
            }
        }
        return sb.toString().trimEnd()
    }

    fun getAllRecords(): List<Record> =
        synchronized(history) { history.toList() }

    fun clearForTest() {
        synchronized(history) { history.clear() }
    }
}
