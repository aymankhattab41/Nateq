package com.aymankhattab.nateq.core.audio.enginehost

import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * ع٣٠-2: سجل المضيف المعزول (:engine_host).
 *
 * يحتفظ بحلقة (ring buffer) من آخر [BUFFER_CAPACITY] سطراً
 * لكل محرك لتسهيل تشخيص أعطال التهيئة وعقود JNI ونقلها إلى الواجهة.
 */
object HostLog {

    const val BUFFER_CAPACITY = 200

    private val buffers = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val lock = Any()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun tag(engineId: String): String = "HostLog/$engineId"

    private fun logToAndroid(
        level: String,
        tag: String,
        msg: String,
        t: Throwable? = null
    ) {
        runCatching {
            when (level) {
                "D" -> Log.d(tag, msg)
                "I" -> Log.i(tag, msg)
                "W" -> if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
                "E" -> if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
            }
        }
    }

    private fun append(engineId: String, level: String, msg: String) {
        val time = synchronized(lock) {
            timeFormat.format(Date())
        }
        val line = "[$time] [$level] $msg"
        val queue = buffers.computeIfAbsent(engineId) { ArrayDeque() }
        synchronized(queue) {
            while (queue.size >= BUFFER_CAPACITY) {
                queue.pollFirst()
            }
            queue.addLast(line)
        }
    }

    fun d(engineId: String, msg: String) {
        logToAndroid("D", tag(engineId), msg)
        append(engineId, "D", msg)
    }

    fun i(engineId: String, msg: String) {
        logToAndroid("I", tag(engineId), msg)
        append(engineId, "I", msg)
    }

    fun w(engineId: String, msg: String, t: Throwable? = null) {
        logToAndroid("W", tag(engineId), msg, t)
        if (t != null) {
            val err = "${t.javaClass.simpleName}: ${t.message}"
            append(engineId, "W", "$msg ($err)")
        } else {
            append(engineId, "W", msg)
        }
    }

    fun e(engineId: String, msg: String, t: Throwable? = null) {
        logToAndroid("E", tag(engineId), msg, t)
        if (t != null) {
            val err = "${t.javaClass.simpleName}: ${t.message}"
            append(engineId, "E", "$msg ($err)")
        } else {
            append(engineId, "E", msg)
        }
    }

    /**
     * تسجيل تفاصيل بدء التهيئة (المسار، الملفات، ABI، إصدار المكتبة).
     */
    fun logInitDetails(
        engineId: String,
        dataDir: String,
        extraInfo: Map<String, Any?> = emptyMap()
    ) {
        i(engineId, "بدء تهيئة $engineId على المسار: $dataDir")
        val abis = Build.SUPPORTED_ABIS?.joinToString(", ") ?: "unknown"
        i(engineId, "ABI الجهاز: $abis")

        val dir = File(dataDir)
        if (dir.exists() && dir.isDirectory) {
            val allFiles = dir.walkTopDown()
                .filter { it.isFile }
                .take(50)
                .toList()
            val hdrCount = allFiles.count {
                it.extension.equals("hdr", ignoreCase = true)
            }
            val datCount = allFiles.count {
                it.extension.equals("dat", ignoreCase = true)
            }
            i(
                engineId,
                "ملفات البيانات (${allFiles.size} مفحوصة): " +
                    "hdr=$hdrCount, dat=$datCount"
            )
            allFiles.forEach { f ->
                val rel = f.relativeToOrSelf(dir).path
                d(engineId, "  ملف: $rel (${f.length()} بايت)")
            }
        } else {
            w(engineId, "مجلد البيانات غير موجود أو ليس مجلداً: $dataDir")
        }

        for ((k, v) in extraInfo) {
            i(engineId, "$k: $v")
        }
    }

    /** استرجاع آخر [maxLines] سطراً للمحرك المحدد. */
    fun getLog(engineId: String, maxLines: Int = BUFFER_CAPACITY): String {
        val queue = buffers[engineId] ?: return ""
        val list = synchronized(queue) {
            queue.toList()
        }
        val count = maxLines.coerceAtLeast(1).coerceAtMost(list.size)
        return list.takeLast(count).joinToString("\n")
    }

    /** مسح سجل محرك معين أو كل السجلات. */
    fun clear(engineId: String? = null) {
        if (engineId != null) {
            buffers[engineId]?.let { queue ->
                synchronized(queue) { queue.clear() }
            }
        } else {
            buffers.clear()
        }
    }
}
