package com.aymankhattab.nateq.settings

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import com.aymankhattab.nateq.core.audio.announcement.ArabicSpeechNormalizer
import com.aymankhattab.nateq.core.audio.announcement.prepareArabicForEngine
import com.aymankhattab.nateq.core.audio.announcement.EventLoudnessPolicy
import com.aymankhattab.nateq.core.audio.announcement.ResolvedAudioRoute
import com.aymankhattab.nateq.core.audio.engine.AudioEffectManager
import com.aymankhattab.nateq.core.audio.engine.quirks.EngineQuirks
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import com.aymankhattab.nateq.core.audio.providers.EmbeddedProviders
import com.aymankhattab.nateq.core.audio.providers.EngineStatusRegistry
import com.aymankhattab.nateq.core.audio.providers.VocalizerEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VocalizerModelCatalog
import com.aymankhattab.nateq.core.audio.providers.VoiceDescriptor
import com.aymankhattab.nateq.core.audio.providers.VoiceProvider
import com.aymankhattab.nateq.core.data.CategoryLoudnessConfig
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.TextProcessor
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * قيم معاينة نطق: كلها تُبنى من قيم العرض الحالية المعروضة في الشاشة
 * (بند الأوامر 4) لا من القيم المحفوظة القديمة.
 */
internal data class PreviewParams(
    val enginePkg: String?,
    val voiceName: String,
    val languageTag: String,
    val speechRate: Float,
    val pitch: Float,
    val volume: Float,
    val sampleText: String,
    /**
     * هل هذه معاينةُ **فئةِ متصل**؟ فتُطبَّق سماتُ المسار الحقيقي
     * (`USAGE_NOTIFICATION_EVENT` + مجرى الإشعارات) فتخرج المعاينةُ
     * بالصوتِ نفسه الذي يخرج به النطقُ الفعلي.
     */
    val isCallerCategory: Boolean = false,
    /**
     * فئةُ النطق الصريحة (time أو battery أو caller أو sms أو notifications)
     * لحلِّ مسار الصوت ومستوى جهارته ومؤثراته طبقاً لـ
     * [AnnouncementSpeaker].
     */
    val category: String? = null
)

/**
 * يبني طلب المعاينة من قيم العرض الحالية مباشرةً: موضع صوت الـ Spinner
 * وتقدم شرائط السرعة/النبرة/الصوت — بند الأوامر 4 يستلزم أن تُقرأ قيم
 * الشاشة المعروضة لا القيم المحفوظة القديمة إن كانت الشاشة تعرض قيماً
 * غير محفوظة بعد. تحويل تقدّم الشرائط يطابق شريط الإعدادات
 * ([Int.speedFactor] بحد أدنى 0.25).
 */
internal fun buildPreviewParams(
    voices: List<NateqVoice>,
    voiceSelection: Int,
    enginePkg: String?,
    rateProgress: Int,
    pitchProgress: Int,
    volumePercent: Int,
    sampleText: String,
    category: String? = null
): PreviewParams {
    val voice = voices.getOrNull(voiceSelection) ?: voices.firstOrNull()
    return buildPreviewParamsFrom(
        voiceName = voice?.name.orEmpty(),
        languageTag = voice?.languageTag.orEmpty(),
        enginePkg = enginePkg,
        rateProgress = rateProgress,
        pitchProgress = pitchProgress,
        volumePercent = volumePercent,
        sampleText = sampleText,
        category = category
    )
}

internal fun buildPreviewParamsFrom(
    voiceName: String,
    languageTag: String,
    enginePkg: String?,
    rateProgress: Int,
    pitchProgress: Int,
    volumePercent: Int,
    sampleText: String,
    isCallerCategory: Boolean = false,
    category: String? = null
): PreviewParams {
    val caller = isCallerCategory ||
        AnnouncementSpeaker.isCallerCategory(category)
    return PreviewParams(
        enginePkg = enginePkg,
        voiceName = voiceName,
        languageTag = languageTag,
        speechRate = rateProgress.speedFactor(),
        pitch = pitchProgress.speedFactor(),
        volume = (volumePercent / 100f).coerceIn(0f, 1f),
        sampleText = sampleText,
        isCallerCategory = caller,
        category = category ?: if (caller) {
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER
        } else {
            null
        }
    )
}

