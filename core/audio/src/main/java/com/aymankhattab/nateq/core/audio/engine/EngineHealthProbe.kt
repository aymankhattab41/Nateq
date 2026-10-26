package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import com.aymankhattab.nateq.core.audio.engine.quirks.EngineQuirks
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * فحص واختبار جاهزية محركات النطق لتشخيص أسباب «لا يعمل خالص»
 * (الجولة 55 - الأمر 8).
 */
object EngineHealthProbe {

    private const val TAG = "EngineHealthProbe"
    private const val BIND_TIMEOUT_MS = 5000L

    data class ProbeResult(
        val enginePackage: String,
        val label: String,
        val isHealthy: Boolean,
        val summaryAr: String,
        val bindTimeMs: Long,
        val voicesCount: Int,
        val arAvailability: Int,
        val enAvailability: Int,
        val voiceSetSuccess: Boolean,
        val synthesisStatus: Int,
        val fileSizeBytes: Long,
        val durationMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val cachedResults = ConcurrentHashMap<String, ProbeResult>()

    /**
     * فحص محرك محدد عبر محاولة الربط واختبار اللغات وضبط الصوت والتوليف.
     */
    suspend fun probeEngine(
        context: Context,
        enginePackage: String,
        label: String = enginePackage
    ): ProbeResult = withContext(Dispatchers.IO) {
        val startBind = SystemClock.elapsedRealtime()
        val bindLatch = CountDownLatch(1)
        val initStatus = AtomicInteger(TextToSpeech.ERROR)
        val ttsRef = AtomicReference<TextToSpeech?>()

        try {
            val tts = TextToSpeech(context, { status ->
                initStatus.set(status)
                bindLatch.countDown()
            }, enginePackage)
            ttsRef.set(tts)

            val bound = bindLatch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val bindTime = SystemClock.elapsedRealtime() - startBind

            if (!bound || initStatus.get() != TextToSpeech.SUCCESS) {
                val reason = if (!bound) {
                    "مهلة الربط انتهت (>5s)"
                } else {
                    "فشل التهيئة (status=${initStatus.get()})"
                }
                val res = ProbeResult(
                    enginePackage = enginePackage,
                    label = label,
                    isHealthy = false,
                    summaryAr = "$label: غير متاح ($reason)",
                    bindTimeMs = bindTime,
                    voicesCount = 0,
                    arAvailability = TextToSpeech.LANG_NOT_SUPPORTED,
                    enAvailability = TextToSpeech.LANG_NOT_SUPPORTED,
                    voiceSetSuccess = false,
                    synthesisStatus = TextToSpeech.ERROR,
                    fileSizeBytes = 0L,
                    durationMs = 0L
                )
                saveResult(context, res)
                return@withContext res
            }

            // فحص الأصوات واللغات
            val quirks = EngineQuirks.forPackage(enginePackage)
            val needsRegion = quirks.requiresLocaleWithRegion
            val needsLangFirst = quirks.needsSetLanguageBeforeSetVoice

            val arLocale = if (needsRegion) {
                Locale("ar", "SA")
            } else {
                Locale("ar")
            }
            val enLocale = if (needsRegion) {
                Locale("en", "US")
            } else {
                Locale("en")
            }

            val voices = runCatching { tts.voices.orEmpty() }.getOrDefault(
                emptySet()
            )
            val arStatus = tts.isLanguageAvailable(arLocale)
            val enStatus = tts.isLanguageAvailable(enLocale)

            if (arStatus == TextToSpeech.LANG_MISSING_DATA) {
                val res = ProbeResult(
                    enginePackage = enginePackage,
                    label = label,
                    isHealthy = false,
                    summaryAr = "$label: بيانات الصوت العربي غير مثبتة",
                    bindTimeMs = bindTime,
                    voicesCount = voices.size,
                    arAvailability = arStatus,
                    enAvailability = enStatus,
                    voiceSetSuccess = false,
                    synthesisStatus = TextToSpeech.ERROR,
                    fileSizeBytes = 0L,
                    durationMs = 0L
                )
                saveResult(context, res)
                return@withContext res
            }

            // محاولة تعيين صوت
            var voiceSetOk = false
            val chosenVoice = voices.firstOrNull {
                it.locale.language == "ar"
            } ?: voices.firstOrNull {
                it.locale.language == "en"
            } ?: voices.firstOrNull()

            if (chosenVoice != null) {
                if (needsLangFirst) {
                    val effLocale = if (needsRegion &&
                        chosenVoice.locale.country.isNullOrEmpty()
                    ) {
                        if (chosenVoice.locale.language == "ar") {
                            Locale("ar", "SA")
                        } else {
                            Locale("en", "US")
                        }
                    } else {
                        chosenVoice.locale
                    }
                    runCatching { tts.language = effLocale }
                }
                val setRes = runCatching { tts.voice = chosenVoice }
                if (setRes.isSuccess) {
                    val current = runCatching { tts.voice }.getOrNull()
                    voiceSetOk = (current?.name == chosenVoice.name)
                }
            } else {
                val effLocale = if (needsRegion) {
                    Locale("ar", "SA")
                } else {
                    Locale("ar")
                }
                runCatching { tts.language = effLocale }
                voiceSetOk = true
            }

            // اختبار توليد ملف صوتي قصير
            val tempFile = File(
                context.cacheDir,
                "probe_${System.nanoTime()}.wav"
            )
            val synthLatch = CountDownLatch(1)
            val synthSuccess = AtomicBoolean(false)
            val synthUtteranceId = "probe_${System.currentTimeMillis()}"

            tts.setOnUtteranceProgressListener(
                object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == synthUtteranceId) {
                            synthSuccess.set(true)
                            synthLatch.countDown()
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == synthUtteranceId) {
                            synthLatch.countDown()
                        }
                    }
                }
            )

