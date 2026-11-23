package com.aymankhattab.nateq.core.audio.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.aymankhattab.nateq.core.audio.enginehost.AcapelaHostSession
import com.aymankhattab.nateq.core.audio.enginehost.MicrosoftHostSession
import com.aymankhattab.nateq.core.audio.enginehost.VocalizerHostSession
import com.aymankhattab.nateq.core.audio.providers.AcapelaEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.EmbeddedProviders
import com.aymankhattab.nateq.core.audio.providers.MicrosoftEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VocalizerEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VoiceDescriptor
import com.aymankhattab.nateq.core.common.AppDispatchers
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LanguageCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * منسّق موحّد لإدارة جاهزية وإحماء الأصوات المدمجة عبر كافة المحركات
 * (Acapela، Vocalizer، Microsoft).
 *
 * الخصائص:
 * 1. تهيئة الأصوات المختارة فعلياً على [AppDispatchers.io] عند بدء
 *    التطبيق أو الخدمة أو تغير الاختيار أو فتح شاشة الأصوات.
 * 2. دمج الطلبات المتزامنة (coalescing) لمنع تكرار تهيئة نفس الصوت.
 * 3. إعطاء الأولوية للصوت المختار والصوت المطلوب للمعاينة.
 * 4. إلغاء أو خفض أولوية الإحماء الاستباقي فوراً عند وصول طلب نطق حقيقي
 *    من المستخدم لتفريغ موارد المعالج وقنوات الصوت له.
 * 5. إحماء صامت حقيقي (volume=0f مع إهمال PCM دون طلب AudioFocus).
 * 6. تفريغ الجلسات غير النشطة عند نقص الذاكرة (onTrimMemory).
 */
object VoiceReadinessCoordinator {

    private const val TAG = "VoiceReadiness"

    private val coordinatorScope = CoroutineScope(
        SupervisorJob() + AppDispatchers.io
    )

    private val inFlightJobs = ConcurrentHashMap<String, Deferred<Boolean>>()
    private val readyVoices = ConcurrentHashMap<String, Boolean>()
    private val isRealSpeechActive = AtomicBoolean(false)

