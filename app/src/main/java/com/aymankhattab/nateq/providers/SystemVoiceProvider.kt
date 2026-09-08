package com.aymankhattab.nateq.providers

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.settings.SettingsRepository
import com.aymankhattab.nateq.util.LocaleUtils
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * مزود احتياطي يعمل بدون إنترنت، عن طريق تفويض النطق إلى محرك TTS
 * طرفي يثبّته المستخدم بنفسه (مثل eSpeak، MultiTTS، ...) بدل المحركات
 * المدمجة في الجهاز (جوجل/سامسونج).
 *
 * ## ماذا يختار هذا المزوّد؟
 * يكشف ديناميكياً كل محركات TTS المثبتة في النظام، ثم يختار:
 *   1) المحركات الطرفية التي نصبّها المستخدم (كل ما عدا ناطق نفسه،
 *      وجوجل، وسامسونج المدمجين) — هذه هي الأَولى بالتفضيل.
 *   2) إن لم يجد أي محرك طرفي، عاد إلى أي محرك متاح (جوجل) كملاذ أخير
 *      حتى لا يبقى التطبيق صامتاً.
 *
 * ## الاكتفاء الذاتي (مهم)
 * يربط مباشرةً بمحركٍ يعيّنه عند البناء عبر منشئ
 * `TextToSpeech(context, listener, engine)` — لا يتعلق إطلاقاً باختيار
 * "المحرك الافتراضي" في شاشة إعدادات TTS النظامية التي قد تُسقطها
 * المَشغّلون كسامسونج. ومستبعدٌ دائماً كونُه نفسه، فلا يحدث تكرار ذاتي.
 */
