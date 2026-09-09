package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.aymankhattab.nateq.engine.EmojiSpeech
import com.aymankhattab.nateq.core.audio.engine.LanguageSegmenter
import com.aymankhattab.nateq.core.audio.engine.Segment
import com.aymankhattab.nateq.engine.SpeechPart
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LanguageCode
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * إعدادات نطق أسماء الإيموجي (فئة «نطق الإيموجي») — تُقرأ من الإعدادات مرة
 * واحدة لكل دورة نطق وتُطبق على مقاطع أسماء الإيموجي فقط.
 */
internal data class EmojiSpeechConfig(
    val voiceId: String?,
    val arabic: Boolean,
    val rate: Float,
    val pitch: Float,
    val volume: Float
)

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
class AnnouncementSpeaker(
    context: Context,
    private var voiceId: String? = null
) {

    companion object {
        private const val TAG = "NATEQ_TTS"

        // نطاق الإيموجي الشائع (بلوكات Unicode): رموز التباين (2600-27BF)،
        // الأسهم/الرموز الإضافية (2B00-2BFF)، الأعلام الإقليمية (1F1E6-1F1FF)
        // والبلوكات التكميلية كلها تُغطى بزوج الاستبدال العام
        // (D83C-DBFF + DC00-DFFF) مع متغير التباين FE0F والرابط
        // الصفري ZWJ (200D). يُستخدم لتنظيف النصوص
        // الخارجية (SMS/إشعارات/اسم المتصل) قبل النطق عبر المحرك الخارجي حتى
        // لا يُقرأ الإيموجي باسمه الإنجليزي (مثل بعض المحركات).
        private val EMOJI_REGEX = Regex(
            "[\u2600-\u27BF\u2B00-\u2BFF\uFE0F\u200D" +
                "\uD83C-\uDBFF\uDC00-\uDFFF]+"
        )

        // مثيل واحد مشترك لكل عملية. تعدد المتحدثات (مثيل لكل مستقبِل) كان
        // يفتح محرك TTS منفصلاً في كل مرة فيتقاطع صوتان ويستنزف الذاكرة.
        @Volatile
        private var shared: AnnouncementSpeaker? = null

        /** الحصول على المتحدث المشترك الوحيد (محمي بالإنشاء المزدوج). */
        @JvmStatic
        fun getInstance(context: Context): AnnouncementSpeaker {
            return shared ?: synchronized(this) {
                shared ?: AnnouncementSpeaker(context.applicationContext)
                .also { shared = it }
            }
        }

        /** هل معرّف الصوت إنجليزي؟ يقبل الصيغ القديمة
         * (nateq-en…/en-local) والموحّدة (en-US). */
        private fun isEnglishVoiceName(voiceId: String?): Boolean =
            voiceId?.let {
                it.startsWith("nateq-en", ignoreCase = true) ||
                    it.startsWith("en-local", ignoreCase = true) ||
                    it.startsWith("en-US", ignoreCase = true)
            } ?: false
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(
        Context.AUDIO_SERVICE
    ) as AudioManager
    private val mainHandler = android.os.Handler(
        android.os.Looper.getMainLooper()
    )
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    // النطق المنتظر لحين وصول Audio Focus المؤجل (DELAYED): يُخزَّن الإجراء
    // ويُطلق فور استلام AUDIOFOCUS_GAIN، مع مؤقّت أمان يمنع ضياع الإعلان
    // إن لم يتحرر التركيز أبداً.
    private var pendingFocusAction: (() -> Unit)? = null
    private var pendingFocusTimer: Runnable? = null

    private var tts: TextToSpeech? = null
    private var nowSpeaking = false

    /** مقسم النصوص المختلطة الكتابات داخل إعلانات
     * التطبيق (منطق نقي بلا حالة). */
    private val languageSegmenter = LanguageSegmenter()

    /**
     * خطاف يُستدعى عند اكتمال آخر جملة في دورة النطق الحالية (onDone/onError
     * للـ lastQueuedUtteranceId فقط). تستخدمه أداة الساعة لتحرير goAsync() و
     * WakeLock المؤقت عقب اكتمال النطق فعلياً — تأخير التحرير حتى بقاء العملية
     * حية بينما يُهيّئ محرك TTS وينطق (Android 14+ يجمد العملية بعد onReceive).
     */
    @Volatile
    var onSpeechComplete: (() -> Unit)? = null

    /**
     * معرّف آخر جزء أُرسل إلى المحرك في دورات النطق الحالية. يُقارن به عند
     * استقبال onDone/onError لنحرر التركيز الصوتي فقط عند اكتمال الجزء الأخير،
     * لا بعد أول جزء — فالإعلان متعدد المقاطع (نص + أسماء إيموجي متتابعة) يبقى
     * محمياً من تشويش التطبيقات الأخرى حتى ينتهي كل النطق. Volatile لأن الكتابة
     * قد تأتي من خيط إرسال (Main أو IO) والقراءة من مستمع المحرك على Main.
     */
    @Volatile
    private var lastQueuedUtteranceId: String? = null

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
        // انضمام ذرّي إلى "في طور التهيئة" أو بدؤها مرة واحدة
        // (يحجب الاستدعاءات المتزامنة من خيوط مختلفة فلا تحدث
        // تهيئة مزدوجة ولا ConcurrentModification).
        if (initializing.getAndSet(true)) {
            pendingInitCallbacks.add(onReady)
            return
        }
        pendingInitCallbacks.add(onReady)

        // المحرك المختار من المستخدم (مثل MultiTTS أو Lord نفسه) له الأولوية
        // يُفضَّل الحقل المحقون في التطبيق عبر AnnouncementAppContext (نفس كائن
        // Hilt المشترك من كل عملية)، وإلا يُبنى محلياً — قراءة
        // لحظية غير محفوظة.
        val injected =
            (appContext as? AnnouncementAppContext)?.settingsRepository
        val savedEngine = try {
            (injected ?: SettingsRepository(appContext))
                .getSelectedEnginePackage()
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
            // سحب كل النداءات المتراكمة دفعةً واحدة (ذرّي تجاه
            // الإضافات اللاحقة)، ثم إتاحة التهيئة التالية قبل
            // استدعاء النداءات (لا استدعاء تحت قفلٍ لتجنب أي
            // deadlock لو دخل الـ callback دعوةً متزامنة أخرى).
            val callbacks = ArrayList<(Boolean) -> Unit>()
            while (true) {
                val cb = pendingInitCallbacks.poll() ?: break
                callbacks.add(cb)
            }
            initializing.set(false)
            callbacks.forEach { cb -> cb(success) }
        }
        newTts.apply {
            setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        nowSpeaking = true
                    }

                    @Deprecated("Java Override")
                    override fun onDone(utteranceId: String?) {
                        // نحرر التركيز فقط عند اكتمال آخر جزء في
                        // الطابور، لا عند أول جزء — الإعلان متعدد
                        // المقاطع (نص + أسماء إيموجي متتابعة) يبقى
                        // محمياً من تشويش التطبيقات الأخرى حتى
                        // ينتهي كامل النطق.
                        if (utteranceId != null
                            && utteranceId == lastQueuedUtteranceId
                        ) {
                            releaseAudioFocus()
                            onSpeechComplete?.invoke()
                        }
                        nowSpeaking = false
                    }

                    @Deprecated("Java Override")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId != null
                            && utteranceId == lastQueuedUtteranceId
                        ) {
                            releaseAudioFocus()
                            onSpeechComplete?.invoke()
                        }
                        nowSpeaking = false
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
                @Suppress("DEPRECATION")
                // setEngineByPackageName مُهمل لكنه الطريقة
                // الوحيدة لتحديد المحرك
                setEngineByPackageName(engine)
            }
        }
    }

    /**
     * ينطق نصاً (يدفع طابور نطق جديد).
     * @param text النص المراد نطقه
     * @param locale لغة النص لتحديد صوت المحرك المناسب
     * @param speechRate سرعة النطق (1.0 = طبيعي)
     * @param pitch النبرة (1.0 = طبيعي)
     * @param volume مستوى الصوت (0.0..1.0) — يُطبّق عبر معامل الصوت إن أمكن
     *
     * عند تفعيل «نطق الإيموجي» يُقسَّم النص تلقائياً إلى مقاطع، ويُنطق كل اسم
     * إيموجي بإعدادات فئة «نطق الإيموجي» المستقلة (صوت/سرعة/نبرة/مستوى صوت)
     * عبر جملة متتابعة بعده — فلا تُقرأ أسماء الإيموجي بالصوت الافتراضي.
     */
    fun speak(
        text: String,
        locale: Locale,
        speechRate: Float,
        pitch: Float,
        volume: Float
    ) {
        // إعدادات نطق الإيموجي تُحسم قبل طلب التركيز حتى تكون المقاطع جاهزة
        // للدورة (بلا قراءة متكررة للإعدادات عند كل عودة تركيز).
        val emojiCfg = resolveEmojiConfig(locale)
        val parts = if (emojiCfg != null) {
            EmojiSpeech.split(text, emojiCfg.arabic)
        } else {
            null
        }

        // نُفوض النطق دائماً لمحركٍ مثبّت (منهج MultiTTS): يستبعد اختيار المحرك
        // حزمة LORD نفسها، فيمرّ `tts.speak()` عبر محركٍ خارجي مستقر بدل حلقة
        // ربط النظام TextToSpeech → خدمة LORD التي قد تُسقط الصوت على Samsung.

        // أندرويد 15+ يقيد صوت الخلفية: النطق من مستقبلات
        // المتصل/الرسائل/الإشعارات لا يُضمن دون خدمة أمامية.
        // نشغّل خدمة الإعلانات (specialUse) إن لم تكن قائمة
        // حتى تُحتسب العملية "أمامية" وتسمح للـ TTS الخارجي
        // بالنطق.
        try {
            if (!AnnouncementSchedulerService.isRunning) {
                AnnouncementSchedulerService.startIfNeeded(appContext)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scheduler service start failed", t)
        }

        // نتيجة منح التركيز تُحترم: على أندرويد 17 قد يُنبّه النظام بطلبٍ
        // مؤجل (DELAYED) أو مرفوض (FAILED) بدل المنح الفوري.
        when (requestAudioFocus()) {
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                // التركيز سيُسلَّم لاحقاً عبر onAudioFocusChange؛ ننتظر وصول
                // AUDIOFOCUS_GAIN ثم ننطق. مؤقّت الأمان يحرّر الإعلان ما دام
                // التركيز قد تحرّر فعلاً (لا نطق أبداً والتركيز ما يزال محجوزاً
                // لمشغّلٍ آخر — المكالمة الهاتفية أشهره — فيتداخل معه الصوت).
                pendingFocusAction = {
                    startSpeech(
                        text, locale, speechRate, pitch, volume,
                        emojiCfg, parts
                    )
                }
                val timer = Runnable {
                    val action = pendingFocusAction
                    pendingFocusAction = null
                    pendingFocusTimer = null
                    // حارس المسار المؤجل: ننطق عند انقضاء المهلة فقط إن بلغنا
                    // التركيز فعلاً؛ وإلا إلغاءٌ صامت (بدل إجبار النطق فوق
                    // مكالمةٍ أو وسائطَ صارمةٍ حجزت التركيز، كما كان يحدث).
                    if (hasAudioFocus) {
                        action?.invoke()
                    } else {
                        Log.w(TAG,
                        "[Focus] DELAYED أُلغيت الصامتة:" +
                        " التركيز لم يُسلَّم")
                    }
                }
                pendingFocusTimer = timer
                mainHandler.postDelayed(timer, 3000)
            }
            AudioManager.AUDIOFOCUS_REQUEST_FAILED ->
                // فشل الحصول على التركيز (المكالمة الهاتفية أشهر
                // الأسباب): إلغاءٌ فوري صامت — لا ننطق الإعلان فوق
                // صوتٍ ناشطٍ محجوز. لم يكن هذا التصرف سابقاً (كان
                // يُنطق «أفضل جهد» بعد 400ms) لكنه خارج آداب
                // النظام ويقطع المحادثة الهاتفية.
                Log.w(TAG,
                    "[Focus] FAILED — إلغاء الإعلان صامتاً" +
                    " (صوتٌ ناشط يملك التركيز)")
            else ->
                // AUDIOFOCUS_REQUEST_GRANTED: التركيز مُنح فوراً — ننطق مباشرة.
                startSpeech(
                    text, locale, speechRate, pitch, volume,
                    emojiCfg, parts
                )
        }
    }

    /**
     * يقرأ إعدادات فئة «نطق الإيموجي» من الإعدادات؛ يعيد null عند التعطيل
     * (يبقى السلوك القديم: استبعاد الإيموجي من النطق).
     */
    private fun resolveEmojiConfig(baseLocale: Locale): EmojiSpeechConfig? {
        return try {
            val settings =
                (appContext as? AnnouncementAppContext)
                    ?.settingsRepository
                ?: SettingsRepository(appContext)
            if (!settings.isEmojiPronunciationEnabled()) return null
            val voiceId = settings.getPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_EMOJI
            )
            EmojiSpeechConfig(
                voiceId = voiceId,
                // لغة التسمية: صوت الإيموجي المختار يحددها،
                // وإلا فتمرّ للغة النص الفعلية
                arabic = if (voiceId != null) {
                    !isEnglishVoiceName(voiceId)
                } else {
                    baseLocale.language.let { LanguageCode.isArabic(it) }
                },
                rate = settings.getSpeechRateForCategory(
                    SettingsRepository.VOICE_CATEGORY_EMOJI
                ),
                pitch = settings.getPitchForCategory(
                    SettingsRepository.VOICE_CATEGORY_EMOJI
                ),
                volume = settings.getVolumeForCategory(
                    SettingsRepository.VOICE_CATEGORY_EMOJI
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "emoji config resolve failed", t)
            null
        }
    }

    /** تهيئة المحرك ثم نطق المقاطع بتأجيل قصير يسمح لاتصال TTS بالاستقرار. */
    private fun startSpeech(
        text: String,
        locale: Locale,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        emojiCfg: EmojiSpeechConfig?,
        parts: List<SpeechPart>?
    ) {
        ensureInit { ready ->
            if (!ready) {
                releaseAudioFocus()
                return@ensureInit
            }
            // تأجيل قصير يسمح لاتصال محرك TTS بالاستقرار بعد
            // onInit (حتى لو أعلن Success مبكراً، قد يبقى ربط
            // النظام معلقاً لحظياً ويُسقط speak فورياً).
            mainHandler.postDelayed({
                doSpeakParts(
                    text, locale, speechRate, pitch, volume,
                    emojiCfg, parts, attempt = 1
                )
            }, 150)
        }
    }

    /** ينطق المقاطع بالتتابع: النصوص بصوت الإعلان (النص المختلط الكتابات
     *  يُقسَّم إلى مقاطع لغوية فيُنطق كلٌّ بلغته وصوته — بند 17)، وأسماء
     *  الإيموجي بصوت فئتها. */
    private fun doSpeakParts(
        text: String,
        locale: Locale,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        emojiCfg: EmojiSpeechConfig?,
        parts: List<SpeechPart>?,
        attempt: Int
    ) {
        val units = buildSpeakUnits(
            text, locale, speechRate, pitch, volume, emojiCfg, parts
        )
        units.forEachIndexed { index, unit ->
            val queueMode = if (index == 0) {
                TextToSpeech.QUEUE_FLUSH
            } else {
                TextToSpeech.QUEUE_ADD
            }
            doSpeak(
                unit.text,
                unit.locale,
                unit.rate,
                unit.pitch,
                unit.volume,
                partVoice = unit.voiceId,
                queueMode = queueMode,
                attempt = attempt
            )
        }
    }

    /** وحدة نطق مستقلة بمعاملاتها (لغة/صوت/أشرطة) داخل دورة الإعلان الواحدة. */
    private data class SpeakUnit(
        val text: String,
        val locale: Locale,
        val rate: Float,
        val pitch: Float,
        val volume: Float,
        val voiceId: String?
    )

    private fun buildSpeakUnits(
        text: String,
        locale: Locale,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        emojiCfg: EmojiSpeechConfig?,
        parts: List<SpeechPart>?
    ): List<SpeakUnit> {
        val units = ArrayList<SpeakUnit>()
        val segments = parts ?: listOf(SpeechPart(text, false))
        segments.forEach { part ->
            if (part.isEmojiName && emojiCfg != null) {
                val emojiLocale = if (emojiCfg.arabic) {
                    Locale.forLanguageTag(LanguageCode.AR.tag)
                } else {
                    Locale.forLanguageTag(LanguageCode.EN.tag)
                }
                units.add(
                    SpeakUnit(
                        part.text, emojiLocale, emojiCfg.rate,
                        emojiCfg.pitch, emojiCfg.volume,
                        emojiCfg.voiceId
                    )
                )
            } else {
                addLanguageUnits(
                    units, part.text, locale,
                    speechRate, pitch, volume
                )
            }
        }
        return units
    }

    /** يضمّ نصاً (قد يكون مختلط الكتابات) للوحدات كلٍّ بلغةٍ مناسبة: عربي ← صوت
     *  الإعلان الحالي ولغته، إنجليزية/غيرها ← الصوت الإنجليزي المفضّل (إن حُفظ)
     *  وإلا صوت الإعلان إن كان إنجليزياً وإلا لسان محركٍ إنجليزي (بند 17). */
    private fun addLanguageUnits(
        out: MutableList<SpeakUnit>,
        text: String,
        baseLocale: Locale,
        baseRate: Float,
        basePitch: Float,
        baseVolume: Float
    ) {
        val languageSegments = runCatching {
            languageSegmenter.segment(text, LanguageCode.AR.tag)
        }.getOrDefault(emptyList())
        val effective = if (languageSegments.isEmpty()) {
            listOf(Segment(text, LanguageCode.AR.tag))
        } else {
            languageSegments
        }
        val enVoice = englishFallbackVoice()
        effective.forEach { segment ->
            val arabic = LanguageCode.isArabic(segment.languageTag)
            val segmentLocale = if (arabic) {
                baseLocale
            } else {
                Locale.forLanguageTag(LanguageCode.EN.tag)
            }
            val segmentVoice = if (arabic) voiceId else enVoice
            out.add(
                SpeakUnit(
                    segment.text, segmentLocale, baseRate,
                    basePitch, baseVolume, segmentVoice
                )
            )
        }
    }

    /** صوتُ الإنجليزية المفضّل لسقوط مقاطع «en» في الإعلانات المختلطة. */
    private fun englishFallbackVoice(): String? {
        // صوتُ EN المخصص (إن حُفظ في إعدادات اللغة)؛ وإلا صوت الإعلان الحالي
        // إن كان إنجليزياً؛ وإلا null ← المحرك يعلّق Locale("en") بنفسه.
        return runCatching {
            (appContext as? AnnouncementAppContext)?.settingsRepository
                ?: SettingsRepository(appContext)
        }.getOrNull()?.getPreferredVoiceId(LanguageCode.EN.tag)
            ?: if (isEnglishVoiceName(voiceId)) voiceId else null
    }

    private fun doSpeak(
        text: String,
        locale: Locale,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        partVoice: String?,
        queueMode: Int,
        attempt: Int
    ) {
        val tts = tts ?: return
        tts.setSpeechRate(speechRate)
        tts.setPitch(pitch)
        // تطبيق الصوت المفضّل بالاسم (مثل "ar-EG") عندما يَعرضه المحرك
        // المربوط فعلاً (محرك LORD نفسه). إذا لم يجده المحرك (محرك خارجي مثل
        // جوجل/MultiTTS لا يملك هذه الأسماء) نرجع لتحديد اللغة فقط، فيبقى
        // اختيار الصوت محدوداً بلسان المحرك كما هو متوقَّع.
        val vid = partVoice
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
        // تنظيف النص من الإيموجي قبل النطق (نصوص خارجية قد
        // تحوي رموزاً يُقرؤها المحرك الخارجي أسماءها الإنجليزية).
        // في مسار نطق الإيموجي لا يصل إيموجي لمقاطع النص (قُسمت
        // أصلاً) فالتنظيف هنا لا مساس به.
        // وتطبيع NFC يرمم النصوص القادمة مشكولةً
        // Bidi/NFD من الجذر (SMS/إشعارات).
        val cleanText = java.text.Normalizer.normalize(
            EMOJI_REGEX.replace(text, " "),
            java.text.Normalizer.Form.NFC
        )
        val utteranceId = "nateq_announce_${System.currentTimeMillis()}"
        // سجّل آخر معرّف يُرسَل قبل speak حتى يقارن به المستمع onDone/onError
        // ليحرر التركيز عند اكتمال آخر جزء فقط (لا بعد أول جزء من الجملة).
        lastQueuedUtteranceId = utteranceId
        val status = tts.speak(cleanText, queueMode, params, utteranceId)
        if (status == TextToSpeech.ERROR && attempt < 3) {
            mainHandler.postDelayed({
                doSpeak(
                    text, locale, speechRate, pitch, volume,
                    partVoice, queueMode, attempt + 1
                )
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

    /**
     * إغلاق تام عند خروج الخدمة الأمامية (onDestroy): يوقف النطق، يُبطل كل
     * المؤقتات المعلّقة (انتظار التركيز المؤجل + محاولات إعادة النطق)، يحرر
     * التركيز، ويُغلق محرك TTS نهائياً (بند [7] — منع تسريب مؤقتات/محرك).
     */
    fun shutdown() {
        mainHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        pendingFocusAction = null
        pendingFocusTimer?.let { mainHandler.removeCallbacks(it) }
        pendingFocusTimer = null
        releaseAudioFocus()
        shutdownSafely()
        nowSpeaking = false
    }

    // طلب تخفيف صوت الوسائط أثناء النطق (Audio Ducking).
    // عند وصول التركيز المؤجل (DELAYED) يُطلق هذا المستمع النطق المنتظر.
    private val onAudioFocusChange =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // تسليم التركيز المؤجل وصل — شغّل النطق المُخزّن.
                hasAudioFocus = true
                val action = pendingFocusAction
                pendingFocusAction = null
                pendingFocusTimer?.let { mainHandler.removeCallbacks(it) }
                pendingFocusTimer = null
                action?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // فقد التركيز (مكالمة/وسائط) — أوقف النطق فوراً
                hasAudioFocus = false
                tts?.stop()
                nowSpeaking = false
                releaseAudioFocus()
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: إعلاننا قصير، نستمر دون حاجة
            // لخفض الصوت (النظام يخفض الوسائط المخالفة لا إعلاننا).
        }
    }

    /**
     * يطلب Audio Focus متقطع قابل للخفض (MAY_DUCK).
     * @return نتيجة النظام: GRANTED / DELAYED / FAILED (يُحترم الجميع).
     */
    private fun requestAudioFocus(): Int {
        return try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                    .setAudioAttributes(
                        AudioAttributes.Builder()
.setUsage(
                            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                        )
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    // نحتاج قبول التأجيل: على أندرويد 17 قد يُنبّه النظام بطلبٍ
                    // مؤجل (DELAYED) عند ارتفاع ضغط الصوت في الخلفية.
                    .setAcceptsDelayedFocusGain(true)
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
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                hasAudioFocus = true
            }
            result
        } catch (t: Throwable) {
            // بدون إذن MODIFY_AUDIO_SETTINGS في الـ Manifest يرمي النظام
            // SecurityException هنا — نلتقطه ونُعد النطق بلا تركيز (أفضل جهد).
            Log.w(TAG, "requestAudioFocus failed", t)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
    }

    /** التخلي عن Audio Focus بعد انتهاء النطق. */
    private fun releaseAudioFocus() {
        hasAudioFocus = false
        // إلغاء أي نطق معلّق بانتظار التركيز حتى لا يُنطق نص قديم لاحقاً.
        pendingFocusAction = null
        pendingFocusTimer?.let { mainHandler.removeCallbacks(it) }
        pendingFocusTimer = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                }
                audioFocusRequest = null
            } else {
                // تمرير نفس المستمع المسجَّل عند الطلب (لا null): null يُطلق
                // التركيز لكنه يترك تسجيل المستمع في AudioService قائماً فتتسرب
                // مراجع المستمعين مع تتابع دورات النطق على أندرويد ما قبل O.
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(onAudioFocusChange)
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

    /** ذرّي: يمنع سباق بدء تهيئة المحرك مرتين
     * (single-flight) عبر خيوط متعددة. */
    private val initializing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** آمنة للتسابق: تُستدعى `ensureInit` بالتوازي من مستقبِلات/مؤقّتات مختلفة
     *  (Main/IO)، ويرصد `onInit` (على Main) النتائج. طابور متزامن يمنع
     *  ConcurrentModificationException في القراءة والإضافة المتزامنتين. */
    private val pendingInitCallbacks =
        ConcurrentLinkedQueue<(Boolean) -> Unit>()
}