/**
 * يبني طلب معاينة لفئةٍ بلا أدوات صوت داخلها (الوقت/الإشعارات) من قيم
 * الفئة المعروضة على صفوف الكتالوج (بند الأوامر 4): صوت الفئة
 * المحفوظ/محركها/سرعتها/نبرتها/مستوى صوتها تُقرأ فوراً وقت الضغط.
 */
internal fun buildCategoryPreviewParams(
    voices: List<NateqVoice>,
    voiceId: String?,
    enginePkg: String?,
    rate: Float,
    pitch: Float,
    volume: Float,
    sampleText: String,
    category: String? = null
): PreviewParams {
    val voice = voices.firstOrNull { it.name == voiceId }
        ?: voices.firstOrNull()
    val isCaller = AnnouncementSpeaker.isCallerCategory(category)
    return PreviewParams(
        enginePkg = enginePkg,
        voiceName = voice?.name.orEmpty(),
        languageTag = voice?.languageTag.orEmpty(),
        speechRate = rate.coerceAtLeast(MIN_SPEED_PITCH_FACTOR),
        pitch = pitch.coerceAtLeast(MIN_SPEED_PITCH_FACTOR),
        volume = volume.coerceIn(0f, 1f),
        sampleText = sampleText,
        isCallerCategory = isCaller,
        category = category ?: if (isCaller) {
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER
        } else {
            null
        }
    )
}

/**
 * حلّ مسار الصوت للمعاينة ليطابق مسار النطق الحقيقي في [AnnouncementSpeaker]
 * تماماً (القناة الصوتية وسمات AudioAttributes بحسب وضع القناة وسياسة
 * الجهارة).
 */
internal fun resolvePreviewAudioRoute(
    context: Context,
    category: String?,
    isCallerCategory: Boolean = false
): ResolvedAudioRoute {
    val audioManager = context.getSystemService(
        Context.AUDIO_SERVICE
    ) as? android.media.AudioManager
    val inCall = runCatching {
        val mode = audioManager?.mode
        mode == android.media.AudioManager.MODE_IN_CALL ||
            mode == android.media.AudioManager.MODE_IN_COMMUNICATION
    }.getOrDefault(false)
    val isSilent = runCatching {
        val rm = audioManager?.ringerMode
        rm == android.media.AudioManager.RINGER_MODE_SILENT ||
            rm == android.media.AudioManager.RINGER_MODE_VIBRATE
    }.getOrDefault(false)
    val effectiveCat = category ?: if (isCallerCategory) {
        SettingsRepository.ANNOUNCE_CATEGORY_CALLER
    } else {
        null
    }
    val settings = SettingsRepository.create(context)
    val config = settings.getCategoryLoudnessConfig(effectiveCat)
    return EventLoudnessPolicy.resolveTargetStreamAndAttributes(
        category = effectiveCat,
        config = config,
        inCall = inCall,
        isSilentOrVibrate = isSilent
    )
}

/**
 * تجهيز نص المعاينة ليطابق مسار النطق الحقيقي في [AnnouncementSpeaker]:
 * تنظيف الإيموجي وتطبيع NFC وضبط اللفظ العربي ومعالجة الدلالات وقاموس النطق.
 */
internal fun preparePreviewText(
    context: Context,
    rawText: String,
    languageTag: String,
    enginePkg: String?
): String {
    val stripped = AnnouncementSpeaker.stripEmojis(rawText)
    val nfc = java.text.Normalizer.normalize(
        stripped,
        java.text.Normalizer.Form.NFC
    )
    val textProcessor = TextProcessor(
        context.applicationContext,
        SettingsRepository.create(context),
        PronunciationDictionary.shared(context.applicationContext)
    )
    val tag = languageTag.ifBlank { "ar" }
    val processed = runCatching {
        textProcessor.process(
            nfc,
            tag,
            enginePkg
        )
    }.getOrDefault(nfc)
    return prepareArabicForEngine(processed, tag, enginePkg)
}

