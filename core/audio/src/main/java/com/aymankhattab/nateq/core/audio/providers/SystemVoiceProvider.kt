package com.aymankhattab.nateq.core.audio.providers

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.LruCache
import android.util.Log
import android.widget.Toast
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.audio.engine.BytePool
import com.aymankhattab.nateq.core.data.VoicePrefsProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.ConnectivityMonitor
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.VoiceIdContract
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    /** المرجع المحقون عبر Hilt إن وُجد
     *  (يمرره NateqTtsService/TimeAnnouncementManager)،
     *  وإلا يُبنى محلياً — قراءة لحظية لا تُحفظ
     *  فلا يعَ وزير إن كان null. */
    private val injectedSettings: VoicePrefsProvider? = null
) : VoiceProvider {


/**
     * مُنفّذ خلفية لنقل التنفيذ الحاصر
     * ([synthesizeInternal] الذي ينتظر اكتمال كتابة المحرك
     * عبر `await`) خارج Main Looper. سبب الحاجة:
     * استدعاء التهيئة `onInit` يصدر من `TextToSpeech` عبر
     * منشئ المعالِجات على Main thread، وإن بُعِث `onDone`
     * من المحرك الخارجي على Main أيضاً، فالحظر داخل
     * `onInit` يسبب Deadlock ويجمّد الواجهة حتى المهلة
     * (قصيرة للنصوص القصيرة). نقل الاصطناع إلى خيط خلفي
     * يحرّر Main فوراً.
     *
     * **بند ب.txt 3.3 — خطوط ذاكرة موازية (مرحلة 7):** يُقاس حجم المنفّذ
     * أصلعاً من عدد محركات TTS المثبّتة فعلياً في النظام ([resolvedPoolSize])
     * لا عتبةٍ جامدة — نصٌّ مختلط عبر N محركات يتوازى حتى N جلسة (مثيلُ
     * كل محركٍ بقفله الخاص يبقي جلساتِه متتالية)، مع سقف [MAX_SYNTH_POOL]
     * يمنع استنزاف الموارد على الأجهزة المنخفضة والمحركات المزدحمة. */
    private val synthExecutor: ExecutorService =
        Executors.newFixedThreadPool(
            resolvedPoolSize(
                installedEngineCount(context),
                totalDeviceMemory(context)
            )
        )

    /** مترادف استعلامي لعدد المحركات المثبّتة (يغذّي [resolvedPoolSize]). */
    private fun installedEngineCount(context: Context): Int =
        EnginePicker.installedEnginePackages(context).size

    /** الذاكرة الكلية للجهاز — عند التعذّر تُرجع اللانهاية فيُبقي السلوك
     *  القائم (السقف بالمحركات فقط) بدل التضييق الأعمى. */
    private fun totalDeviceMemory(context: Context): Long = runCatching {
        val am = context.getSystemService(
            Context.ACTIVITY_SERVICE
        ) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(Long.MAX_VALUE)

    /** معالج نبض Main للجدولة الزمنية
     *  لمهلة التهيئة [INIT_TIMEOUT_MS] (غير حاصر). */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** مفسح الإعلانات الصوتية للأخطاء — قابل للحقن في الاختبارات */
    internal var errorAnnouncer: (String) -> Unit = { message ->
        announceAudibly(message)
    }

    private val unconfiguredLanguageNotified = mutableSetOf<String>()
    private val engineFailureNotified = mutableSetOf<String>()

    private fun announceAudibly(message: String) {
        mainHandler.post {
            runCatching {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            runCatching {
                val am = context.getSystemService(
                    Context.ACCESSIBILITY_SERVICE
                ) as? android.view.accessibility.AccessibilityManager
                if (am?.isEnabled == true) {
                    val event = android.view.accessibility.AccessibilityEvent
                        .obtain(
                            android.view.accessibility.AccessibilityEvent
                                .TYPE_ANNOUNCEMENT
                        )
                    event.text.add(message)
                    event.className = javaClass.name
                    event.packageName = context.packageName
                    am.sendAccessibilityEvent(event)
                }
            }
            runCatching {
                com.aymankhattab.nateq.core.audio.announcement
                    .AudioCuePlayer.getInstance(context)
                    .play(
                        com.aymankhattab.nateq.core.audio.announcement
                            .AudioCue(
                                com.aymankhattab.nateq.core.audio
                                    .announcement.CueType.BATTERY_LOW
                            )
                    ) { }
            }
        }
    }

    private fun notifyNoEngineForLanguageOnce(languageTag: String) {
        val shouldNotify = synchronized(unconfiguredLanguageNotified) {
            unconfiguredLanguageNotified.add(languageTag)
        }
        if (shouldNotify) {
            val msg = try {
                context.getString(R.string.engine_not_selected_for_language)
            } catch (_: Throwable) {
                "لم يُحدَّد محرك نطق لهذه اللغة، افتح الإعدادات لاختياره"
            }
            Log.w(TAG, "[Provider] $msg (lang=$languageTag)")
            try {
                errorAnnouncer(msg)
            } catch (t: Throwable) {
                Log.w(TAG, "errorAnnouncer failed", t)
            }
        }
    }

    private fun notifyEngineFailedOnce(engine: String?) {
        val key = engine ?: "unknown"
        val shouldNotify = synchronized(engineFailureNotified) {
            engineFailureNotified.add(key)
        }
        if (shouldNotify) {
            val msg = try {
                context.getString(R.string.engine_failed_for_selected)
            } catch (_: Throwable) {
                "تعذّر النطق بالمحرك المحدَّد"
            }
            Log.e(TAG, "[Provider] $msg (engine=$engine)")
            try {
                errorAnnouncer(msg)
            } catch (t: Throwable) {
                Log.w(TAG, "errorAnnouncer failed", t)
            }
        }
    }

    /**
     * مراقب حالة الإنترنت الاستباقي ([ConnectivityMonitor]) — يسجّل
     * ConnectivityManager.NetworkCallback ويخبرنا لحظياً عند انقطاع الاتصال،
     * فيتخلى المزوّد فوراً عن الأصوات التي تتطلب شبكة (جوجل السحابي) ويلجأ
     * محلياً دون انتظار مهلة التوليد كاملة (Guid: التراجع الفوري بلا تأخير).
     */
    private val connectivity = ConnectivityMonitor(context)

    companion object {
        private const val TAG = "NATEQ_TTS"

        /** معرف مزود نظام TTS — يُستخدم للتراجع
         *  الافتراضي للصوت والمسارات العامة. */
        const val SYSTEM_PROVIDER_ID = "system"

        /** قيمة احتياطية إذا تعذّر قراءة ترويسة WAV
         *  (تطابق القيمة السابقة ثابتة). */
        const val FALLBACK_SAMPLE_RATE = 22050

        /**
         * مهلة أقصى لتهيئة محرك TTS خارجي: إن علق المحرك داخل
         * `TextToSpeech(context, listener)` ولم يرُدّ البتة، بقي الكوروتين
         * معلقاً للأبد — نعتبر التهيئة فاشلة بعد 5 ثوانٍ ونتراجع للمحرك التالي
         * بدل التجمّد اللانهائي.
         */
        private const val INIT_TIMEOUT_MS = 5_000L

        /** دورية إعادة المحاولة (افتراع مؤجل) لمقطعٍ وجد محركاً محجوزاً
         *  قيد التهيئة في [prewarmingEngines] (سباق 2.1): لا يُنشئ مثيلاً
         *  ثانياً ولا ينتزع محركاً غيرَ مكتمل — ينتظر اكتمالَ onInit،
         *  وسقفُ الانتظار الكلي هو [INIT_TIMEOUT_MS]. */
        private const val ENGINE_MATURITY_POLL_MS = 100L

        /** دورية فحص الإلغاء أثناء انتظار اكتمال الكتابة. */
        private const val CANCELLATION_POLL_MS = 100L

        /**
         * مهلة انتظار اكتمال كتابة المحرك لملف الصوت
         * حسب طول النص (بالمللي ثانية).
         * للنصوص القصيرة 1.5–3 ثوانٍ فقط: قارئات الشاشة
         * لا تحتمل مهلة 30 ثانية لكل محرك (وتصل سلسلة
         * التراجع بين محركين إلى 60 ثانية — بطء غير مقبول)،
         * والنصوص الطويلة تحصل على مهلة أوسع لكتابة
         * الملف كاملاً. عامة (لا internal) لأن اختبارها
         * في وحدة :app مباشرة (نفس نمط UpdateChecker
         * في :core:data).
         */
        fun synthesisTimeoutMs(textLength: Int): Long = when {
            textLength <= 10 -> 1500L
            textLength <= 80 -> 2000L
            textLength <= 300 -> 3000L
            else -> 8000L
        }


        /** حجم الدفعة الدنيا لقراءة صوت التخليق أثناء كتابته (بند ب.txt
         *  3.2): لا يُقرأ الملف النامي إلا حين يتراكم ما يعادل هذا الحجم
         *  الجديد على القرص — بلا قراءات رقاقة خلف رقاقة على ملفٍ ينمو. */
        private const val STREAM_CHUNK_BYTES = 64 * 1024

        /** طول رأس WAV المقروء لفحص خاناته أثناء البثّ — يكفي لرؤوس
         *  المحركات المعهودة (44 بايتاً + قوائم خانات نحيفة) دون قراءة
         *  الملف كاملاً. */
        private const val WAV_HEAD_READ_BYTES = 4 * 1024

        /** سقف أقصى حجم تراكمي (بالبايت) لكاش LRU لنطقات الواجهة المتكررة —
         *  4 MiB على الأجهزة ذات حد ربطات ≥256MiB (أندرويد 12+ عملياً) فيتسع
         *  الكاش لمئات العبارات الشائعة (بند ب.txt 3.5-2)، و1 MiB على المنخفضة
         *  حتى لا يفقر ذاكرة عملية النطق المحدودة أصلاً. */
        private val PCM_CACHE_MAX_BYTES: Int =
            if (Runtime.getRuntime().maxMemory() >= 256L * 1024 * 1024) {
                1 shl 22
            } else {
                1 shl 20
            }

        /** قصاصة بطول نصوص تُخزَّن في كاش PCM — النصوص الأطول تُمنح من المسبح
         *  مباشرة بلا كاش (PCM كبير ونادراً ما يتكرر حرفياً). */
        private const val PCM_CACHE_MAX_TEXT_LENGTH = 120
    }

    /** نتيجة استخراج الصوت من ملف WAV: بيانات PCM ومعدل العينات الحقيقي
     *  والطول الصالح الصريح (قد يكون أصغر من `pcm.size` لأن المصفوفة قد تكون
     *  مخزناً من المسبح أكبر من بياناته الفعلية). */
    internal data class PcmExtract(
        val pcm: ByteArray,
        val sampleRateInHz: Int,
        val validLength: Int
    )

    override val providerId = SYSTEM_PROVIDER_ID
    override val displayName: String
        get() = context.getString(R.string.voice_provider_system)

    /** مسبح محركات TTS مربوطة (بند 4): معرّف حزمة المحرك -> مثيلٌ حي يبقى
     *  دافئاً بين النطقات. يمحو كلفة إعادة تهيئة [TextToSpeech] (150–800ms
     *  لدى بعض المحركات) عند التبديل المتكرر بين لغات/محركات مختلفة —
     *  يحوّل التبديل إلى O(1) عبر [ConcurrentHashMap] آمن التزامن. تُغلَق
     *  كل المثيلات في [shutdown]، ويُسقَط المثيل المعطوب (فشل تهيئة أو
     *  نطق) عبر [dropBrokenEngine] فلا يُعاد استخدامه.
     *
     *  المصدر الأوحد للحقيقة في مسار النطق: كل مقطع يحلّ مثيله منه
     *  (`enginePool[currentEngine]`) ويمرّره وسيطاً كاملاً للاصطناع
     *  (كالذي يقوم به [synthesizeInternal]) — لا حقلٌ عامّ «نشط» تتسابق
     *  عليه المقاطع المتوازية فيفسد التوجيه (بند السباقات). */
    private val enginePool = ConcurrentHashMap<String, TextToSpeech>()

    /** محركاتٌ قيد التدفئة (بند ب.txt 3.4-2) تحت [ttsLock]: تبقى خارج
     *  [enginePool] حتى يكتمل ربط onInit الناجح فلا تُرَى في اختيارات
     *  النطق قبل نضجها؛ أي فشل تسقطه التدفئة وسكت (لا يكسر طلباً). */
    private val prewarmingEngines = LinkedHashSet<String>()

    /** قفل مزامنة دورة حياة المسبح والمثيلات:
     *  حسم الربط/الإعادة في [synthesizeWithEngine]
     *  والإغلاق في [shutdown] يتسابقان فعلياً
     *  عند تدمير الخدمة أثناء نطقٍ جارٍ —
     *  القفل يمنع إنشاء محركٍ جديد في منتصف
     *  الإغلاق النهائي. */
    private val ttsLock = Any()

    /** حارس idempotence: [shutdown] يُستدعى مرة واحدة من onDestroy؛ استدعاءات
     *  لاحقة لا تفعل شيئاً (لا تكرر unregister ولا تلمس المتغيّرات). */
    @Volatile
    private var shutdownCalled = false

    /** مسبح صفائف PCM المُعاد استخدامها عبر طلبات النطق. */
    private val pcmPool = BytePool()

    /**
     * مجمّعات معايرة RMS لكل محرك (بند الأوامر د.3.3): تُبذَر بالمحفوظ
     * أولَ استخدامٍ ثم تتجمد بعد [RMS_CALIBRATION_SAMPLES] شرائح صالحة
     * فيُحفَظ الكسبُ المستقر — خريطةٌ متزامنة لجلسات التخليق المتوازية.
     */
    private val rmsCalibrators = ConcurrentHashMap<String, RmsCalibrator>()

    /**
     * كاش LRU في الذاكرة لإعلانات النطق القصيرة الشائعة (بند 19.1): عندما
     * يكرّر TalkBack قراءة نفس عنصر الواجهة (عناوين/أزرار/تسميات ثابتة) نعيد
     * بثّ PCM الجاهز من الذاكرة بلا أي كتابة/قراءة على القرص — يحذف عنق زجاجة
     * I/O الفلاش للمتكرر منها. المفتاح يجمع النص مع كل وسائط الصوت (المعدل/
     * النبرة/الصوت/المحرك) فيتخلى الكاش تلقائياً عند أي تغيير إعداد. السعة
     * محدودة بالبايت وبطول النص الأقصى فلا يجفّ ذاكرة العملية بالنصوص الطويلة.
     */
    private val pcmCache = object : LruCache<String,
        VoiceCacheEntry>(PCM_CACHE_MAX_BYTES) {
        override fun sizeOf(
            key: String, value: VoiceCacheEntry
        ): Int = value.pcm.size + 24
    }

    /** بيانات PCM معبّأة للبث من الكاش، مترافقة مع تنسيقها الأصلي. */
    private data class VoiceCacheEntry(
        val pcm: ByteArray,
        val sampleRateInHz: Int,
        val validLength: Int
    )

    /** حزمة المحرك الذي أصبح «نشطاً» مؤخراً — علاّمة انتقال فقط لتنظيف ملف
     *  WAV المؤقت ذي الاسم الثابت عند التبديل بين محركين
     *  ([onActiveEngineSwitch]).
     *  ليست جزءاً من مسار النطق: اختيار المثيل للطلب يكون حصراً من
     *  [enginePool] ([ConcurrentHashMap]) لكل حزمة — بلا حقلٍ عام مشترك
     *  بين المقاطع المتوازية (بند السباقات). */
    @Volatile
    private var lastEnginePackage: String? = null

    override fun isConfigured(): Boolean = true // متاح دائمًا

    /**
     * إغلاق نهائي لكل موارد المزوّد عند تدمير الخدمة: يحرر رابط الـ IPC
     * للمحرك المربوط ([TextToSpeech.shutdown])، يوقف منفّذ الخلفية، يلغي
     * مهلات التهيئة المعلقة، ويفرّغ كاش PCM. يُستدعى مرة واحدة من
     * [NateqTtsService.onDestroy] — استدعاءات لاحقة لا تفعل شيئاً.
     */
    override fun shutdown() {
        if (shutdownCalled) return
        shutdownCalled = true
        connectivity.unregister()
        // حسم المحركات (إيقاف/إغلاق/تفريغ) تحت قفل دورة الحياة كي لا يتقاطع
        // مع حسم الربط في synthesizeWithEngine؛ بقية التنظيف خارج القفل.
        synchronized(ttsLock) {
            for (engine in enginePool.values) {
                runCatching { engine.stop() }
                runCatching { engine.shutdown() }
            }
            enginePool.clear()
            prewarmingEngines.clear()
            lastEnginePackage = null
        }
        pcmCache.evictAll()
        pcmPool.clear()
        tempWavFile().delete()
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { synthExecutor.shutdownNow() }
    }

    /**
     * يستعلم عن المحرك المحدد صراحةً للغة [languageTag]،
     * بشرط أن يكون مثبّتاً فعلاً في النظام. لا اختيار تلقائياً ولا احتياطياً
     * ذكياً على الإطلاق — إن لم يحدد المستخدم محركاً يُعاد null.
     */
    private fun pickEnginePackage(languageTag: String? = null): String? {
        if (languageTag == null) return null
        val installed = EnginePicker.installedEnginePackages(context)
        val settings = injectedSettings
            ?: SettingsRepository.create(context)
        return try {
            val perLang = settings.getEngineForLanguage(languageTag)
            if (perLang != null && installed.contains(perLang)) {
                perLang
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun listVoices(locale: Locale): List<VoiceDescriptor> {
        // إرجاع واصف يحمل المعرّف والـ locale الصحيحين (لغة ISO-2) حتى يتطابق
        // مع الـ Voice المُعلن في onGetVoices ولينطق المحرك باللغة الصحيحة.
        // نطبّع كود اللغة من ISO-3 (eng, ara) إلى ISO-2 (en, ar).
        val normLanguage = normalizeLanguage(locale.language)
        // معرفات الأصوات يجب أن تطابق أسماء onGetVoices/tts_engine.xml
        // ("ar-EG"/"en-US"/"fr"/"de"/…) — عبر عقد موحّد يشارك الكتالوج
        // في استخدامه، حتى تعمل مطابقة id في الفئات والإعلانات لكل اللغات
        // (كانت اللغات غير ar/en تخرج "nateq-<lang>-local" وتساقط اختيارها).
        val voiceId = VoiceIdContract.createId(normLanguage)
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
                displayName = when (normLanguage) {
                    // العربية/الإنجليزية بأسماء الترجمة
                    // الحالية (كاملة بكل الواجهات).
                    LanguageCode.AR.tag ->
                        context.getString(R.string.voice_name_arabic)
                    LanguageCode.EN.tag ->
                        context.getString(R.string.voice_name_english)
                    // أي لغة أجنبية باسمها الحقيقي بلغة واجهة التطبيق
                    // (كانت كل اللغات تُعرض "الإنجليزية" خطأً).
                    else -> displayNameFor(normLocale)
                },
                locale = normLocale
            )
        )
    }

    /** اسم لغة أجنبية بلغة واجهة التطبيق
     *  (لا لغة النظام) بحرف أول كبير حيث ينطبق. */
    private fun displayNameFor(locale: Locale): String {
        val appLocale = context.resources
            .configuration.locales.get(0)
            ?: Locale.getDefault()
        return locale.getDisplayName(appLocale).replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(appLocale) else ch.toString()
        }
    }

    /** تطبيع كود اللغة من ISO-3 إلى ISO-2 (مثل eng→en، ara→ar) */
    private fun normalizeLanguage(code: String): String =
        LocaleUtils.normalizeLanguageCode(code)

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
        // **تفويض النطق لمحركٍ مثبّت** (منهج MultiTTS):
        // نصّل دائماً عبر محرك TTS خارجي نربط به مباشرةً
        // (جوجل/سامسونج/طرفي). تُفضَّل المحركات الطرفية
        // إن وُجدت وإلا جوجل، وتُستبعد دائماً حزمة التطبيق
        // نفسه (`EnginePicker.installedEnginePackages`)
        // فلا يحدث تكرار ذاتي.
        // إلغاء قابل للتعاون: على عكس suspendCoroutine،
        // يُبلَّغ suspendCancellableCoroutine بالخارج
        // عند إلغاء المهمة (onStop من المحرك)، فنضبط علماً
        // ونتوقف فوراً بدل انتظار القفل حتى المهلة
        // المتكيّفة بطول النص. الاستئناف بعد الإلغاء
        // يُسقط تلقائياً وهذا متوقع.
        suspendCancellableCoroutine<Unit> { cont ->
            val cancelled = AtomicBoolean(false)
            cont.invokeOnCancellation {
                cancelled.set(true)
                // بند ب.txt 3.3: مع جلساتٍ متوازية على مثيلاتٍ مختلفة يجب
                // إيقافها كلِّها عند الإلغاء — لا مثيلٌ محددّ —
                // وإلا استمرّ محركٌ موزٍّ للكتابة على جلسةٍ أُلغيت من
                // المسارين معاً.
                for (instance in enginePool.values) {
                    runCatching { instance.stop() }
                }
            }
            val resolvedEngine = resolveEngine(enginePackage, voiceLocale)
            if (resolvedEngine == null) {
                val installed = EnginePicker.installedEnginePackages(context)
                if (enginePackage != null
                    && !installed.contains(enginePackage)
                ) {
                    notifyEngineFailedOnce(enginePackage)
                } else {
                    val langTag = voiceLocale?.language?.takeIf {
                        it.isNotEmpty()
                    } ?: voice.locale.language.takeIf { it.isNotEmpty() }
                    val configured = langTag?.let { tag ->
                        runCatching {
                            (injectedSettings
                                ?: SettingsRepository.create(context))
                                .getEngineForLanguage(tag)
                        }.getOrNull()
                    }
                    if (configured == null) {
                        notifyNoEngineForLanguageOnce(langTag ?: "")
                    } else if (!installed.contains(configured)) {
                        notifyEngineFailedOnce(configured)
                    }
                }
                cont.resume(Unit)
                return@suspendCancellableCoroutine
            }

            // إذا تُحدَّد لغة عبر التحويل التلقائي،
            // نستخدم صوتاً بلغتها النهائية.
            val effectiveVoice = if (voiceLocale != null
                && voiceLocale.language.isNotEmpty()
            ) {
                voice.copy(locale = voiceLocale)
            } else {
                voice
            }

            // كاش الذاكرة (بند 19.1): إن وُجدت نسخة جاهزة لنفس النص بذات
            // وسائط الصوت، نُبثّها فوراً من الذاكرة بلا أي تلامس مع القرص أو
            // ربط محرك — يلغي تماماً دورة WAV للعبارات المتكررة لدى TalkBack.
            val cacheKey = buildCacheKey(
                text, effectiveVoice, speechRate,
                pitch, volume, resolvedEngine, desiredVoiceName
            )
            val cached = if (cacheKey != null) pcmCache.get(cacheKey) else null
            if (cached != null) {
                onFormatInfo(cached.sampleRateInHz, 1)
                onAudioChunk(cached.pcm, cached.validLength)
                cont.resume(Unit)
                return@suspendCancellableCoroutine
            }

            synthesizeWithEngine(
                resolvedEngine,
                text,
                effectiveVoice,
                speechRate,
                pitch,
                volume,
                onFormatInfo,
                onAudioChunk,
                cont,
                cancelled,
                desiredVoiceName,
                cacheKey
            )
        }
    }

    /**
     * مفتاح الكاش للعبارة: النص مع كل ما يؤثر في PCM (الصوت المحسوب من المحرك،
     * المعدل، النبرة، مستوى الصوت، وحزمة المحرك المرتبطة، واسم الصوت المختار
     * [desiredVoiceName]). أي تغير فيها يُولّد
     * مفتاحاً مختلفاً فيتخلى الكاش تلقائياً عن القيمة القديمة. النصوص الأطول
     * من [PCM_CACHE_MAX_TEXT_LENGTH] لا تُخزَّن
     * (PCM كبير ونادراً يتكرر حرفياً) — تُرجع null
     * فلا يُمسّ الكاش من أصله.
     */
    private fun buildCacheKey(
        text: String,
        voice: VoiceDescriptor,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        engine: String?,
        desiredVoiceName: String?
    ): String? {
        if (text.length > PCM_CACHE_MAX_TEXT_LENGTH) return null
        // تمثيل عائم مضبوط بالبايت حتى لا ينتج
        // عن التنقية العشرية مفاتيح متقلبة.
        val r = speechRate.toRawBits()
        val p = pitch.toRawBits()
        val v = volume.toRawBits()
        // desiredVoiceName جزءٌ من صوت النطق: اختيار صوتٍ محدد يفترق PCM عن
        // صوتِ المحرك الافتراضي وإن اتحد النص واللغة والوسائط — إغفالُه كان
        // يخلط قيمَ صوتين مختلفين في كاشٍ واحد (بند جدول الحصر).
        val voiceName = desiredVoiceName ?: ""
        return "$text|${voice.id}|$r|$p|$v|$engine|$voiceName|${voice.locale}"
    }

    /** يخزّن نسخة مستقلة من بيانات PCM في الكاش
     *  (النسخة آمنة لأن المتلقي والمسبح قد يعيدان
     *  استخدام المخزن؛ نعطي الكاش نسخته الخاصة
     *  التي لا تتغير). */
    private fun storeInCache(
        key: String, pcm: ByteArray,
        sampleRate: Int, validLength: Int
    ) {
        try {
            val copy = ByteArray(validLength)
            System.arraycopy(pcm, 0, copy, 0, validLength)
            val evicted = pcmCache.put(
                key, VoiceCacheEntry(
                    copy, sampleRate, validLength
                )
            )
            evicted?.pcm?.let { /* تركه للمُجمّع — الكاش لم يعد يحتاجه */ }
        } catch (_: Exception) {
            // فشل التخزين (نفاد ذاكرة لحظي) — نسقط الصف فقط بلا أثر على النطق.
        }
    }

    /**
     * يحدّد محرك TTS الذي سيُستخدَم، مع التحقق من أنه مثبَّت فعلاً.
     * المحرك الصريح [enginePackage] يفوز إن كان مثبّتاً،
     * وإلا يُستعلم عن محرك لغة [voiceLocale] المحفوظ إن كان مثبّتاً.
     * إن لم يحدّد المستخدم محركاً أو كان المحرك غير مثبّت، يُعاد null.
     */
    private fun resolveEngine(
        enginePackage: String?,
        voiceLocale: Locale?
    ): String? {
        val installed = EnginePicker.installedEnginePackages(context)
        if (enginePackage != null) {
            return if (installed.contains(enginePackage)) {
                enginePackage
            } else {
                null
            }
        }
        val lang = voiceLocale?.language?.takeIf { it.isNotEmpty() }
        return pickEnginePackage(lang)
    }

    /** (المحور 6) عند انتقال المتحدث النشط إلى محركٍ مختلف يُحذف ملف
     *  الـ WAV المؤقت ذو الاسم الثابت على [synthExecutor] — بعد أي
     *  تركيبٍ جارٍ (المنفّذ أحادي الخيط) فلا يُحذف ملفٌ يُكتب الآن.
     *  كاشُ PCM لا يُمسح عند التبديل: مفاتيحُ الكاش تجمع النص مع
     *  حزمة المحرك (انظر [buildCacheKey]) فتبقى إدخالاتُ كل محرك
     *  معزولةً تحت مفتاحها وتُعاد فائدتها عند العودة لنفس المحرك —
     *  مسحُها كان يهدر كاش 4MiB مع كل تبديل للغات ثنائية. */
    private fun onActiveEngineSwitch(engine: String) {
        val previous = lastEnginePackage
        lastEnginePackage = engine
        if (previous == null || previous == engine) return
        synthExecutor.execute {
            runCatching { tempWavFile().delete() }
        }
        Log.d(TAG, "[Provider] active engine switch:" +
            " $previous -> $engine")
    }

    /**
     * يُنفّذ النطق عبر المحرك المعطى حصراً، وعند فشله
     * (تهيئة أو مهلة أو نطق) يُسقط المثيل من المسبح
     * ويُعلن الفشل صوتياً دون أي استبدال بمحرك آخر.
     */
    private fun synthesizeWithEngine(
        engine: String,
        text: String,
        voice: VoiceDescriptor,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onFormatInfo: (sampleRateInHz: Int, channelCount: Int) -> Unit,
        onAudioChunk: (ByteArray, Int) -> Unit,
        cont: kotlin.coroutines.Continuation<Unit>,
        cancelled: AtomicBoolean,
        desiredVoiceName: String?,
        cacheKey: String?
    ) {
        val done = AtomicBoolean(false)
        val speakNow = { engineInstance: TextToSpeech, enginePackage: String ->
            if (!done.getAndSet(true)) {
                runSynthesisOnBackground(
                    beforeSpeak = {
                        synchronized(engineInstance) {
                            synthesizeInternal(
                                engineInstance, text, voice, speechRate,
                                pitch, volume, onFormatInfo, onAudioChunk,
                                cancelled, desiredVoiceName, cacheKey,
                                enginePackage
                            )
                        }
                    },
                    onSuccess = { cont.resume(Unit) },
                    onFailure = {
                        try {
                            dropBrokenEngine(engineInstance)
                            notifyEngineFailedOnce(enginePackage)
                        } catch (t: Throwable) {
                            Log.e(TAG, "onFailure handling failed", t)
                        } finally {
                            cont.resume(Unit)
                        }
                    }
                )
            }
        }
        lateinit var attemptWith: (String) -> Unit
        attemptWith = { currentEngine: String ->
            if (!cancelled.get()) {
                synchronized(ttsLock) {
                    when {
                        shutdownCalled -> {
                            Log.w(TAG,
                                "[Provider] engine bind skipped:" +
                                " provider shutting down")
                            if (!done.getAndSet(true)) cont.resume(Unit)
                        }
                        else -> {
                            val instance = enginePool[currentEngine]
                            if (instance != null) {
                                Log.d(
                                    TAG,
                                    "[Provider] pool hit" +
                                    " engine=$currentEngine"
                                )
                                onActiveEngineSwitch(currentEngine)
                                speakNow(instance, currentEngine)
                            } else if (currentEngine in prewarmingEngines) {
                                Log.d(TAG,
                                    "[Provider] await maturity:" +
                                    " $currentEngine")
                                mainHandler.postDelayed({
                                    if (!shutdownCalled
                                        && !cancelled.get()
                                    ) {
                                        attemptWith(currentEngine)
                                    }
                                }, ENGINE_MATURITY_POLL_MS)
                            } else {
                                Log.w(TAG,
                                    "[Provider] init engine=" +
                                    "$currentEngine")
                                val initSettled =
                                    AtomicBoolean(false)
                                val hold =
                                    arrayOfNulls<TextToSpeech>(1)
                                prewarmingEngines.add(
                                    currentEngine
                                )
                                hold[0] = TextToSpeech(
                                    context, { status ->
                                    if (initSettled.getAndSet(true)) {
                                        return@TextToSpeech
                                    }
                                    val built = hold[0]
                                    if (built == null) {
                                        return@TextToSpeech
                                    }
                                    val eng = currentEngine
                                    if (status == TextToSpeech.SUCCESS
                                        && !cancelled.get()
                                    ) {
                                        synchronized(ttsLock) {
                                            if (shutdownCalled) {
                                                runCatching {
                                                    built.shutdown()
                                                }
                                            } else {
                                                enginePool[eng] = built
                                                prewarmingEngines.remove(eng)
                                                onActiveEngineSwitch(eng)
                                            }
                                        }
                                        if (shutdownCalled) {
                                            if (!done.getAndSet(true)) {
                                                cont.resume(Unit)
                                            }
                                        } else {
                                            speakNow(built, eng)
                                        }
                                    } else {
                                        Log.e(TAG,
                                            "[Provider] init" +
                                            " failed: $eng" +
                                            " status=$status")
                                        synchronized(ttsLock) {
                                            prewarmingEngines.remove(eng)
                                        }
                                        dropBrokenEngine(built)
                                        notifyEngineFailedOnce(eng)
                                        if (!done.getAndSet(true)) {
                                            cont.resume(Unit)
                                        }
                                    }
                                }, currentEngine)
                                val created = hold[0]!!
                                mainHandler.postDelayed({
                                    if (!initSettled.getAndSet(true)
                                        && done.compareAndSet(false, true)
                                        && !cancelled.get()
                                    ) {
                                        Log.w(TAG,
                                            "[Provider] init" +
                                            " timeout:" +
                                            " ${INIT_TIMEOUT_MS}" +
                                            "ms: $currentEngine")
                                        synchronized(ttsLock) {
                                            prewarmingEngines.remove(
                                                currentEngine
                                            )
                                        }
                                        dropBrokenEngine(created)
                                        notifyEngineFailedOnce(currentEngine)
                                        cont.resume(Unit)
                                    }
                                }, INIT_TIMEOUT_MS)
                            }
                        }
                    }
                }
            }
        }

        attemptWith(engine)
    }

    /**
     * يُسقط مثيل محركٍ معطوباً (فشلت تهيئته أو نطقه) من المسبح ويغلقه —
     * حتى لا يُعاد استخدامه في طلباتٍ لاحقة. يُستدعى على كل مسارات الفشل
     * الخاصّة بالمثيل بعينه (لا بمعرّف الحزمة): يُزال من المسبح بمطابقة
     * الهوية مهما كان مفتاحه الحالي. آمن التزامن تحت [ttsLock]. لا
     * حقلٌ عامٌّ يُصفَّر هنا: لا «نشط» مشترك بين المقاطع.
     */
    private fun dropBrokenEngine(instance: TextToSpeech) {
        synchronized(ttsLock) {
            runCatching { instance.stop() }
            runCatching { instance.shutdown() }
            enginePool.entries.removeAll { e -> e.value === instance }
        }
    }

    /**
     * ينفّذ الاصطناع الحاصر على خيط خلفية غير-`Main`
     * ثم يستأنف/يتراجع وفق النتيجة.
     * السبب: عند استدعاء النطق من داخل `onInit`
     * يصدر ذلك على Main Looper، وإن بعث المحرك
     * `onDone` على Main أيضاً فالحظر يسبب Deadlock
     * وتجميد الواجهة حتى المهلة.
     * نقل عمليّة الانتظار إلى خيط خلفي يحرّر Main
     * فوراً فيكتمل النطق بسرعة.
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
            try {
                if (ok) onSuccess() else onFailure()
            } catch (t: Throwable) {
                Log.e(TAG, "[Provider] synthesis completion handler threw", t)
            }
        }
    }

    /**
     * يُنفّذ النطق عبر المحرك المربوط ويُعيد true
     * عند النجاح (صَرْف بيانات صوتية)، أو false
     * عند الفشل (حتى يتراجع المتصل إلى محرك بديل).
     */
    private fun synthesizeInternal(
        engine: TextToSpeech,
        text: String,
        voice: VoiceDescriptor,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onFormatInfo: (sampleRateInHz: Int, channelCount: Int) -> Unit,
        onAudioChunk: (ByteArray, Int) -> Unit,
        cancelled: AtomicBoolean,
        desiredVoiceName: String?,
        cacheKey: String?,
        enginePackage: String? = null
    ): Boolean {
        // يُمرَّر المثيل من المسبح صراحةً (لا حقلٍ عام): بعد إدخال مسبح
        // المحركات قد يتغير المحرك المُنطَق به أثناء تتابع نطقات لغات
        // مختلفة — المثيل الملتقط يُضمن أن تُنفَّذ كل جولة على محركها
        // الصحيح وإن قُطع على مثيلٍ آخر لنطقٍ تالٍ (المسبح يبقيها كلها
        // حية).
        // السرعة والنبرة تُمرَّران مباشرةً للمحرك
        // (engine.setSpeechRate/setPitch)
        // بدل التعديل الخطي الرقمي اليدوي الذي كان يلغي أثرهما بتشويه معدني
        // (وفق توصية التقرير: إعادة أخذ العينات بنسبة p ثم عكسها ترك الصوت
        //  بنفس النبرة والمدة مع تنعيم مضاعف مشوّه). مستوى الصوت (volume)
        // يبقى رقمياً لأنه تطبيق معامل مضاعف محايد لا يشوّه.
        // فشل ضبط السرعة/النبرة يجب ألا يُبتلع صامتاً: يُسجَّل ERROR
        // (لا يُفشل النطق — عطلهما لا يمنع التخليق لكنه يشوّه الإخراج).
        if (engine.setSpeechRate(speechRate) == TextToSpeech.ERROR) {
            Log.e(TAG,
                "[Provider] engine.setSpeechRate failed" +
                " (rate=$speechRate)")
        }
        if (engine.setPitch(pitch) == TextToSpeech.ERROR) {
            Log.e(TAG,
                "[Provider] engine.setPitch failed" +
                " (pitch=$pitch)")
        }
        // إن لم يدعم المحرك لغةَ النطق يبقى على لغته السابقة (العربية) فيقرأ
        // الحروف اللاتينية بصوتٍ عربي — نعود فوراً (false) ليتولى المتصل
        // التراجع على [speechLanguage] الفعلية (en) لمحركٍ يدعم اللغة.
        val langResult = engine.setLanguage(voice.locale)
        if (langResult == TextToSpeech.LANG_NOT_SUPPORTED
            || langResult == TextToSpeech.LANG_MISSING_DATA
        ) {
            Log.w(TAG,
                "[Provider] engine lacks ${voice.locale}" +
                " (result=$langResult) — fallback")
            return false
        }
        // إن اختار المستخدم صوتاً محدداً من حوار التحويل (اسم صوت في محرك
        // خارجي مثل MultiTTS) نطبّقه هنا عبر `voice`، مع التراجع الصامت إلى
        // اللغة إذا لم يجده المحرك (تجنّباً لكسر النطق لمجرد اسم غير مطابق).
        if (!desiredVoiceName.isNullOrBlank()) {
            runCatching {
                val matching = engine.voices?.firstOrNull {
                    it.name == desiredVoiceName
                }
                if (matching != null) {
                    // صوتٌ يتطلب اتصالاً (جوجل السحابي)
                    // مع غياب الإنترنت: تراجع فوري دون دفع
                    // المحرك لانتظارِ مهلة التوليد كاملة عبثاً —
                    // الحالة يُرسلها [connectivity] استباقياً
                    // عبر NetworkCallback ومحسوبة حيّاً لحظياً.
                    if (matching.isNetworkConnectionRequired
                        && !connectivity.isOnlineNow()
                    ) {
                        Log.w(
                            TAG,
                            "[Provider] offline & voice requires network" +
                            " ($desiredVoiceName) — فوري محلي"
                        )
                        return false
                    }
                    engine.voice = matching
                }
            }
        }
        // إن لم يُحدَّد اسم صوت (المحرك الافتراضي)
        // لكن المحرك نفسه صوته الافتراضي سحابي
        // (جوجل يعتمد الشبكة افتراضياً للعربية/الهندية
        // مثلاً)، فالفحص نفسه: الإنترنت غائب → تركُ
        // التوليد فوراً وإرجاع فوري (يتولى المتصل التراجع).
        if (!connectivity.isOnlineNow()) {
            val currentRequiresNetwork = runCatching {
                engine.voice?.isNetworkConnectionRequired == true
            }.getOrDefault(false)
            if (currentRequiresNetwork) {
                Log.w(TAG,
                    "[Provider] offline & default engine voice" +
                    " requires network — فوري محلي")
                return false
            }
        }

        val utteranceId = "nateq_${System.currentTimeMillis()}"
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        val tempFile = sessionWavFile()

        // توصية المراجعة 4: حذف الملف المؤقت قبل كل كتابة جديدة — لا تُقرأ
        // بقايا كتابة سابقة مقطوعة/تالفة (محرك أُجهض في منتصف الكتابة ثم
        // أعلن onDone خاصة) إن لم يبدأ المحرك الجديد كتابتَه من الصفر.
        // التوليف متسلسل على [synthExecutor] أحادي الخيط فلا تنازع على
        // هذا الحذف، وتكلفته عمليات نظام خفيفة على مسار لا يمرّ به إلا ما
        // لا يغطيه كاش PCM.

        // synthesizeToFile يُرجع SUCCESS فوراً قبل اكتمال الكتابة، لذلك ننتظر
        // اكتمال الكتابة عبر UtteranceProgressListener قبل قراءة الملف — وإلا
        // نقرأ ملفاً فارغاً/غير مكتمل ولا يُسمع أي صوت نهائياً.
        val done = CountDownLatch(1)
        // عمل غير ذرّي (var boolean) يتسابق بين خيط الاصطناع (على
        // [synthExecutor]) وخيط الفحص هنا — سنجعله ذرياً عبر
        // AtomicBoolean ليقرأ الخيطُ الحاصر قيمةً سليمة دائماً عند
        // فحص الشرطي التالي.
        val failed = AtomicBoolean(false)
        try {
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                @Deprecated("Java Deprecated")
                override fun onDone(utteranceId: String?) {
                    done.countDown()
                }

                @Deprecated("Java Deprecated")
                override fun onError(utteranceId: String?) {
                    failed.set(true)
                    done.countDown()
                }
            })
        } catch (e: RuntimeException) {
            Log.w(TAG, "[Provider] setOnUtteranceProgressListener threw", e)
        }

        // توصية المراجعة 4: حذف الملف المؤقت قبل كل كتابة جديدة — لا تُقرأ
        // بقايا كتابة سابقة مقطوعة/تالفة (محرك أُجهض في منتصف الكتابة ثم
        // أعلن onDone خاصة) إن لم يبدأ المحرك الجديد كتابتَه من الصفر.
        // التوليف متسلسل على [synthExecutor] أحادي الخيط فلا تنازع على
        // هذا الحذف، وتكلفته عمليات نظام خفيفة على مسار لا يمرّ به إلا ما
        // لا يغطيه كاش PCM.
        runCatching { if (tempFile.exists()) tempFile.delete() }

        val status = engine.synthesizeToFile(
            text, params, tempFile, utteranceId
        )

        var success = false
        if (status == TextToSpeech.SUCCESS) {
            // ننتظر فعلاً حتى يكتب المحرك الملف كاملاً
            // (أو يُلغى الإعلان/النطق) مع فحص الإلغاء كل
            // 100ms بدل القفل الأعمى — فإذا أوقف المستخدم
            // النطق (onStop) نتحرر فوراً. المهلة متكيّفة
            // مع طول النص ([synthesisTimeoutMs]): للنصوص
            // القصيرة 1.5–3 ثوانٍ فقط (لا يحتمل قارئ
            // الشاشة 30 ثانية انتظار) وللطويلة أوسع
            // ليكتمل كتابة الملف.
            val waitMs = synthesisTimeoutMs(text.length)
            var deadline = SystemClock.elapsedRealtime() + waitMs
            var finished = false
            // **بثّ مجزّأ أثناء الكتابة (بند ب.txt 3.2):** بدل انتظار اكتمال
            // ملف WAV كاملاً ثم قراءته في دفعةٍ واحدة، تُقرأ الدفعاتُ
            // المتراكمة حديثاً كل [CANCELLATION_POLL_MS] وتُدفع للمتلقي
            // فوراً — يتسلم أول بايتات الصوت قبل نهاية كتابة المحرك فيختصر
            // زمنَ أول صوتٍ لقارئ الشاشة. لا يجوز التفاعل مع ملفٍ لم تتضح
            // خاناته بعد: [readWavStreamMeta] يرجع null حتى تظهر خانة data
            // فيُحسم البث. أي تعثرٍ قبل بثّ شريحةٍ واحدة يُسقط البث هادئاً
            // إلى المسار الكامل التقليدي (قراءة نهائية موحّدة) بالأسفل.
            var streamMeta: WavStreamMeta? = null
            var dataStart = -1L
            var readSoFar = 0L
            var emittedAny = false
            var streamReadFailed = false
            val cachedParts = ArrayList<ByteArray>()
            val totalCacheBytes = IntArray(1)
            try {
                while (true) {
                    if (done.await(
                            CANCELLATION_POLL_MS,
                            TimeUnit.MILLISECONDS
                        )
                    ) {
                        finished = true
                        break
                    }
                    if (cancelled.get() ||
                        Thread.currentThread().isInterrupted ||
                        SystemClock.elapsedRealtime() >= deadline
                    ) {
                        break
                    }
                    if (!streamReadFailed && streamMeta == null &&
                        tempFile.exists() && tempFile.length() >= 44L
                    ) {
                        val head = ByteArray(WAV_HEAD_READ_BYTES)
                        val got = java.io.RandomAccessFile(
                            tempFile, "r"
                        ).use { raf ->
                            var i = 0
                            while (i < head.size) {
                                val n = raf.read(head, i, head.size - i)
                                if (n < 0) break
                                i += n
                            }
                            i
                        }
                        if (got > 0) {
                            streamMeta = readWavStreamMeta(head, got)
                        }
                    }
                    if (!streamReadFailed && streamMeta != null) {
                        val start = if (dataStart < 0) {
                            streamMeta.dataStart.also { dataStart = it }
                        } else {
                            dataStart
                        }
                        val available = tempFile.length() - start
                        val toRead = available - readSoFar
                        if (toRead >= STREAM_CHUNK_BYTES) {
                            try {
                                emitStreamChunk(
                                    tempFile, start + readSoFar,
                                    toRead.toInt(), volume, streamMeta,
                                    onFormatInfo, onAudioChunk,
                                    cacheKey, cachedParts,
                                    totalCacheBytes, enginePackage
                                )
                                // بند 1.1: readSoFar تراكمي؛ كان يُسند
                                // إليه toRead (دلتا) فتتكرر الشريحةُ
                                // ويتقطّع الصوت في البث.
                                readSoFar = available
                                emittedAny = true
                                // مرحلة 7: كتابةٌ متقدمة تُرجئ الـ deadline
                                // فلا تُقطع التخليقُ الطويل البطيءُ الذي ما
                                // زال يُنتج صوتاً (كان يُقتَطع عند المهلة
                                // الثابتة رغم التقدم).
                                deadline = extendStreamDeadline(
                                    deadline, emittedAny,
                                    SystemClock.elapsedRealtime()
                                )
                            } catch (e: Exception) {
                                if (emittedAny) {
                                    // قُرئ صوتٌ فعلاً: لا نُعيد بثّه كاملاً —
                                    // يكفي أن يُكمل المتبقي في النهاية.
                                    streamReadFailed = true
                                } else {
                                    // لم يتبدّأ البث أصلاً: التراجع للمسار
                                    // الكامل التقليدي بالأسفل بلا ازدواج.
                                    streamReadFailed = true
                                }
                                Log.w(TAG,
                                    "[Provider] streaming read failed —" +
                                    " " +
                                    if (emittedAny) {
                                        "إتمام المتبقي"
                                    } else {
                                        "المسار الكامل"
                                    }, e)
                            }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            if (finished && !failed.get() && streamMeta != null &&
                emittedAny
            ) {
                // إتمام البث: يُقرأ ما تبقى بعد آخر دفعة ثم يُعلَّم النجاح —
                // لا مسار القراءة الكاملة (تجنّب بثٍّ مكرر).
                val start = if (dataStart < 0) 0L else dataStart
                val remaining = tempFile.length() - start - readSoFar
                if (remaining > 0) {
                    try {
                        emitStreamChunk(
                            tempFile, start + readSoFar,
                            remaining.toInt(), volume, streamMeta,
                            onFormatInfo, onAudioChunk,
                            cacheKey, cachedParts, totalCacheBytes,
                            enginePackage
                        )
                    } catch (e: Exception) {
                        Log.w(TAG,
                            "[Provider] stream remainder flush failed", e)
                    }
                }
                if (cacheKey != null && cachedParts.isNotEmpty() &&
                    totalCacheBytes[0] <= PCM_CACHE_MAX_BYTES
                ) {
                    val all = ByteArray(totalCacheBytes[0])
                    var pos = 0
                    for (part in cachedParts) {
                        System.arraycopy(part, 0, all, pos, part.size)
                        pos += part.size
                    }
                    storeInCache(
                        cacheKey, all,
                        streamMeta.sampleRateInHz, totalCacheBytes[0]
                    )
                }
                success = true
            } else if (finished && !failed.get() && tempFile.exists()
                && tempFile.length() > 44
            ) {
                try {
                    // المسار الكامل التقليدي: قراءة الصوت من ملف التخليق
                    // (تخطّي رأس WAV وقائمة الخانات) دون تحميل الملف
                    // كاملاً ثم نسخه — كان ذلك يرفع ذروة الذاكرة 2-3× حجم
                    // الملف للنصوص الطويلة.
                    val extracted = extractPcm(tempFile)
                    if (extracted.pcm.isEmpty() || extracted.validLength == 0) {
                        Log.e(TAG, "[Provider] extractPcm returned empty")
                    } else {
                        // إبلاغ المتصل بالتنسيق الفعلي
                        // (معدل عينات/قنوات) قبل أي شريحة
                        // حتى يبدأ callback.start() بهما
                        // بدل 22050 الثابتة.
                        onFormatInfo(extracted.sampleRateInHz, 1)
                        // مستوى الصوت فقط يُعالج رقماً
                        // (المعامل المضاعف المحايد): السرعة
                        // والنبرة صارتا تخصان المحرك عبر
                        // setSpeechRate/setPitch.
                        val validLength = extracted.validLength
                        val scaledData = if (volume != 1.0f) {
                            applyVolume(
                                extracted.pcm, volume, validLength,
                                enginePackage
                            )
                        } else {
                            extracted.pcm
                        }
                        // منح البثّ إلى الكاش نسخةً مستقلة
                        // من البيانات (بند 19.1): المتلقي
                        // والمسبح قد يعيدان استخدام المخزن،
                        // فنسخة الكاش ثابتة.
                        cacheKey?.let {
                            storeInCache(
                                it, scaledData,
                                extracted.sampleRateInHz, validLength
                            )
                        }
                        // الطول الصالح صريح عبر المعامل الثاني:
                        // فقد يكون حجم مصفوفة الشريحة أكبر (مسبح
                        // مُعاد استخدامه) — والبيانات الصحيحة
                        // حتى validLength فقط.
                        onAudioChunk(scaledData, validLength)
                        success = true
                        // المستهلك نسخ الشريحة (audioAvailable)
                        // ولم يُمسك بمرجعها — فنعيد كل مسبَّحٍ صاحبَ
                        // حقّ الردّ: `extracted.pcm` مسبَّح دائماً،
                        // والناتجُ الجديد من applyVolume مسبَّح أيضاً
                        // (يعيده إن اختلف عن المصدر) — وما لم يأتِ من
                        // المسبح لا يُعاد إليه (بند السباقات).
                        if (scaledData !== extracted.pcm) {
                            pcmPool.release(scaledData)
                        }
                        pcmPool.release(extracted.pcm)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[Provider] read audio failed", e)
                }
            } else {
                val fileSize = if (tempFile.exists()) {
                    tempFile.length()
                } else {
                    -1
                }
                Log.e(
                    TAG,
                    "[Provider] synthesis not completed:" +
                        " failed=${failed.get()} finished=$finished" +
                        " size=$fileSize"
                )
            }
        } else {
            Log.e(TAG, "[Provider] synthesizeToFile status=$status")
        }
        // ملفُ الجلسة خاص بها فيُحذف في كل المصائر — لا بقايا تتراكم في
        // الكاش بعد النطق، ولا يُمسّ ملفُ جلسةٍ أخرى (الأسماءُ فريدة).
        runCatching { tempFile.delete() }
        return success
    }

    /** عدّاد تزايدي لأسماء ملفات WAV المؤقتة الفريدة لكل جلسة تخليق (بند
     *  ب.txt 3.2/3.3): مع جلساتٍ متوازية على منفّذي [synthExecutor] لم يعد
     *  ملفٌ واحد ثابت الاسم آمناً — كل جلسة تكتب/تقرأ ملفها الخاص فلا يفسد
     *  بثٌّ على آخر. */
    private val sessionFileCounter = AtomicLong(0)

    /** ملف مؤقت فريد لحملة التخليق الحالية؛ يُحذف في نهايتها مهما كان
     *  المصير ([synthesizeInternal] في finally قبل العودة)، وبقاياه عاجزةٌ
     *  عن النطق يلتقطها [StartupTempSweeper] عند الإقلاع — لا يتعارض اسمٌ
     *  فريدٌ مع نشاطٍ لجلسةٍ أخرى. */
    private fun sessionWavFile(): java.io.File {
        val id = sessionFileCounter.incrementAndGet()
        return java.io.File(context.cacheDir, "nateq_tts_$id.wav")
    }

    /** ملفُ الاسم الثابت التاريخي — يُحذف دفاعياً في [shutdown]
     *  و[onActiveEngineSwitch] لأي بقايا عتيقة؛ التخليق الجديد لا يعود
     *  إليه (اسمٌ فريد لكل جلسة). */
    private fun tempWavFile(): java.io.File =
        java.io.File(context.cacheDir, "nateq_tts_session.wav")

    /**
     * تدفئة محركات TTS قبل أول نطقٍ فعلي (بند ب.txt 3.4-2): يربط مثيلاً لكل
     * محركٍ مطلوب على الخلفية دون أن ينتظر المتصلُ اكتمالَ الربط ولا أن يكسر
     * النطق لو تعثر. أول ربط [TextToSpeech] يكلف غالباً 150–800ms لدى بعض
     * المحركات — فإذا أقبل TalkBack وأولُ طلبٍ على محركٍ بارد، علِق أول نطق
     * على هذه الكلفة؛ التدفئة تجعل المثيل جاهزاً في [enginePool] قبل السؤال.
     * المثيلاتُ لا تُعرض للاختيار قبل نجاح onInit (تُجمع في [prewarmingEngines]
     * ثم تُنقل)، والمحركات المربوطة أصلاً تُتجاوز — فاستدعاءٌ متكرر آمن.
     */
    fun prewarmEngines(engines: List<String>) {
        if (shutdownCalled || engines.isEmpty()) return
        val installed = EnginePicker.installedEnginePackages(context)
        val targets = engines.filter { it in installed }
        if (targets.isEmpty()) return
        // سقف التدفئة بالذاكرة أيضاً: لا تُربط مثيلات فوق طاقة الجهاز.
        val capped = targets.take(
            resolvedPoolSize(targets.size, totalDeviceMemory(context))
        )
        if (capped.isEmpty()) return
        synthExecutor.execute {
            for (engine in capped) {
                if (shutdownCalled) return@execute
                var skip = false
                synchronized(ttsLock) {
                    if (shutdownCalled) return@execute
                    if (enginePool.containsKey(engine) ||
                        engine in prewarmingEngines
                    ) {
                        skip = true
                    } else {
                        prewarmingEngines.add(engine)
                    }
                }
                if (skip) continue
                Log.d(TAG, "[Provider] prewarm engine=$engine")
                val mainHandler = Handler(Looper.getMainLooper())
                val hold = arrayOfNulls<TextToSpeech>(1)
                val instance = try {
                    TextToSpeech(context, { status ->
                        mainHandler.post {
                            val built = hold[0]
                            synchronized(ttsLock) {
                                if (built == null) {
                                    prewarmingEngines.remove(engine)
                                    return@synchronized
                                }
                                when {
                                    shutdownCalled -> {
                                        runCatching { built.shutdown() }
                                    }
                                    status == TextToSpeech.SUCCESS -> {
                                        enginePool[engine] = built
                                    }
                                    else -> runCatching { built.shutdown() }
                                }
                                prewarmingEngines.remove(engine)
                            }
                        }
                    }, engine)
                } catch (t: Throwable) {
                    Log.e(TAG, "[Provider] prewarm bind failed: $engine", t)
                    synchronized(ttsLock) { prewarmingEngines.remove(engine) }
                    continue
                }
                hold[0] = instance
            }
        }
    }

    /** يقرأ [len] بايتاً من موضع [start] في ملف التخليق النامي، يطبّق مستوى
     *  الصوت ثم يدفع البيانات للمتلقي ([onAudioChunk])، ويُجمِّع نسخةً
     *  مستقلة للكاش (بند 19.1) ضمن سقف [PCM_CACHE_MAX_BYTES] عند مرور
     *  [cacheKey]. يُبلَّغ التنسيق مرةً واحدة عبر [onFormatInfo] قبل أول
     *  شريحة. المتلقي ينسخ الشريحة قبل العودة فالمخزنُ يُردّ للمسبح. يرمي
     *  عند فشل القراءة فيتولى المتصلُ المصير (إتمام المتبقي أو التراجع). */
    private fun emitStreamChunk(
        file: java.io.File,
        start: Long,
        len: Int,
        volume: Float,
        meta: WavStreamMeta,
        onFormatInfo: (Int, Int) -> Unit,
        onAudioChunk: (ByteArray, Int) -> Unit,
        cacheKey: String?,
        cachedParts: ArrayList<ByteArray>,
        totalCacheBytes: IntArray,
        enginePackage: String? = null
    ) {
        val raw = ByteArray(len)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            var i = 0
            while (i < len) {
                val n = raf.read(raw, i, len - i)
                if (n < 0) break
                i += n
            }
        }
        // بند 2.2: استيريو → مونو قبل البث (المتلقي أحادي القناة).
        val samples = if (meta.channelCount > 1) {
            downmixStereoToMono(raw, len)
        } else {
            raw
        }
        val samplesLen = samples.size
        onFormatInfo(meta.sampleRateInHz, 1)
        val scaledData = if (volume != 1.0f) {
            applyVolume(samples, volume, samplesLen, enginePackage)
        } else {
            samples
        }
        if (cacheKey != null &&
            totalCacheBytes[0] + samplesLen <= PCM_CACHE_MAX_BYTES
        ) {
            cachedParts.add(scaledData.copyOf(samplesLen))
            totalCacheBytes[0] += samplesLen
        }
        onAudioChunk(scaledData, samplesLen)
        // لا يُعاد إلى المسبح إلا ما صدر عن المسبح فعلاً: عند مستوى الصوت
        // الكامل (volume == 1.0f) تُبث الشريحةُ الأصلية المدروسة مباشرةً —
        // وهي إما raw محلي أو ناتج downmixStereoToMono محلي — وأي محاولة
        // لإعادتها للمسبح تُدخله صفيفاً أجنبياً يُحوّله بعد ذلك لحاملِه
        // (استخدامٌ مزدوج لنفس الصفيف من مالكَين). الناتجُ المسبَّح وحده
        // (من applyVolume) صاحبُ حقّ الردّ.
        if (volume != 1.0f) pcmPool.release(scaledData)
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
    private fun extractPcm(file: java.io.File): PcmExtract =
        extractPcmFromFile(file, pcmPool)

    /**
     * مستوى الصوت يُطبَّق رقماً (كسبٌ مضاعف مجمَّع): التطبيع الأولي عبر
     *  [normalizedRmsGain] يجذب جهارةَ المحرك إلى هدفٍ موحّد (فمحركُ
     *  eSpeak يهمس ومحركٌ آخر يصرخ — المستخدم لا يضبط الجهارة لكل محرك)
     *  ثم يُضرب فوقه كسبُ المستخدم [volume]. السرعة والنبرة تخصان المحرك
     *  عبر setSpeechRate/setPitch — لا إعادة أخذ عينات يدوية كانت تشوّه.
     *
     *  يعالج فقط حتى [validLength] الصالح (لا
     *  `pcmData.size`): مع إعادة الاستخدام غير الحرفية
     *  من المسبح قد تكون المصفوفة أكبر من بياناتها
     *  الفعلية، ولا يُمرَّر القمامة. الناتج من المسبح
     *  (تجنّباً لإنشاء صفيفٍ جديد في كل إعلان).
     *
     *  **عقد الملكية:** الدالة لا تُعيد المصدر إلى المسبح
     *  أبداً — مصدرٌ قد يكون مخزناً مسبّحاً وقد يكون
     *  محلياً (raw/downmix)؛ «سكين» المصدر شأنُ المتصل
     *  الذي يعرف أصله (بند السباقات: إعادة مصفوفةٍ لم
     *  تأتِ من المسبح تفسده وتمزق استخدامَه المزدوج).
     */
    private fun applyVolume(
        pcmData: ByteArray,
        volume: Float,
        validLength: Int,
        enginePackage: String? = null
    ): ByteArray {
        val result = pcmPool.acquire(validLength)
        val gain = rmsGainFor(pcmData, volume, validLength, enginePackage)
        var i = 0
        while (i + 1 < validLength) {
            // Read 16-bit sample (little endian)
            val sample = (pcmData[i + 1].toInt() shl 8) or
                (pcmData[i].toInt() and 0xFF)
            // Apply combined RMS gain
            val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
            // Write back as little endian
            result[i] = (scaled and 0xFF).toByte()
            result[i + 1] = (scaled ushr 8).toByte()
            i += 2
        }
        return result
    }

    /**
     * كسب الشريحة مع المعايرة الدائمة لكل محرك (بند الأوامر د.3.3): بلا
     * محركٍ (قراءة ملف WAV) يُستخدم التطبيعُ اللحظي كما كان؛ ومع محركٍ
     * تُغذَّى المقاييسُ الصالحة لمجمّعِه حتى التجميد ثم يُثبَّت الكسبُ
     * ويُحفَظ — فلا يضخّم الصوتُ بين شرائح النطق الواحد. الصمتُ يبقى
     * بكسب المستخدم وحده دائماً (لا تضخيم للهمهمة).
     */
    private fun rmsGainFor(
        pcmData: ByteArray,
        volume: Float,
        validLength: Int,
        enginePackage: String?
    ): Float {
        if (enginePackage == null) {
            return normalizedRmsGain(pcmData, validLength, volume)
        }
        val live = rmsNormalizationScale(pcmData, validLength) ?: return volume
        val calibrator = calibratorFor(enginePackage)
        val wasSettled = calibrator.isSettled()
        calibrator.observe(live)
        val effective = if (calibrator.isSettled()) {
            calibrator.current() ?: live
        } else {
            live
        }
        if (!wasSettled && calibrator.isSettled()) {
            injectedSettings?.saveEngineRmsCalibration(
                enginePackage, effective
            )
        }
        return effective * volume
    }

    /** مجمّع المحرك (يُبذَر بالمحفوظ أول مرة) — مسار التخليق وحده يملكه. */
    private fun calibratorFor(enginePackage: String): RmsCalibrator =
        rmsCalibrators.getOrPut(enginePackage) {
            RmsCalibrator().also { calibrator ->
                rmsCalibrationOf(enginePackage)?.let { saved ->
                    calibrator.seed(saved)
                }
            }
        }

    /** المحفوظ مُحجَّماً لحدود المحرك — تالفٌ/غائب = بلا بذر (null). */
    private fun rmsCalibrationOf(enginePackage: String): Float? =
        injectedSettings?.getEngineRmsCalibration(enginePackage)
            ?.takeIf { it.isFinite() }
            ?.coerceIn(MIN_RMS_GAIN, MAX_RMS_GAIN)

    /** مسح معايرة محرك لإعادة معايرتها من الصفر (ذاكرةً وقرصاً). */
    fun clearEngineCalibration(enginePackage: String) {
        rmsCalibrators.remove(enginePackage)
        injectedSettings?.clearEngineRmsCalibration(enginePackage)
    }
}

/**
 * نافذة تمديد مهلة البثّ بعد كل شريحة تُبثّ بنجاح (مرحلة 7): في السابق
 * كان [deadline] الأصلي (من [SystemVoiceProvider.synthesisTimeoutMs])
 * مطلقاً فالكتابةُ البطيئة المتقدمة تُقتَطع قبل اكتمالها رغم أن المحرك ما
 * زال يكتب — الآن كل شريحة تُبثّ بنجاح تُرجئ الـ deadline هذا المقدار،
 * فيبقى البثّ حياً ما تقدمت الكتابة فعلاً، ولا يُقطع إلا بجمود. 5 ثوانٍ
 * (لنصوصٍ أوسع) تعادل تقريباً مهلة أطول نص وتكفي لدورات جوجل المتعثرة
 * أن تُخطّ دون الجمود.
 */
internal const val STREAM_DEADLINE_EXTEND_MS = 5_000L

/**
 * يُرجئ مهلة البثّ [deadline] إن أُبثّت شريحةٌ فعلاً ([chunkEmitted]) —
 *  كتابةُ المحرك المتقدمةُ علامةُ حياةٍ فلا تُقطع قبل اكتمالها، بينما
 *  الجمودُ (لا شريحة جديدة) يُبقي الـ deadline الأصلي فيتحرر المدير.
 *  منطقٌ نقي (التواقيت من [android.os.SystemClock]) قابل للاختبار الآلي.
 */
internal fun extendStreamDeadline(
    deadline: Long,
    chunkEmitted: Boolean,
    now: Long
): Long {
    return if (chunkEmitted) now + STREAM_DEADLINE_EXTEND_MS else deadline
}

/**
 * يستخرج بيانات PCM الخام ومعدل العينات الحقيقي من ملف WAV بتخطّي الرأس
 * وقائمة الخانات بصيغة آمنة (حتى مع رؤوس أطول من 44 بايتاً لدى بعض
 * المحركات مثل MultiTTS). يقرأ من القرص مباشرة عبر [java.io.RandomAccessFile]:
 * قراءة 8 بايت لكل رأس خانة، ثم بيانات خانة `data` في مصفوفة مُسترجَعة من
 * مسبح [BytePool] — دون تحميل الملف كاملاً في الذاكرة (كان `readBytes`
 * يرفع ذروة التخصيص إلى 2-3× حجم الملف للنصوص الطويلة فيقل الضغط على GC).
 *
 * معدل العينات يُقرأ من خانة `fmt ` (بايتات sampleRate في موضعها القياسي)
 * حتى يمررها المتصل لـ callback.start() بدل القيمة الثابتة 22050 التي كانت
 * تجعل Android يشغّل ملفات 24k/44.1k بسرعة ونبرة خاطئتين.
 * يُعاد [SystemVoiceProvider.PcmExtract] بطول صالح صريح لأن مصفوفة المخزن
 * قد تكون أكبر من البيانات الفعلية (مسبح مُعاد استخدامه).
 * @return [SystemVoiceProvider.PcmExtract] أو كائناً بمصفوفة/طول صفريين
 *         عند التعذر.
 */
internal fun extractPcmFromFile(
    file: java.io.File,
    pool: BytePool
): SystemVoiceProvider.PcmExtract {
    return try {
        java.io.RandomAccessFile(file, "r").use {
            parseWavChunks(it, pool)
        }
    } catch (e: Exception) {
        // أي خطأ قراءة — نُرجع فارغاً فيتخلى المتصل عن الملف.
        SystemVoiceProvider.PcmExtract(
            ByteArray(0), SystemVoiceProvider.FALLBACK_SAMPLE_RATE, 0
        )
    }
}

/** يخفض عيّنات استيريو (16-بت، قناتان متداخلتان LR) إلى مونو وسطياً —
 *  (L+R)/2 — فلا تُبثّ القناتان تباعاً كما لو كانتا أحاديتين فيتشوّه
 *  الإيقاع ويَضطرب الزمن. بند 2.2. */
private fun downmixStereoToMono(
    stereo: ByteArray,
    length: Int,
    pool: BytePool? = null
): ByteArray {
    val frames = length / 4
    val mono = pool?.acquire(frames * 2) ?: ByteArray(frames * 2)
    var s = 0
    var m = 0
    while (s + 3 < length) {
        val left = (stereo[s + 1].toInt() shl 8) or
            (stereo[s].toInt() and 0xFF)
        val right = (stereo[s + 3].toInt() shl 8) or
            (stereo[s + 2].toInt() and 0xFF)
        val sample = (left + right) / 2
        mono[m] = (sample and 0xFF).toByte()
        mono[m + 1] = (sample ushr 8).toByte()
        s += 4
        m += 2
    }
    return mono
}

/** يمشي خانات WAV من القرص: خانة `data` تُقرأ مباشرة في المسبح، ومعدل
 *  العينات من خانة `fmt `، مع نهاية 44 بايت احتياطية عند غياب `data`. */
private fun parseWavChunks(
    raf: java.io.RandomAccessFile,
    pool: BytePool
): SystemVoiceProvider.PcmExtract {
    val fileLen = raf.length()
    val emptyExtract = { sampleRate: Int ->
        SystemVoiceProvider.PcmExtract(
            ByteArray(0), sampleRate, 0
        )
    }
    if (fileLen < 12) return emptyExtract(
        SystemVoiceProvider.FALLBACK_SAMPLE_RATE
    )

    val head = ByteArray(4)
    if (!readFully(raf, head, 0, 4)) {
        return emptyExtract(SystemVoiceProvider.FALLBACK_SAMPLE_RATE)
    }
    if (head[0] != 'R'.code.toByte()
        || head[1] != 'I'.code.toByte()
        || head[2] != 'F'.code.toByte()
        || head[3] != 'F'.code.toByte()
    ) {
        // ليس ملف WAV صالح — نُبقي البيانات كاملة
        // من المسبح دون نسخة وسيطة.
        val all = readFromPoolOrNull(raf, 0, fileLen.toInt(), pool)
            ?: return emptyExtract(SystemVoiceProvider.FALLBACK_SAMPLE_RATE)
        return SystemVoiceProvider.PcmExtract(
            all, SystemVoiceProvider.FALLBACK_SAMPLE_RATE, fileLen.toInt()
        )
    }

    var sampleRate = SystemVoiceProvider.FALLBACK_SAMPLE_RATE
    var numChannels = 1
    var offset = 12L // بعد "RIFF"+الحجم+"WAVE"
    val chunkHeader = ByteArray(8)
    val rateBuf = ByteArray(4)
    val chanBuf = ByteArray(2)
    while (offset + 8 <= fileLen) {
        raf.seek(offset)
        if (!readFully(raf, chunkHeader, 0, 8)) break
        val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
        // حجم الخانة كقيمة **غير موقّعة** (اكتشاف البت 31):
        // قراءته إشارةً كان يجعل المؤشر ينقص في ملفٍ تالف
        // (مثل 0x80000000 = -2147483648) وقد يدخل في حلقة
        // لا نهائية تعيد نفس المواضع — الآن لا ينقص المؤشر
        // أبداً لأن الحجم Long في 0..2^32-1.
        val chunkSize = readLeInt(chunkHeader, 4).toLong() and 0xFFFFFFFFL
        if (chunkId == "data") {
            val dataStart = offset + 8
            val dataLen = minOf(
                chunkSize, fileLen - dataStart
            ).coerceAtLeast(0L).toInt()
            if (dataLen <= 0) return emptyExtract(sampleRate)
            val out = readFromPoolOrNull(raf, dataStart, dataLen, pool)
                ?: return emptyExtract(sampleRate)
            // بند 2.2: محركٌ قد يكتب استيريو رغم أحادية النطق — بثُّ
            // القناتين تباعاً كان يضاعف المدة ويشوّه الإيقاع؛ نُخفض
            // الملفات الاستيريو إلى مونو (مسار النطق أحادي القناة).
            if (numChannels > 1) {
                val mono = downmixStereoToMono(out, dataLen, pool)
                pool.release(out)
                return SystemVoiceProvider.PcmExtract(
                    mono, sampleRate, dataLen / 2
                )
            }
            return SystemVoiceProvider.PcmExtract(out, sampleRate, dataLen)
        }
        if (chunkId == "fmt "
            && chunkSize >= 16
            && offset + 24 <= fileLen
        ) {
            // تنسيق: معدل العيّنات في الموضع 4 من جسم الخانة
            // (وليس 8 الذي يحمل byteRate) — عينات سليمة
            // 14.1k–192k وإلا نحافظ على الاحتياطية.
            raf.seek(offset + 12)
            if (readFully(raf, rateBuf, 0, 4)) {
                val rate = readLeInt(rateBuf, 0)
                if (rate in 14100..192000) sampleRate = rate
            }
            // بند 2.2: عدد القنوات في الموضع 2 من جسم الخانة
            // (بعد audioFormat بايتين) — نقرأه لنخفض الاستيريو.
            raf.seek(offset + 10)
            if (readFully(raf, chanBuf, 0, 2)) {
                val channels = (chanBuf[1].toInt() shl 8) or
                    (chanBuf[0].toInt() and 0xFF)
                if (channels in 1..2) numChannels = channels
            }
        }
        // تقدمٌ حتميٌ موجَّب (chunkSize ≥ 0 دائماً) مع كسرٍ
        // إذا تجاوزت الخانة نهاية الملف (رأس تالف) بدل مواصلة
        // القراءة من مواقع عشوائية.
        val next = offset + 8 + chunkSize
        if (next > fileLen) break
        offset = next
    }
    // لم نعثر على خانة data — نعود لافتراض 44 بايت احتياطاً.
    if (fileLen > 44) {
        val dataLen = (fileLen - 44).toInt()
        val out = readFromPoolOrNull(raf, 44, dataLen, pool)
            ?: return emptyExtract(sampleRate)
        return SystemVoiceProvider.PcmExtract(out, sampleRate, dataLen)
    }
    return emptyExtract(sampleRate)
}

/** وصف رأس WAV للبثّ المجزّأ (بند ب.txt 3.2): موضع بداية بيانات الصوت
 *  ([dataStart]) ومعدل العينات من خانة `fmt ` حين تُعرف، وعددُ القنوات
 *  (بند 2.2) ليُخفض الاستيريو إلى مونو قبل البث. */
internal class WavStreamMeta(
    val dataStart: Long,
    val sampleRateInHz: Int,
    val channelCount: Int = 1
) {
    companion object {
        /** حدّ أعلى/أدنى لمعدل عينات مقبول (يطابق [parseWavChunks]). */
        private const val MIN_RATE = 14_100
        private const val MAX_RATE = 192_000
    }
}

/** يفحص [length] بايتاً من بداية ملف WAV (على نموّه أثناء كتابة المحرك)
 *  ويستخلص موضع خانة `data` ومعدل العينات. يرجع null إذا لم تتضح خانة
 *  الصوت بعد (المحرك ما زال يكتب الرأس) — سيعاد الفحص على الطول المحدَّث،
 *  وإذا لم يتضح أبداً تُترك القراءة للمسار الكامل التقليدي. منطقٌ نقيٌّ
 *  عن Android: قابل للاختبار مباشرة. */
internal fun readWavStreamMeta(
    bytes: ByteArray,
    length: Int
): WavStreamMeta? {
    if (length < 12) return null
    if (bytes[0] != 'R'.code.toByte()
        || bytes[1] != 'I'.code.toByte()
        || bytes[2] != 'F'.code.toByte()
        || bytes[3] != 'F'.code.toByte()
    ) {
        // ليس ملف WAV صالحاً: يتولاه المسار الكامل لاحقاً (لا بثّ مجزّأ).
        return null
    }
    var sampleRate = SystemVoiceProvider.FALLBACK_SAMPLE_RATE
    var channelCount = 1
    var offset = 12L
    while (offset + 8 <= length) {
        val chunkId = String(
            bytes, offset.toInt(), 4, Charsets.US_ASCII
        )
        val chunkSize = readLeLong(bytes, offset.toInt() + 4)
        if (chunkId == "data") {
            return WavStreamMeta(
                offset + 8, sampleRate, channelCount
            )
        }
        if (chunkId == "fmt " && chunkSize >= 16 &&
            offset + 24 <= length
        ) {
            // بند 1.2: sampleRate في +12 (بعد audioFormat/channels
            // بايتان لكلٍّ) لا +16 الذي هو byteRate (2× للـ mono 16bit).
            val rate = readLeIntFrom(bytes, offset.toInt() + 12)
            if (rate in 14_100..192_000) sampleRate = rate
            // بند 2.2: عدد القنوات في +10 (بعد audioFormat بايتين).
            val channels = (bytes[offset.toInt() + 11].toInt() shl 8) or
                (bytes[offset.toInt() + 10].toInt() and 0xFF)
            if (channels in 1..2) channelCount = channels
        }
        val next = offset + 8 + chunkSize
        if (next > length || next <= offset) break
        offset = next
    }
    // خانة data لم تتضح بعد — يُعاد الفحص على النمو اللاحق.
    return null
}

/** قراءة عدد صحيح غير موقّع بطول 4 بايت (0..2^32-1) من مصفوفة. */
private fun readLeLong(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

/** قراءة عدد صحيح موقّع بطول 4 بايت من مصفوفة (little-endian). */
private fun readLeIntFrom(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

/** يقرأ [len] بايت من [start] في مصفوفة من [pool] ويعيدها؛ على فشل القراءة
 *  يعيد المصفوفة إلى المسبح ويعيد null — كانت المواضع الثلاثة تُسقط
 *  المصفوفة عند فشل readFully فيتسرب حملٌ من المسبح الثابت المعمّر مع كل
 *  ملفٍ منكور (تراكم ذاكرة بلا حد). */
private fun readFromPoolOrNull(
    raf: java.io.RandomAccessFile,
    start: Long,
    len: Int,
    pool: BytePool
): ByteArray? {
    val out = pool.acquire(len)
    raf.seek(start)
    if (!readFully(raf, out, 0, len)) {
        pool.release(out)
        return null
    }
    return out
}

/** يقرأ [len] بايت كاملة من [start] — [java.io.RandomAccessFile.read]
 *  قد يُرجع أقل من المطلوب، فنجمع حتى الاكتمال. تُرجع false عند نهاية الملف. */
private fun readFully(
    raf: java.io.RandomAccessFile,
    out: ByteArray,
    start: Int,
    len: Int
): Boolean {
    var read = 0
    while (read < len) {
        val n = raf.read(out, start + read, len - read)
        if (n < 0) return false
        read += n
    }
    return true
}

/** قراءة عدد صحيح صغير التدرج (little-endian) بطول 4 بايت */
private fun readLeInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