class SystemVoiceProvider(
    private val context: Context,
    /** المرجع المحقون عبر Hilt إن وُجد (يمرره NateqTtsService/TimeAnnouncementManager)،
     *  وإلا يُبنى محلياً — قراءة لحظية لا تُحفظ فلا يعَ وزير إن كان null. */
    private val injectedSettings: SettingsRepository? = null
) : VoiceProvider {

    /**
     * مُنفّذ خلفية أحادي الخيط لنقل التنفيذ الحاصر ([synthesizeInternal] الذي ينتظر
     * اكتمال كتابة المحرك عبر `await`) خارج Main Looper. سبب الحاجة: استدعاء التهيئة
     * `onInit` يصدر من `TextToSpeech` عبر منشئ المعالِجات على Main thread، وإن بُعِث
     * `onDone` من المحرك الخارجي على Main أيضاً، فالحظر داخل `onInit` يسبب Deadlock
     * ويجمّد الواجهة حتى المهلة (30 ثانية). نقل الاصطناع إلى خيط خلفي يحرّر Main فوراً.
     */
    private val synthExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "NATEQ_TTS"

        /** قيمة احتياطية إذا تعذّر قراءة ترويسة WAV (تطابق القيمة السابقة ثابتة). */
        private const val FALLBACK_SAMPLE_RATE = 22050

        /** أقصى مدة انتظار لكتابة المحرك ملف الصوت قبل اعتبار التخليق فاشلاً. */
        private const val MAX_SYNTH_WAIT_MS = 30_000L

        /** دورية فحص الإلغاء أثناء انتظار اكتمال الكتابة. */
        private const val CANCELLATION_POLL_MS = 100L

        /**
         * الحجم الأدنى المخزَّن في المسبح (بايت). الصفائف الصغيرة أرخص في
         * الإنشاء والنسخ، فلا فائدة من خزنها — نُعيدها للمُجمّع مباشرة.
         */
        private const val POOL_MIN_SIZE_BYTES = 4096

        /**
         * أقصى سعة للعناصر في المسبح. حدٌّ صغير يمنع تسرّب الذاكرة عندما
         * تُرك النصوص الطويلة صفائفَ ضخمة خاملةً في القائمة؛ الثمانية عناصر
         * تكفي لتداخل النطق وتتابع الإعلانات الشائعة (ساعة/إشعار+رسائل…).
         */
        private const val POOL_MAX_CAPACITY = 8
    }

    /**
     * مسبح صفائف PCM قابل لإعادة الاستخدام. بيانات الصوت أسرع مصادر تشكيل
     * المصفوفات في مسار التخليق (تُقرأ للملف ثم تُكتب وقد تُعاد معالجة مستوى
     * الصوت)، وإعادة إنشائها في كل إعلان تُرهق المُجمّع وتُؤجج GC. نستعيد
     * الصفائف المستهلكة (بعد أن ينسخها المُتلقّي عبر [synthesize]) ونعيد
     * استخدامها للطلب التالي بدل إنشاء جديد.
     *
     * ## إعادة استخدام غير حرفية (مهم)
     * نقبل عند الاسترجاع أي صفيف حجمه **أكبر من أو يساوي** [minSize] (وليس
     * المطابقة الحرفية فحسب). البيانات الصوتية متغيرة الحجم بين إعلان وآخر،
     * والمطابقة الحرفية كانت تُفشل إعادة الاستخدام فتُنشأ مصفوفة جديدة في كل
     * مرة — فيرتفع ضغط الـ GC. الطول الصالح يُمرَّر صراحةً عبر [PcmExtract]
     * و[VoiceProvider.synthesize] (`validLength`)، فلا تُبثّ القمامة الزاوية:
     * تُقرأ بيانات البيانات الفعلية فقط ويستهلك المتلقي حتى `validLength`
     * (أصغر من `array.size` أو مساوٍ له)، وما وراءه لا يُرسل أبداً.
     */
    private class BytePool {
        /** رامي/مستقبل أحادي — FIFO بسيط كافٍ. */
        private val available: ArrayDeque<ByteArray> = ArrayDeque()
        private val lock = Any()

        /** يُرجع مخزّناً بحجم [minSize] أو أكبر (لا إنشاء إن أمكن) للاستهلاك المتغير. */
        fun acquire(minSize: Int): ByteArray {
            if (minSize < POOL_MIN_SIZE_BYTES) return ByteArray(minSize)
            synchronized(lock) {
                var best: ByteArray? = null
                val it = available.iterator()
                while (it.hasNext()) {
                    val candidate = it.next()
                    if (candidate.size >= minSize) {
                        // نفضّل الأقرب استهلاكاً للحجم لتقليل الهدر؛ يغادر أي مرشح.
                        if (best == null || candidate.size < best.size) {
                            best = candidate
                        }
                    }
                }
                if (best != null) {
                    available.remove(best)
                    return best
                }
            }
            return ByteArray(minSize)
        }

        /** يُخزّن صفيفاً لإعادة الاستخدام (يحتفظ به كما هو، وتُبثّ "الطول الصالح" صراحةً). */
        fun release(array: ByteArray): Boolean {
            if (array.size < POOL_MIN_SIZE_BYTES) return false
            synchronized(lock) {
                if (available.size >= POOL_MAX_CAPACITY) return false
                available.addLast(array)
                return true
            }
        }
    }

    /** نتيجة استخراج الصوت من ملف WAV: بيانات PCM ومعدل العينات الحقيقي
     *  والطول الصالح الصريح (قد يكون أصغر من  `pcm.size` لأن المصفوفة قد تكون
     *  مخزناً من المسبح أكبر من بياناته الفعلية). */
    private data class PcmExtract(
        val pcm: ByteArray,
        val sampleRateInHz: Int,
        val validLength: Int
    )

    override val providerId = "system"
    override val displayName: String
        get() = context.getString(R.string.voice_provider_system)

    private var tts: TextToSpeech? = null

    /** مسبح صفائف PCM المُعاد استخدامها عبر طلبات النطق (انظر [BytePool]). */
    private val pcmPool = BytePool()

    /** حزمة المحرك المرتبط حالياً للتحقق من إعادة الاستخدام عند ثباتها */
    private var ttsEngine: String? = null

    override fun isConfigured(): Boolean = true // متاح دائمًا

    /**
     * يختار المحرك الذي ينطق به التطبيق:
     * 1) يفضّل المحرك الذي اختاره المستخدم في شاشة الإعدادات (إن وُجد ومثبَّت).
     * 2) وإلا اختار محركاً طرفياً نصبّه المستخدم (لا جوجل ولا سامسونج)،
     *    ويتم عمل fallback إلى جوجل كملاذ أخير.
     */
    private fun pickEnginePackage(): String? {
        // المحرك المختار من المستخدم (مثل MultiTTS) له الأولوية
        val selected = try {
            (injectedSettings ?: SettingsRepository(context)).getSelectedEnginePackage()
        } catch (e: Exception) {
            null
        }
        if (selected != null &&
            EnginePicker.installedEnginePackages(context).contains(selected)
        ) {
            return selected
        }
        return EnginePicker.pickEnginePackage(context)
    }

    override suspend fun listVoices(locale: Locale): List<VoiceDescriptor> {
        // إرجاع واصف يحمل المعرّف والـ locale الصحيحين (لغة ISO-2) حتى يتطابق
        // مع الـ Voice المُعلن في onGetVoices ولينطق المحرك باللغة الصحيحة.
        // نطبّع كود اللغة من ISO-3 (eng, ara) إلى ISO-2 (en, ar).
        val normLanguage = normalizeLanguage(locale.language)
        // معرفات الأصوات يجب أن تطابق أسماء onGetVoices/tts_engine.xml
        // ("ar-EG"/"en-US") حتى تعمل مطابقة id في الفئات والإعلانات.
        val voiceId = when (normLanguage) {
            "ar" -> "ar-EG"
            "en" -> "en-US"
            else -> "nateq-$normLanguage-local"
        }
        val normLocale = if (normLanguage != locale.language) {
            if (locale.country.isNullOrEmpty()) {
                Locale.forLanguageTag(normLanguage)
            } else {
                Locale.forLanguageTag("$normLanguage-${locale.country}")
            }
        } else {
            locale
        }
        return listOf(
            VoiceDescriptor(
                id = voiceId,
                providerId = providerId,
                displayName = if (normLanguage == "ar") context.getString(R.string.voice_name_arabic) else context.getString(R.string.voice_name_english),
                locale = normLocale
            )
        )
    }

    /** تطبيع كود اللغة من ISO-3 إلى ISO-2 (مثل eng→en، ara→ar) */
    private fun normalizeLanguage(code: String): String = LocaleUtils.normalizeLanguageCode(code)

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
        desiredVoiceName: String?
    ) {
        // **تفويض النطق لمحركٍ مثبّت** (منهج MultiTTS): نصّل دائماً عبر محرك TTS
        // خارجي نربط به مباشرةً (جوجل/سامسونج/طرفي). تُفضَّل المحركات الطرفية
        // إن وُجدت وإلا جوجل، وتُستبعد دائماً حزمة التطبيق نفسه
        // (`EnginePicker.installedEnginePackages`) فلا يحدث تكرار ذاتي.
        // إلغاء قابل للتعاون: على عكس suspendCoroutine، يُبلَّغ suspendCancellableCoroutine
        // بالخارج عند إلغاء المهمة (onStop من المحرك)، فنضبط علماً ونتوقف فوراً بدل
        // انتظار القفل حتى 30 ثانية. الاستئناف بعد الإلغاء يُسقط تلقائياً وهذا متوقع.
        suspendCancellableCoroutine<Unit> { cont ->
            val cancelled = AtomicBoolean(false)
            cont.invokeOnCancellation {
                cancelled.set(true)
                runCatching { tts?.stop() }
            }
            val ttsEngine = resolveEngine(enginePackage)

            // إذا تُحدَّد لغة عبر التحويل التلقائي، نستخدم صوتاً بلغتها النهائية.
            val effectiveVoice = if (voiceLocale != null && voiceLocale.language.isNotEmpty()) {
                voice.copy(locale = voiceLocale)
            } else {
                voice
            }

            synthesizeWithEngine(
                ttsEngine,
                text,
                effectiveVoice,
                speechRate,
                pitch,
                volume,
                onFormatInfo,
                onAudioChunk,
                cont,
                cancelled,
                desiredVoiceName
            )
        }
    }

    /** يحدّد محرك TTS الذي سيُستخدَم، مع التحقق من أنه مثبَّت فعلاً. */
    private fun resolveEngine(enginePackage: String?): String? {
        val engine = enginePackage ?: pickEnginePackage()
        return if (engine != null && EnginePicker.installedEnginePackages(context).contains(engine)) {
            engine
        } else {
            pickEnginePackage()
        }
    }

    /**
     * يُنفّذ النطق عبر المحرك المعطى، وعند فشل المحرك الطرفي (مثل SmartVoice الذي
     * يفشل synthesizeToFile) يتراجع تلقائياً إلى محرك جوجل المدمج كملاذ أخير حتى
     * لا يبقى التطبيق صامتاً على أي جهاز.
     */