    /**
     * تجهيز الأصوات النشطة المعينة في الإعدادات في الخلفية على IO.
     */
    fun prepareActiveVoices(
        context: Context,
        priorityVoiceId: String? = null,
        priorityEngineId: String? = null
    ): Job = coordinatorScope.launch {
        val appCtx = context.applicationContext ?: context
        val settings = runCatching {
            SettingsRepository.create(appCtx)
        }.getOrNull() ?: return@launch

        val targets = mutableListOf<Pair<String, String>>()

        if (!priorityEngineId.isNullOrBlank() &&
            !priorityVoiceId.isNullOrBlank()
        ) {
            targets.add(priorityEngineId to priorityVoiceId)
        }

        val arEngine = settings.getEngineForLanguage(LanguageCode.AR.tag)
        val arVoice = settings.getVoiceForLanguage(LanguageCode.AR.tag)
        if (!arEngine.isNullOrBlank() && !arVoice.isNullOrBlank()) {
            targets.add(arEngine to arVoice)
        }

        val numEngine = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )
        val numVoice = settings.getPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )
        if (!numEngine.isNullOrBlank() && !numVoice.isNullOrBlank()) {
            targets.add(numEngine to numVoice)
        }

        val enEngine = settings.getEngineForLanguage(LanguageCode.EN.tag)
        val enVoice = settings.getVoiceForLanguage(LanguageCode.EN.tag)
        if (!enEngine.isNullOrBlank() && !enVoice.isNullOrBlank()) {
            targets.add(enEngine to enVoice)
        }

        val distinctTargets = targets.distinctBy { "${it.first}:${it.second}" }

        for ((engineId, voiceId) in distinctTargets) {
            if (isRealSpeechActive.get()) {
                Log.d(TAG, "نطق حقيقي جارٍ؛ إيقاف التجهيز الاستباقي")
                break
            }
            runCatching {
                prepareVoice(appCtx, engineId, voiceId)
            }
        }
    }

    /**
     * تجهيز صوت محدد بصمت مع دمج الطلبات المتزامنة.
     */
    suspend fun prepareVoice(
        context: Context,
        engineId: String,
        voiceId: String,
        priority: Boolean = false
    ): Boolean = withContext(AppDispatchers.io) {
        val cleanEngine = engineId.trim()
        val cleanVoice = voiceId.trim()
        if (cleanEngine.isBlank() || cleanVoice.isBlank()) {
            return@withContext false
        }

        val key = "$cleanEngine:$cleanVoice"

        // فحص الجاهزية الفعلية الحالية
        if (isVoiceReady(cleanEngine, cleanVoice)) {
            readyVoices[key] = true
            return@withContext true
        }

        // دمج الطلبات المتزامنة (coalescing)
        val existing = inFlightJobs[key]
        if (existing != null) {
            return@withContext runCatching {
                existing.await()
            }.getOrDefault(false)
        }

        val deferred = coordinatorScope.async {
            try {
                if (isRealSpeechActive.get() && !priority) {
                    return@async false
                }
                executeSilentWarmup(context, cleanEngine, cleanVoice)
            } catch (ce: CancellationException) {
                Log.d(TAG, "أُلغي إحماء الصوت $key لصالح طلب نطق حقيقي")
                false
            } catch (t: Throwable) {
                Log.w(TAG, "فشل تجهيز الصوت $key: ${t.message}")
                false
            } finally {
                inFlightJobs.remove(key)
            }
        }

        val prev = inFlightJobs.putIfAbsent(key, deferred)
        if (prev != null) {
            deferred.cancel()
            return@withContext runCatching { prev.await() }.getOrDefault(false)
        }

        val success = runCatching { deferred.await() }.getOrDefault(false)
        if (success) {
            readyVoices[key] = true
        }
        success
    }

    private fun isEnglishVoice(voiceId: String): Boolean {
        val lower = voiceId.lowercase(Locale.ROOT)
        return lower.contains("en-") ||
            lower.contains("en_") ||
            lower.contains("-en") ||
            lower.contains("_en") ||
            lower.contains("us") ||
            lower.contains("uk") ||
            lower.contains("gb") ||
            lower.contains("jenny") ||
            lower.contains("guy") ||
            lower.contains("aria") ||
            lower.contains("samantha") ||
            lower.contains("tom") ||
            lower.contains("will") ||
            lower.contains("ava") ||
            lower.contains("andrew") ||
            lower.contains("brian") ||
            lower.contains("emma") ||
            lower.contains("oliver")
    }

    private suspend fun executeSilentWarmup(
        context: Context,
        engineId: String,
        voiceId: String
    ): Boolean {
        val appCtx = context.applicationContext ?: context
        val isEn = isEnglishVoice(voiceId)
        val probeText = if (isEn) "Hello" else "مرحبا"
        val probeLocale = if (isEn) Locale.US else Locale.forLanguageTag("ar")

        when (engineId) {
            AcapelaEmbeddedVoiceProvider.PROVIDER_ID -> {
                val provider = EmbeddedProviders.acapela(appCtx)
                if (!provider.isConfigured()) return false
                var hasPcmSamples = false
                val descriptor = VoiceDescriptor(
                    id = voiceId,
                    providerId = engineId,
                    displayName = voiceId,
                    locale = probeLocale
                )
                provider.synthesize(
                    text = probeText,
                    voice = descriptor,
                    speechRate = 1.0f,
                    pitch = 1.0f,
                    volume = 0.0f,
                    onFormatInfo = { _, _ -> },
                    onAudioChunk = { chunk, validLen ->
                        if (validLen > 0 && chunk.isNotEmpty()) {
                            hasPcmSamples = true
                        }
                    },
                    enginePackage = null,
                    voiceLocale = probeLocale,
                    desiredVoiceName = voiceId,
                    category = "warmup"
                )
                return hasPcmSamples &&
                    AcapelaHostSession.isWarmVoiceReady(voiceId)
            }
            VocalizerEmbeddedVoiceProvider.PROVIDER_ID -> {
                val provider = EmbeddedProviders.vocalizer(appCtx)
                if (!provider.isConfigured()) return false
                var hasPcmSamples = false
                val descriptor = VoiceDescriptor(
                    id = voiceId,
                    providerId = engineId,
                    displayName = voiceId,
                    locale = probeLocale
                )
                provider.synthesize(
                    text = probeText,
                    voice = descriptor,
                    speechRate = 1.0f,
                    pitch = 1.0f,
                    volume = 0.0f,
                    onFormatInfo = { _, _ -> },
                    onAudioChunk = { chunk, validLen ->
                        if (validLen > 0 && chunk.isNotEmpty()) {
                            hasPcmSamples = true
                        }
                    },
                    enginePackage = null,
                    voiceLocale = probeLocale,
                    desiredVoiceName = voiceId,
                    category = "warmup"
                )
                return hasPcmSamples &&
                    VocalizerHostSession.isWarmVoiceLoaded(voiceId)
            }
            MicrosoftEmbeddedVoiceProvider.PROVIDER_ID -> {
                val provider = EmbeddedProviders.microsoft(appCtx)
                if (!provider.isConfigured()) return false
                var hasPcmSamples = false
                val descriptor = VoiceDescriptor(
                    id = voiceId,
                    providerId = engineId,
                    displayName = voiceId,
                    locale = probeLocale
                )
                provider.synthesize(
                    text = probeText,
                    voice = descriptor,
                    speechRate = 1.0f,
                    pitch = 1.0f,
                    volume = 0.0f,
                    onFormatInfo = { _, _ -> },
                    onAudioChunk = { chunk, validLen ->
                        if (validLen > 0 && chunk.isNotEmpty()) {
                            hasPcmSamples = true
                        }
                    },
                    enginePackage = null,
                    voiceLocale = probeLocale,
                    desiredVoiceName = voiceId,
                    category = "warmup"
                )
                return hasPcmSamples &&
                    MicrosoftHostSession.isWarmReady(voiceId)
            }
            else -> return false
        }
    }

    /**
     * إشعار ببدء طلب نطق حقيقي من المستخدم لإلغاء أي تجهيز استباقي منافس.
     */
    fun onSpeechRequested(
        engineId: String? = null,
        voiceId: String? = null
    ) {
        isRealSpeechActive.set(true)
        val activeTarget = if (!engineId.isNullOrBlank() &&
            !voiceId.isNullOrBlank()
        ) {
            "$engineId:$voiceId"
        } else {
            null
        }

        // إلغاء كل الأعمال الاستباقية ما عدا ما يخدم الصوت المطلوب ذاته
        for ((key, deferred) in inFlightJobs) {
            if (activeTarget == null || key != activeTarget) {
                deferred.cancel()
            }
        }
    }

    /**
     * إشعار بانتهاء النطق الحقيقي.
     */
    fun onSpeechCompleted() {
        isRealSpeechActive.set(false)
    }

    /**
     * فحص ما إذا كان الصوت دافئاً وجاهزاً في الذاكرة حالياً.
     */
    fun isVoiceReady(engineId: String, voiceId: String): Boolean {
        return when (engineId) {
            AcapelaEmbeddedVoiceProvider.PROVIDER_ID ->
                AcapelaHostSession.isWarmVoiceReady(voiceId)
            MicrosoftEmbeddedVoiceProvider.PROVIDER_ID ->
                MicrosoftHostSession.isWarmReady(voiceId)
            VocalizerEmbeddedVoiceProvider.PROVIDER_ID ->
                VocalizerHostSession.isWarmVoiceLoaded(voiceId)
            else -> readyVoices["$engineId:$voiceId"] == true
        }
    }

    /**
     * انتظار مهمة التجهيز إذا كانت جارية لنفس الصوت دون إطلاق نداء ثانٍ.
     */
    suspend fun awaitIfInProgress(
        engineId: String,
        voiceId: String
    ): Boolean {
        val key = "$engineId:$voiceId"
        val inFlight = inFlightJobs[key] ?: return false
        return runCatching { inFlight.await() }.getOrDefault(false)
    }

    /**
     * التعامل مع نقص الذاكرة وإفراغ الجلسات غير النشطة.
     */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            for ((_, deferred) in inFlightJobs) {
                deferred.cancel()
            }
            inFlightJobs.clear()
            readyVoices.clear()
            MicrosoftHostSession.trimResources()
            VocalizerHostSession.trimResources()
        }
    }

    fun resetForTest() {
        for ((_, deferred) in inFlightJobs) {
            deferred.cancel()
        }
        inFlightJobs.clear()
        readyVoices.clear()
        isRealSpeechActive.set(false)
    }
}
