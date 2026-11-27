package com.aymankhattab.nateq.core.audio.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.aymankhattab.nateq.core.audio.enginehost.AcapelaHostSession
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import com.aymankhattab.nateq.core.audio.enginehost.HostWarmStatusRegistry
import com.aymankhattab.nateq.core.audio.enginehost.MicrosoftHostSession
import com.aymankhattab.nateq.core.audio.enginehost.VocalizerHostSession
import com.aymankhattab.nateq.core.audio.providers.AcapelaEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceRepository
import com.aymankhattab.nateq.core.audio.providers.EmbeddedProviders
import com.aymankhattab.nateq.core.audio.providers.MicrosoftEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.MicrosoftModelCatalog
import com.aymankhattab.nateq.core.audio.providers.MicrosoftVoiceRepository
import com.aymankhattab.nateq.core.audio.providers.VocalizerEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VocalizerModelCatalog
import com.aymankhattab.nateq.core.audio.providers.VocalizerVoiceRepository
import com.aymankhattab.nateq.core.audio.providers.VoiceDescriptor
import com.aymankhattab.nateq.core.common.AppDispatchers
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.LocaleUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * منسّق موحّد لإدارة جاهزية وإحماء الأصوات المدمجة عبر كافة المحركات
 * (Acapela، Vocalizer، Microsoft).
 *
 * الخصائص:
 * 1. تهيئة الأصوات المختارة فعلياً على [AppDispatchers.io] عند بدء
 *    التطبيق أو الخدمة أو تغير الاختيار أو فتح شاشة الأصوات.
 * 2. دمج الطلبات المتزامنة (single-flight) بحجز الملكية قبل التنفيذ
 *    وحذف المهمة بمقارنة الهوية وتنظيف الفاشلة والملغاة.
 * 3. إعطاء الأولوية للنطق الحقيقي وربط علمه بدورة الطلب ومعرفه الفريد.
 * 4. التحقق من جاهزية المضيف عبر IPC وسجل الحالة المشترك بين العمليات
 *    دون الاعتماد على متغيرات companion محلية عبر حدود العمليات.
 * 5. إحماء صامت حقيقي بدقة لغوية مستخرجة من بيانات الصوت المثبت.
 */
object VoiceReadinessCoordinator {

    private const val TAG = "VoiceReadiness"

    private val coordinatorScope = CoroutineScope(
        SupervisorJob() + AppDispatchers.io
    )

    private val inFlightDeferreds =
        ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val readyVoices = ConcurrentHashMap<String, Boolean>()
    private val actuallyUsedEmbeddedVoices =
        ConcurrentHashMap.newKeySet<String>()

    private val speechRequestSeq = AtomicLong(0L)
    private val activeSpeechRequestId = AtomicLong(0L)
    private val isRealSpeechActive = AtomicBoolean(false)
    private val lastSpeechCompletedAtMs = AtomicLong(0L)

    @Volatile
    private var appContext: Context? = null
    private val rescheduleJob = java.util.concurrent.atomic.AtomicReference<Job?>(
        null
    )

    @androidx.annotation.VisibleForTesting
    internal var rewarmQuietDelayMs: Long = 8_000L

    @androidx.annotation.VisibleForTesting
    internal var rewarmAfterTrimDelayMs: Long = 60_000L

    @androidx.annotation.VisibleForTesting
    internal var rewarmPeriodicDelayMs: Long = 30 * 60_000L

