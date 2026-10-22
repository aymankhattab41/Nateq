package com.aymankhattab.nateq.core.audio.accessibility

import android.app.Application
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * سجل تشخيصي لمحاولات إعادة تفعيل خدمة إمكانية الوصول
 * (الجولة 51 والجولة 52 - الأمر 1).
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
        val nudgeAttempts: Int = 0,
        val fallbackReason: String? = null,
        val callingProcess: String = "main",
        val connectedBefore: Boolean = false,
        val enabledBefore: Boolean = false,
        val connectedAfter: Boolean = false,
        val enabledAfter: Boolean = false,
        val nudgeReason: String? = null,
        val failureAnnounced: Boolean = false
    ) {
        fun formatLine(): String {
            val dateStr = SimpleDateFormat(
                "HH:mm:ss", Locale.US
            ).format(Date(timestampMs))
            val connStr = if (serviceConnected) "اتصلت" else "لم تتصل"
            val writeStr = if (writeResult) "كتابة: نعم" else "كتابة: لا"
            val nudgeStr = if (nudgeAttempts > 0) {
                val reasonTag = if (!nudgeReason.isNullOrBlank()) {
                    ": $nudgeReason"
                } else ""
                " (تحريك: $nudgeAttempts$reasonTag)"
            } else ""
            val failStr = if (failureAnnounced) " [أُعلن الفشل]" else ""
            val errStr = if (!fallbackReason.isNullOrBlank()) {
                " [$fallbackReason]"
            } else ""
            val stateStr = "قبل: متصل=$connectedBefore/مفعّل=$enabledBefore" +
                " | بعد: متصل=$connectedAfter/مفعّل=$enabledAfter"
            return "[$dateStr][$callingProcess] $reason | $connStr | " +
                "$writeStr$nudgeStr$failStr$errStr | $stateStr | " +
                readinessSummary
        }
    }

    private val memoryLog = ConcurrentLinkedQueue<AttemptRecord>()

    fun detectProcessName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val name = Application.getProcessName()
            if (name.endsWith(":tts")) "tts" else "main"
        } else {
            "main"
        }
    }

    fun record(
        context: Context?,
        reason: String,
        readinessSummary: String,
        writeResult: Boolean,
        serviceConnected: Boolean,
        nudgeAttempts: Int = 0,
        fallbackReason: String? = null,
        callingProcess: String = detectProcessName(),
        connectedBefore: Boolean = false,
        enabledBefore: Boolean = false,
        connectedAfter: Boolean = serviceConnected,
        enabledAfter: Boolean = false,
        nudgeReason: String? = null,
        failureAnnounced: Boolean = false
    ) {
        val entry = AttemptRecord(
            timestampMs = System.currentTimeMillis(),
            reason = reason,
            readinessSummary = readinessSummary,
            writeResult = writeResult,
            serviceConnected = serviceConnected,
            nudgeAttempts = nudgeAttempts,
            fallbackReason = fallbackReason,
            callingProcess = callingProcess,
            connectedBefore = connectedBefore,
            enabledBefore = enabledBefore,
            connectedAfter = connectedAfter,
            enabledAfter = enabledAfter,
            nudgeReason = nudgeReason,
            failureAnnounced = failureAnnounced
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
                    "${it.nudgeAttempts};${it.fallbackReason.orEmpty()};" +
                    "${it.callingProcess};${it.connectedBefore};" +
                    "${it.enabledBefore};${it.connectedAfter};" +
                    "${it.enabledAfter};${it.nudgeReason.orEmpty()};" +
                    "${it.failureAnnounced}"
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
                        fallbackReason = p[6].takeIf { it.isNotBlank() },
                        callingProcess = if (p.size > 7) p[7] else "main",
                        connectedBefore = if (p.size > 8) {
                            p[8].toBoolean()
                        } else false,
                        enabledBefore = if (p.size > 9) {
                            p[9].toBoolean()
                        } else false,
                        connectedAfter = if (p.size > 10) {
                            p[10].toBoolean()
                        } else p[4].toBoolean(),
                        enabledAfter = if (p.size > 11) {
                            p[11].toBoolean()
                        } else false,
                        nudgeReason = if (p.size > 12) {
                            p[12].takeIf { it.isNotBlank() }
                        } else null,
                        failureAnnounced = if (p.size > 13) {
                            p[13].toBoolean()
                        } else false
                    )
                } else null
            }.sortedBy { it.timestampMs }
            memoryLog.clear()
            memoryLog.addAll(parsed.takeLast(MAX_ENTRIES))
        }
    }
}