/**
 * حساب السرعة والنبرة ومستوى الصوت الفعلي للمعاينة متطابقاً مع
 * [AnnouncementSpeaker] بمراعاة معززات السرعة والصوت والسقف الآمن للمحرك.
 */
internal fun computeEffectivePreviewSpeech(
    context: Context,
    rate: Float,
    pitch: Float,
    volume: Float
): Triple<Float, Float, Float> {
    val settings = SettingsRepository.create(context)
    val boost = if (runCatching { settings.isSpeechBoostEnabled() }
            .getOrDefault(false)) {
        runCatching { settings.getSpeechBoostValue() }
            .getOrDefault(1.0f)
    } else {
        1.0f
    }
    val volumeBoost = if (runCatching { settings.isVolumeBoostEnabled() }
            .getOrDefault(false)) {
        runCatching { settings.getVolumeBoostValue() }
            .getOrDefault(1.0f)
    } else {
        1.0f
    }
    val effectiveRate = AnnouncementSpeaker.clampedSpeechRate(rate * boost)
    val effectivePitch = pitch.coerceAtLeast(0.25f)
    val effectiveVolume = (
        (if (volume <= 0f) 0.05f else volume) * volumeBoost
    ).coerceIn(0.05f, 1f)
    return Triple(effectiveRate, effectivePitch, effectiveVolume)
}

/** أسماء رنات رأس الساعة (تطابق قائمة spinner_time_chime_sound). */
internal const val CHIME_SOUND_DEFAULT = "classic_bell"
internal const val CHIME_SOUND_DIGITAL = "digital_chime"
internal const val CHIME_SOUND_SOFT = "soft_ding"

/** اسم الرنة من موضع سبنرا الرنات (0→classic، 1→digital، وإلا soft). */
internal fun chimeSoundNameAt(position: Int): String = when (position) {
    1 -> CHIME_SOUND_DIGITAL
    2 -> CHIME_SOUND_SOFT
    else -> CHIME_SOUND_DEFAULT
}

/** مستوى صوت الرنة من تقدّم شريطها (0..100) — يطابق تحويل شريط
 *  seekbar_time_chime_volume: 0.1..1.0. */
internal fun chimeVolumeFromProgress(progress: Int): Float =
    (0.1f + progress / 100f * 0.9f).coerceIn(0.1f, 1f)

/** أسماء كل أصوات Vocalizer في الكتالوج (الاسم والمعرّف) — تُبنى مرة. */
private val VOCALIZER_VOICE_KEYS: Set<String> by lazy {
    VocalizerModelCatalog.ITEMS
        .flatMap { listOf(it.voiceName, it.id, it.modelId) }
        .map { it.lowercase() }
        .toSet()
}

/**
 * هل الاسم صوتٌ من أصوات Vocalizer المدمجة؟ يُعرف بالكتالوج كاملاً لا
 * بقائمة أسماء محصورة (كانت Laila/Maged/Tariq/Samantha فقط، فيُوجَّه
 * غيرها خطأً إلى المسار النظامي). مطابقة كاملة (غير جزئية) مع الاسم أو
 * المعرّف، أو بادئة معرّف `vocalizer`.
 */
internal fun isVocalizerVoiceName(voiceName: String?): Boolean {
    val name = voiceName?.trim()?.lowercase() ?: return false
    if (name.isEmpty()) return false
    if (name.startsWith("vocalizer")) return true
    return name in VOCALIZER_VOICE_KEYS
}