    /**
     * تجهيز الأصوات النشطة المعينة في الإعدادات في الخلفية على IO.
     * يشمل أيضاً الأصوات المدمجة المستخدمة فعلاً (لا المسماة فقط) حتى لا
     * تبدأ باردة بعد خمول/تفريغ ذاكرة، مع عدم المزاحمة أثناء النطق الحقيقي.
     */
    fun prepareActiveVoices(
        context: Context,
        priorityVoiceId: String? = null,
        priorityEngineId: String? = null
    ): Job = coordinatorScope.launch {
        val appCtx = context.applicationContext ?: context
        appContext = appCtx
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

        // الأصوات المدمجة المستخدمة فعلاً: تمنع البدء البارد بعد خمول/trim.
        for (key in actuallyUsedEmbeddedVoices) {
            val split = key.indexOf(':')
            if (split <= 0 || split >= key.lastIndex) continue
            val engine = key.substring(0, split)
            val voice = key.substring(split + 1)
            if (isEmbeddedEngine(engine)) {
                targets.add(engine to voice)
            }
        }

        val distinctTargets = targets.distinctBy {
            "${HostProtocol.normalizeEngineId(it.first)}:${it.second.trim()}"
        }

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

    private fun isEmbeddedEngine(engineId: String): Boolean =
        when (HostProtocol.normalizeEngineId(engineId)) {
            HostProtocol.ENGINE_ACAPELA,
            HostProtocol.ENGINE_VOCALIZER,
            HostProtocol.ENGINE_MICROSOFT -> true
            else -> false
        }

    /**
     * يسجّل صوتاً مدمجاً استُخدم فعلياً ليُدرج في التدفئة اللاحقة بعد
     * الخمول/التفريغ، فلا تبدأ أول جملة باردة لصوت استُخدم سابقاً.
     */
    fun recordActuallyUsedVoice(engineId: String?, voiceId: String?) {
        val e = engineId?.trim().orEmpty()
        val v = voiceId?.trim().orEmpty()
        if (e.isBlank() || v.isBlank()) return
        if (!isEmbeddedEngine(e)) return
        actuallyUsedEmbeddedVoices.add(
            "${HostProtocol.normalizeEngineId(e)}:$v"
        )
    }

    @androidx.annotation.VisibleForTesting
    internal fun scheduleRewarmIfIdle(
        delayMs: Long = rewarmQuietDelayMs
    ) {
        val ctx = appContext ?: return
        val next = coordinatorScope.launch {
            val current = kotlin.coroutines.coroutineContext[Job]
            try {
                kotlinx.coroutines.delay(delayMs)
                if (isRealSpeechActive.get()) return@launch
                prepareActiveVoices(ctx).join()
                // دورية معقولة أثناء الخمول الطويل دون استنزاف البطارية.
                if (!isRealSpeechActive.get()) {
                    rescheduleJob.compareAndSet(current, null)
                    scheduleRewarmIfIdle(rewarmPeriodicDelayMs)
                }
            } catch (_: CancellationException) {
                // أُلغي لأن نطقاً حقيقياً بدأ أو أُعيدت الجدولة.
            } catch (t: Throwable) {
                Log.w(TAG, "فشلت إعادة جدولة تدفئة الأصوات", t)
            } finally {
                rescheduleJob.compareAndSet(current, null)
            }
        }
        val prev = rescheduleJob.getAndSet(next)
        if (prev != null && prev !== next) {
            prev.cancel()
        }
    }

    private fun cancelReschedule() {
        rescheduleJob.getAndSet(null)?.cancel()
    }

    @androidx.annotation.VisibleForTesting
    internal fun hasPendingRewarmForTest(): Boolean =
        rescheduleJob.get()?.isActive == true

    /**
     * تجهيز صوت محدد بصمت مع دمج الطلبات المتزامنة (Single-Flight آمن).
     */
    suspend fun prepareVoice(
        context: Context,
        engineId: String,
        voiceId: String,
        priority: Boolean = false
    ): Boolean = withContext(AppDispatchers.io) {
        val cleanEngine = HostProtocol.normalizeEngineId(engineId)
        val cleanVoice = voiceId.trim()
        if (cleanEngine.isBlank() || cleanVoice.isBlank()) {
            return@withContext false
        }

        val key = "$cleanEngine:$cleanVoice"

        // فحص الجاهزية الفعلية الحالية
        if (isVoiceReady(context, cleanEngine, cleanVoice)) {
            readyVoices[key] = true
            return@withContext true
        }

        if (isRealSpeechActive.get() && !priority) {
            return@withContext false
        }

        // حجز ملكية المهمة قبل بدء التنفيذ
        val myDeferred = CompletableDeferred<Boolean>()
        val existing = inFlightDeferreds.putIfAbsent(key, myDeferred)
        if (existing != null) {
            return@withContext runCatching {
                existing.await()
            }.getOrDefault(false)
        }

        val appCtx = context.applicationContext ?: context
        try {
            if (isRealSpeechActive.get() && !priority) {
                myDeferred.complete(false)
                return@withContext false
            }

            coordinatorScope.launch {
                try {
                    if (isRealSpeechActive.get() && !priority) {
                        myDeferred.complete(false)
                        return@launch
                    }
                    val ok = executeSilentWarmup(
                        appCtx,
                        cleanEngine,
                        cleanVoice
                    )
                    myDeferred.complete(ok)
                } catch (ce: CancellationException) {
                    myDeferred.cancel(ce)
                } catch (t: Throwable) {
                    Log.w(TAG, "فشل تجهيز الصوت $key: ${t.message}")
                    myDeferred.complete(false)
                }
            }

            val success = runCatching {
                myDeferred.await()
            }.getOrDefault(false)

            if (success) {
                readyVoices[key] = true
            }
            success
        } catch (ce: CancellationException) {
            myDeferred.cancel(ce)
            throw ce
        } finally {
            // حذف المهمة بمقارنة الهوية لا المفتاح وحده
            inFlightDeferreds.remove(key, myDeferred)
        }
    }

    fun resolveProbeDetails(
        context: Context,
        engineId: String,
        voiceId: String
    ): Pair<String, Locale> {
        val cleanEngine = HostProtocol.normalizeEngineId(engineId)
        val directTag = LocaleUtils.toBcp47Tag(voiceId, fallbackToRaw = false)
        val directLocale = if (directTag != null) {
            Locale.forLanguageTag(directTag)
        } else {
            null
        }

        val voiceLocale: Locale? = directLocale ?: when (cleanEngine) {
            HostProtocol.ENGINE_MICROSOFT -> {
                MicrosoftModelCatalog.findItemByNameOrModel(
                    context,
                    voiceId
                )?.locale
            }
            HostProtocol.ENGINE_ACAPELA -> {
                AcapelaVoiceRepository.getSnapshot().voices.firstOrNull {
                    it.id.equals(voiceId, ignoreCase = true)
                }?.locale
            }
            HostProtocol.ENGINE_VOCALIZER -> {
                VocalizerModelCatalog.findByIdOrHeader(voiceId)?.locale
                    ?: VocalizerModelCatalog.findItemsForVoiceName(voiceId)
                        .firstOrNull()?.locale
            }
            else -> null
        }

        val locale = voiceLocale ?: Locale.forLanguageTag("ar")

        val lang = locale.language.lowercase(Locale.ROOT)
        val probeText = when (lang) {
            "ar" -> "مرحبا"
            "en" -> "Hello"
            "fr" -> "Bonjour"
            "es" -> "Hola"
            "de" -> "Hallo"
            "it" -> "Ciao"
            "tr" -> "Merhaba"
            "ru" -> "Привет"
            else -> if (lang == "ar") "مرحبا" else "Hello"
        }
        return probeText to locale
    }

    private suspend fun executeSilentWarmup(
        context: Context,
        engineId: String,
        voiceId: String
    ): Boolean {
        val appCtx = context.applicationContext ?: context
        val (probeText, probeLocale) = resolveProbeDetails(
            appCtx,
            engineId,
            voiceId
        )

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
                    isVoiceReady(appCtx, engineId, voiceId)
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
                    isVoiceReady(appCtx, engineId, voiceId)
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
                    isVoiceReady(appCtx, engineId, voiceId)
            }
            else -> return false
        }
    }

    /**
     * إشعار ببدء طلب نطق حقيقي من المستخدم لإلغاء أي تجهيز استباقي منافس.
     * يرجع معرفاً فريداً للطلب لربط دورة حياته.
     */
    fun onSpeechRequested(
        engineId: String? = null,
        voiceId: String? = null
    ): Long {
        val reqId = speechRequestSeq.incrementAndGet()
        activeSpeechRequestId.set(reqId)
        isRealSpeechActive.set(true)
        cancelReschedule()
        recordActuallyUsedVoice(engineId, voiceId)
        val activeTarget = if (!engineId.isNullOrBlank() &&
            !voiceId.isNullOrBlank()
        ) {
            val norm = HostProtocol.normalizeEngineId(engineId)
            "$norm:${voiceId.trim()}"
        } else {
            null
        }

        // إلغاء كل الأعمال الاستباقية ما عدا ما يخدم الصوت المطلوب ذاته
        for ((key, deferred) in inFlightDeferreds) {
            if (activeTarget == null || key != activeTarget) {
                deferred.cancel()
            }
        }
        return reqId
    }

    /**
     * إشعار بانتهاء النطق الحقيقي مع ربطه بمعرف الطلب.
     * بعد هدوء قصير يعيد جدولة تدفئة الأصوات المدمجة في الخلفية دون
     * مزاحمة أي نطق حقيقي وارد.
     */
    fun onSpeechCompleted(requestId: Long? = null) {
        val ended = if (requestId == null) {
            activeSpeechRequestId.set(0L)
            isRealSpeechActive.set(false)
            true
        } else {
            // لا تجعل انتهاء طلب قديم يخفض علم طلب أحدث
            if (activeSpeechRequestId.compareAndSet(requestId, 0L)) {
                isRealSpeechActive.set(false)
                true
            } else {
                false
            }
        }
        if (ended) {
            lastSpeechCompletedAtMs.set(System.currentTimeMillis())
            scheduleRewarmIfIdle(rewarmQuietDelayMs)
        }
    }

    /**
     * فحص ما إذا كان الصوت دافئاً وجاهزاً في الذاكرة حالياً.
     * يفحص مصدر الحقيقة المشترك بين العمليات
     * (مراجعة البيانات وجيل المضيف وPCM).
     */
    fun isVoiceReady(
        context: Context,
        engineId: String,
        voiceId: String
    ): Boolean {
        val cleanEngine = HostProtocol.normalizeEngineId(engineId)
        val cleanVoice = voiceId.trim()
        val expectedRev = when (cleanEngine) {
            HostProtocol.ENGINE_ACAPELA ->
                AcapelaVoiceRepository.getStorageRevision(context)
            HostProtocol.ENGINE_VOCALIZER ->
                VocalizerVoiceRepository.getStorageRevision(context)
            HostProtocol.ENGINE_MICROSOFT ->
                MicrosoftVoiceRepository.getStorageRevision(context)
            else -> 0L
        }
        val hostGen = EngineHostClient.hostGeneration.get()
        val isWarm = HostWarmStatusRegistry.isVoiceWarm(
            context = context,
            engineId = cleanEngine,
            voiceId = cleanVoice,
            expectedRevision = expectedRev,
            expectedHostGen = hostGen
        )
        if (isWarm) {
            readyVoices["$cleanEngine:$cleanVoice"] = true
            return true
        }

        // التحقق من حالة المضيف في البيئة المحلية
        // (الاختبارات أو داخل نفس العملية)
        val companionReady = when (cleanEngine) {
            HostProtocol.ENGINE_ACAPELA ->
                AcapelaHostSession.isWarmVoiceReady(cleanVoice)
            HostProtocol.ENGINE_MICROSOFT ->
                MicrosoftHostSession.isWarmReady(cleanVoice)
            HostProtocol.ENGINE_VOCALIZER ->
                VocalizerHostSession.isWarmVoiceLoaded(cleanVoice)
            else -> readyVoices["$cleanEngine:$cleanVoice"] == true
        }
        return companionReady
    }

    /**
     * فحص الجاهزية دون تمرير سياق (للاختبارات أو النداءات المحلية).
     */
    fun isVoiceReady(engineId: String, voiceId: String): Boolean {
        val cleanEngine = HostProtocol.normalizeEngineId(engineId)
        val cleanVoice = voiceId.trim()
        return when (cleanEngine) {
            HostProtocol.ENGINE_ACAPELA ->
                AcapelaHostSession.isWarmVoiceReady(cleanVoice)
            HostProtocol.ENGINE_MICROSOFT ->
                MicrosoftHostSession.isWarmReady(cleanVoice)
            HostProtocol.ENGINE_VOCALIZER ->
                VocalizerHostSession.isWarmVoiceLoaded(cleanVoice)
            else -> readyVoices["$cleanEngine:$cleanVoice"] == true
        }
    }

    /**
     * انتظار مهمة التجهيز إذا كانت جارية لنفس الصوت دون إطلاق نداء ثانٍ.
     */
    suspend fun awaitIfInProgress(
        engineId: String,
        voiceId: String
    ): Boolean {
        val cleanEngine = HostProtocol.normalizeEngineId(engineId)
        val key = "$cleanEngine:${voiceId.trim()}"
        val inFlight = inFlightDeferreds[key] ?: return false
        return runCatching { inFlight.await() }.getOrDefault(false)
    }

    /**
     * التعامل مع نقص الذاكرة وإفراغ الجلسات غير النشطة.
     * بعد التفريغ يُعاد جدولة تدفئة خفيفة بعد هدوء لتجنب بدء بارد لاحق.
     */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            cancelReschedule()
            for ((_, deferred) in inFlightDeferreds) {
                deferred.cancel()
            }
            inFlightDeferreds.clear()
            readyVoices.clear()
            MicrosoftHostSession.trimResources()
            VocalizerHostSession.trimResources()
            scheduleRewarmIfIdle(rewarmAfterTrimDelayMs)
        }
    }

    fun resetForTest() {
        cancelReschedule()
        for ((_, deferred) in inFlightDeferreds) {
            deferred.cancel()
        }
        inFlightDeferreds.clear()
        readyVoices.clear()
        actuallyUsedEmbeddedVoices.clear()
        isRealSpeechActive.set(false)
        activeSpeechRequestId.set(0L)
        speechRequestSeq.set(0L)
        lastSpeechCompletedAtMs.set(0L)
        appContext = null
        rewarmQuietDelayMs = 8_000L
        rewarmAfterTrimDelayMs = 60_000L
        rewarmPeriodicDelayMs = 30 * 60_000L
        HostWarmStatusRegistry.resetForTests()
    }
}
