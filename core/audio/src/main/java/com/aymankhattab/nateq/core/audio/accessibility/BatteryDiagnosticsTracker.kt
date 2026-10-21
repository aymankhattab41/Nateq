package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * متتبع استهلاك البطارية وتشخيص الأداء (الجولة 50 - الأمر 2).
 * يسجل: الاستيقاظات، أقفال التشغيل، أحداث إمكانية الوصول، وحالات الخدمات.
 */
object BatteryDiagnosticsTracker {

    private const val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L
    private const val ONE_MINUTE_MS = 60 * 1000L

    data class WakeupEvent(
        val timestamp: Long,
        val type: String,
        val tag: String
    )

    data class WakeLockRecord(
        val timestamp: Long,
        val durationMs: Long,
        val tag: String
    )

    data class A11yEventRecord(
        val timestamp: Long,
        val eventType: Int
    )

    private val wakeups = ConcurrentLinkedQueue<WakeupEvent>()
    private val wakeLockDurations = ConcurrentLinkedQueue<WakeLockRecord>()
    private val a11yEvents = ConcurrentLinkedQueue<A11yEventRecord>()
    private val serviceStartTimes = ConcurrentHashMap<String, Long>()
    private val usageStatsPolls = AtomicInteger(0)

    fun recordAlarmWakeup(tag: String) {
        val now = System.currentTimeMillis()
        wakeups.add(WakeupEvent(now, "ALARM", tag))
        trimOldRecords(now)
    }

    fun recordWakeLockAcquired(tag: String, durationMs: Long) {
        val now = System.currentTimeMillis()
        wakeups.add(WakeupEvent(now, "WAKELOCK", tag))
        wakeLockDurations.add(WakeLockRecord(now, durationMs, tag))
        trimOldRecords(now)
    }

    fun recordAccessibilityEvent(eventType: Int) {
        val now = System.currentTimeMillis()
        a11yEvents.add(A11yEventRecord(now, eventType))
        trimA11yEvents(now)
    }

    fun recordServiceStarted(serviceName: String) {
        serviceStartTimes[serviceName] = System.currentTimeMillis()
    }

    fun recordServiceStopped(serviceName: String) {
        serviceStartTimes.remove(serviceName)
    }

    fun recordUsageStatsPoll() {
        usageStatsPolls.incrementAndGet()
    }

    fun getUsageStatsPollCount(): Int = usageStatsPolls.get()

    fun getWakeupsLast24Hours(): Int {
        val cutoff = System.currentTimeMillis() - TWENTY_FOUR_HOURS_MS
        return wakeups.count { it.timestamp >= cutoff }
    }

    fun getTotalWakeLockDurationSecondsLast24Hours(): Long {
        val cutoff = System.currentTimeMillis() - TWENTY_FOUR_HOURS_MS
        val totalMs = wakeLockDurations
            .filter { it.timestamp >= cutoff }
            .sumOf { it.durationMs }
        return totalMs / 1000L
    }

    fun getA11yEventsPerMinuteByType(): Map<Int, Int> {
        val cutoff = System.currentTimeMillis() - ONE_MINUTE_MS
        val recent = a11yEvents.filter { it.timestamp >= cutoff }
        val counts = mutableMapOf<Int, Int>()
        for (event in recent) {
            counts[event.eventType] = (counts[event.eventType] ?: 0) + 1
        }
        return counts
    }

    fun getActiveForegroundServices(): Map<String, Long> {
        return ConcurrentHashMap(serviceStartTimes)
    }

    private fun trimOldRecords(now: Long) {
        val cutoff = now - TWENTY_FOUR_HOURS_MS
        while (true) {
            val head = wakeups.peek() ?: break
            if (head.timestamp < cutoff) {
                wakeups.poll()
            } else {
                break
            }
        }
        while (true) {
            val head = wakeLockDurations.peek() ?: break
            if (head.timestamp < cutoff) {
                wakeLockDurations.poll()
            } else {
                break
            }
        }
    }

    private fun trimA11yEvents(now: Long) {
        val cutoff = now - ONE_MINUTE_MS
        while (true) {
            val head = a11yEvents.peek() ?: break
            if (head.timestamp < cutoff) {
                a11yEvents.poll()
            } else {
                break
            }
        }
    }

    fun buildBatteryReport(context: Context? = null): String {
        val sb = StringBuilder()
        sb.appendLine("--- استهلاك البطارية ---")
        sb.appendLine(
            "• عدد الاستيقاظات (آخر 24 ساعة): ${getWakeupsLast24Hours()}"
        )
        sb.appendLine(
            "• إجمالي مدة أقفال التشغيل: " +
                "${getTotalWakeLockDurationSecondsLast24Hours()} ثانية"
        )

        val a11yCounts = getA11yEventsPerMinuteByType()
        if (a11yCounts.isEmpty()) {
            sb.appendLine("• أحداث إمكانية الوصول في الدقيقة: 0")
        } else {
            val details = a11yCounts.entries.joinToString(", ") {
                "نوع ${it.key}: ${it.value}/د"
            }
            sb.appendLine("• أحداث إمكانية الوصول في الدقيقة: $details")
        }

        sb.appendLine("• حالة الخدمات الأمامية:")
        val services = listOf(
            "إعلانات Lord" to "AnnouncementSchedulerService",
            "مراقبة التطبيقات المحمية" to "ProtectedAppWatcherService",
            "تسجيل المكالمات" to "CallRecordingForegroundService",
            "تنزيل الأصوات" to "VoiceDownloadService"
        )
        val now = System.currentTimeMillis()
        for ((label, name) in services) {
            val startedAt = serviceStartTimes[name]
            if (startedAt != null) {
                val durSec = (now - startedAt) / 1000L
                sb.appendLine("  - $label: تعمل (منذ ${durSec}s)")
            } else {
                sb.appendLine("  - $label: متوقفة")
            }
        }

        sb.appendLine("• عدد استطلاعات UsageStats: ${getUsageStatsPollCount()}")

        if (context != null) {
            runCatching {
                val file = File(context.filesDir, "battery_diagnostics.txt")
                file.writeText(sb.toString())
            }
        }

        return sb.toString()
    }
}
