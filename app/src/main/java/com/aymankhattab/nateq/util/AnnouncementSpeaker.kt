package com.aymankhattab.nateq.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.aymankhattab.nateq.providers.EnginePicker
import com.aymankhattab.nateq.settings.SettingsRepository
import java.util.Locale

/**
 * متحدث مستقل يستخدمه التطبيق للإعلانات الصوتية التلقائية
 * (مستوى البطارية، اسم المتصل، الرسائل الواردة) دون المرور عبر خدمة النظام.
 * يربط مباشرةً بمحرك TTS الذي اختاره [EnginePicker] أو المحرك المحفوظ في
 * الإعدادات، وينطق عبر `speak()` ليعمل في الخلفية حتى لو لم يُظهر النظام
 * شاشة تخليق (على عكس TextToSpeechService الذي يقود النظام).
 *
 * ليتفادى حلقة ربط النظام TextToSpeech → خدمة LORD نفسها (التي قد تُسقط
 * الصوت)، يستبعد دائماً حزمة التطبيق نفسه عند اختيار المحرك فيفوض النطق
 * لمحركٍ مثبّت خارجي (منهج MultiTTS).
 */
class AnnouncementSpeaker(context: Context, private var voiceId: String? = null) {

    companion object {
        private const val TAG = "NATEQ_TTS"

        // مثيل واحد مشترك لكل عملية. تعدد المتحدثات (مثيل لكل مستقبِل) كان
        // يفتح محرك TTS منفصلاً في كل مرة فيتقاطع صوتان ويستنزف الذاكرة.
        @Volatile
        private var shared: AnnouncementSpeaker? = null

        /** الحصول على المتحدث المشترك الوحيد (محمي بالإنشاء المزدوج). */
        @JvmStatic
        fun getInstance(context: Context): AnnouncementSpeaker {
            return shared ?: synchronized(this) {
                shared ?: AnnouncementSpeaker(context.applicationContext).also { shared = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var tts: TextToSpeech? = null
    private var nowSpeaking = false

    /** تغيير الصوت المفضّل لدورات النطق القادمة (يُعيد الربط إن لزم) */
    fun resetVoice(newVoiceId: String?) {
        if (newVoiceId == voiceId) return
        voiceId = newVoiceId
        shutdownSafely()
    }

    /**
     * يهيّئ المحرك مرة واحدة؛ يعيد true عند الجاهزية.
     * يمنع سباق التهيئة المزدوج (Single-flight): الاستدعاءات المتزامنة أثناء
     * التهيئة تصطّف جميعها وتُستدعى بنتيجة واحدة عند اكتمال onInit.
     */
    private fun ensureInit(onReady: (Boolean) -> Unit) {
        val existing = tts
        if (existing != null) {
            onReady(true)
            return
        }
        if (initializing) {
            pendingInitCallbacks.add(onReady)
            return
        }
        initializing = true
        pendingInitCallbacks.add(onReady)

        // المحرك المختار من المستخدم (مثل MultiTTS أو Lord نفسه) له الأولوية
        val savedEngine = try {
            SettingsRepository(appContext).getSelectedEnginePackage()
        } catch (e: Exception) {
            null
        }
        val engine = savedEngine ?: EnginePicker.pickEnginePackage(appContext)
        var newTts: TextToSpeech? = null
        newTts = TextToSpeech(appContext) { status ->
            val success = status == TextToSpeech.SUCCESS
            if (success) {
                // لا تُخزَّن إلا المثيلات الناجحة؛ المثيل الفاشل يُهمَل ولا
                // يظل "جاهزاً" للدورات اللاحقة (كان يفسد المتحدث مسبقاً).
                tts = newTts
            } else {
                tts = null
            }
            initializing = false
            val callbacks = pendingInitCallbacks.toList()
            pendingInitCallbacks.clear()
            callbacks.forEach { cb -> cb(success) }
        }
        newTts.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    nowSpeaking = true
                }

                @Deprecated("Java Override")
                override fun onDone(utteranceId: String?) {
                    nowSpeaking = false
                    releaseAudioFocus()
                    // الإبقاء على المحرك حياً بين الإعلانات لتجنب إعادة ربط
                    // مكلفة عند كل نطق؛ يُغلق صراحةً عبر stop()/resetVoice().
                }

                @Deprecated("Java Override")
                override fun onError(utteranceId: String?) {
                    nowSpeaking = false
                    releaseAudioFocus()
                }
            })

            // نطق الإعلانات يصنّف كـ مساعد إتاحة صوتي (لا مسار موسيقى)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            if (engine != null) {
                @Suppress("DEPRECATION") // setEngineByPackageName مُهمل لكنه الطريقة الوحيدة لتحديد المحرك
                setEngineByPackageName(engine)
            }
        }
    }

    /**
     * ينطق نصاً فورياً (يدفع طابور نطق جديد).
     * @param text النص المراد نطقه
     * @param locale لغة النص لتحديد صوت المحرك المناسب
     * @param speechRate سرعة النطق (1.0 = طبيعي)
     * @param pitch النبرة (1.0 = طبيعي)
     * @param volume مستوى الصوت (0.0..1.0) — يُطبّق عبر معامل الصوت إن أمكن
     */
    fun speak(text: String, locale: Locale, speechRate: Float, pitch: Float, volume: Float) {
        // نُفوض النطق دائماً لمحركٍ مثبّت (منهج MultiTTS): يستبعد اختيار المحرك
        // حزمة LORD نفسها، فيمرّ `tts.speak()` عبر محركٍ خارجي مستقر بدل حلقة
        // ربط النظام TextToSpeech → خدمة LORD التي قد تُسقط الصوت على Samsung.
        requestAudioFocus()
        ensureInit { ready ->
            if (!ready) {
                releaseAudioFocus()
                return@ensureInit
            }
            // تأجيل قصير يسمح لاتصال محرك TTS بالاستقرار بعد onInit (حتى لو أعلن
            // Success مبكراً، قد يبقى ربط النظام معلقاً لحظياً ويُسقط speak فورياً).
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                doSpeak(text, locale, speechRate, pitch, volume, attempt = 1)
            }, 150)
        }
    }

    private fun doSpeak(text: String, locale: Locale, speechRate: Float, pitch: Float, volume: Float, attempt: Int) {
        val tts = tts ?: return
        tts.setSpeechRate(speechRate)
        tts.setPitch(pitch)
        // تطبيق الصوت المفضّل بالاسم (مثل "nateq-ar-local") عندما يَعرضه المحرك
        // المربوط فعلاً (محرك LORD نفسه). إذا لم يجده المحرك (محرك خارجي مثل
        // جوجل/MultiTTS لا يملك هذه الأسماء) نرجع لتحديد اللغة فقط، فيبقى
        // اختيار الصوت محدوداً بلسان المحرك كما هو متوقَّع.
        val vid = voiceId
        if (vid != null) {
            val voice = runCatching { tts.voices }.getOrNull()
                ?.firstOrNull { it.name == vid }
            if (voice != null) {
                tts.voice = voice
            } else {
                tts.setLanguage(locale)
            }
        } else {
            tts.setLanguage(locale)
        }
        val params = android.os.Bundle().apply {
            if (volume in 0f..1f) {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            }
        }
        val utteranceId = "nateq_announce_${System.currentTimeMillis()}"
        val status = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (status == TextToSpeech.ERROR && attempt < 3) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                doSpeak(text, locale, speechRate, pitch, volume, attempt + 1)
            }, 250)
        } else if (status == TextToSpeech.ERROR) {
            // استنفاد المحاولات: تصريف الموارد حتى لا يبقى التركيز مكتوم الصوت
            // ومحرك مكسور "جاهزاً" للدورات القادمة.
            nowSpeaking = false
            releaseAudioFocus()
            shutdownSafely()
        }
    }

    /** إيقاف أي نطق جارٍ وتحرير الموارد */
    fun stop() {
        tts?.stop()
        releaseAudioFocus()
        shutdownSafely()
    }

    /** طلب تخفيف صوت الوسائط أثناء النطق (Audio Ducking) */
    private val onAudioFocusChange = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // فقد التركيز (مكالمة/وسائط) — أوقف النطق فوراً
                tts?.stop()
                nowSpeaking = false
                releaseAudioFocus()
            }
        }
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(onAudioFocusChange)
                    .build()
                audioFocusRequest = focusReq
                audioManager.requestAudioFocus(focusReq)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    onAudioFocusChange,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "requestAudioFocus failed", t)
        }
    }

    /** التخلي عن Audio Focus بعد انتهاء النطق */
    private fun releaseAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "releaseAudioFocus failed", t)
        }
    }

    private fun shutdownSafely() {
        try {
            tts?.shutdown()
        } catch (ignored: Throwable) {
        }
        tts = null
    }

    private var initializing = false
    private val pendingInitCallbacks = mutableListOf<(Boolean) -> Unit>()
}