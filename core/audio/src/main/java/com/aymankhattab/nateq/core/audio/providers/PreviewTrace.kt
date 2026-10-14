package com.aymankhattab.nateq.core.audio.providers

import android.util.Log
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import java.util.concurrent.ConcurrentHashMap

/**
 * ع٣٣-7 / ع٣٨-7: تتبّع المعاينة / النطق للمحركات المدمجة
 * وتسجيل الخطوة الأخيرة والنتيجة.
 */
data class PreviewTrace(
    val requestedEngine: String,
    val voiceName: String,
    val matchedModel: String = "",
    val path: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    var lastStep: String = "استدعاء المضيف",
    var isSuccess: Boolean = false,
    var failureReason: String? = null
) {
    companion object {
        private const val TAG = "PreviewTrace"

        @Volatile
        private var lastTrace: PreviewTrace? = null

        private val engineTraces =
            ConcurrentHashMap<String, PreviewTrace>()

        fun record(
            requestedEngine: String,
            voiceName: String,
            matchedModel: String = "",
            path: String = "",
            initialStep: String = "استدعاء المضيف"
        ): PreviewTrace {
            val trace = PreviewTrace(
                requestedEngine = requestedEngine,
                voiceName = voiceName,
                matchedModel = matchedModel,
                path = path,
                lastStep = initialStep,
                isSuccess = false,
                failureReason = null
            )
            lastTrace = trace
            val norm = HostProtocol.normalizeEngineId(requestedEngine)
            engineTraces[norm] = trace
            Log.i(
                TAG,
                "PreviewTrace.record: engine=$requestedEngine, " +
                    "voice=$voiceName, model=$matchedModel, " +
                    "step=$initialStep"
            )
            return trace
        }

        fun updateStep(step: String, engineId: String? = null) {
            val trace = if (engineId != null) {
                val norm = HostProtocol.normalizeEngineId(engineId)
                engineTraces[norm] ?: lastTrace
            } else {
                lastTrace
            }
            trace?.let {
                it.lastStep = step
                Log.d(
                    TAG,
                    "PreviewTrace.updateStep: engine=${it.requestedEngine}" +
                        ", step=$step"
                )
            }
        }

        fun markSuccess(
            step: String = "اكتمل",
            engineId: String? = null
        ) {
            val trace = if (engineId != null) {
                val norm = HostProtocol.normalizeEngineId(engineId)
                engineTraces[norm] ?: lastTrace
            } else {
                lastTrace
            }
            trace?.let {
                it.lastStep = step
                it.isSuccess = true
                it.failureReason = null
                Log.i(
                    TAG,
                    "PreviewTrace.markSuccess: engine=${it.requestedEngine}"
                )
            }
        }

        fun markFailure(
            code: String?,
            reason: String?,
            engineId: String? = null
        ) {
            val trace = if (engineId != null) {
                val norm = HostProtocol.normalizeEngineId(engineId)
                engineTraces[norm] ?: lastTrace
            } else {
                lastTrace
            }
            trace?.let {
                it.isSuccess = false
                val formattedReason = when {
                    !code.isNullOrBlank() && !reason.isNullOrBlank() ->
                        if (reason.contains(code)) reason
                        else "[$code] $reason"
                    !code.isNullOrBlank() -> "[$code]"
                    !reason.isNullOrBlank() -> reason
                    else -> "فشل غير محدد"
                }
                it.failureReason = formattedReason
                Log.w(
                    TAG,
                    "PreviewTrace.markFailure: engine=${it.requestedEngine}" +
                        ", step=${it.lastStep}, reason=$formattedReason"
                )
            }
        }

        fun latest(): PreviewTrace? = lastTrace

        fun latestForEngine(engineId: String): PreviewTrace? {
            val norm = HostProtocol.normalizeEngineId(engineId)
            return engineTraces[norm] ?: lastTrace?.takeIf {
                HostProtocol.normalizeEngineId(it.requestedEngine) == norm
            }
        }

        fun summaryForEngine(engineId: String): String {
            val trace = latestForEngine(engineId)
                ?: return "آخر معاينة: لم تُجرَ بعد"
            return if (trace.isSuccess) {
                "آخر معاينة: نجاح (${trace.voiceName})"
            } else {
                val reason = trace.failureReason ?: "عند ${trace.lastStep}"
                "آخر معاينة: فشل ($reason)"
            }
        }

        fun clear() {
            lastTrace = null
            engineTraces.clear()
        }

        fun formatLatest(): String {
            val trace = lastTrace
                ?: return "(لا يوجد تتبّع معاينة سابق)"
            val statusText = if (trace.isSuccess) {
                "نجاح"
            } else {
                "فشل (${trace.failureReason ?: "غير محدد"})"
            }
            return "محرك: ${trace.requestedEngine}\n" +
                "صوت: ${trace.voiceName}\n" +
                "نموذج مطابق: ${trace.matchedModel}\n" +
                "المسار: ${trace.path}\n" +
                "آخر خطوة: ${trace.lastStep}\n" +
                "الحالة: $statusText"
        }
    }
}
