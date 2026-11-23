package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import com.aymankhattab.nateq.core.audio.announcement.LoudnessStage
import com.aymankhattab.nateq.core.audio.engine.SpeechFallbackTracker
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import com.aymankhattab.nateq.core.audio.enginehost.VocalizerHostSession
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.VoiceIdContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * مزود صوت محرك Nuance Vocalizer Expressive 2 (Embedded Engine) —
 * **لا تُحمَّل المكتبة الأصلية في هذه العملية أبداً** (ع٢٩-5): كل
 * نداء (تهيئة/قائمة أصوات/نطق) يمرّ عبر [EngineHostClient] إلى
 * `:engine_host`، فيه يعيش `VocalizerEngine` وحده؛ فإن انهار
 *JNI قُتلت تلك العملية فقط.
 */
class VocalizerEmbeddedVoiceProvider(
    private val context: Context,
    private val customVoiceDir: File? = null
) : VoiceProvider {

    companion object {
        private const val TAG = "VocalizerEmbedded"
        const val PROVIDER_ID = "vocalizer_embedded"

        /**
         * هل جُرّبت إعادةُ المحاولة التلقائية بعد علامة انهيارٍ في هذه
         * العملية؟ **مرّة واحدة فقط:** العلامةُ الحقيقية تعني انهياراً
         * أصلياً أثناء `init`، فتكرارُ المحاولة في كل نداء يُدخل المضيف
         * في حلقة انهيار. أمّا العلامةُ البائتة (بعد [HostProtocol.
         * MARKER_TTL_MS]) فقد مُسحت في [HostProtocol.hasMarker] فلم
         * يصلنا رمزها أصلاً.
         */
        private val crashRetryDone = AtomicBoolean(false)

        /** تصفير حارس إعادة المحاولة — للاختبارات فقط. */
        internal fun resetCrashRetryForTest() {
            crashRetryDone.set(false)
        }

        fun resolveDataDir(context: Context): File? =
            VocalizerEmbeddedVoiceProvider(context).resolveDataDir()

        /**
         * بند 12: إعلان مسموع فعلي (أفضل جهد) لفشل الصوت مع الحفاظ على
         * سبب الخطأ، عبر تنبيه مرئي ومحاولة نطق بنظام TTS الافتراضي.
         */
        internal fun announceVoiceFailureAudibly(
            context: Context,
            message: String
        ) {
            val appCtx = context.applicationContext ?: context
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    Toast.makeText(
                        appCtx, message, Toast.LENGTH_LONG
                    ).show()
                }
                runCatching {
                    var tts: TextToSpeech? = null
                    tts = TextToSpeech(appCtx) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            runCatching {
                                tts?.language =
                                    Locale.forLanguageTag("ar")
                                tts?.speak(
                                    message,
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "nateq_voice_fail"
                                )
                            }
                        } else {
                            runCatching { tts?.shutdown() }
                        }
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching { tts?.stop() }
                        runCatching { tts?.shutdown() }
                    }, 6000L)
                }
            }
        }
    }

    /**
     * ع٣١-2: تحديد مجلد البيانات الفعلي لـ Vocalizer.
     */
    fun resolveDataDir(): File? {
        EmbeddedStorage.migrateExternalToInternal(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        return findDataDir()
    }

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Vocalizer Expressive 2 (مدمج)"

    /** مسار بيانات مُتحقق منه يُعاد استخدامه (بلا مسح قرصي متكرر). */
    @Volatile
    private var cachedDataPath: String? = null

    /** كل مجلدات البحث من المصدر الموحّد (ع٢٩-3) — بلا مسارات خام. */
    private fun searchDirs(): List<File> {
        val extra = listOfNotNull(customVoiceDir)
        return EmbeddedStorage.allSearchDirs(
            context,
            EmbeddedStorage.Engine.VOCALIZER,
            extra
        )
    }

    /**
     * يبحث عن مجلد فيه ملفات بيانات Vocalizer (.dat/.hdr) لصوت مكتمل.
     * فحصُ قرصٍ فقط — لا يلمس المكتبة الأصلية ولا المضيف ولا يُنشئ مجلدات.
     */
    private fun findDataDir(): File? {
        for (dir in searchDirs()) {
            if (VocalizerVoiceManifest.hasCompleteVoice(dir)) {
                return dir
            }
        }
        return null
    }

    /**
     * ع٣١-4: فحص خفيف لا يلمس المكتبة الأصلية — يعود true
     * فقط عند وجود صوت مكتمل واحد على الأقل (.hdr + .dat >= 1MB).
     */
    override fun isConfigured(): Boolean {
        if (customVoiceDir != null) {
            return EmbeddedStorage.inventory(
                context,
                EmbeddedStorage.Engine.VOCALIZER,
                listOfNotNull(customVoiceDir)
            ).isCompleteVoicePresent
        }
        val snapshot = VocalizerVoiceRepository.getSnapshot()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!snapshot.isLoaded) {
                VocalizerVoiceRepository.triggerBackgroundRefresh(context)
            }
            return snapshot.isConfigured
        }
        return if (snapshot.isLoaded) {
            snapshot.isConfigured
        } else {
            EmbeddedStorage.inventory(
                context,
                EmbeddedStorage.Engine.VOCALIZER
            ).isCompleteVoicePresent
        }
    }

    /**
     * ع٢٩-5: تهيئة المحرّك **في المضيف**. المسار يُحقَن مرة واحدة،
     * ثم يُستدعى init عند كل نطق/قائمة حتى يعود المضيف المُعاد
     * تشغيله (session جديدة) إلى جاهزيته بنداءٍ واحد سريع.
     */
    private suspend fun ensureInitialized(): Boolean {
        if (cachedDataPath == null) {
            // ع٢٩-3: تُنجَز هجرة المجلد الخارجي قبل البحث.
            EmbeddedStorage.migrateExternalToInternal(
                context,
                EmbeddedStorage.Engine.VOCALIZER
            )
            // ع٢٩-2 (6): بلا مجلد بيانات صالح لا ننشئ محركاً أصلاً.
            val dataDir = findDataDir()
            if (dataDir == null) {
                Log.w(
                    TAG,
                    "No Vocalizer data dir (.dat/.hdr) found — " +
                        "engine will not be created"
                )
                EngineStatusRegistry.record(
                    PROVIDER_ID,
                    EngineStatusRegistry.State.NOT_INSTALLED,
                    message = "لا ملفات أصوات Vocalizer على القرص"
                )
                return false
            }
            cachedDataPath = dataDir.absolutePath
        }
        val prevState = EngineStatusRegistry.statusOf(PROVIDER_ID)?.state
        EngineStatusRegistry.noteInitPending(PROVIDER_ID)
        val dataDir = cachedDataPath.orEmpty()
        var result = EngineHostClient.init(
            context,
            HostProtocol.ENGINE_VOCALIZER,
            dataDir
        )
        // **شفاءٌ تلقائي من علامة انهيارٍ عالقة:** علامةٌ كُتبت ثم قُتلت
        // العملية قبل محوها (LMK/ANR/إغلاق قسري) كانت تُبقي المحرّك
        // «فاشل التهيئة» أبداً مع سلامة بياناته. إن كانت العلامة حديثة
        // فعلاً فإعادةُ المحاولة الواحدة تكشفها: ينهار المضيف وحده ولا
        // تتكرر المحاولة في هذه العملية.
        if (!result.ok &&
            result.code == HostProtocol.CODE_CRASHED_LAST_RUN &&
            crashRetryDone.compareAndSet(false, true)
        ) {
            Log.w(
                TAG,
                "علامة انهيار عالقة — محوها وإعادة محاولة واحدة"
            )
            HostProtocol.clearMarker(
                context.filesDir,
                HostProtocol.ENGINE_VOCALIZER
            )
            result = EngineHostClient.init(
                context,
                HostProtocol.ENGINE_VOCALIZER,
                dataDir,
                force = true
            )
        }
        if (!result.ok) {
            Log.e(
                TAG,
                "تهيئة المضيف فشلت [${result.code}]: ${result.message}"
            )
            EngineStatusRegistry.noteFailure(
                PROVIDER_ID,
                result.code,
                result.message
            )
            return false
        }
        EngineStatusRegistry.noteReady(PROVIDER_ID)
        // ع٣٠-9: إبطال الكاش فقط عند الانتقال إلى READY من غيرها
        if (prevState != EngineStatusRegistry.State.READY) {
            EnginePicker.invalidateAfterStorageChange()
        }
        return true
    }

    override suspend fun listVoices(locale: Locale): List<VoiceDescriptor> {
        return withContext(Dispatchers.IO) {
            if (!ensureInitialized()) {
                // بند 5: عدم إرجاع قائمة فارغة بلا سبب واضح للواجهة.
                val st = EngineStatusRegistry.statusOf(context, PROVIDER_ID)
                val reason = st.lastError ?: st.code
                if (reason.isNullOrBlank()) {
                    val msg =
                        "تعذّرت تهيئة محرك Vocalizer أو لم " +
                            "يُعثر على بيانات أصوات"
                    EngineStatusRegistry.noteFailure(
                        PROVIDER_ID, "NOT_INITIALIZED", msg
                    )
                    Log.w(TAG, "listVoices: غير مهيّأ — $msg")
                } else {
                    Log.w(TAG, "listVoices: غير مهيّأ — $reason")
                }
                return@withContext emptyList()
            }
            val result = EngineHostClient.listVoices(
                context,
                HostProtocol.ENGINE_VOCALIZER
            )
            result.failure?.let { failure ->
                val msg = failure.message
                    ?: "فشل جلب أصوات Vocalizer"
                Log.e(
                    TAG,
                    "قائمة أصوات المضيف فشلت " +
                        "[${failure.code}]: $msg"
                )
                EngineStatusRegistry.noteFailure(
                    PROVIDER_ID,
                    failure.code,
                    msg
                )
                // بند 5: تمرير سبب الفشل للواجهة عبر سجل الحالة.
                return@withContext emptyList()
            }
            result.voices.mapNotNull { voice ->
                val name = voice.name.ifBlank { return@mapNotNull null }
                val rawLang = voice.langTag
                // بند 6: تسجيل اسم الصوت واللغة الخام لكل صوت.
                Log.i(
                    TAG,
                    "listVoices voice: name=$name rawLang=$rawLang"
                )

                // ع٣١-10 (4): langTag القادم من المضيف هو مصدر اللغة؛
                // تطبيع الوسم إلى BCP-47 صحيح (بند 6).
                val normalizedTag = LocaleUtils.toBcp47Tag(rawLang)
                    ?.takeIf { it.isNotBlank() }
                    ?: rawLang.takeIf { it.isNotBlank() }
                val voiceLocale = if (!normalizedTag.isNullOrBlank()) {
                    runCatching {
                        Locale.forLanguageTag(normalizedTag)
                    }.getOrDefault(locale)
                } else {
                    // بند 5: اشتقاق اللغة عند فراغها وتسجيل ذلك.
                    val derived = LocaleUtils.toBcp47Tag(name)
                        ?: locale.toLanguageTag()
                    Log.w(
                        TAG,
                        "listVoices: لغة فارغة للصوت $name، " +
                            "اشتُقّت: $derived"
                    )
                    runCatching {
                        Locale.forLanguageTag(derived)
                    }.getOrDefault(locale)
                }

                // البند 6: طَبِّع رمز اللغة قبل المقارنة كي تُقبل
                // الصيغ الثلاثية (ara/enu/…) والثنائية (ar/en) معاً.
                // إصلاح 1.6.142 (ع4): استنتاج لغة مفقودة (مثل Mariam→ara/ar)
                // أو لغة المحرك الافتراضية لمنع مجموعة لغة فارغة.
                val inferredLocale = resolveVoiceLocale(
                    candidate = voiceLocale,
                    rawLang = rawLang,
                    voiceId = voice.id,
                    voiceName = name,
                    fallback = locale
                )
                val requestedLang = locale.language.takeIf {
                    it.isNotBlank()
                }?.let { LocaleUtils.normalizeLanguageCode(it) }
                val voiceLangCode = LocaleUtils.normalizeLanguageCode(
                    inferredLocale.toLanguageTag().ifBlank { rawLang }
                )
                val matchesLang = requestedLang == null ||
                    voiceLangCode.equals(requestedLang, ignoreCase = true)
                if (!matchesLang) return@mapNotNull null

                VoiceDescriptor(
                    id = voice.id,
                    providerId = PROVIDER_ID,
                    displayName = name,
                    locale = inferredLocale
                )
            }
        }
    }


    /**
     * يضمن أن يكون للغة الصوت قيمة غير فارغة. عند غياب اللغة يستنتجها من
     * المعرّف/الاسم (مثل Mariam → ara/ar)، وإلا يستخدم لغة المحرك/السياق
     * الافتراضية [fallback]، منعاً لمجموعة لغة فارغة.
     */
    private fun resolveVoiceLocale(
        candidate: Locale,
        rawLang: String,
        voiceId: String,
        voiceName: String,
        fallback: Locale
    ): Locale {
        if (candidate.language.isNotBlank()) return candidate
        val inferredTag = inferLanguageTag(voiceId, voiceName, rawLang)
            ?: fallback.toLanguageTag()
                .takeIf { fallback.language.isNotBlank() }
            ?: LanguageCode.AR.tag
        return runCatching { Locale.forLanguageTag(inferredTag) }
            .getOrDefault(fallback)
            .takeIf { it.language.isNotBlank() }
            ?: Locale.forLanguageTag(LanguageCode.AR.tag)
    }

    private fun inferLanguageTag(
        voiceId: String,
        voiceName: String,
        rawLang: String
    ): String? {
        val combined = "$voiceId $voiceName $rawLang"
        if (combined.contains("mariam", ignoreCase = true)) {
            return LanguageCode.AR.tag
        }
        if (combined.contains("tarik", ignoreCase = true) ||
            combined.contains("salma", ignoreCase = true) ||
            combined.contains("leila", ignoreCase = true) ||
            combined.contains("mehdi", ignoreCase = true)
        ) {
            return LanguageCode.AR.tag
        }
        val normalized = LocaleUtils.toBcp47Tag(rawLang)
            ?.takeIf { it.isNotBlank() }
            ?: LocaleUtils.toBcp47Tag(voiceName)
            ?: LocaleUtils.toBcp47Tag(voiceId)
        val tag = normalized?.let { Locale.forLanguageTag(it) }
        return tag?.toLanguageTag()?.takeIf { tag.language.isNotBlank() }
    }

    override suspend fun synthesize(
        text: String,
        voice: VoiceDescriptor,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onFormatInfo: (sampleRateInHz: Int, channelCount: Int) -> Unit,
        onAudioChunk: (ByteArray, Int) -> Unit,
        enginePackage: String?,
        voiceLocale: Locale?,
        desiredVoiceName: String?,
        category: String?
    ) {
        withContext(Dispatchers.IO) {
            if (!ensureInitialized()) {
                Log.e(TAG, "Cannot synthesize: host not initialized")
                return@withContext
            }
            val requestedLocale = voiceLocale ?: voice.locale
            // بند 2/4: مرّر هوية الصوت المثبت المحدد للمستخدم (معرّف
            // المتغيّر/الهيدر) إن وُجدت، ولا تعتمد على معرّف لغة فقط.
            val requestedId = desiredVoiceName?.takeIf { it.isNotBlank() }
                ?: voice.id.takeIf { it.isNotBlank() }
                ?: voice.displayName
            val rawName = requestedId.removePrefix("vocalizer_").trim()
            // المضيف (`listVoices`) مصدر الأصوات المثبتة فعلياً.
            val installedDescriptors = runCatching {
                listVoices(requestedLocale)
            }.getOrDefault(emptyList())
            val installed = installedDescriptors.map {
                InstalledVocalizerVoice(
                    name = it.displayName,
                    language = it.locale.language
                )
            }
            val resolution = resolveInstalledVocalizerVoice(
                context = context,
                requestedName = rawName,
                requestedLanguage = requestedLocale.language,
                installed = installed
            )
            // إن لم يُطابق المطلوب ولم يكن اسم الصوت محدداً من المستخدم
            // (معرّف لغة فقط) فلا نختار بديلاً بصمتاً (بند 2).
            val targetVoiceId = when {
                resolution.matched -> {
                    // فضّل المعرّف الثابت للمتغيّر المطابق إن وُجد.
                    installedDescriptors.firstOrNull {
                        it.id.equals(resolution.name, true) ||
                            it.displayName.equals(resolution.name, true)
                    }?.id ?: installedDescriptors.firstOrNull {
                        VoiceIdContract.sameVoiceName(
                            it.displayName, resolution.name
                        )
                    }?.id ?: resolution.name
                }
                VocalizerHostSession.isLanguageOnlyVoiceId(rawName) ||
                    VocalizerHostSession.isLanguageOnlyVoiceId(requestedId) -> {
                    val msg = "معرّف لغة فقط بلا صوت محدد: $requestedId"
                    Log.e(TAG, msg)
                    EngineStatusRegistry.noteFailure(
                        PROVIDER_ID, "VOICE_NOT_FOUND", msg
                    )
                    announceVoiceFailureAudibly(context, msg)
                    val failureReason =
                        "فشل نطق Vocalizer [VOICE_NOT_FOUND]: $msg"
                    runCatching {
                        SpeechFallbackTracker.record(
                            text = text,
                            languageTag = "ar",
                            requestedEngine = PROVIDER_ID,
                            requestedVoice = requestedId,
                            actualEngine = PROVIDER_ID,
                            actualVoice = null,
                            isFallback = true,
                            fallbackReason = failureReason,
                            source = category ?: "VocalizerEmbedded"
                        )
                    }
                    runCatching {
                        SettingsRepository.create(context)
                            .setLastFallbackFailureInfo(
                                "$failureReason | requested=$requestedId"
                            )
                    }
                    return@withContext
                }
                else -> resolution.name.ifBlank { requestedId }
            }
            val isEvent = LoudnessStage.isEventCategory(category)
            val chunkConsumer: (ByteArray, Int) -> Unit = if (isEvent) {
                val gainDb = runCatching {
                    SettingsRepository.create(context).getEventPcmGainDb()
                }.getOrDefault(LoudnessStage.DEFAULT_GAIN_DB)
                if (gainDb > 0f) {
                    { chunk, validLength ->
                        if (validLength > 0) {
                            LoudnessStage.processPcm16(
                                chunk, 0, validLength, gainDb
                            )
                        }
                        onAudioChunk(chunk, validLength)
                    }
                } else {
                    onAudioChunk
                }
            } else {
                onAudioChunk
            }
            val synthResult = EngineHostClient.synthesize(
                context = context,
                engineId = HostProtocol.ENGINE_VOCALIZER,
                voiceId = targetVoiceId,
                text = text,
                rate = speechRate,
                pitch = pitch,
                volume = volume,
                onFormatInfo = onFormatInfo,
                onAudioChunk = chunkConsumer
            )
            if (!synthResult.ok) {
                val msg = synthResult.message
                    ?: "فشل تخليق صوت Vocalizer"
                Log.e(
                    TAG,
                    "نطق المضيف فشل [${synthResult.code}]: $msg"
                )
                EngineStatusRegistry.noteFailure(
                    PROVIDER_ID,
                    synthResult.code,
                    msg
                )
                // بند 12: إعلان مسموع فعلي عند فشل تحميل/وجود الصوت.
                if (synthResult.code == "VOICE_NOT_FOUND" ||
                    synthResult.code == "LOAD_VOICE_FAILED"
                ) {
                    announceVoiceFailureAudibly(context, msg)
                }
                val failureReason =
                    "فشل نطق Vocalizer [${synthResult.code}]: $msg"
                runCatching {
                    SpeechFallbackTracker.record(
                        text = text,
                        languageTag = "ar",
                        requestedEngine = PROVIDER_ID,
                        requestedVoice = requestedId,
                        actualEngine = PROVIDER_ID,
                        actualVoice = null,
                        isFallback = true,
                        fallbackReason = failureReason,
                        source = category ?: "VocalizerEmbedded"
                    )
                }
                runCatching {
                    SettingsRepository.create(context)
                        .setLastFallbackFailureInfo(
                            "$failureReason | requested=$requestedId"
                        )
                }
            }
        }
    }

    override fun shutdown() {
        cachedDataPath = null
        EngineHostClient.release(
            context,
            HostProtocol.ENGINE_VOCALIZER
        )
    }
}
