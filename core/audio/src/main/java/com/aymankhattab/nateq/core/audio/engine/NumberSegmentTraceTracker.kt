package com.aymankhattab.nateq.core.audio.engine

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/**
 * متتبع تشخيص كل مقطع رقمي/إيموجي (ع٥٧-أمر 4):
 * يسجّل النص والمحرك/الصوت المطلوبين والمستعملين فعلاً
 * وجاهزية المضيف ومدة الانتظار وسبب التراجع وأهمية العملية.
 * يحفظ آخر 100 سجل ويعرضها في شاشة التشخيص.
 */
object NumberSegmentTraceTracker {

    const val MAX_RECORDS = 100

    data class SegmentTrace(
        val timestamp: Long,
        val text: String,
        val requestedEngine: String?,
        val requestedVoice: String?,
        val actualEngine: String?,
        val actualVoice: String?,
        val hostReady: Boolean,
        val waitDurationMs: Long,
        val fallbackReason: String?,
        val importance: Int
    )

    private val records = LinkedList<SegmentTrace>()
    private val lock = Any()

    fun record(
        text: String,
        requestedEngine: String?,
        requestedVoice: String?,
        actualEngine: String?,
        actualVoice: String?,
        hostReady: Boolean,
        waitDurationMs: Long = 0L,
        fallbackReason: String? = null,
        importance: Int = -1
    ) {
        val entry = SegmentTrace(
            timestamp = System.currentTimeMillis(),
            text = text.take(60),
            requestedEngine = requestedEngine,
            requestedVoice = requestedVoice,
            actualEngine = actualEngine,
            actualVoice = actualVoice,
            hostReady = hostReady,
            waitDurationMs = waitDurationMs,
            fallbackReason = fallbackReason,
            importance = importance
        )
        synchronized(lock) {
            if (records.size >= MAX_RECORDS) {
                records.removeFirst()
            }
            records.addLast(entry)
        }
    }

    fun getRecent(): List<SegmentTrace> {
        synchronized(lock) {
            return ArrayList(records)
        }
    }

    fun clear() {
        synchronized(lock) {
            records.clear()
        }
    }

    /**
     * يقرأ أهمية العملية الحالية — يُستخدم لتسجيل
     * هل التطبيق في الواجهة أم في الخلفية.
     */
    fun currentImportance(context: Context): Int {
        return runCatching {
            val am = context.getSystemService(
                Context.ACTIVITY_SERVICE
            ) as? ActivityManager
            val info = ActivityManager
                .RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance
        }.getOrDefault(
            ActivityManager.RunningAppProcessInfo
                .IMPORTANCE_GONE
        )
    }

    fun format(): String {
        val list = getRecent()
        if (list.isEmpty()) {
            return "(لا توجد سجلات مقاطع رقمية)"
        }
        val sdf = SimpleDateFormat(
            "HH:mm:ss.SSS", Locale.US
        )
        return buildString {
            appendLine(
                "=== تتبع المقاطع الرقمية" +
                    " (${list.size}) ==="
            )
            for ((i, t) in list.withIndex()) {
                val ts = sdf.format(Date(t.timestamp))
                appendLine(
                    "#${i + 1} [$ts] '${t.text}'"
                )
                appendLine(
                    "  مطلوب: ${t.requestedEngine}" +
                        "/${t.requestedVoice}"
                )
                appendLine(
                    "  فعلي: ${t.actualEngine}" +
                        "/${t.actualVoice}"
                )
                val ready = if (t.hostReady) {
                    "جاهز"
                } else {
                    "غير جاهز"
                }
                appendLine(
                    "  المضيف: $ready" +
                        " انتظار: ${t.waitDurationMs}ms" +
                        " أهمية: ${t.importance}"
                )
                if (!t.fallbackReason.isNullOrBlank()) {
                    appendLine(
                        "  تراجع: ${t.fallbackReason}"
                    )
                }
            }
        }
    }
}