            val params = Bundle().apply {
                putString(
                    TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                    synthUtteranceId
                )
            }
            val synthStatus = tts.synthesizeToFile(
                "اختبار", params, tempFile, synthUtteranceId
            )

            synthLatch.await(3000L, TimeUnit.MILLISECONDS)
            if (quirks.lateWriteSettleMs > 0L) {
                try {
                    Thread.sleep(quirks.lateWriteSettleMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            val fileSize = if (tempFile.exists()) tempFile.length() else 0L
            runCatching { tempFile.delete() }

            val isHealthy = fileSize > 44L
            val summary = when {
                !voiceSetOk && chosenVoice != null ->
                    "$label: يرفض الصوت ${chosenVoice.name}"
                synthStatus != TextToSpeech.SUCCESS ->
                    "$label: فشل الطلب (status=$synthStatus)"
                fileSize <= 44L ->
                    "$label: ينتج ملفاً فارغاً (صامت ✗)"
                else ->
                    "$label: يعمل بشكل سليم ✓"
            }

            val result = ProbeResult(
                enginePackage = enginePackage,
                label = label,
                isHealthy = isHealthy,
                summaryAr = summary,
                bindTimeMs = bindTime,
                voicesCount = voices.size,
                arAvailability = arStatus,
                enAvailability = enStatus,
                voiceSetSuccess = voiceSetOk,
                synthesisStatus = synthStatus,
                fileSizeBytes = fileSize,
                durationMs = 0L
            )
            saveResult(context, result)
            result
        } catch (t: Throwable) {
            Log.e(TAG, "probeEngine failed for $enginePackage", t)
            val errRes = ProbeResult(
                enginePackage = enginePackage,
                label = label,
                isHealthy = false,
                summaryAr = "$label: حدث استثناء أثناء الفحص",
                bindTimeMs = 0L,
                voicesCount = 0,
                arAvailability = TextToSpeech.LANG_NOT_SUPPORTED,
                enAvailability = TextToSpeech.LANG_NOT_SUPPORTED,
                voiceSetSuccess = false,
                synthesisStatus = TextToSpeech.ERROR,
                fileSizeBytes = 0L,
                durationMs = 0L
            )
            saveResult(context, errRes)
            errRes
        } finally {
            runCatching { ttsRef.get()?.shutdown() }
        }
    }

    private fun saveResult(context: Context, result: ProbeResult) {
        cachedResults[result.enginePackage] = result
        runCatching {
            val key = "engine_health_${result.enginePackage}"
            val prefs = context.getSharedPreferences(
                "nateq_settings",
                Context.MODE_PRIVATE
            )
            prefs.edit().putString(key, result.summaryAr).apply()
        }
    }

    fun getEngineLabel(context: Context, enginePackage: String): String {
        return runCatching {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(enginePackage, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrDefault(enginePackage)
    }

    fun getCachedResult(enginePackage: String): ProbeResult? =
        cachedResults[enginePackage]

    fun getCachedSummary(
        context: Context,
        enginePackage: String
    ): String? {
        cachedResults[enginePackage]?.let { return it.summaryAr }
        return runCatching {
            val key = "engine_health_$enginePackage"
            val prefs = context.getSharedPreferences(
                "nateq_settings",
                Context.MODE_PRIVATE
            )
            prefs.getString(key, null)
        }.getOrNull()
    }

    fun formatReport(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== جاهزية محركات النطق (Engine Health) ===")
        if (cachedResults.isEmpty()) {
            sb.appendLine("• لم يتم فحص المحركات بعد.")
        } else {
            cachedResults.values.forEach { res ->
                val icon = if (res.isHealthy) "✓" else "✗"
                sb.appendLine("• [${res.label}] $icon ${res.summaryAr}")
                sb.appendLine(
                    "   ربط=${res.bindTimeMs}ms  أصوات=${res.voicesCount}" +
                        "  حجم_الملف=${res.fileSizeBytes}B"
                )
            }
        }
        return sb.toString()
    }
}
