package com.aymankhattab.nateq.settings

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

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
     *
     * **لماذا غابت قبلُ (شُكوى: «صوت المعاينة أعلى»):** المعاينةُ لم
     * تطبّق `setAudioAttributes` ولا `KEY_PARAM_STREAM`، فمضى الصوتُ
     * على `STREAM_MUSIC` كاملاً، ولم يبقَ إلا `KEY_PARAM_VOLUME` —
     * **معاملٌ تتجاهله أغلبُ محركات النطق**. فلم يكن الشريطُ يُخفض
     * شيئاً. وهذا حصرٌ في المتصل: بقيةُ الفئات على مسارها كما كان.
     */
    val isCallerCategory: Boolean = false
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
    sampleText: String
): PreviewParams {
    val voice = voices.getOrNull(voiceSelection) ?: voices.firstOrNull()
    return buildPreviewParamsFrom(
        voiceName = voice?.name.orEmpty(),
        languageTag = voice?.languageTag.orEmpty(),
        enginePkg = enginePkg,
        rateProgress = rateProgress,
        pitchProgress = pitchProgress,
        volumePercent = volumePercent,
        sampleText = sampleText
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
    isCallerCategory: Boolean = false
): PreviewParams = PreviewParams(
    enginePkg = enginePkg,
    voiceName = voiceName,
    languageTag = languageTag,
    speechRate = rateProgress.speedFactor(),
    pitch = pitchProgress.speedFactor(),
    volume = (volumePercent / 100f).coerceIn(0f, 1f),
    sampleText = sampleText,
    isCallerCategory = isCallerCategory
)

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
    sampleText: String
): PreviewParams {
    val voice = voices.firstOrNull { it.name == voiceId }
        ?: voices.firstOrNull()
    return PreviewParams(
        enginePkg = enginePkg,
        voiceName = voice?.name.orEmpty(),
        languageTag = voice?.languageTag.orEmpty(),
        speechRate = rate.coerceAtLeast(MIN_SPEED_PITCH_FACTOR),
        pitch = pitch.coerceAtLeast(MIN_SPEED_PITCH_FACTOR),
        volume = volume.coerceIn(0f, 1f),
        sampleText = sampleText
    )
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

/**
 * معاينة نطق مشتركة لكل أقسام الشاشة (بند الأوامر 4): تُشغّل عيّنة عبر
 * محرك TTS مؤقت بقيم (محرك، صوت، سرعة، نبرة، مستوى صوت، نص) مع إغلاق
 * ذاتي — نفس ضمانات بند 4.2 (لا تراكم مثيلات معلقة عند الضغط المتكرر
 * أو مغادرة الشاشة).
 */
internal class VoicePreviewHelper(private val context: Context) {

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

    /**
     * يشغّل المعاينة بالقيم المعطاة. [onFinished] يُستدعى مرة واحدة عند
     * اكتمال النطق أو فشله أو تعذر تهيئة المحرك — يُستخدم لإعلان حالة
     * قارئ الشاشة في نهاية المعاينة.
     */
    @Suppress("DEPRECATION")
    fun play(params: PreviewParams, onFinished: () -> Unit = {}) {
        // بند 4.2: إغلاق أي معاينة جارية أولاً — الضغط السريع المتكرر لا
        // يتراكم محركات معلقة (المثيل القائم لا يُعاد استخدامه بعد إيقافه
        // المفاجئ؛ يُغلق ويُخلق سواه).
        currentPreviewTts?.let { tts ->
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
        currentPreviewTts = null

        val appContext = context.applicationContext
        val hold = arrayOfNulls<TextToSpeech>(1)
        val mainHandler = Handler(Looper.getMainLooper())
        val desiredEngine = params.enginePkg?.takeIf { it.isNotBlank() }

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
            val avail = runCatching { previewTts.getVoices().orEmpty() }
                .getOrDefault(emptySet())
            val voice = avail.firstOrNull { it.name == params.voiceName }
            if (voice != null) {
                // نضبط المحرك على لسان الصوت المختار حتى لا يقرأ النص بلغة
                // المحرك الافتراضية.
                runCatching { previewTts.setVoice(voice) }
                runCatching { previewTts.language = voice.locale }
            } else if (params.languageTag.isNotEmpty()) {
                runCatching {
                    previewTts.language =
                        Locale.forLanguageTag(params.languageTag)
                }
            }
previewTts.setSpeechRate(params.speechRate)
        runCatching { previewTts.setPitch(params.pitch) }
        // **معاينةُ المتصل تُطبِّق سماتِ المسارِ الحقيقي** — بلاها كان
        // الصوتُ يخرج على `STREAM_MUSIC` كاملاً فيرتفع عن شريط الصوت،
        // لأن `KEY_PARAM_VOLUME` وحده تتجاهله أغلبُ المحركات. نفسُ
        // السماتِ المستعملة في [AnnouncementSpeaker] فالمعاينةُ تسمع
        // ما سيُنطَقُ فعلاً لا ما يُفترض.
        if (params.isCallerCategory) {
            runCatching {
                previewTts.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(
                            android.media.AudioAttributes
                                .USAGE_NOTIFICATION_EVENT
                        )
                        .setContentType(
                            android.media.AudioAttributes
                                .CONTENT_TYPE_SONIFICATION
                        )
                        .build()
                )
            }
        }
        val bundle = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, params.volume)
            if (params.isCallerCategory) {
                putInt(
                    TextToSpeech.Engine.KEY_PARAM_STREAM,
                    android.media.AudioManager.STREAM_NOTIFICATION
                )
            }
        }
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
                        onFinished()
                    }
                })
            val result = runCatching {
                previewTts.speak(
                    params.sampleText,
                    TextToSpeech.QUEUE_FLUSH,
                    bundle,
                    "preview"
                )
            }.getOrDefault(TextToSpeech.ERROR)
            if (result == TextToSpeech.ERROR) {
                finishPreview(previewTts, engineKey(params))
                onFinished()
                return
            }
        } catch (t: Throwable) {
            Log.w("NATEQ_TTS", "preview failed", t)
            finishPreview(previewTts, engineKey(params))
            onFinished()
        }
    }

    /** مفتاح المحرك الذي سيعاد به مثيلٌ دافئ — حزمة المحرك المختارة أو
     *  null للنظامي (يطابق [VoicePreviewHelper] معيار إعادة الاستخدام). */
    private fun engineKey(params: PreviewParams): String? =
        params.enginePkg?.takeIf { it.isNotBlank() }
}