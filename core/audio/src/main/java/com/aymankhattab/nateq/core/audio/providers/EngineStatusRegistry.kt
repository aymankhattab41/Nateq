package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * سجلّ حالات المحركات المدمجة وأسباب أعطالها (ع٢٩-8 بند 1 + ع٣٠-10).
 *
 * لكل محرك حالةٌ حاكمة وآخر رسالة خطأ. تسجّل هنا:
 * - فحص [EnginePicker] (كاش الاكتشاف): موجود/غير مثبّت + أي استثناء،
 * - المزوّدون الثلاثة عند فشل تهيئة المضيف أو نطقه (أكواد HostProtocol،
 *   ومنها `CRASHED_LAST_RUN` ورموز الترخيص)،
 * - نجاح التهيئة (`READY`) ووجود ملفات الأصوات (`VOICES_PRESENT`).
 *
 * ع٣٠-10: يُحفظ السجل في ملف `filesDir/engine_status_<id>.json` يكتبه
 * المضيف عند كل init وتقرؤه الواجهة عند العرض فلا يضيع السبب إذا أُعيد
 * تشغيل الواجهة أو انهار المضيف.
 */
object EngineStatusRegistry {

    /** حالات المحرك الثمانية (ع٢٩-8 بند 1 + ع٢٩-7 بند 4). */
    enum class State {
        /** لا ملفات أصوات على الإطلاق. */
        NOT_INSTALLED,

        /** الملفات موجودة ولم تُجرَّ تهيئة بعد. */
        VOICES_PRESENT,

        /** تهيئة المضيف جارية الآن. */
        INIT_PENDING,

        /** التهيئة نجحت والمحرك جاهز للنطق. */
        READY,

        /** فشل تهيئة/استخدام المكتبة الأصلية (بالرمز المعلَن). */
        NATIVE_INIT_FAILED,

        /** انهار في آخر تشغيل — تُطلب إعادة المحاولة بـforce. */
        CRASHED_LAST_RUN,

        /** مشكلة ترخيص المكتبة. */
        LICENSE_ISSUE,

        /** تجريبي — غير جاهز (عقدة JNI/سياق غير مستوفاة). */
        EXPERIMENTAL
    }

    /** حالة محرك واحدة مع سببها وآخر رسالة خطأ. */
    data class EngineStatus(
        val engineId: String,
        val state: State,
        val code: String? = null,
        val lastError: String? = null,
        val updatedAt: Long = System.currentTimeMillis()
    )

    /**
     * تسمية الحالة بالعربية للمستخدم (شارة/حالة/Toast) — مصدر واحد
     * لكل الواجهات فلا يفترض كل عرض صياغته.
     */
    fun labelAr(state: State): String = when (state) {
        State.NOT_INSTALLED -> "غير مثبّت"
        State.VOICES_PRESENT -> "الملفات موجودة (لم تُهيَّأ)"
        State.INIT_PENDING -> "جارٍ التهيئة"
        State.READY -> "جاهز"
        State.NATIVE_INIT_FAILED -> "فشل تهيئة المحرّك"
        State.CRASHED_LAST_RUN -> "انهار في آخر تشغيل"
        State.LICENSE_ISSUE -> "مشكلة ترخيص"
        State.EXPERIMENTAL -> "تجريبي — غير جاهز"
    }

    private val statuses = ConcurrentHashMap<String, EngineStatus>()

    /** مجلد ملفات التطبيق لمطالعة ملفات الحالة الدائمة (ع٣٠-10). */
    @Volatile
    internal var defaultFilesDir: File? = null

