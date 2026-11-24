package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.os.Process
import com.aymankhattab.nateq.util.VoiceIdContract
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * سجل موثوق ومزامن لحالة الأصوات الدافئة في عملية :engine_host.
 *
 * الخصائص:
 * 1. مشاركة حالة الجاهزية الحقيقية بين كافة العمليات عبر ملفات ذرية
 *    في filesDir وكاش محلي في الذاكرة.
 * 2. التحقق من الصوت الفعلي ومراجعة البيانات وجيل المضيف ونجاح PCM.
 * 3. إبطال صريح عند تبديل الصوت، إغلاق الجلسة، موت المضيف أو تغيير الملفات.
 */
object HostWarmStatusRegistry {

    private const val PREFIX = "host_warm_"
    private const val SUFFIX = ".json"

    data class HostWarmStatus(
        val engineId: String,
        val actualVoiceId: String,
        val dataRevision: Long,
        val hostGeneration: Long,
        val pcmSuccess: Boolean,
        val hostPid: Int = -1,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val memoryCache = ConcurrentHashMap<String, HostWarmStatus>()

    fun statusFile(filesDir: File, engineId: String): File {
        val norm = HostProtocol.normalizeEngineId(engineId)
        return File(filesDir, "$PREFIX$norm$SUFFIX")
    }

    fun recordWarmVoice(
        context: Context,
        engineId: String,
        actualVoiceId: String,
        dataRevision: Long,
        hostGeneration: Long,
        pcmSuccess: Boolean,
        hostPid: Int = Process.myPid()
    ) {
        val norm = HostProtocol.normalizeEngineId(engineId)
        val cleanVoice = actualVoiceId.trim()
        val status = HostWarmStatus(
            engineId = norm,
            actualVoiceId = cleanVoice,
            dataRevision = dataRevision,
            hostGeneration = hostGeneration,
            pcmSuccess = pcmSuccess,
            hostPid = hostPid,
            timestampMs = System.currentTimeMillis()
        )
        memoryCache[norm] = status

        val filesDir = context.applicationContext?.filesDir ?: context.filesDir
        runCatching {
            val file = statusFile(filesDir, norm)
            val tmp = File(filesDir, "$PREFIX$norm.tmp")
            val json = toJson(status)
            FileOutputStream(tmp).use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun readWarmStatus(
        context: Context,
        engineId: String
    ): HostWarmStatus? {
        val norm = HostProtocol.normalizeEngineId(engineId)
        val filesDir = context.applicationContext?.filesDir ?: context.filesDir
        val file = statusFile(filesDir, norm)
        if (!file.exists()) {
            memoryCache.remove(norm)
            return null
        }

        val cached = memoryCache[norm]
        if (cached != null && file.lastModified() <= cached.timestampMs) {
            return cached
        }

        return runCatching {
            val text = file.readText(Charsets.UTF_8)
            val parsed = fromJson(text)
            if (parsed != null) {
                memoryCache[norm] = parsed
            }
            parsed
        }.getOrNull()
    }

    fun clearWarmStatus(context: Context, engineId: String) {
        val norm = HostProtocol.normalizeEngineId(engineId)
        memoryCache.remove(norm)
        val filesDir = context.applicationContext?.filesDir ?: context.filesDir
        runCatching {
            val file = statusFile(filesDir, norm)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    fun clearAll(context: Context) {
        memoryCache.clear()
        val filesDir = context.applicationContext?.filesDir ?: context.filesDir
        runCatching {
            val files = filesDir.listFiles { _, name ->
                name.startsWith(PREFIX) && name.endsWith(SUFFIX)
            }
            files?.forEach { it.delete() }
        }
    }

    fun isVoiceWarm(
        context: Context,
        engineId: String,
        voiceId: String,
        expectedRevision: Long,
        expectedHostGen: Long? = null,
        expectedPid: Int? = null
    ): Boolean {
        val status = readWarmStatus(context, engineId) ?: return false
        if (!status.pcmSuccess) return false
        if (status.dataRevision != expectedRevision) return false
        if (expectedHostGen != null &&
            status.hostGeneration != expectedHostGen
        ) {
            return false
        }
        if (expectedPid != null &&
            expectedPid > 0 &&
            status.hostPid > 0 &&
            status.hostPid != expectedPid
        ) {
            return false
        }
        return matchesVoice(status.actualVoiceId, voiceId)
    }

    fun matchesVoice(actualVoiceId: String, requestedVoiceId: String): Boolean {
        val a = actualVoiceId.trim()
        val r = requestedVoiceId.trim()
        if (a.equals(r, ignoreCase = true)) return true
        if (VoiceIdContract.sameVoiceName(a, r)) return true
        val canA = VoiceIdContract.canonicalVoiceName(a)
        val canR = VoiceIdContract.canonicalVoiceName(r)
        if (canA.isNotBlank() && canR.isNotBlank()) {
            if (canA.equals(canR, ignoreCase = true)) return true
        }
        return a.contains(r, ignoreCase = true) ||
            r.contains(a, ignoreCase = true)
    }

    fun toJson(status: HostWarmStatus): String {
        val obj = JSONObject()
        obj.put("engineId", status.engineId)
        obj.put("actualVoiceId", status.actualVoiceId)
        obj.put("dataRevision", status.dataRevision)
        obj.put("hostGeneration", status.hostGeneration)
        obj.put("pcmSuccess", status.pcmSuccess)
        obj.put("hostPid", status.hostPid)
        obj.put("timestampMs", status.timestampMs)
        return obj.toString()
    }

    fun fromJson(json: String): HostWarmStatus? = runCatching {
        val obj = JSONObject(json)
        HostWarmStatus(
            engineId = obj.getString("engineId"),
            actualVoiceId = obj.getString("actualVoiceId"),
            dataRevision = obj.getLong("dataRevision"),
            hostGeneration = obj.getLong("hostGeneration"),
            pcmSuccess = obj.optBoolean("pcmSuccess", false),
            hostPid = obj.optInt("hostPid", -1),
            timestampMs = obj.optLong(
                "timestampMs",
                System.currentTimeMillis()
            )
        )
    }.getOrNull()

    fun resetForTests() {
        memoryCache.clear()
    }
}