/**
 * معاينة نطق مشتركة لكل أقسام الشاشة (بند الأوامر 4): تُشغّل عيّنة عبر
 * محرك TTS مؤقت بقيم (محرك، صوت، سرعة، نبرة، مستوى صوت، نص) مع إغلاق
 * ذاتي — نفس ضمانات بند 4.2 (لا تراكم مثيلات معلقة عند الضغط المتكرر
 * أو مغادرة الشاشة).
 */
internal class VoicePreviewHelper(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentEmbeddedJob: Job? = null

    @Volatile
    private var currentAudioTrack: AudioTrack? = null

    /** مثيل محرك المعاينة الجاري — يُغلق قبل أي معاينة جديدة وعند الإطلاق. */
    @Volatile
    private var currentPreviewTts: TextToSpeech? = null

    /** محرك معاينة دافئ يُعاد استخدامه بين المعاينات المتتالية على نفس
     *  المحرك (بند الأداء): بدل إغلاق المحرك وإعادة تهيئة ربطه عند كل
     *  معاينة — يتسبب في زمن انتظار وارتجاف — يبقى جاهزاً فيُستدعى ثانيةً
     *  بلا TextToSpeech جديدة. يُغلق عند تغيّر المحرك أو مغادرة الشاشة. */
    @Volatile
    private var warmTts: TextToSpeech? = null

    /** حزمة المحرك الذي بُني عليه [warmTts] (null = المحرك النظامي). */
    @Volatile
    private var warmTtsEngine: String? = null

    /** يغلق أي معاينة جارية والمثيل الدافئ ويحرر المرجعين
     *  (يُستدعى من onDestroyView). */
    fun release() {
        currentEmbeddedJob?.cancel()
        currentEmbeddedJob = null
        stopAudioTrack()
        currentPreviewTts?.let { tts ->
            runCatching { tts.shutdown() }
        }
        currentPreviewTts = null
        warmTts?.let { tts ->
            runCatching { tts.shutdown() }
        }
        warmTts = null
        warmTtsEngine = null
    }

    private fun stopAudioTrack() {
        currentAudioTrack?.let { track ->
            runCatching {
                track.pause()
                track.flush()
                track.release()
            }
        }
        currentAudioTrack = null
    }

    private fun isEmbeddedEngine(pkg: String?): Boolean =
        HostProtocol.isEmbeddedEngine(pkg)

    private fun getEmbeddedProvider(pkg: String): VoiceProvider {
        val norm = HostProtocol.normalizeEngineId(pkg)
        return when (norm) {
            HostProtocol.ENGINE_VOCALIZER ->
                EmbeddedProviders.vocalizer(context.applicationContext)
            else ->
                throw IllegalArgumentException("Unknown engine: $pkg")
        }
    }

    private suspend fun playEmbeddedPreview(
        enginePkg: String,
        params: PreviewParams,
        onFinished: () -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        withContext(Dispatchers.IO) {
            val audioManager = context.getSystemService(
                Context.AUDIO_SERVICE
            ) as? android.media.AudioManager
            if (audioManager?.mode ==
                android.media.AudioManager.MODE_IN_CALL
            ) {
                mainHandler.post { onFinished() }
                return@withContext
            }
            val route = resolvePreviewAudioRoute(
                context, params.category, params.isCallerCategory
            )
            val focusGain =
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            val focusReq = if (Build.VERSION.SDK_INT >= 26) {
                android.media.AudioFocusRequest.Builder(focusGain)
                    .setAudioAttributes(route.audioAttributes)
                    .build()
            } else {
                null
            }
            if (Build.VERSION.SDK_INT >= 26) {
                focusReq?.let { audioManager?.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    null,
                    route.stream,
                    focusGain
                )
            }
            val (effectiveRate, effectivePitch, effectiveVolume) =
                computeEffectivePreviewSpeech(
                    context, params.speechRate, params.pitch, params.volume
                )
            val cleanText = preparePreviewText(
                context, params.sampleText, params.languageTag, enginePkg
            )
            var track: AudioTrack? = null
            var totalFramesWritten = 0L
            var bytesPerFrame = 2
            var timedOut = false
            var errored = false
            try {
                // ع٢٩-10 (2): مهلة صريحة من SynthesisBudget عبر
                // EngineHostClient — لا تعليقٌ مفتوح إن تعثّر المضيف.
                val timeoutMs = EngineHostClient.previewTimeoutMs(
                    cleanText
                )
                withTimeout(timeoutMs) {
                    val provider = getEmbeddedProvider(enginePkg)
                    val loc = if (params.languageTag.isNotBlank()) {
                        Locale.forLanguageTag(params.languageTag)
                    } else {
                        Locale.getDefault()
                    }
                    val descriptor = VoiceDescriptor(
                        id = params.voiceName,
                        providerId = enginePkg,
                        displayName = params.voiceName,
                        locale = loc
                    )

                    provider.synthesize(
                        text = cleanText,
                        voice = descriptor,
                        speechRate = effectiveRate,
                        pitch = effectivePitch,
                        volume = effectiveVolume,
                        onFormatInfo = { sampleRateInHz, channelCount ->
                            val channelConfig =
                                if (channelCount == 2) {
                                    AudioFormat.CHANNEL_OUT_STEREO
                                } else {
                                    AudioFormat.CHANNEL_OUT_MONO
                                }
                            bytesPerFrame = channelCount * 2
                            val minBuf = AudioTrack.getMinBufferSize(
                                sampleRateInHz,
                                channelConfig,
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                            val bufSize = maxOf(minBuf, 8192)
                            val format = AudioFormat.Builder()
                                .setSampleRate(sampleRateInHz)
                                .setEncoding(
                                    AudioFormat.ENCODING_PCM_16BIT
                                )
                                .setChannelMask(channelConfig)
                                .build()
                            val newTrack = AudioTrack.Builder()
                                .setAudioAttributes(route.audioAttributes)
                                .setAudioFormat(format)
                                .setBufferSizeInBytes(bufSize)
                                .setTransferMode(
                                    AudioTrack.MODE_STREAM
                                )
                                .build()
                            newTrack.setVolume(effectiveVolume)
                            newTrack.play()
                            track = newTrack
                            currentAudioTrack = newTrack
                        },
                        onAudioChunk = { chunk, validLength ->
                            val current = track
                            if (current != null &&
                                current.playState ==
                                AudioTrack.PLAYSTATE_PLAYING
                            ) {
                                current.write(
                                    chunk,
                                    0,
                                    validLength
                                )
                                totalFramesWritten +=
                                    (validLength / bytesPerFrame)
                            }
                        },
                        enginePackage = enginePkg,
                        voiceLocale = loc,
                        desiredVoiceName = params.voiceName,
                        category = params.category
                            ?: if (params.isCallerCategory) {
                                SettingsRepository.ANNOUNCE_CATEGORY_CALLER
                            } else {
                                null
                            }
                    )
                    track?.let { t ->
                        val startWait = System.currentTimeMillis()
                        while (t.playState ==
                            AudioTrack.PLAYSTATE_PLAYING &&
                            t.playbackHeadPosition <
                            totalFramesWritten &&
                            System.currentTimeMillis() - startWait
                            < 3000L
                        ) {
                            Thread.sleep(25)
                        }
                        runCatching { t.stop() }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                timedOut = true
                val spent = EngineHostClient.previewTimeoutMs(
                    params.sampleText
                )
                Log.w(
                    "NATEQ_TTS",
                    "Embedded preview timeout after ${spent}ms: $e"
                )
                postStatus(
                    "انتهت مهلة المعاينة على " +
                        providerLabel(enginePkg)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                errored = true
                Log.w("NATEQ_TTS", "Embedded preview failed", t)
                postStatus(
                    failureReason(enginePkg, t.message)
                )
            } finally {
                if (Build.VERSION.SDK_INT >= 26) {
                    focusReq?.let {
                        audioManager?.abandonAudioFocusRequest(it)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager?.abandonAudioFocus(null)
                }
                track?.let { t ->
                    runCatching { t.release() }
                }
                if (currentAudioTrack === track) {
                    currentAudioTrack = null
                }
                mainHandler.post { onFinished() }
            }

            // ع٢٩-10 (2): صوتٌ لم يصل أصلاً (تهيئة فاشلة تُسجَّل ولا
            // تُرمي) — يُعرض السبب الفعلي من السجلّ لا «فشل» عام.
            if (!timedOut && !errored &&
                track == null && totalFramesWritten == 0L
            ) {
                postStatus(failureReason(enginePkg, null))
            }
        }
    }

    /** يعرض نص حالة المعاينة على Toast في الخيط الرئيسي. */
    private fun postStatus(message: String) {
        mainHandler.post {
            Toast.makeText(
                context.applicationContext,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** اسم المحرك المعروض؛ يعود للـ id إن تعذّر أخذه. */
    private fun providerLabel(enginePkg: String): String =
        runCatching { getEmbeddedProvider(enginePkg).displayName }
            .getOrDefault(enginePkg)

    /**
     * السبب الفعلي للعرض (ع٢٩-10 بند 2): رسالة الاستثناء إن وُجدت،
     * وإلا آخر خطأ مسجَّل في [EngineStatusRegistry] مع تسمية حالة
     * غير نهائية (لا «جاهز» يُقرأ فشلاً)، ثم صياغة مُعنونة لا «فشل».
     */
    private fun failureReason(
        enginePkg: String,
        throwableMessage: String?
    ): String {
        val status = if (isEmbeddedEngine(enginePkg)) {
            EngineStatusRegistry.statusOf(
                context.applicationContext, enginePkg
            )
        } else null
        val label = status?.state
            ?.takeIf { it != EngineStatusRegistry.State.READY }
            ?.takeIf { it != EngineStatusRegistry.State.INIT_PENDING }
            ?.let { EngineStatusRegistry.labelAr(it) }
        val errDetail = status?.lastError?.takeIf { it.isNotBlank() }
            ?: status?.code?.takeIf { it.isNotBlank() }
        val detail = throwableMessage?.takeIf { it.isNotBlank() } ?: errDetail
        return when {
            detail != null && label != null -> "$label: $detail"
            detail != null -> detail
            label != null -> "$label — ${providerLabel(enginePkg)}"
            else -> "تعذّر تشغيل المعاينة على ${providerLabel(enginePkg)}"
        }
    }

    /**
     * يشغّل المعاينة بالقيم المعطاة. [onFinished] يُستدعى مرة واحدة عند
     * اكتمال النطق أو فشله أو تعذر تهيئة المحرك — يُستخدم لإعلان حالة
     * قارئ الشاشة في نهاية المعاينة.
     */
    @Suppress("DEPRECATION")
    fun play(params: PreviewParams, onFinished: () -> Unit = {}) {
        currentEmbeddedJob?.cancel()
        currentEmbeddedJob = null
        stopAudioTrack()

        // بند 4.2: إغلاق أي معاينة جارية أولاً — الضغط السريع المتكرر لا
        // يتراكم محركات معلقة (المثيل القائم لا يُعاد استخدامه بعد إيقافه
        // المفاجئ؛ يُغلق ويُخلق سواه).
        currentPreviewTts?.let { tts ->
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
        currentPreviewTts = null

        val desiredEngine = params.enginePkg?.takeIf { it.isNotBlank() }
        if (params.category == SettingsRepository.VOICE_CATEGORY_NUMBERS) {
            val speaker = AnnouncementSpeaker(context.applicationContext)
            val langTag = params.languageTag.ifEmpty { "ar" }
            if (params.voiceName.isNotBlank()) {
                speaker.resetVoice(params.voiceName)
            }
            speaker.speak(
                params.sampleText,
                Locale.forLanguageTag(langTag),
                params.speechRate,
                params.pitch,
                params.volume,
                engineOverride = desiredEngine,
                category = params.category
            )
            onFinished()
            return
        }
        if (isEmbeddedEngine(desiredEngine) ||
            (desiredEngine == null && isVocalizerVoiceName(params.voiceName))
        ) {
            val canon = if (isEmbeddedEngine(desiredEngine)) {
                HostProtocol.normalizeEngineId(desiredEngine!!)
            } else {
                HostProtocol.ENGINE_VOCALIZER
            }
            currentEmbeddedJob = scope.launch {
                playEmbeddedPreview(canon, params, onFinished)
            }
            return
        }

        val appContext = context.applicationContext
        val hold = arrayOfNulls<TextToSpeech>(1)
        val mainHandler = Handler(Looper.getMainLooper())

        // المثيل الدافئ لنفس المحرك يُعاد استخدامه مباشرةً — لا إعادة
        // ربط ولا تهيئة (بند الأداء). نتزحزحه من الدفء إلى الجاري.
        val warm = warmTts
        if (warm != null && warmTtsEngine == desiredEngine) {
            hold[0] = warm
            currentPreviewTts = warm
            warmTts = null
            warmTtsEngine = null
            mainHandler.post {
                speakSample(warm, params, onFinished)
            }
            return
        }

        // محركٌ مختلف أو أول معاينة: إغلاق الدافئ السابق وتهيئة مثيل جديد.
        warm?.let { runCatching { it.shutdown() } }
        warmTts = null
        warmTtsEngine = null

        val listener: (Int) -> Unit = { status ->
            mainHandler.post {
                val tts = hold[0]
                if (tts == null || tts !== currentPreviewTts) {
                    runCatching { tts?.shutdown() }
                    return@post
                }
                if (status != TextToSpeech.SUCCESS) {
                    runCatching { tts.shutdown() }
                    if (currentPreviewTts === tts) currentPreviewTts = null
                    postStatus(
                        "تعذر تهيئة محرك النطق: ${desiredEngine ?: "النظامي"}"
                    )
                    onFinished()
                } else {
                    speakSample(tts, params, onFinished)
                }
            }
        }
        val created = runCatching {
            @Suppress("DEPRECATION")
            val instance = if (desiredEngine == null) {
                TextToSpeech(appContext, listener)
            } else {
                TextToSpeech(appContext, listener, desiredEngine)
            }
            hold[0] = instance
            currentPreviewTts = instance
            instance
        }
        if (created.isFailure) {
            runCatching { hold[0]?.shutdown() }
            currentPreviewTts = null
            postStatus(
                "تعذر إنشاء TextToSpeech للمحرك: " +
                    (desiredEngine ?: "النظامي")
            )
            onFinished()
        }
    }

    /** ينهي معاينة [tts] بعد اكتمالها/فشلها: يُعيدها دافئةً لمعاينة تالية
     *  على نفس المحرك إن لم تسبقها معاينةٌ أحدث، وإلا يُغلقها. */
    private fun finishPreview(
        tts: TextToSpeech?,
        engineKey: String?
    ) {
        if (tts == null) return
        if (currentPreviewTts === tts) currentPreviewTts = null
        if (warmTts == null) {
            warmTts = tts
            warmTtsEngine = engineKey
        } else {
            runCatching { tts.shutdown() }
        }
    }

    @Suppress("DEPRECATION")
    private fun speakSample(
        previewTts: TextToSpeech?,
        params: PreviewParams,
        onFinished: () -> Unit
    ) {
        if (previewTts == null) {
            onFinished()
            return
        }
        try {
            val avail = runCatching { previewTts.voices }.getOrNull()
            val loc = if (params.languageTag.isNotBlank()) {
                Locale.forLanguageTag(params.languageTag)
            } else {
                Locale.getDefault()
            }
            val chosen = AnnouncementSpeaker.voiceFor(
                avail, params.voiceName, loc
            )
            val enginePackage = engineKey(params)
            val quirks = enginePackage?.let { EngineQuirks.forPackage(it) }
            val needsLangFirst = quirks?.needsSetLanguageBeforeSetVoice == true
            val needsRegion = quirks?.requiresLocaleWithRegion == true

            if (chosen != null) {
                if (needsLangFirst) {
                    runCatching {
                        chosen.locale?.let { previewTts.language = it }
                    }
                }
                val effectiveLocale = if (needsRegion) {
                    runCatching {
                        previewTts.voices?.firstOrNull {
                            it.locale.language == loc.language
                        }?.locale
                    }.getOrNull() ?: chosen.locale
                } else {
                    chosen.locale
                }
                if (needsLangFirst) {
                    runCatching {
                        effectiveLocale?.let { previewTts.language = it }
                    }
                }
                val setVoiceResult = previewTts.setVoice(chosen)
                if (setVoiceResult != TextToSpeech.SUCCESS) {
                    runCatching {
                        previewTts.language = effectiveLocale ?: loc
                    }
                }
            } else if (params.languageTag.isNotEmpty()) {
                runCatching { previewTts.language = loc }
            }

            val (effectiveRate, effectivePitch, effectiveVolume) =
                computeEffectivePreviewSpeech(
                    context, params.speechRate, params.pitch, params.volume
                )
            previewTts.setSpeechRate(effectiveRate)
            runCatching { previewTts.setPitch(effectivePitch) }

            val route = resolvePreviewAudioRoute(
                context, params.category, params.isCallerCategory
            )
            runCatching {
                previewTts.setAudioAttributes(route.audioAttributes)
            }
            val cat = params.category ?: if (params.isCallerCategory) {
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER
            } else {
                null
            }
            val bundle = Bundle().apply {
                putFloat(
                    TextToSpeech.Engine.KEY_PARAM_VOLUME,
                    effectiveVolume
                )
                putInt(
                    TextToSpeech.Engine.KEY_PARAM_STREAM,
                    route.stream
                )
                if (!cat.isNullOrBlank()) {
                    putString(
                        AudioEffectManager.PARAM_CATEGORY,
                        cat
                    )
                }
            }
            val cleanText = preparePreviewText(
                context, params.sampleText, params.languageTag, enginePackage
            )
            previewTts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    @Deprecated("Java Deprecated")
                    override fun onDone(utteranceId: String?) {
                        finishPreview(previewTts, engineKey(params))
                        onFinished()
                    }

                    @Deprecated("Java Deprecated")
                    override fun onError(utteranceId: String?) {
                        finishPreview(previewTts, engineKey(params))
                        val engineName = params.enginePkg ?: "المحرك النظامي"
                        postStatus(
                            "خطأ أثناء نطق المعاينة على $engineName"
                        )
                        onFinished()
                    }
                })
            val result = runCatching {
                previewTts.speak(
                    cleanText,
                    TextToSpeech.QUEUE_FLUSH,
                    bundle,
                    "preview"
                )
            }.getOrDefault(TextToSpeech.ERROR)
            if (result == TextToSpeech.ERROR) {
                finishPreview(previewTts, engineKey(params))
                val engineName = params.enginePkg ?: "النظامي"
                postStatus(
                    "فشل إرسال النص لمحرك النطق: $engineName"
                )
                onFinished()
                return
            }
        } catch (t: Throwable) {
            Log.w("NATEQ_TTS", "preview failed", t)
            finishPreview(previewTts, engineKey(params))
            postStatus(
                t.localizedMessage ?: "تعذر تشغيل المعاينة"
            )
            onFinished()
        }
    }

    /** مفتاح المحرك الذي سيعاد به مثيلٌ دافئ — حزمة المحرك المختارة أو
     *  null للنظامي (يطابق [VoicePreviewHelper] معيار إعادة الاستخدام). */
    private fun engineKey(params: PreviewParams): String? =
        params.enginePkg?.takeIf { it.isNotBlank() }
}
