package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * سجل تشخيصي لمحاولات إعادة تفعيل خدمة إمكانية الوصول (الجولة 51 - الأمر 1).
 * يحتفظ بآخر 20 محاولة في الذاكرة مع تخزين دائم خفيف.
 */
object RestoreDiagnosticsTracker {

    private const val MAX_ENTRIES = 20
    private const val PREFS_NAME = "restore_diagnostics_prefs"
    private const val KEY_HISTORY = "history_entries"

    data class AttemptRecord(
        val timestampMs: Long,
        val reason: String,
        val readinessSummary: String,
        val writeResult: Boolean,
        val serviceConnected: Boolean,
        val nudgeAttempts: Int,
        val fallbackReason: String?
    ) {
        fun formatLine(): String {
            val dateStr = SimpleDateFormat(
                "HH:mm:ss", Locale.US
            ).format(Date(timestampMs))
            val connStr = if (serviceConnected) "اتصلت" else "لم تتصل"
            val writeStr = if (writeResult) "كتابة: نعم" else "كتابة: لا"
            val nudgeStr = if (nudgeAttempts > 0) {
                " (تحريك: $nudgeAttempts)"
            } else ""
            val errStr = if (!fallbackReason.isNullOrBlank()) {
                " [$fallbackReason]"
            } else ""
            return "[$dateStr] $reason | $connStr | $writeStr$nudgeStr" +
                "$errStr | $readinessSummary"
        }
    }

    private val memoryLog = ConcurrentLinkedQueue<AttemptRecord>()

    fun record(
        context: Context?,
        reason: String,
        readinessSummary: String,
        writeResult: Boolean,
        serviceConnected: Boolean,
        nudgeAttempts: Int = 0,
        fallbackReason: String? = null
    ) {
        val entry = AttemptRecord(
            timestampMs = System.currentTimeMillis(),
            reason = reason,
            readinessSummary = readinessSummary,
            writeResult = writeResult,
            serviceConnected = serviceConnected,
            nudgeAttempts = nudgeAttempts,
            fallbackReason = fallbackReason
        )
        memoryLog.add(entry)
        while (memoryLog.size > MAX_ENTRIES) {
            memoryLog.poll()
        }
        if (context != null) {
            persist(context)
        }
    }

    fun getRecentAttempts(context: Context? = null): List<AttemptRecord> {
        if (memoryLog.isEmpty() && context != null) {
            loadFromDisk(context)
        }
        return memoryLog.toList().takeLast(MAX_ENTRIES)
    }

    fun clear(context: Context? = null) {
        memoryLog.clear()
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }

    private fun persist(context: Context) {
        runCatching {
            val lines = memoryLog.map {
                "${it.timestampMs};${it.reason};${it.readinessSummary};" +
                    "${it.writeResult};${it.serviceConnected};" +
                    "${it.nudgeAttempts};${it.fallbackReason.orEmpty()}"
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_HISTORY, lines.toSet())
                .apply()
        }
    }

    private fun loadFromDisk(context: Context) {
        runCatching {
            val set = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE
            ).getStringSet(KEY_HISTORY, emptySet()).orEmpty()
            val parsed = set.mapNotNull { line ->
                val p = line.split(";")
                if (p.size >= 7) {
                    AttemptRecord(
                        timestampMs = p[0].toLongOrNull() ?: 0L,
                        reason = p[1],
                        readinessSummary = p[2],
                        writeResult = p[3].toBoolean(),
                        serviceConnected = p[4].toBoolean(),
                        nudgeAttempts = p[5].toIntOrNull() ?: 0,
                        fallbackReason = p[6].takeIf { it.isNotBlank() }
                    )
                } else null
            }.sortedBy { it.timestampMs }
            memoryLog.clear()
            memoryLog.addAll(parsed.takeLast(MAX_ENTRIES))
        }
    }
}