private fun synthesizeWithEngine(
        engine: String?,
        text: String,
        voice: VoiceDescriptor,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onFormatInfo: (sampleRateInHz: Int, channelCount: Int) -> Unit,
        onAudioChunk: (ByteArray, Int) -> Unit,
        cont: kotlin.coroutines.Continuation<Unit>,
        cancelled: AtomicBoolean,
        desiredVoiceName: String?
    ) {
        val done = AtomicBoolean(false)
        val attemptWith = { currentEngine: String? ->
            if (currentEngine == null) {
                Log.e(TAG, "[Provider] no engine available to bind")
                if (!done.getAndSet(true)) cont.resume(Unit)
            } else if (!cancelled.get()) {
                if (tts == null || ttsEngine != currentEngine) {
                    // نغلق أي محرك سابق قبل ربط محرك جديد (خاصة بعد فشل محرك).
                    if (tts != null) {
                        runCatching { tts?.shutdown() }
                        tts = null
                    }
                    Log.w(TAG, "[Provider] init engine=$currentEngine")
                    tts = TextToSpeech(context, { status ->
                        if (done.getAndSet(true)) return@TextToSpeech
                        if (status == TextToSpeech.SUCCESS && !cancelled.get()) {
                            // onInit صدر من TextToSpeech على Main Looper؛ نقل الاصطناع
                            // الحاصر (انتظار كتابة الملف) إلى خيط خلفي كي لا يُحظر Main —
                            // فلو بعث المحرك onDone على Main أيضاً حصل Deadlock حتى المهلة.
                            runSynthesisOnBackground(
                                beforeSpeak = {
                                    synthesizeInternal(text, voice, speechRate, pitch, volume, onFormatInfo, onAudioChunk, cancelled, desiredVoiceName)
                                },
                                onSuccess = { cont.resume(Unit) },
                                onFailure = {
                                    // فشل النطق — جرّب محرك جوجل إن أمكن.
                                    retryWithGoogle(engine, voice, text, speechRate, pitch, volume, onFormatInfo, onAudioChunk, cont, cancelled, desiredVoiceName)
                                }
                            )
                        } else {
                            Log.e(TAG, "[Provider] engine init failed: $currentEngine status=$status")
                            retryWithGoogle(engine, voice, text, speechRate, pitch, volume, onFormatInfo, onAudioChunk, cont, cancelled, desiredVoiceName)
                        }
                    }, currentEngine)
                    ttsEngine = currentEngine
                } else if (!done.getAndSet(true)) {
                    // مثيل نفس المحرك جاهز — ننطق مباشرة بإعادة استخدامه. ننفّذ الاصطناع
                    // الحاصر على خيط خلفي (اختبارياً قد نصل هنا من مسار Main) حتى لا
                    // يُحظر Main لو بعث المحرك onDone على Main أيضاً.
                    runSynthesisOnBackground(
                        beforeSpeak = {
                            synthesizeInternal(text, voice, speechRate, pitch, volume, onFormatInfo, onAudioChunk, cancelled, desiredVoiceName)
                        },
                        onSuccess = { cont.resume(Unit) },
                        onFailure = {
                            retryWithGoogle(engine, voice, text, speechRate, pitch, volume, onFormatInfo, onAudioChunk, cont, cancelled, desiredVoiceName)
                        }
                    )
                }
            }
        }

        if (engine != null && EnginePicker.installedEnginePackages(context).contains(engine)) {
            attemptWith(engine)
        } else {
            attemptWith(EnginePicker.pickEnginePackage(context))
        }
    }

    /**
     * عند فشل المحرك الأصلي، يتراجع إلى أفضل محرك متبقٍ من القائمة الكاملة
     * (جوجل أولاً إن وُجد، وإلا MultiTTS/سامسونج/أي محرك حقيقي) — ليغطي أجهزة
     * الأسواق التي لا تصلها خدمة جوجل (الصين مثلاً). لا يُعاد المحرك الفاشل.
     */
    private fun retryWithGoogle(
        originalEngine: String?,
        voice: VoiceDescriptor,
        text: String,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onFormatInfo: (sampleRateInHz: Int, channelCount: Int) -> Unit,
        onAudioChunk: (ByteArray, Int) -> Unit,
        cont: kotlin.coroutines.Continuation<Unit>,
        cancelled: AtomicBoolean,
        desiredVoiceName: String?
    ) {
        if (cancelled.get()) return
        val remaining = EnginePicker.installedEnginePackages(context)
        val fallback = EnginePicker.pickFallbackEngineFrom(remaining, originalEngine)
        // لا نُعيد المحرك الأصلي الفاشل، ولا نتراجع إن لم يبقَ أي محرك.
        if (fallback != null && fallback != originalEngine && EnginePicker.installedEnginePackages(context).contains(fallback)) {
            Log.w(TAG, "[Provider] falling back to engine: $fallback")
            synthesizeWithEngine(fallback, text, voice, speechRate, pitch, volume, onFormatInfo, onAudioChunk, cont, cancelled, desiredVoiceName)
        } else {
            cont.resume(Unit)
        }
    }

    /**
     * ينفّذ الاصطناع الحاصر على خيط خلفية غير-`Main` ثم يستأنف/يتراجع وفق النتيجة.
     * السبب: عند استدعاء النطق من داخل `onInit` يصدر ذلك على Main Looper، وإن بعث
     * المحرك `onDone` على Main أيضاً فالحظر يسبب Deadlock وتجميد الواجهة حتى المهلة.
     * نقل عمليّة الانتظار إلى خيط خلفي يحرّر Main فوراً فيكتمل النطق بسرعة.
     */
    private fun runSynthesisOnBackground(
        beforeSpeak: () -> Boolean,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        synthExecutor.execute {
            val ok = try {
                beforeSpeak()
            } catch (t: Throwable) {
                Log.e(TAG, "[Provider] synthesis on background threw", t)
                false
            }
            if (ok) onSuccess() else onFailure()
        }
    }

    /**
     * يُنفّذ النطق عبر المحرك المربوط ويُعيد true عند النجاح (صَرْف بيانات صوتية)،
     * أو false عند الفشل (حتى يتراجع المتصل إلى محرك بديل).
     */
    private fun synthesizeInternal(
        text: String,
        voice: VoiceDescriptor,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onFormatInfo: (sampleRateInHz: Int, channelCount: Int) -> Unit,
        onAudioChunk: (ByteArray, Int) -> Unit,
        cancelled: AtomicBoolean,
        desiredVoiceName: String?
    ): Boolean {
        // حقل cancelled و مجموعة params — (توقيع internal)
        val engine = tts
        if (engine == null) return false
        // السرعة والنبرة تُمرَّران مباشرةً للمحرك (engine.setSpeechRate/setPitch)
        // بدل التعديل الخطي الرقمي اليدوي الذي كان يلغي أثرهما بتشويه معدني
        // (وفق توصية التقرير: إعادة أخذ العينات بنسبة p ثم عكسها ترك الصوت
        //  بنفس النبرة والمدة مع تنعيم مضاعف مشوّه). مستوى الصوت (volume)
        // يبقى رقمياً لأنه تطبيق معامل مضاعف محايد لا يشوّه.
        engine.setSpeechRate(speechRate)
        engine.setPitch(pitch)
        engine.setLanguage(voice.locale)
        // إن اختار المستخدم صوتاً محدداً من حوار التحويل (اسم صوت في محرك
        // خارجي مثل MultiTTS) نطبّقه هنا عبر `voice`، مع التراجع الصامت إلى
        // اللغة إذا لم يجده المحرك (تجنّباً لكسر النطق لمجرد اسم غير مطابق).
        if (!desiredVoiceName.isNullOrBlank()) {
            runCatching {
                val matching = engine.voices?.firstOrNull { it.name == desiredVoiceName }
                if (matching != null) engine.voice = matching
            }
        }

        val utteranceId = "nateq_${System.currentTimeMillis()}"
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        val tempFile = java.io.File(context.cacheDir, "nateq_tts_${System.currentTimeMillis()}.wav")

        // synthesizeToFile يُرجع SUCCESS فوراً قبل اكتمال الكتابة، لذلك ننتظر
        // اكتمال الكتابة عبر UtteranceProgressListener قبل قراءة الملف — وإلا
        // نقرأ ملفاً فارغاً/غير مكتمل ولا يُسمع أي صوت نهائياً.
        val done = CountDownLatch(1)
        var failed = false
        try {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                @Deprecated("Java Deprecated")
                override fun onDone(utteranceId: String?) {
                    done.countDown()
                }

                @Deprecated("Java Deprecated")
                override fun onError(utteranceId: String?) {
                    failed = true
                    done.countDown()
                }
            })
        } catch (e: RuntimeException) {
            Log.w(TAG, "[Provider] setOnUtteranceProgressListener threw", e)
        }

        val status = engine.synthesizeToFile(text, params, tempFile, utteranceId)

        var success = false
        if (status == TextToSpeech.SUCCESS) {
            // ننتظر فعلاً حتى يكتب المحرك الملف كاملاً (أو يُلغى الإعلان/النطق)،
            // بفحص الإلغاء كل 100ms بدل القفل الأعمى 30 ثانية — فإذا أوقف
            // المستخدم النطق (onStop) نتحرر فوراً ولا نعلق 30 ثانية.
            val deadline = SystemClock.elapsedRealtime() + MAX_SYNTH_WAIT_MS
            var finished = false
            try {
                while (true) {
                    if (done.await(CANCELLATION_POLL_MS, TimeUnit.MILLISECONDS)) {
                        finished = true
                        break
                    }
                    if (cancelled.get() ||
                        Thread.currentThread().isInterrupted ||
                        SystemClock.elapsedRealtime() >= deadline
                    ) {
                        break
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            if (finished && !failed && tempFile.exists() && tempFile.length() > 44) {
                try {
                    // قراءة بيانات الصوت مباشرة من ملف التخليق (تخطّي رأس WAV
                    // وقائمة الخانات) دون قراءة الملف كاملاً ثم نسخه — كان ذلك
                    // يرفع ذروة الذاكرة 2-3× حجم الملف للنصوص الطويلة.
                    val extracted = extractPcm(tempFile)
                    if (extracted.pcm.isEmpty() || extracted.validLength == 0) {
                        Log.e(TAG, "[Provider] extractPcm returned empty")
                    } else {
                        // إبلاغ المتصل بالتنسيق الفعلي (معدل عينات/قنوات) قبل أي شريحة
                        // حتى يبدأ callback.start() بهما بدل 22050 الثابتة.
                        onFormatInfo(extracted.sampleRateInHz, 1)
                        // مستوى الصوت فقط يُعالج رقماً (المعامل المضاعف المحايد):
                        // السرعة والنبرة صارتا تخصان المحرك عبر setSpeechRate/setPitch.
                        val validLength = extracted.validLength
                        val scaledData = if (volume != 1.0f) applyVolume(extracted.pcm, volume, validLength) else extracted.pcm
                        // الطول الصالح صريح عبر المعامل الثاني: فقد يكون حجم
                        // مصفوفة الشريحة أكبر (مسبح مُعاد استخدامه) — والبيانات
                        // الصحيحة حتى validLength فقط.
                        onAudioChunk(scaledData, validLength)
                        success = true
                        // المستهلك نسخ الشريحة (audioAvailable) ولم يُمسك بمرجعها —
                        // فنُرجع المخزن للمسبح لإعادة استخدامه في الطلب التالي.
                        pcmPool.release(scaledData)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[Provider] read audio failed", e)
                } finally {
                    tempFile.delete()
                }
            } else {
                Log.e(
                    TAG,
                    "[Provider] synthesis not completed: failed=$failed finished=$finished size=${if (tempFile.exists()) tempFile.length() else -1}"
                )
                tempFile.delete()
            }
        } else {
            Log.e(TAG, "[Provider] synthesizeToFile status=$status")
        }
        return success
    }

    /**
     * يستخرج بيانات PCM الخام ومعدل العينات الحقيقي من ملف WAV بتخطّي الرأس
     * وقائمة الخانات بصيغة آمنة، حتى مع رؤوس أطول من 44 بايتاً (ببعض المحركات
     * مثل MultiTTS). يقرأ من القرص مباشرة (RandomAccessFile) فيقرأ خانة data
     * وحدها دون نسخ الملف كاملاً إلى الذاكرة.
     *
     * معدل العينات يُقرأ من خانة `fmt ` (بايتات sampleRate في موضعها القياسي)
     * حتى يمررها المتصل لـ callback.start() بدل القيمة الثابتة 22050 التي كانت
     * تجعل Android يشغّل ملفات 24k/44.1k بسرعة ونبرة خاطئتين.
     * يُعاد [PcmExtract] بطول صالح صريح لأن مصفوفة المخزن قد تكون أكبر من
     * البيانات الفعلية (مسبح مُعاد استخدامه) — فيُستهلك حتى `validLength` فقط.
     * @return [PcmExtract] أو كائناً بمصفوفة/طول صالح صفري عند التعذر.
     */
    private fun extractPcm(file: java.io.File): PcmExtract {
        try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                if (fileLen < 12) return PcmExtract(ByteArray(0), FALLBACK_SAMPLE_RATE, 0)

                val sig = ByteArray(12)
                raf.readFully(sig)
                if (sig[0] != 'R'.code.toByte() || sig[1] != 'I'.code.toByte() ||
                    sig[2] != 'F'.code.toByte() || sig[3] != 'F'.code.toByte()
                ) {
                    // ليس ملف WAV صالح — نقرأه كاملاً تحسباً (من المسبح إن كان بعيار ملائم).
                    raf.seek(0)
                    val len = fileLen.toInt()
                    val all = pcmPool.acquire(len)
                    raf.readFully(all, 0, len)
                    return PcmExtract(all, FALLBACK_SAMPLE_RATE, len)
                }

                var sampleRate = FALLBACK_SAMPLE_RATE
                var offset = 12L // بعد "RIFF"+الحجم+"WAVE"
                while (offset + 8 <= fileLen) {
                    raf.seek(offset)
                    val header = ByteArray(8)
                    raf.readFully(header)
                    val chunkId = String(header, 0, 4, Charsets.US_ASCII)
                    val chunkSize = readLeInt(header, 4)
                    if (chunkId == "data") {
                        return readDataSection(raf, offset + 8, chunkSize.toLong(), fileLen, sampleRate)
                    }
                    if (chunkId == "fmt " && chunkSize >= 16) {
                        // تنسيق: formatTag(2) + channels(2) + sampleRate(4) + byteRate(4) + ...
                        raf.seek(offset + 8)
                        val fmt = ByteArray(16)
                        raf.readFully(fmt)
                        // معدل العيّنات في الموضع 4 (وليس 8 الذي يحمل byteRate).
                        val rate = readLeInt(fmt, 4)
                        // عينات سليمة (14.1k–192k) وإلا نُبقي الاحتياطية 22050.
                        if (rate in 14100..192000) sampleRate = rate
                    }
                    offset += 8 + chunkSize
                }
                // لم نعثر على خانة data — نعود لافتراض 44 بايت احتياطاً.
                return if (fileLen > 44) readDataSection(raf, 44L, fileLen - 44, fileLen, sampleRate)
                else PcmExtract(ByteArray(0), sampleRate, 0)
            }
        } catch (e: Exception) {
            // أي خطأ قراءة — نُرجع فارغاً فيتخلى المتصل عن الملف.
            return PcmExtract(ByteArray(0), FALLBACK_SAMPLE_RATE, 0)
        }
    }

    /** قراءة خانة بيانات صوتية بطول معلوم بدءاً من الموضع المحدد وتجسيدها كـ [PcmExtract].
     *  يُستخرج المخزن من المسبح (قد يكون أكبر من [dataLen]) لكن تُقرأ بيانات
     *  [dataLen] فقط ويُبلَّغ الطول الصالح بها — تُبثّ القيمة الصحيحة ولا يُرسل
     *  القمامة الزائدة. يعيد مصفوفة/طولاً صفرياً عند التعذر. */
    private fun readDataSection(
        raf: java.io.RandomAccessFile,
        start: Long,
        len: Long,
        fileLen: Long,
        sampleRate: Int
    ): PcmExtract {
        val dataLen = minOf(len, fileLen - start).coerceAtLeast(0L).toInt()
        if (dataLen <= 0) return PcmExtract(ByteArray(0), sampleRate, 0)
        raf.seek(start)
        val out = pcmPool.acquire(dataLen)
        raf.readFully(out, 0, dataLen)
        return PcmExtract(out, sampleRate, dataLen)
    }

    /** قراءة عدد صحيح صغير التدرج (little-endian) بطول 4 بايت */
    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /** مستوى الصوت يُطبَّق رقماً (معامل مضاعف محايد لا يشوّه الصوت):
     *  السرعة والنبرة صارتا تمرَّران مباشرةً للمحرك في [synthesizeInternal]
     *  عبر setSpeechRate/setPitch (مسار المحرك الأصلي بجودة أعلى)، فلا داعي
     *  لإعادة أخذ العينات اليدوية التي كانت تشوّه النطق.
     *
     *  يعالج فقط حتى [validLength] الصالح (لا `pcmData.size`): مع إعادة الاستخدام
     *  غير الحرفية من المسبح قد تكون المصفوفة أكبر من بياناتها الفعلية، ولا يُمرَّر
     *  القمامة. الناتج من المسبح (ويُرجَّع المصدر إليه عند اختلافه) فلا نُنشئ صفيفاً
     *  جديداً في كل إعلان أثناء معالجة المستوى. */
    private fun applyVolume(pcmData: ByteArray, volume: Float, validLength: Int): ByteArray {
        val result = pcmPool.acquire(validLength)
        var i = 0
        while (i + 1 < validLength) {
            // Read 16-bit sample (little endian)
            val sample = (pcmData[i + 1].toInt() shl 8) or (pcmData[i].toInt() and 0xFF)
            // Apply volume
            val scaled = (sample * volume).toInt().coerceIn(-32768, 32767)
            // Write back as little endian
            result[i] = (scaled and 0xFF).toByte()
            result[i + 1] = (scaled ushr 8).toByte()
            i += 2
        }
        if (result !== pcmData) pcmPool.release(pcmData)
        return result
    }
}