    /** مصدر الزمن — حقنة اختبار لتوقيت التسجيل. */
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }

    fun setFilesDir(dir: File) {
        defaultFilesDir = dir
    }

    fun setFilesDir(context: Context) {
        defaultFilesDir = context.filesDir
    }

    /** توحيد معرف المحرك بين صيغ المضيف والمزود. */
    fun normalizeId(engineId: String): String = when (engineId.lowercase()) {
        "vocalizer", "vocalizer_embedded" -> "vocalizer_embedded"
        "acapela", "acapela_embedded" -> "acapela_embedded"
        "sherpa", "sherpa_onnx_piper", "sherpa_piper" ->
            "sherpa_onnx_piper"
        else -> engineId.lowercase()
    }

    fun statusFileName(engineId: String): String =
        "engine_status_${normalizeId(engineId)}.json"

    fun statusFile(filesDir: File, engineId: String): File {
        val norm = normalizeId(engineId)
        val fileNorm = File(filesDir, "engine_status_$norm.json")
        if (fileNorm.exists()) return fileNorm
        val fileRaw = File(
            filesDir,
            "engine_status_${engineId.lowercase()}.json"
        )
        if (fileRaw.exists()) return fileRaw
        return fileNorm
    }

    /** كتابة ملف الحالة بصيغة JSON (ع٣٠-10). */
    fun writeStatusFile(filesDir: File, status: EngineStatus) {
        val norm = normalizeId(status.engineId)
        val file = File(filesDir, "engine_status_$norm.json")
        val json = JSONObject().apply {
            put("engineId", norm)
            put("state", status.state.name)
            if (status.code != null) put("code", status.code)
            if (status.lastError != null) put("message", status.lastError)
            put("updatedAt", status.updatedAt)
        }
        val tmp = File(filesDir, "engine_status_$norm.json.tmp")
        try {
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {
            runCatching { file.writeText(json.toString()) }
        }
    }

    /** قراءة ملف الحالة إن وجد وتحويله إلى [EngineStatus]. */
    fun readStatusFile(filesDir: File, engineId: String): EngineStatus? {
        val file = statusFile(filesDir, engineId)
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            if (text.isBlank()) return null
            val json = JSONObject(text)
            val id = json.optString("engineId", normalizeId(engineId))
            val stateStr = json.optString("state", "")
            val state = runCatching {
                State.valueOf(stateStr)
            }.getOrNull() ?: return null
            val code = if (json.has("code") && !json.isNull("code")) {
                json.optString("code").takeIf { it.isNotBlank() }
            } else null
            val msg = if (json.has("message") && !json.isNull("message")) {
                json.optString("message").takeIf { it.isNotBlank() }
            } else null
            val time = json.optLong("updatedAt", 0L)
            EngineStatus(
                engineId = id,
                state = state,
                code = code,
                lastError = msg,
                updatedAt = time
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** يسجّل حالة محرك كاملة (استبدال الحالة السابقة كلها). */
    fun record(
        engineId: String,
        state: State,
        code: String? = null,
        message: String? = null
    ) {
        val norm = normalizeId(engineId)
        val status = EngineStatus(
            engineId = norm,
            state = state,
            code = code,
            lastError = message,
            updatedAt = nowProvider()
        )
        statuses[norm] = status
        defaultFilesDir?.let { dir ->
            writeStatusFile(dir, status)
        }
    }

    /**
     * يسجّل فشلاً برمز مُعلَن من المضيف — يحوّل الرمز إلى الحالة
     * الحاكمة ([stateForCode]) ويحفظ السبب للعرض. الرمز أو السبب
     * قد يغيبان عن نتيجة المضيف (`HostResult`) فهما مُساهَلان لا
     * يسقطان التسجيل.
     */
    fun noteFailure(engineId: String, code: String?, message: String?) {
        val safeCode = code ?: "UNKNOWN"
        record(engineId, stateForCode(safeCode), safeCode, message)
    }

    /**
     * تحويل رمز الفشل إلى حالة حاكمة — مرجع الاختبار وحوار التفاصيل.
     * `CRASHED_LAST_RUN` حالةٌ بذاتها، وأي رمز فيه LICENSE ترخيص،
     * وبقيتها فشل مكتبة أصلية.
     */
    fun stateForCode(code: String): State = when {
        code.equals("CRASHED_LAST_RUN", ignoreCase = true) ->
            State.CRASHED_LAST_RUN
        code.equals("NATIVE_INIT_FAILED", ignoreCase = true) ->
            State.NATIVE_INIT_FAILED
        code.contains("LICENSE", ignoreCase = true) ->
            State.LICENSE_ISSUE
        code.contains("JNI", ignoreCase = true) ||
            code.contains("CONTEXT", ignoreCase = true) ||
            code.contains("EXPERIMENTAL", ignoreCase = true) ->
            State.EXPERIMENTAL
        else -> State.NATIVE_INIT_FAILED
    }

    /**
     * الملفات موجودة: يرقّي الغياب/الجديد إلى `VOICES_PRESENT`
     * **ولا يمحو حالة عطل مسجّلة** (تهيئة فاشلة تبقى ظاهرة حتى
     * نجاح صريح عبر [noteReady] أو استبدال بـ[record]).
     */
    fun noteFilesPresent(engineId: String) {
        val current = statusOf(engineId)
        if (current == null || current.state == State.NOT_INSTALLED) {
            record(engineId, State.VOICES_PRESENT)
        }
    }

    /** لا ملفات: أي حالة سابقة تُمحى إلى `NOT_INSTALLED`. */
    fun noteFilesAbsent(engineId: String) {
        record(engineId, State.NOT_INSTALLED)
    }

    /** تهيئة المضيف بدأت (تسبق بداءً فشلها). */
    fun noteInitPending(engineId: String) {
        record(engineId, State.INIT_PENDING)
    }

    /** التهيئة نجحت — يصفّي أي عطل سابق. */
    fun noteReady(engineId: String) {
        record(engineId, State.READY)
    }

    /**
     * حالة محرك مسجلة — تُقرأ حالات الفشل وREADY من الملف بشرط
     * أن يكون updatedAt أحدث من آخر تغيير في التخزين
     * [EmbeddedStorage.lastBumpTime] (ع٣١-4).
     */
    fun statusOf(engineId: String): EngineStatus? {
        val norm = normalizeId(engineId)
        val fileStatus = defaultFilesDir?.let { readStatusFile(it, norm) }
        val memStatus = statuses[norm]
        val bumpTime = EmbeddedStorage.lastBumpTime

        // فحص ملف القرص أولاً (إن وجد وكان أحدث)
        if (fileStatus != null) {
            val isLiveState = fileStatus.state == State.NOT_INSTALLED ||
                fileStatus.state == State.VOICES_PRESENT
            val isNotStale = fileStatus.updatedAt >= bumpTime

            if (!isLiveState && isNotStale) {
                if (memStatus == null ||
                    fileStatus.updatedAt >= memStatus.updatedAt
                ) {
                    statuses[norm] = fileStatus
                    return fileStatus
                }
            }
        }

        if (memStatus != null) {
            val isFailure = memStatus.state == State.NATIVE_INIT_FAILED ||
                memStatus.state == State.CRASHED_LAST_RUN ||
                memStatus.state == State.LICENSE_ISSUE ||
                memStatus.state == State.EXPERIMENTAL
            if (isFailure && memStatus.updatedAt < bumpTime) {
                statuses.remove(norm)
                return null
            }
            return memStatus
        }

        return null
    }

    /**
     * قراءة الحالة مع الاشتقاق الحي لحالتي NOT_INSTALLED و VOICES_PRESENT
     * من [EmbeddedStorage.inventory] عند كل طلب (ع٣١-4).
     */
    fun statusOf(context: Context, engineId: String): EngineStatus {
        setFilesDir(context.filesDir)
        val norm = normalizeId(engineId)
        val recorded = statusOf(norm)

        if (recorded != null &&
            recorded.state != State.NOT_INSTALLED &&
            recorded.state != State.VOICES_PRESENT
        ) {
            return recorded
        }

        val storageEngine = when (norm) {
            "vocalizer", "vocalizer_embedded" ->
                EmbeddedStorage.Engine.VOCALIZER
            "acapela", "acapela_embedded" ->
                EmbeddedStorage.Engine.ACAPELA
            "sherpa", "sherpa_onnx_piper", "sherpa_piper",
            "sherpa_onnx_embedded" ->
                EmbeddedStorage.Engine.SHERPA
            else -> null
        }

        val hasVoices = if (storageEngine != null) {
            EmbeddedStorage.inventory(context, storageEngine)
                .isCompleteVoicePresent
        } else {
            false
        }

        val liveState = if (hasVoices) State.VOICES_PRESENT
        else State.NOT_INSTALLED

        return EngineStatus(
            engineId = norm,
            state = liveState,
            updatedAt = nowProvider()
        )
    }

    /** كل الحالات المرسومة — لشاشة التخزين وجدول التشخيص. */
    fun all(context: Context? = null): List<EngineStatus> {
        if (context != null) {
            val canonicalEngines = listOf(
                "vocalizer_embedded",
                "acapela_embedded",
                "sherpa_onnx_piper"
            )
            return canonicalEngines.map { statusOf(context, it) }
        }
        defaultFilesDir?.let { dir ->
            val files = dir.listFiles { f ->
                f.name.startsWith("engine_status_") &&
                    f.name.endsWith(".json")
            } ?: emptyArray()
            for (f in files) {
                val rawId = f.name.removePrefix("engine_status_")
                    .removeSuffix(".json")
                val diskStatus = readStatusFile(dir, rawId) ?: continue
                val norm = normalizeId(diskStatus.engineId)
                val mem = statuses[norm]
                if (mem == null || diskStatus.updatedAt >= mem.updatedAt) {
                    statuses[norm] = diskStatus
                }
            }
        }
        return statuses.values.sortedBy { it.engineId }
    }

    /** إفراغ كامل — للاختبارات وإعادة التهيئة. */
    fun clear() {
        statuses.clear()
        defaultFilesDir?.let { dir ->
            val files = dir.listFiles { f ->
                f.name.startsWith("engine_status_") &&
                    f.name.endsWith(".json")
            } ?: emptyArray()
            for (f in files) f.delete()
        }
    }

    /** إفراغ الذاكرة فقط مع إبقاء ملفات القرص (للاختبارات فقط). */
    internal fun statusesClearOnly() {
        statuses.clear()
    }
}
