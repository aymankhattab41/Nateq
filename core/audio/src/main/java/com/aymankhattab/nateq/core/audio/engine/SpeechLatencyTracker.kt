package com.aymankhattab.nateq.core.audio.engine

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * متتبع سرعة وزمن تخليق العبارات (الجولة 50 - الأمر 3).
 * يسجل آخر 50 عبارة: زمن المعالجة والتخطيط، إحصاءات كل مقطع،
 * زمن أول بايت صوت (TTFB)، وزمن الانتهاء الكلي.
 */
object SpeechLatencyTracker {

    private const val MAX_HISTORY = 50

    /** بند ١٢: قيمة بند TTFB حين لم يُسجَّل بايتٌ أول فعلاً. سالبةٌ
     *  كي لا تُخلط بقياسٍ صحيح ولا تُحتسب في المتوسط. */
    const val TTFB_NOT_MEASURED = -1L

    data class SegmentMetric(
        val text: String,
        val engine: String?,
        val voiceId: String?,
        val startUptimeMs: Long,
        val endUptimeMs: Long,
        val durationMs: Long,
        val attempts: Int,
        val success: Boolean
    )

    data class UtteranceMetric(
        val text: String,
        val preprocessDurationMs: Long,
        val planningDurationMs: Long,
        val segments: List<SegmentMetric>,
        val timeToFirstByteMs: Long,
        val totalDurationMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val history = ConcurrentLinkedDeque<UtteranceMetric>()
    private val droppedSegmentsCounter = AtomicInteger(0)

    fun recordDroppedSegment(segmentText: String) {
        droppedSegmentsCounter.incrementAndGet()
    }

    fun getDroppedSegmentsCount(): Int = droppedSegmentsCounter.get()

    fun recordUtterance(metric: UtteranceMetric) {
        history.addLast(metric)
        while (history.size > MAX_HISTORY) {
            history.pollFirst()
        }
    }

    fun getAverageDurationMs(): Long {
        val list = history.toList()
        if (list.isEmpty()) return 0L
        return list.map { it.totalDurationMs }.average().toLong()
    }

    fun getP95DurationMs(): Long {
        val list = history.map { it.totalDurationMs }.sorted()
        if (list.isEmpty()) return 0L
        val index = ((list.size - 1) * 0.95).toInt()
        return list[index]
    }

    fun getAverageTimeToFirstByteMs(): Long {
        // بند ١٢: القياسات الغائبة مستبعدة من المتوسط لا مُستبدَلة بزمن
        // العبارة الكلي (كانت تُظهر «أول بايت» بمدة النطق كلِّه).
        val measured = history.toList()
            .map { it.timeToFirstByteMs }
            .filter { it >= 0L }
        if (measured.isEmpty()) return TTFB_NOT_MEASURED
        return measured.average().toLong()
    }

    /** عدد العبارات التي لم يُسجَّل لها بايتٌ أول (بند ١٢). */
    fun getUnmeasuredTtfbCount(): Int =
        history.count { it.timeToFirstByteMs == TTFB_NOT_MEASURED }

    fun getTop5Slowest(): List<UtteranceMetric> {
        return history.toList()
            .sortedByDescending { it.totalDurationMs }
            .take(5)
    }

    fun formatSpeedReport(): String {
        val list = history.toList()
        val sb = StringBuilder()
        sb.appendLine("--- تقرير السرعة ---")
        val count = list.size
        sb.appendLine("• إجمالي العبارات المسجلة: $count (آخر $MAX_HISTORY)")
        sb.appendLine("• متوسط زمن النطق: ${getAverageDurationMs()} ms")
        sb.appendLine("• زمن p95 للنطق: ${getP95DurationMs()} ms")
        val avgPreprocess = if (list.isEmpty()) {
            0L
        } else {
            list.map { it.preprocessDurationMs }.average().toLong()
        }
        val avgPlanning = if (list.isEmpty()) {
            0L
        } else {
            list.map { it.planningDurationMs }.average().toLong()
        }
        sb.appendLine("• متوسط زمن المعالجة المسبقة: $avgPreprocess ms")
        sb.appendLine("• متوسط زمن التخطيط: $avgPlanning ms")
        val avgTtfb = getAverageTimeToFirstByteMs()
        val ttfbText = if (avgTtfb == TTFB_NOT_MEASURED) {
            "غير مقيس"
        } else {
            "$avgTtfb ms"
        }
        sb.appendLine("• متوسط زمن أول بايت (TTFB): $ttfbText")
        sb.appendLine(
            "• عبارات بلا قياس أول بايت: ${getUnmeasuredTtfbCount()}"
        )
        sb.appendLine(
            "• عدد المقاطع المحذوفة (droppedSegments): " +
                "${getDroppedSegmentsCount()}"
        )

        val top5 = getTop5Slowest()
        if (top5.isNotEmpty()) {
            sb.appendLine("• أطول 5 عبارات زمنياً:")
            top5.forEachIndexed { idx, item ->
                val preview = if (item.text.length > 30) {
                    item.text.take(30) + "…"
                } else {
                    item.text
                }
                val itemTtfb = if (item.timeToFirstByteMs ==
                    TTFB_NOT_MEASURED
                ) {
                    "غير مقيس"
                } else {
                    "${item.timeToFirstByteMs}ms"
                }
                sb.appendLine(
                    "  ${idx + 1}. \"$preview\" — إجمالي: " +
                        "${item.totalDurationMs}ms (معالجة: " +
                        "${item.preprocessDurationMs}ms, تخطيط: " +
                        "${item.planningDurationMs}ms, أول بايت: " +
                        "$itemTtfb, " +
                        "${item.segments.size} مقاطع)"
                )
            }
        }
        return sb.toString()
    }

    @androidx.annotation.VisibleForTesting
    fun resetForTesting() {
        history.clear()
        droppedSegmentsCounter.set(0)
    }
}
