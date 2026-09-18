package com.aymankhattab.nateq.core.audio.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.os.Build
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.core.audio.announcement.InterruptionSensors
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.audio.providers.SystemVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VoiceDescriptor
import com.aymankhattab.nateq.core.audio.providers.VoiceProvider
import com.aymankhattab.nateq.core.common.AppDispatchers
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.TextProcessor
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.LocaleUtils
import dagger.hilt.android.AndroidEntryPoint

import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * يحوّل نسبةَ سرعةِ قارئ الشاشة (100 = طبيعي) إلى معامل (1.0 = طبيعي) —
 * TextToSpeech.setSpeechRate يتوقع 1.0 عند الطبيعي لا النسبةَ المئوية نفسها.
 * أي نسبة غير موجبة (0/سلبية) تُعتبر 100 (طبيعي محايد).
 */
internal fun readerRateFromPercent(reqRate: Float): Float {
    val safe = if (reqRate > 0f) reqRate else 100f
    return safe / 100f
}

/**
 * السرعة النهائية للنطق — نموذج الضرب: سرعةُ القارئ معاملٌ أَساسٌ يُضرب
 * في معامل LORD (تفضيل التطبيق)، ثم يُقصّ على نطاقٍ آمن (0.1..6.0).
 * عند اتباع سرعة القارئ يكون المعامل = 1.0 فيُبقى الخرج = سرعة القارئ.
 */
internal fun computeFinalSpeechRate(
    readerRate: Float,
    nateqMultiplier: Float
): Float = (readerRate * nateqMultiplier).coerceIn(0.1f, 6.0f)

/**
 * ==========================================================
 *  المكوّن الأهم في كل المشروع.
 * ==========================================================
 * هذه الخدمة هي التي يتعامل معها نظام أندرويد وقارئ الشاشة (TalkBack
 * أو غيره) مباشرة عند الحاجة لنطق أي نص. أي منطق هنا لازم يكون:
 *  1) سريع الاستجابة (latency منخفض) قدر الإمكان.
 *  2) لا يُجمّد الخيط الرئيسي (نستخدم Coroutines).
 *  3) يدعم onStop بشكل صحيح لإيقاف النطق فورًا عند طلب المستخدم.
 */
@AndroidEntryPoint
class NateqTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "NATEQ_TTS"

        /** مدة صلاحية ذاكرة اكتشاف اللغات (ساعة، بند 16.3) — لا يُسبر كل محرك
         *  داخل الجلسة الطويلة إلا عند الحاجة؛ الاكتشاف يبقى مضموناً عند كل
         *  إنشاء لعملية :tts التي تُقتل بين الجلسات غالباً. */
        private const val DISCOVERY_TTL_MS = 60 * 60 * 1000L

        /** ترميز PCM 16-bit المستخدم في كل البث (ثابت أندرويد). */
        private const val PCM_16BIT = AudioFormat.ENCODING_PCM_16BIT

        /** معيار البث الموحّد للنص المختلط (44100 مونو 16-bit) — ثابتٌ ليُتاح
         *  التدفق مقطعاً بمقطعٍ دون تجميع كامل الصوت في الذاكرة (الذروة = أكبر
         *  مقطعٍ لا مجمل المدة)، ويحفظ جودةً لا تقل عن
         *  المعيار التاريخي 22050. */
        private const val MIXED_UNIFIED_RATE = 44_100

        /** معدل احتياط لمعدلِ مصدرٍ غير معلوم في المقاطع
         *  المختلطة (22050 = معيار LORD) — يظهر فقط إن
         *  تخلف المزوّد عن إبلاغ معدله قبل الشريحة. */
        private const val MIN_UNIFIED_RATE = 22_050

        /** سقف إجمالي أحرف النص المختلط للمسار المتوازي (بند ب.txt 3.3):
         *  فوقه يبقى التخليق متسلسلاً متدفقاً فلا تجتمع مقاطعُ نصٍّ طويل
         *  صفوفاً في الذاكرة؛ وتحت السقف تُخلَّق المقاطعُ معاً على خيوط
         *  مستقلة ثم تُعرض مرتبةً فيختصر الزمنُ إلى أبطأِ مقطع. */
        private const val PARALLEL_MAX_CHARS = 500

        /** نافذة الدمج السلس بين شريحتَي بثّ (مرحلة 7 — crossfade):
         *  512 عيّنة ≈ 11.6ms عند [MIXED_UNIFIED_RATE] — قصيرةٌ لا تُسمع
         *  كمزجٍ بين صوتين طويلاً، وطويلةٌ بما يكفي لقتل «الكليك» عند
         *  الحَدّ بدل القفزة الحادّة. */
        private const val CROSSFADE_FRAMES = 512
    }

    /** مصدر الإعدادات الفريد لعملية:tts — يحقنه Hilt عبر NateqApplication
     *  (Application يشغّل في كل عملية). يُعاد تحميله من القرص في كل
     *  onSynthesizeText لأن العملية:tts منفصلة عن عملية الواجهة. */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** قاموس النطق الشخصي — سنجلتون Hilt موحَّد مع نسخة الواجهة، ويُرصد طابعه
     *  على القرص عند كل تطبيق (في [PronunciationDictionary.apply]) ليلتقط
     *  التعديلات القادمة من عملية الواجهة المنفصلة عن عملية :tts. */
    @Inject
    lateinit var pronunciationDictionary: PronunciationDictionary

    private val serviceScope = CoroutineScope(
        SupervisorJob() + AppDispatchers.io
    )

    /** منفّذ تخليق أحادي الخيط + نطاق تابع: كل طلب
     *  [onSynthesizeText] يُشغَّل عليه بكوروتينٍ ينتظره [join]
     *  المتزامن في الدالة — فتتسلسل الطلبات ولا يستبقها بعضها كما
     *  كان يحدث (إلغاء الرحلة السابقة قبل بدئها كان يقطع نطق الجمل
     *  الطويلة وتصفح TalkBack للقوائم). */
    private val synthesisExecutor = Executors.newSingleThreadExecutor()
    private val synthesisScope = CoroutineScope(
        SupervisorJob() + synthesisExecutor.asCoroutineDispatcher()
    )

    private lateinit var settings: SettingsRepository
    private lateinit var catalog: VoiceCatalog
    private lateinit var requestHandler: SynthesisRequestHandler
    private lateinit var textProcessor: TextProcessor

    /** مقسم النصوص المختلطة الكتابات (منطق نقي مشترك بلا حالة). */
    private val segmenter = LanguageSegmenter()

    /** مسبح مخازن PCM الخاصة بمسار البث الموحّد (بند تسريع النطق): شريحة
     *  المزوّد تُعاد معاينتها في مخزنٍ من المسبح ويُبث فوراً ثم يُعاد ليسكن
     *  بثَ المقطع التالي — بلا إنشاء مصفوفة لكل شريحة من كل مقطع. آمن لأن
     *  [SynthesisCallback.audioAvailable] يستهلك المخزن قبل عودته (عقد
     *  [SystemVoiceProvider] نفسه مع متلقيه). */
    private val pcmBufferPool = BytePool()

    /** كلماتُ التنقل الشائعة التي يكرّر قارئ الشاشة نطقها عبر الواجهة
     *  (بند ب.txt 3.5-1) — تُخلَّق وتُخزَّن في كاش PCM عند الإقلاع فعلى
     *  بثّها الأول تُخرج من الذاكرة مباشرةً بلا قرص. مجموعتان صغيرتان
     *  (عربية/إنجليزية) فلا يستهلكان الكاش المحدود. */
    private val navigationWordsAr = listOf(
        "نعم", "لا", "فتح", "إلغاء", "التالي", "سابق"
    )
    private val navigationWordsEn = listOf(
        "yes", "no", "open", "cancel", "next", "back"
    )

    /** الرحلة اللاتزامنية للتخليق الحالي — تُلغى عند
     *  إيقاف أو استباق طلبٍ جديد. */
    @Volatile private var currentJob: kotlinx.coroutines.Job? = null

    /** عملية الإقلاع الخلفية (اكتشاف اللغات + تدفئة الكاش، بند ب.txt 3.5-1)
     *  — تُلغى فور ورود أول onSynthesizeText حتى لا تزاحم التخليقَ الحقيقي
     *  على منفّذ الكوروتين وموارد المحركات. */
    @Volatile private var warmupJob: kotlinx.coroutines.Job? = null

    /** حارس تشغيل–واحد لاكتشاف اللغات الخلفي: يمنع تداخل
     *  [maybeRefreshDiscovery] من الإقلاع واستدعاءات القراءة المتزامنة
     *  أن تُنفّذ الاكتشافَ مرتين وتكتبا ذاكرة الكتالوج في وقتٍ واحد. */
    private val discoveryMutex = Mutex()

    /**
     * **بند 6.5:** مستقبل تثبيت/إزالة/تحديث الحزم — عندما يتغير
     * محرك TTS طرفٌ ثالث (تثبيت/إزالة/ترقية محرك نطق) يُبطَل
     * اكتشافُ اللغات الحالي فيُعاد بناؤه عند المطالبة التالية فوراً
     * بدل انتظار انقضاء فترة صلاحية الذاكرة الممتدة (ساعة) — كان
     * المحركُ المثبَّت حديثاً لا يظهر لمدة ساعة كاملة.
     * يُسجَّل ديناميكياً ليشمل الحزم قاطبةً (بث حزم النظام) ولا حاجةَ
     * لإعلانه في المانيفست (بثّ حزم غير مسموح إعلانُه منه على API 26+).
     */
    private val packageEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action == Intent.ACTION_PACKAGE_ADDED ||
                action == Intent.ACTION_PACKAGE_REMOVED ||
                action == Intent.ACTION_PACKAGE_CHANGED
            ) {
                if (::catalog.isInitialized) {
                    catalog.invalidateDiscovery()
                    // إعادة اكتشافٍ خلفية فور تغيّر مكوِّن صناعي: اللغات
                    // الجديدة تصير متاحةً في الاستعلام التالي بلا انتظار.
                    if (action != Intent.ACTION_PACKAGE_CHANGED ||
                        EnginePicker.installedEnginePackages(
                            context
                        ).isNotEmpty()
                    ) {
                        refreshDiscoveryIfNeeded()
                    }
                }
            }
        }
    }

    /** يُميّز سبب إلغاء [currentJob]: إيقاف صريح (onStop) أم استباق
     *  بطلبٍ جديد.
     *  عند الإيقاف لا نُنشئ خطأً زائفاً (النظام يعرف أنه أُوقف عمداً)، وعند
     *  الاستباق نُنهي callback الطلب القديم حتى لا يعلق طابور النظام فينتقل
     *  للطلب الجديد. */
    @Volatile private var stopping = false

    /** **بند 8:** رصد الإسكات الفوري (هز/تقارب) أثناء دورة التخليق في مسار
     *  قارئ الشاشة (TalkBack عبر هذه الخدمة). كان الربط محصوراً في دورة
     *  إعلانات التطبيق (AnnouncementSpeaker) فلا يتوقف نطقُ TalkBack على
     *  الهزّ/التقارب — الآن نفسُ الصف [InterruptionSensors] المفعل بالإعدادات
     *  يرصد الرحلةَ الجارية ويلغيها بأمان. معطّل افتراضياً حتى يُفعِّل
     *  المستخدم أحد المفتاحين في الإعدادات (لا يسجّل شيئاً عند التعطيل). */
    @Volatile private var interruptionSensors: InterruptionSensors? = null

    @Volatile
    private var currentLanguage = arrayOf(LanguageCode.AR.tag, "", "")

    override fun onCreate() {
        // مهم: TextToSpeechService.onCreate() يستدعي
        // onLoadLanguage()/onIsLanguageAvailable() قبل انتهاء
        // استدعاء super.onCreate()، لذلك يجب تهيئة كل
        // الـ lateinit كأول شيء هنا (قبل super.onCreate())
        // وإلا تنهار الخدمة في حلقة على الإنشاء.
        // applicationContext متاح فور إنشاء كائن الخدمة،
        // وإنشاء هذه الكائنات النقية (غير المرتبطة بدورة
        // حياة Android) آمن تماماً في هذا الموضع.
        // عند الاستدعاء من TalkBack/النظام بُني الكائن
        // عبر Hilt (Hilt_...) فيكون settingsRepository
        // محقوناً؛ ونبني بقية الشبكة بعناية قبل super.
        // حماية ثانية: إن فشل الحقن لأي سبب نتراجع لكائن
        // محلي حتى لا تنهار الخدمة قبل super.onCreate()
        // في حلقة (طبّاق لتوقيت TextToSpeechService).
        settings = if (::settingsRepository.isInitialized) settingsRepository
        else SettingsRepository.create(applicationContext)

        val dict = if (::pronunciationDictionary.isInitialized) {
            pronunciationDictionary
        } else {
            PronunciationDictionary(applicationContext)
        }

        val providers = listOf(
            SystemVoiceProvider(applicationContext, settings)
        )
        catalog = VoiceCatalog(providers)
        requestHandler = SynthesisRequestHandler(catalog, settings)
        textProcessor = TextProcessor(applicationContext, settings, dict)
        // سلسلة التراجع لكل لغة تستند إلى ذاكرة اكتشاف الكتالوج
        // (المحركات القادرة على اللغة فعلياً) بدل القائمة العالمية.
        providers.forEach { provider ->
            provider.capableEnginesFor = { tag ->
                catalog.discoveredEnginePackagesFor(tag)
            }
        }

        // تدفئة محركات TTS المثبتة على الخلفية (بند ب.txt 3.4-2): أول ربط
        // TextToSpeech يكلف 150–800ms لدى بعض المحركات — نربطها قبل طلب
        // النطق الأول فيأتي النطقُ على مثيلٍ دافئ من المسبح مباشرةً.
        providers.filterIsInstance<SystemVoiceProvider>()
            .firstOrNull()
            ?.let { system ->
                runCatching {
                    system.prewarmEngines(
                        EnginePicker.installedEnginePackages(
                            applicationContext
                        )
                    )
                }
            }

        // اكتشاف اللغات وتدفئة كاش كلمات التنقل على الخلفية (بند ب.txt
        // 3.5-1): استدعاءٌ واحدٌ مؤمَّن يحفظ حد ar/en المضمون عند أي تعذّر،
        // ثم تُخلَّق كلماتُ التنقل عبر المسار الكامل فيُخزَّن نطقُها مسبقاً
        // ويُبثّ من الذاكرة عند أول مطالبة. يُسند الرابط إلى [warmupJob]
        // ليُلغى فور ورود طلب تخليق حقيقي فلا يُزاحم النطق على المحركات.
        warmupJob = serviceScope.launch {
            try {
                maybeRefreshDiscovery()
                warmNavigationCache()
            } catch (t: Throwable) {
                Log.w(TAG, "اكتشاف اللغات وتدفئة الكاش الخلفي فشلا", t)
            }
        }

        // ملفوف بحمايات حتى لا تنهار الخدمة عند أي خطأ تهيئة — لو انهارت هنا
        // يرفض نظام سامسونج المحرك برسالة "يستمر التطبيق في التوقف".
        try {
            super.onCreate()
        } catch (t: Throwable) {
            Log.e(TAG, "super.onCreate() threw", t)
        }
        // رصد المستشعرات مرة واحدة لعمر الخدمة — لا يبدأ نشطاً إن
        // تعطّل المستخدم المفتاحان، لكنه لا يتوقف بين الرحلات ويعيد
        // تقييم الإعدادات فور بدء أول رحلة.
        startInterruptionMonitoring()

        // **بند 6.5:** تسجيل مستقبل الحزم بعد اكتمال شبكة الخدمة —
        // يستقبل بثّ تثبيت/إزالة/تحديث أي حزمة (محركات TTS أساساً)
        // فيُبطل اكتشافَ اللغات ويعيد بناؤه فور المطالبة التالية.
        // عَلَم RECEIVER_EXPORTED مطلوب (بثّ حزم النظام) على API 33+.
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            this, packageEventsReceiver, packageFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

override fun onDestroy() {
        // إلغاء كل العمليات اللاتزامنية المعلّقة للخدمة حتى لا تتسرب مع عمر
        // عملية المحرك، ثم إغلاق النطق الجاري إن وُجد.
        currentJob?.cancel()
        warmupJob?.cancel()
        serviceScope.cancel()
        synthesisScope.cancel()
        synthesisExecutor.shutdown()
        // إلغاء تسجيل مستشعرات الهز/التقارب كي لا تبقى مرصودةً بعد تدمير
        // الخدمة (كانت تُسجَّل في onCreate ولا تُلغى هنا فتتسرب).
        stopInterruptionMonitoring()
        // إلغاء مستقبل الحزم (بند 6.5) — لا يبقى مستمعاً بعد تدمير
        // الخدمة فيتجمع بثّاتُ تثبيت/إزالة الحزم على كائن مردود.
        runCatching { unregisterReceiver(packageEventsReceiver) }
        // إغلاق موارد المزوّدين (TextToSpeech المربوط بالمحرك الخارجي + مراقب
        // الإنترنت + منفّذ الخلفية) كي لا تبقى روابط Binder IPC معلقة بعد
        // تدمير الخدمة — حارس isInitialized لمسارات التدمير
        // المبكر قبل onCreate.
        if (::catalog.isInitialized) catalog.shutdown()
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(
        lang: String?,
        country: String?,
        variant: String?
    ): Int {
        Log.d(TAG,
            "onIsLanguageAvailable() lang=$lang" +
            " country=$country variant=$variant")
        refreshDiscoveryIfNeeded()
        if (lang == null) return TextToSpeech.LANG_NOT_SUPPORTED

        val normLang = normalizeLanguageCode(lang)
        val normCountry = normalizeCountryCode(country)
        val locales = catalog.supportedLocales()

        // تطابق تام لأي لغة تدعمها الأصوات الفعلية (بعد تطبيع الكود)
        if (locales.any { it.language == normLang }) {
            return if (normCountry != null
                && locales.any {
                    it.language == normLang && it.country == normCountry
                }
            ) {
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> = currentLanguage

    override fun onLoadLanguage(
        lang: String?,
        country: String?,
        variant: String?
    ): Int {
        Log.d(TAG,
            "onLoadLanguage() lang=$lang" +
            " country=$country variant=$variant")
        val normLang = normalizeLanguageCode(lang)
        val normCountry = normalizeCountryCode(country)
        val result = onIsLanguageAvailable(normLang, normCountry, variant)
        if (result != TextToSpeech.LANG_NOT_SUPPORTED) {
            currentLanguage = arrayOf(
                normLang,
                normCountry ?: "",
                variant ?: ""
            )
        }
        return result
    }

    /**
     * يطبّع كود اللغة من ISO-3 (ara, eng) إلى ISO-2 (ar, en).
     * موحّد في [LocaleUtils.normalizeLanguageCode].
     */
    private fun normalizeLanguageCode(code: String?): String =
        LocaleUtils.normalizeLanguageCode(code)

    /**
     * يطبّع كود البلد من ISO-3 (EGY, USA) إلى ISO-2 (EG, US).
     * موحّد في [LocaleUtils.normalizeCountryCode].
     */
    private fun normalizeCountryCode(code: String?): String? =
        LocaleUtils.normalizeCountryCode(code)

    // =====================================================
    //  الأصوات (Voices) — المصدر الوحيد لقائمة
    //  "تعيين الصوت الخاص بلغة النص المنطوق" في إعدادات TTS.
    //  بدون هذه الدوال يُعيد TextToSpeechService الأب قائمة فارغة.
    // =====================================================

    override fun onGetVoices(): MutableList<Voice> {
        Log.d(TAG, "onGetVoices() CALLED")
        refreshDiscoveryIfNeeded()
        val voices = catalog.supportedVoices()
        Log.d(TAG,
            "onGetVoices() returning ${voices.size} voices:" +
            " ${voices.map { it.name }}")
        return voices.toMutableList()
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        Log.d(TAG, "onIsValidVoiceName() voiceName=$voiceName")
        if (voiceName == null) return TextToSpeech.ERROR
        return if (catalog.supportedVoices().any { it.name == voiceName }) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }
    }

    override fun onLoadVoice(voiceName: String?): Int {
        Log.d(TAG, "onLoadVoice() voiceName=$voiceName")
        if (voiceName == null) return TextToSpeech.ERROR
        @Suppress("UNUSED_VARIABLE")
        val voice = catalog.supportedVoices().find {
            it.name == voiceName
        }
            ?: return TextToSpeech.ERROR
        // مستقبلاً: أبلّغ SystemVoiceProvider بالصوت المحمّل حالياً
        return TextToSpeech.SUCCESS
    }

    override fun onGetDefaultVoiceNameFor(
        @Suppress("UNUSED_PARAMETER") lang: String?,
        @Suppress("UNUSED_PARAMETER") country: String?,
        @Suppress("UNUSED_PARAMETER") variant: String?
    ): String? {
        val normLang = normalizeLanguageCode(lang)
        val name = catalog.defaultVoiceNameForLanguage(normLang)
        Log.d(TAG,
            "onGetDefaultVoiceNameFor() lang=$lang" +
            " -> norm=$normLang -> $name")
        return name
    }

    override fun onStop() {
        // إيقاف صريح (يأتي على خيطٍ آخر غير خيط التخليق): نرفع العلم ثم
        // نلغي التخليق الجاري. إلغاءٌ كهذا يُلتقط داخل الكوروتين فيُغلق
        // الـ callback بأمان (بند 2.3) فلا يبقى PlaybackSynthesisCallback
        // وAudioTrack معلقين يطبقون صمت TalkBack حتى إعادة تشغيل الخدمة.
        stopping = true
        currentJob?.cancel()
    }

    /** **بند 8 — رصد الهز/التقارب في دورة تخليق قارئ الشاشة (TalkBack):**
     *  كان ربطُ المستشعرات محصوراً في مسار إعلانات التطبيق
     *  (AnnouncementSpeaker)
     *  فلا يتوقف نطقُ قارئ الشاشة على الهزّ/التقارب مهما فُعّل المفتاحان في
     *  الإعدادات. الآن تُفعَّل المستشعرات نفسها **مرة واحدة لعمر الخدمة**
     *  (عند [onCreate] لا مع كل رحلة — رياضةُ تسجيل/إلغاء لكل طلب كان
     *  تهدر عمر البطارية وتترك نافذة تزاحم). تقرأ الإعدادات المحقونة
     *  ([settings])؛ وإن فُعّل أحدهما صدِّق الرصد واستدعِ [interruptSynthesis]
     *  عند الهزة/التقارب ليُوقف الرحلة الجارية بأمان عبر نفس عقد الإلغاء
     *  (بند 2.3) فينتقل طابور TalkBack بسلاسة. */
    private fun startInterruptionMonitoring() {
        if (interruptionSensors != null) return
        val shake = runCatching { settings.isShakeToStopEnabled() }
            .getOrDefault(false)
        val proximity = runCatching { settings.isProximitySilenceEnabled() }
            .getOrDefault(false)
        if (!shake && !proximity) return
        val sensors = InterruptionSensors(
            shakeEnabled = { shake },
            proximityEnabled = { proximity },
            onInterrupt = { interruptSynthesis() }
        )
        interruptionSensors = sensors
        sensors.start(applicationContext)
    }

    /** إيقاف رصد الهز/التقارب — يُستدعى عند اكتمال الرحلة أو إلغائها أو
     *  إيقاف الخدمة حتى لا يبقى الرصد حياً بعد انتهاء الحاجة إليه. */
    private fun stopInterruptionMonitoring() {
        interruptionSensors?.stop()
        interruptionSensors = null
    }

    /** **بند 8 — المعالجة الفورية لتنبيه المستشعر (هزة/تقارب) أثناء نطق
     *  قارئ الشاشة:** نفس عقد الإيقاف الصريح ([onStop]) — نرفع [stopping]
     *  ثم نلغي الرحلة الجارية؛ الكوروتين يلتقط الإلغاء فيُغلق callback بأمان
     *  (بند 2.3) دون خطأٍ زائف لأن النظام يعرف أنه أُوقف عمداً، ويبقى طابور
     *  TalkBack سليماً ليكمل الطلب التالي. */
    private fun interruptSynthesis() {
        stopping = true
        currentJob?.cancel()
    }

    override fun onSynthesizeText(
        request: SynthesisRequest?,
        callback: SynthesisCallback?
    ) {
        if (request == null || callback == null) return
        // طلبُ تخليقٍ حقيقيّ وصل — ألغِ التدفئة الخلفية الجارية (اكتشاف
        // اللغات/كاش كلمات التنقل) حتى تتفرغ منافذُها وأعمدتُها لهذا
        // النطق ولا تزاحمه على المحركات/المسبح (بند ب.txt 3.5-1).
        warmupJob?.cancel()
        // سجلّ مجرّد: طول النص واللغة فقط
        // (النص قد يحوي OTP/حساسيات يقرؤها TalkBack).
        val reqText = request.charSequenceText?.toString()
        Log.d(TAG,
            "onSynthesizeText() len=${reqText?.length}" +
            " lang=${request.language}")

        // تطبيع لغة الطلب من ISO-3 (eng, ara) إلى ISO-2 (en, ar) حتى يبقى
        // حل الصوت والكتالوج متسقين مع اللغتين المدعومتين
        // (العربية/الإنجليزية).
        val normLanguage = normalizeLanguageCode(request.language)
        val normCountry = normalizeCountryCode(request.country)
        val languageTag = Locale.forLanguageTag(
            if (normCountry.isNullOrEmpty()) {
                normLanguage
            } else {
                "$normLanguage-$normCountry"
            }
        ).toLanguageTag()

        // **الامتثال لمعيار AOSP TextToSpeechService (بند 2.1):**
        // الوثائق الرسمية تفرض أن تنتظر onSynthesizeText اكتمال التوليف قبل
        // العودة (Blocking على خيط التخليق). كان الإطلاق اللاتزامني يعود
        // فوراً فيسحب النظامُ الطلبَ التالي من الطابور (عناصر TalkBack
        // المتتابعة) فتُلغى الرحلة السابقة قبل أن تبدأ — فيتقطع نطق الجمل
        // الطويلة ويسقط نطق العناصر. الآن يُشغَّل التخليق على منفّذٍ أحادي
        // الخيط [synthesisScope] وتنتظره الدالة بـ join متزامن: يبقى النظام
        // محجوزاً حتى اكتمال الطلب فيتسلسل الطابور بلا استباقٍ بمُبكّر،
        // والإيقاف (onStop) وحده يُلغي الرحلة الجارية بأمان. لو علِق المحرك
        // الطرفي تُنهي مهله الداخلية المتكيّفة (1.5–8 ث داخل
        // SystemVoiceProvider) الطلبَ بدل تعليق الخيط بلا سقف.
        stopping = false

        // **بند 8 — رصد الإيقاف الفوري (هز/تقارب) في دورة تخليق قارئ
        //  الشاشة (TalkBack):** كان ربطُ المستشعرات محصوراً في
        //  AnnouncementSpeaker (مسار إعلانات التطبيق) فلا يتوقف نظرُ
        //  TalkBack على هزٍّ/تقاربٍ مهما فُعّل المفتاحان في الإعدادات —
        //  المستشعراتُ تُفعل هنا مع كل طلبٍ تخليقٍ وتُوقَف في finally
        //  فيُسكَت قارئُ الشاشة فوراً بنفس عقدِ الإيقاف الصريح.
        startInterruptionMonitoring()
        // **بند 8 — إغلاق نافذة الاستباق:** تُبنى الرحلة LAZY فلا تبدأ
        //  جدولتُها على خيط التخليق قبل إسنادها إلى [currentJob] (كانت تبدأ
        //  فوراً فيسبق إلغاءٌ من مستشعر/onStop إسنادَها فيُفلت الرحلة)؛
        //  نُشغّلها صريحاً بعد الإسناد مباشرةً فتنغلق النافذة تماماً.
        val job = synthesisScope.launch(start = CoroutineStart.LAZY) {
            try {
                // إعادة تحميل الإعدادات من القرص لأن `:tts`
                // process منفصل عن عملية الإعدادات
                // (SettingsActivity)، وSharedPreferences لا
                // يتشارك عبر العمليات. بدون reload() تبقى
                // القيم القديمة محشوة في الذاكرة.
                settings.reload()
                // **بند 17 — النصوص المختلطة واللغات:**
                // 1) تقسيم النص المختلط الكتابات (عربي/إنجليزي/غيرها)
        //    إلى مقاطع
                //    لغوية يُنطق كلٌّ منها بمحركه وصوته المخصصين وصفوفِ لغته؛
                // 2) حوار اللغات يعرض كل اللغات المكتشفة لا ar/en فقط (البنية
                //    السفلية جاهزة فعلاً للغات غير محدودة)؛
                // 3) غياب صوتٍ للغة يتراجع تلقائياً للصوت الافتراضي للجهاز بدل
                //    قطع النطق كلياً عبر callback.error().
                // **بند 2.2:** charSequenceText قد يكون null (طلبات قديمة/
                // فارغة) فكان toString() المباشر يرمي NPE ويسقط التخليق —
                // الاستدعاء الآمن يرد النص الفارغ بدل الانهيار.
                val rawText = request.charSequenceText?.toString().orEmpty()
                // **بند التقسيم:** المعالجة الدلالية تُطبَّق قبل تقسيم اللغة
                // حتى لا يفصل المقسمُ رمزَ العملة («USD»/«EUR») عن مبلغه
                // فلينقطع «1500 USD» إلى مقطعٍ عربي وآخر إنجليزي؛ ناتجُها
                // كلماتٌ عربية فيُقسَّم المبلغُ كله مقطعاً عربياً واحداً.
                val semanticText = textProcessor.processSemantics(
                    rawText, languageTag
                )
                val segments = segmenter.segment(semanticText, languageTag)
                // **سرعة القارئ (معامل مُوحّد للمسارين):** نسبةُ
                // request.getSpeechRate() المئوية (100 = طبيعي) تتحول هنا مرة
                // واحدة إلى معامل (readerRate) يُمرَّر للمسار الأحادي
                // (synthesizeSingle) والمسارات المختلطة (synthesizeMixed) —
                // فلا يختلف صوتُ النص المختلط عن الأحادي، ولا يرى المسار
                // المختلط request إطلاقاً.
                val reqRate = request.getSpeechRate().toFloat()
                val readerRate = readerRateFromPercent(reqRate)

                if (segments.size == 1) {
                    // **بند 2.5:** حتى النص المفرد تُستعمل لغةُ المقطع
                    // المكتشفة للتوجيه لا لغةُ الطلب الأصلية — كلمةٌ إنجليزية
                    // وحيدة («Settings»، «Cancel») داخل واجهة عربية لم تعد
                    // تُنطق بصوت/محرك العربية أو تتعثّر: تُوجَّه لمحركها
                    // المناسب فوراً.
                    synthesizeSingle(
                        semanticText, segments[0].languageTag,
                        callback, readerRate
                    )
                } else {
                    // نص مختلط الكتابات: نطق كل مقطع بلغته/محركه ثم مزج الصوت
                    // بمعدلٍ موحّد عبر بثٍّ واحد (مونو).
                    synthesizeMixed(segments, readerRate, callback)
                }
            } catch (e: CancellationException) {
                // إبطال صريح: الإيقاف (onStop) معروف للنظام فلا نُطلق خطأً
                // زائفاً، أما الاستباقُ فلم يعد وارداً مع النمط الحاجز (يُبقي
                // النظامُ خيطَ التخليق حتى العودة) ويبقى التحوط للسلامة.
                if (stopping) {
                    // بند 2.4: خروج صامت عند الإيقاف الحقيقي — استدعاء
                    // error() هنا خطأٌ زائف يخرق عقد AOSP (النظام ألغى
                    // الطلب بنفسه عبر onStop فلا ينتظر إخطاراً آخر).
                    Log.d(TAG, "onSynthesizeText cancelled (stop)")
                } else {
                    Log.d(TAG, "onSynthesizeText cancelled (preempt)")
                    // بند 2.3: الاستباق وحده يُغلق الـ callback دائماً
                    // (تحوّط من رمي النظام InterruptedException/عدم تحلّه
                    // عند الإيقاف) حتى لا يعلق طابور النظام بطلبٍ ميت أو
                    // يبقى AudioTrack مفتوحاً.
                    runCatching { callback.error() }
                }
            } catch (e: Exception) {
                runCatching { callback.error() }
            }
        }
        currentJob = job
        job.start()
        try {
            // انتظار متزامن على خيط التخليق حتى اكتمال التوليف (معيار AOSP)؛
            // التقاط Throwable يُبقي الخدمة حية حتى لو قذف التخليق خطأً
            // غير متوقع — لا انهيار لخيط النظام إطلاقاً.
            runBlocking { job.join() }
        } catch (t: Throwable) {
            Log.w(TAG, "onSynthesizeText join interrupted", t)
        } finally {
            currentJob = null
            // **بند 8:** ختام الرحلة — نوقف رصدَ الهز/التقارب فور انتهاء
            // (أو إلغاء/خطأ) التخليق حتى لا يبقى الرصدُ حياً بعد انتهاء
            // الحاجة إليه (لا تسريب طاقةٍ ولا مستشعرٍ عالق).
            stopInterruptionMonitoring()
        }
    }

    /** حل الصوت للغةٍ معيّنة مع التراجع التلقائي (بند 17.3): صوت الكتالوج
     *  المفضّل للغة إن وُجد، وإلا صوتٌ افتراضي للمزود النظامي (محرك الجهاز
     *  الافتراضي + لغة الطلب) حتى لا يُقطع النطق عند غياب صوتٍ مخصص. الصوت
     *  المُرجَع دائماً موجود (fallback يضمنه)؛ المزود وحده قد يكون غائباً
     *  عند فساد الكتالوج فيُعالَج في مواقع الاستدعاء. */
    private suspend fun resolveVoiceWithFallback(
        languageTag: String
    ): Pair<VoiceDescriptor, VoiceProvider?> {
        val voice = requestHandler.resolveVoiceForLocale(languageTag)
        if (voice != null) {
            return voice to catalog.findProvider(voice.providerId)
        }
        Log.w(TAG,
            "resolveVoiceWithFallback: لا صوت بالكتالوج" +
            " للغة $languageTag — تراجع لصوت الجهاز" +
            " الافتراضي")
        val fallbackVoice = VoiceDescriptor(
            id = "",
            providerId = SystemVoiceProvider.SYSTEM_PROVIDER_ID,
            displayName = "Device Default",
            locale = Locale.forLanguageTag(languageTag)
        )
        return fallbackVoice to catalog.findProvider(
            SystemVoiceProvider.SYSTEM_PROVIDER_ID
        )
    }

    /** المسار الأحادي (نص بلغةٍ واحدة) — نفس التدفق التفصيلي السابق حرفياً:
     *  حل الصوت (مع تراجع الجهاز الافتراضي)، التحويل التلقائي، أشرطة اللغة، ثم
     *  تخليق وبث مباشر عبر المزوّد بتقسيم المخزن المؤقت المُثبَت. */
    private suspend fun synthesizeSingle(
        rawText: String,
        languageTag: String,
        callback: SynthesisCallback,
        readerRate: Float
    ) {
        val (voice, foundProvider) = resolveVoiceWithFallback(languageTag)
        val provider = foundProvider ?: run {
            Log.e(TAG,
            "synthesizeSingle لا مزود متاح إطلاقاً:" +
            " lang=$languageTag")
            callback.error()
            return
        }
        val autoConvert = requestHandler.isAutoConvertEnabled()
        val convertTarget = resolveConvertTarget(languageTag)
        // **سرعة النطق — نموذج الضرب (readerRate أساس، وLORD معامل):**
        // كان النموذج القديم استبدالاً (إما سرعة LORD أو نسبة القارئ)، وكان
        // float القارئ يُمرَّر حرفياً إلى TextToSpeech.setSpeechRate الذي
        // يتوقع 1.0 = طبيعي فتُنطق القيمة المئوية (150 → 150×) سخيفة.
        // الآن: سرعة القارئ النسبةُ المئوية (100 = طبيعي) تتحول إلى معامل
        // (readerRate)، وتُضرب في معامل LORD —
        //   finalRate = (readerRate * nateqMultiplier).coerceIn(0.1f, 6.0f)
        // - عند تفعيل «اتبع سرعة القارئ» (افتراضياً) مضاعف LORD = 1.0
        //   مهما كانت التفضيلات المحفوظة، فيُنطق كما ضبطه النظام حرفياً.
        // - عند تعطيله يُضرب تفضيل التحويل الصريح لهذه اللغة أولاً، ثم
        //   التفضيل الصريح، ثم السرعة العامة فعلاً فوق سرعة القارئ.
        val explicitLordRate =
            requestHandler.getExplicitLanguageRate(languageTag)
        val lordRate = requestHandler.getSpeechRate(languageTag)
        val nateqMultiplier: Float =
            if (settings.isFollowReaderRateEnabled()) {
                1.0f
            } else {
                convertTarget?.convertRate
                    ?: explicitLordRate ?: lordRate ?: 1.0f
            }
        val speechRate = computeFinalSpeechRate(readerRate, nateqMultiplier)
        val pitch = requestHandler.getPitch(languageTag)
        val volume = requestHandler.getVolume(languageTag)

        // عند التفعيل نغلب إعدادات التحويل (السرعة/النبرة/الصوت) ونتجاهل
        // صوت كتالوج LORD ضمنياً — نقدّم للمزوّد محركاً ولغةً محددين.
        if (convertTarget != null) {
            Log.d(TAG,
                "synthesizeSingle AUTO-CONVERT lang=$languageTag" +
                " engine=${convertTarget.convertEngine}" +
                " loc=${convertTarget.convertLocale}" +
                " rate=${convertTarget.convertRate}")
        }
        Log.d(TAG,
            "synthesizeSingle lang=$languageTag" +
            " lordRate=$lordRate readerRate=$readerRate" +
            " usedRate=$speechRate voice=${voice.id}" +
            " provider=${provider.providerId}" +
            " autoConvert=$autoConvert")

        // Process text through TextProcessor
        // (numbers, dates, currencies, etc.)
        val processedText = textProcessor.process(rawText, languageTag)

        // **توجيه locale حسب لغة النص:** engine/locale من التحويل لا يُمرَّران
        // إلا إذا كانت لغة الهدف تطابق لغة النص الفعلية (المقطع) لا لغة
        // الطلب العامة (غالباً لغة النظام). هذا يمنع إعادة توجيه الكلمة
        // الإنجليزية المفردة في واجهة عربية إلى محرك/لغة عربية (locale=ar)
        // وبالعكس، مع بقاء أشرطة السرعة/النبرة/الصوت تُطبّق دائماً على
        // النص نفسه.
        val convertLang = convertTarget?.convertLocale?.language
        val normLanguage = normalizeLanguageCode(languageTag)
        val matchesRequest = convertLang == null
            || normLanguage == convertLang
            || (normLanguage == LanguageCode.AR.tag
            && convertLang == "ara")
            || (normLanguage == LanguageCode.EN.tag
            && convertLang == "eng")

        val finalRate = speechRate
        val finalPitch = convertTarget?.let { it.convertPitch } ?: pitch
        val finalVolume = convertTarget?.let { it.convertVolume } ?: volume
        // توجيه المحرك/الصوت: يفضّل هدف التحويل المطابق، وإلا تفضيل لغة النص
        // نفسه (سارٍ دائماً بلا ربط بحالة «التحويل التلقائي»).
        val routed = LanguageSpeechRouter.route(
            matchesRequest = matchesRequest,
            convertEngine = convertTarget?.convertEngine,
            convertVoiceName = convertTarget?.convertVoiceName,
            perLanguageEngine = settings.getEngineForLanguage(languageTag),
            perLanguageVoiceName = settings.getVoiceForLanguage(languageTag)
        )
        val finalEngine = routed.engine
        val finalLocale = if (matchesRequest) {
            convertTarget?.let { it.convertLocale }
        } else {
            null
        }
        val finalVoiceName = routed.voiceName

        // تخليق الصوت الفعلي عبر المزوّد. يُبلّغنا التنسيق
        // (معدل عينات/قنوات) قبل أول شريحة، فنبدأ
        // callback.start بالقيم الفعلية بدل 22050 الثابتة
        // التي كانت تجعل Android يشغّل ملفات 24k/44.1k
        // بسرعة ونبرة خاطئتين.
        var started = false
        provider.synthesize(
            processedText, voice, finalRate, finalPitch, finalVolume,
            { sampleRateInHz, channelCount ->
                if (!started) {
                    callback.start(
                        /* sampleRateInHz = */ sampleRateInHz,
                        /* audioFormat = */ PCM_16BIT,
                        /* channelCount = */ channelCount
                    )
                    started = true
                }
            }, { chunk, validLength ->
                // ضمانة: إن لم يبلّغ المزوّد بالتنسيق مطلقاً نبدأ بالقيم
                // الافتراضية قبل أول بايت حتى يبقى التخليق صالحاً دائماً.
                if (!started) {
                    callback.start(
                        /* sampleRateInHz = */ 22050,
                        /* audioFormat = */ PCM_16BIT,
                        /* channelCount = */ 1
                    )
                    started = true
                }
                // المنهج المُثبَت (كما في TtsService الرسمي
                // لـ espeak-ng/MultiTTS): لا يجوز تمرير كامل
                // المخزن المؤقت دفعةً واحدة؛ يُقسَّم إلى أجزاء
                // بمقدار callback.getMaxBufferSize() وإلا يرفض
                // النظام التخليق ويهبط الصوت. نقسّم كل دفعة من
                // المزوّد احتراماً لقيود الـ callback.
            // المعامل الثاني (validLength) هو طول
                // البيانات الصالح الصريح — فقد تكون مصفوفة
                // الشريحة بحجم أكبر من بياناتها الفعلية (مسبح
                // مُعاد استخدامه)، فيُمسح حتى length فقط.
                val maxBytes = callback.maxBufferSize
                var offset = 0
                while (offset < validLength) {
                    val bytesToWrite = minOf(maxBytes, validLength - offset)
                    callback.audioAvailable(chunk, offset, bytesToWrite)
                    offset += bytesToWrite
                }
        }, finalEngine, finalLocale, finalVoiceName)

        // **ضمانة انهيار:** done() قبل start() ترمي
        // IllegalStateException في إطار أندرويد — إن فشل
        // المحرك بصمت (لا تنسيق ولا شريحة) يبقى started=false
        // فنُنهي بـ error() لا بـ done(). والاستثناءات الرامية
        // قبل هذا الموضع تصل إلى catch في onSynthesizeText
        // (إنهاءٌ واحد error() بلا ازدواج).
        if (started) {
            callback.done()
        } else {
            callback.error()
        }
    }

    /** وصفُ مقطعٍ محلول جاهز للتخليق (بعد تجهيز النص وحل الصوت والتحويل
     *  والتوجيه) — مشترك بين المسارين المتسلسل والمتوازي لتُبنى أشرطةُ
     *  الصوت ومفاتيحُ الكاش نفسها. */
    private class SegmentParams(
        val text: String,
        val voice: VoiceDescriptor,
        val provider: VoiceProvider,
        val speechRate: Float,
        val pitch: Float,
        val volume: Float,
        val engine: String?,
        val locale: Locale?,
        val voiceName: String?
    )

    /** صوتُ مقطعٍ واحد مخلَّق (شريحاته PCM الخام بلا معاينة) — حصيلة
     *  [synthesizeSegmentRaw] للمسار المتوازي. */
    private class SegmentAudio(
        val nativeRate: Int,
        val nativeChannels: Int,
        val chunks: List<ByteArray>
    )

    /** يحلّ مقطعاً لغوياً كاملاً: تجهيز النص بدليل لغته، حل الصوت مع سقوط
     *  الجهاز الافتراضي، خلاصة أشرطة التحويل (السرعة/النبرة/الصوت)، ثم توجيه
     *  المحرك/اللغة عبر [LanguageSpeechRouter] — خريطةٌ واحدة يعتمدها المساران
     *  المتسلسل والمتوازي فتتطابق أصواتُ النطق ومفاتيحُ الكاش بينهما.
     *  يرجع null عند غياب مزودٍ للمقطع (يُسقَط وحده كدأب المسار المختلط). */
    private suspend fun resolveSegmentParams(
        segment: Segment,
        readerRate: Float
    ): SegmentParams? {
        val segTag = segment.languageTag
        val processed = textProcessor.process(segment.text, segTag)
        val (voice, foundProvider) = resolveVoiceWithFallback(segTag)
        val provider = foundProvider
        if (provider == null) {
            Log.w(TAG,
            "synthesizeMixed: لا مزود لمقطع $segTag —" +
            " يُسقط وحده: ${segment.text}")
            return null
        }
        val segRate = requestHandler.getSpeechRate(segTag)
        val segPitch = requestHandler.getPitch(segTag)
        val segVolume = requestHandler.getVolume(segTag)
        val convert = resolveConvertTarget(segTag)
        val segLang = segTag.takeWhile { it.isLetter() }
        val convertLang = convert?.convertLocale?.language
        val matches = convertLang == null || segLang == convertLang
        // **سرعة النطق في المسار المختلط — نموذج الضرب مطابق للمسار الأحادي:**
        // readerRate (معامل القارئ المنقول كوسيط من onSynthesizeText) أساسٌ
        // يُضرب في معامل LORD؛ وعند تفعيل «اتبع سرعة القارئ» المعامل = 1.0.
        // (لم يكن المسار المختلط يرى request إطلاقاً فتُنطق مقاطعه دائماً
        //  بسرعة LORD المطلقة ويُخالف المسار الأحادي — كان عيباً متوارثاً.)
        val nateqMultiplier: Float =
            if (settings.isFollowReaderRateEnabled()) {
                1.0f
            } else {
                convert?.convertRate ?: segRate ?: 1.0f
            }
        val finalRate = computeFinalSpeechRate(readerRate, nateqMultiplier)
        val finalPitch = convert?.convertPitch ?: segPitch
        val finalVolume = convert?.convertVolume ?: segVolume
        val routed = LanguageSpeechRouter.route(
            matchesRequest = matches,
            convertEngine = convert?.convertEngine,
            convertVoiceName = convert?.convertVoiceName,
            perLanguageEngine = settings.getEngineForLanguage(segTag),
            perLanguageVoiceName = settings.getVoiceForLanguage(segTag)
        )
        return SegmentParams(
            processed, voice, provider, finalRate, finalPitch,
            finalVolume, routed.engine,
            if (matches) convert?.convertLocale else null,
            routed.voiceName
        )
    }

    /** يخلّق مقطعاً واحداً ويجمع شريحاته الخام (نسخٌ مستقلة لأن المزوّد يعيد
     *  كل شريحةٍ لمسبحه بعد ندائها) — خطوةُ العمل الموازي في
     *  [synthesizeParallel]. مقطعٌ يعجز محركُه يُسقط وحده (null). */
    private suspend fun synthesizeSegmentRaw(
        segment: Segment,
        readerRate: Float
    ): SegmentAudio? {
        val params = resolveSegmentParams(segment, readerRate)
            ?: return null
        val chunks = ArrayList<ByteArray>()
        var nativeRate = 0
        var nativeChannels = 1
        try {
            params.provider.synthesize(
                params.text,
                params.voice,
                params.speechRate,
                params.pitch,
                params.volume,
                { sampleRateInHz, channelCount ->
                    nativeRate = sampleRateInHz
                    nativeChannels = channelCount
                },
                { chunk, validLength ->
                    if (validLength > 0) {
                        chunks.add(chunk.copyOf(validLength))
                    }
                },
                params.engine,
                params.locale,
                params.voiceName
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG,
            "synthesizeMixed: مقطع ${segment.languageTag}" +
            " فشل تخليقه — يُسقط وحده", t)
            return null
        }
        return SegmentAudio(nativeRate, nativeChannels, chunks)
    }

    /**
     * يعيد معاينة شريحةِ مقطعٍ محلي إلى المعيار الموحّد ويبثّها بالترتيب —
     *  الشيفرة المشتركة بين المسارين المتسلسل والمتوازي. المخزنُ من المسبح
     *  يُردّ في finally على كل المصائر (المتلقي ينسخ الشريحة عبر
     *  audioAvailable).
     *
     *  [phase] يحمل الطورَ الكسري لإعادة العينات عبر دفعاتِ المقطع نفسه
     *  (بند 2.7): نسخة [PcmResampler.convertInto] الحاملة للطور تكون
     *  مستمرةً فلا تتقبّط عند حدود الدفعة، ويُمرّر المتصل [phase] نفسَه
     *  لكلَّ دفعاتِ مقطعٍ واحدٍ (LongArray(2) طازج لكل مقطعٍ بعد ضبط
     *  معدله وقنواته) فيبقى الاتساقُ موكولاً إليه.
     *
     *  [crossfader] يدمج رأسَ كل شريحة مع ذيل سابقتها بانحدارٍ خطي (مرحلة 7):
     *  بلا قفزاتٍ حادّة يُسمع لها «كليك» عند حدود دفعاتِ البثّ — كلُّ مقاطعِ
     *  النطق الواحد تشترك في كائنٍ واحد (ترانزيتيون سلس لا انقطاعٌ مجزَّأ)
     *  ويبثّ ذيلُه المتبقي عند النهاية عبر [emitCrossfadeTail].
     *
     *  @return عدد البايتات المدمجة (قبل بثّ الذيل النهائي) — للفحص الآلي.
     */
    private fun emitMixedChunk(
        chunk: ByteArray,
        validLength: Int,
        nativeRate: Int,
        nativeChannels: Int,
        phase: LongArray,
        crossfader: ChunkCrossfader,
        maxBytes: Int,
        started: () -> Boolean,
        markStarted: () -> Unit,
        callback: SynthesisCallback
    ): Int {
        val rate = if (nativeRate > 0) nativeRate else MIN_UNIFIED_RATE
        val required = PcmResampler.convertedByteCount(
            chunk, 0, validLength, rate,
            nativeChannels, MIXED_UNIFIED_RATE
        )
        if (required <= 0) return 0
        // +2 بايت: النافذةُ المستمرة قد تُخرج فريماً زائداً عن التقدير
        // الطازج (نصفَ فريمٍ متبقياً عبر حدود الدفعة) فلا يُقصّ صوتٌ
        // عند كل فتحة بين دفعتين.
        val mono = pcmBufferPool.acquire(required + 2)
        try {
            val written = PcmResampler.convertInto(
                chunk, 0, validLength, rate,
                nativeChannels, MIXED_UNIFIED_RATE, mono, 0, phase
            )
            if (written <= 0) return 0
            val emitLen = crossfader.process(mono, written)
            if (emitLen <= 0) return 0
            if (!started()) {
                callback.start(
                    /* sampleRateInHz = */ MIXED_UNIFIED_RATE,
                    /* audioFormat = */ PCM_16BIT,
                    /* channelCount = */ 1
                )
                markStarted()
            }
            var offset = 0
            while (offset < emitLen) {
                val bytesToWrite = minOf(maxBytes, emitLen - offset)
                callback.audioAvailable(mono, offset, bytesToWrite)
                offset += bytesToWrite
            }
            return emitLen
        } finally {
            pcmBufferPool.release(mono)
        }
    }

    /** يبثّ الذيلَ المحجوز من [crossfader] (عيّنات الشريحة الأخيرة التي
     *  احتجزها الدمج) عند نهاية النطق فيصل الصوتُ تماماً بلا اقتطاع. */
    private fun emitCrossfadeTail(
        crossfader: ChunkCrossfader,
        maxBytes: Int,
        started: () -> Boolean,
        markStarted: () -> Unit,
        callback: SynthesisCallback
    ) {
        val frames = crossfader.pendingFrames
        if (frames <= 0) return
        val out = pcmBufferPool.acquire(frames * 2)
        try {
            crossfader.copyPending(out, 0)
            if (!started()) {
                callback.start(
                    /* sampleRateInHz = */ MIXED_UNIFIED_RATE,
                    /* audioFormat = */ PCM_16BIT,
                    /* channelCount = */ 1
                )
                markStarted()
            }
            var offset = 0
            while (offset < frames * 2) {
                val bytesToWrite = minOf(maxBytes, frames * 2 - offset)
                callback.audioAvailable(out, offset, bytesToWrite)
                offset += bytesToWrite
            }
        } finally {
            pcmBufferPool.release(out)
        }
    }

    /**
     * المسار المتسلسل المتدفق (النص الطويل أو المقطع المفرد): يُخلَّق كل
     * مقطعٍ وتُبثّ شريحتُه بالترتيب فور إنتاجها — الذروة = أكبر مقطعٍ وحده
     * لا مجموع النص — مع إبقاء التعامل مع المخازن كما كان حرفياً (بند 2.4:
     * إرجاع المخزن حتمياً). مقطعٌ يعجز محركُه يُسقط وحده ويُكمل البقية.
     */
    private suspend fun synthesizeMixedSequential(
        segments: List<Segment>,
        readerRate: Float,
        callback: SynthesisCallback
    ) {
        var started = false
        val maxBytes = callback.maxBufferSize
        val crossfader = ChunkCrossfader(CROSSFADE_FRAMES)
        for (segment in segments) {
            val params = resolveSegmentParams(segment, readerRate)
                ?: continue
            var nativeRate = 0
            var nativeChannels = 1
            val phase = LongArray(2)
            try {
                params.provider.synthesize(
                    params.text,
                    params.voice,
                    params.speechRate,
                    params.pitch,
                    params.volume,
                    { sampleRateInHz, channelCount ->
                        nativeRate = sampleRateInHz
                        nativeChannels = channelCount
                    },
                    { chunk, validLength ->
                        emitMixedChunk(
                            chunk, validLength, nativeRate,
                            nativeChannels, phase, crossfader, maxBytes,
                            { started }, { started = true }, callback
                        )
                    },
                    params.engine,
                    params.locale,
                    params.voiceName
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG,
                "synthesizeMixed: مقطع ${segment.languageTag}" +
                " فشل تخليقه — يُسقط وحده", t)
            }
        }
        emitCrossfadeTail(
            crossfader, maxBytes, { started },
            { started = true }, callback
        )
        if (!started) {
            callback.error()
            return
        }
        callback.done()
    }

    /**
     * المسار المتوازي للنص المختلط القصير (بند ب.txt 3.3): تُخلَّق مقاطعُ
     * النص في آنٍ واحد على خيوط مستقلة (المزوّدُ بقادرٍ على أكثر من لغة
     * يخلّق كلَّ لغةٍ ضمن محركها ومثيلها — وجلساتُ المثيل الواحد تبقى
     * متتاليةً بقفله فيأمن ناطقٌ واحد) ثم تُعرض النتائجُ مرتبةً بالترتيب
     * بمعدلٍ موحّد فيختصر الزمنُ الكلي إلى أبطأِ مقطع بدل المجموع. الحارسُ
     * في [synthesizeMixed] يُبقي النصوصَ الطويلة على المتسلسل فلا تتجمع
     * مقاطعُها في الذاكرة على دفعة.
     */
    private suspend fun synthesizeParallel(
        segments: List<Segment>,
        readerRate: Float,
        callback: SynthesisCallback
    ) {
        val audios = coroutineScope {
            segments.map { segment ->
                async(AppDispatchers.io) {
                    synthesizeSegmentRaw(segment, readerRate)
                }
            }.map { it.await() }
        }
        var started = false
        val maxBytes = callback.maxBufferSize
        val crossfader = ChunkCrossfader(CROSSFADE_FRAMES)
        for (audio in audios) {
            if (audio == null) continue
            val phase = LongArray(2)
            for (chunk in audio.chunks) {
                emitMixedChunk(
                    chunk, chunk.size,
                    audio.nativeRate, audio.nativeChannels,
                    phase, crossfader, maxBytes,
                    { started }, { started = true }, callback
                )
            }
        }
        emitCrossfadeTail(
            crossfader, maxBytes, { started },
            { started = true }, callback
        )
        if (!started) {
            callback.error()
            return
        }
        callback.done()
    }

    /** النص المختلط الكتابات: لكل مقطعٍ لغوي يُعالَج النص بدليل لغته (العربية
     *  بقنواتها الكاملة وسواها بالتنظيف فقط)، ويُحل صوت المقطع من
     *  كتالوجه أو من
     *  تراجع الجهاز الافتراضي، ويُخلَّق بلغته ومحركِه — ثم تُعاد عينات كل مقطع
     *  فور إنتاجه إلى معيارٍ صوتي موحّد ثابت
     *  ([MIXED_UNIFIED_RATE]، مونو) وتُدفع
     *  للـ callback مقطعاً مقطعاً بلا تجميع صوت المقرّأ كاملاً في الذاكرة.
     *
     * ## لماذا معيار ثابت بدل "المعدل الأعلى" كما كان؟
     * المعيار القديم جمّع أولاً كل عينات المقاطع (بما يوازي كامل مدة النص) في
     * الذاكرة ليعرف أعلى معدل ثم أعاد المعاينة وبثّ — فقراءة مقالٍ طويل متعدد
     * اللغات قد تستهلك عشرات الميغابايت وتُسقط عملية :tts بـ OOM. مع المعيار
     * الثابت يصح التدفق: كل مقطع يُعاد معاينته لحظ إنتاجه ويُدفع فوراً، فيبقى
     * الذروة = أكبر مقطعٍ وحده لا مجمل النص (و44100 أعلى من المعيار التاريخي
     * 22050 فلا يُخسر صوت — الخفض إلى أقل مستوى كان افتراضَ الدفع الأصلي).
     *
     * ## متوازٍ أم متسلسل؟
     * النصُّ المختلط القصير (تحت [PARALLEL_MAX_CHARS]) يُخلَّق مقاطعه معاً
     * على خيوط مستقلة ثم تُعرض مرتبةً (تسريع الزمن الكلي)، والطويل يبقى
     * متسلسلاً متدفقاً فلا تتجمع مقاطعه في الذاكرة.
     *
     * مقطعٌ يعجز محركُه عن التخليق يُسقط وحده (يُسجَّل ويُكمل البقية) بدل قطع
     * النطق كلياً؛ وإن فشل الكل تُرك callback.error() كملاذٍ أخير. */
    private suspend fun synthesizeMixed(
        segments: List<Segment>,
        readerRate: Float,
        callback: SynthesisCallback
    ) {
        val totalChars = segments.sumOf { it.text.length }
        if (segments.size > 1 && totalChars <= PARALLEL_MAX_CHARS) {
            synthesizeParallel(segments, readerRate, callback)
        } else {
            synthesizeMixedSequential(segments, readerRate, callback)
        }
    }

    /** تدفئة كاش PCM بكلمات التنقل الشائعة لكل لغة مدعومة (بند ب.txt 3.5-1):
     *  تشغيلُها عبر الوجهة الكاملة (محرك/أشرطة/صوت) يمنح الكاشَ ذات المفاتيح
     *  التي سيبحثها النطقُ اللاحق فيُبثّ منها مباشرةً بلا قرص. لا يُؤخّر نطقاً
     *  أبداً (خيط خلفية بلا حاجز)، وأي فشلٍ في كلمة يُسقطها وحدها، واللغةُ
     *  بلا صوتٍ تُتجاوز صامتةً — والمجموعتان قصيرتان فلا يكدّسان الكاش
     *  المحدود. */
    private suspend fun warmNavigationCache() {
        val languages = listOf(LanguageCode.AR.tag, LanguageCode.EN.tag)
        for (lang in languages) {
            val words = if (lang == LanguageCode.AR.tag) {
                navigationWordsAr
            } else {
                navigationWordsEn
            }
            val probe = Segment(words.firstOrNull() ?: "نعم", lang)
            // تدفئة الكاش بسرعة القارئ الاعتيادية (1.0 = طبيعي): مفاتيح
            // الكاش تتبع السرعة، والكلمات الدفاعية تُنطق غالباً بالسرعة
            // الافتراضية للنظام — والباقي يتولد عن الطلب نفسه.
            val params = resolveSegmentParams(probe, 1.0f) ?: continue
            for (word in words) {
                try {
                    params.provider.synthesize(
                        word,
                        params.voice,
                        params.speechRate,
                        params.pitch,
                        params.volume,
                        { _, _ -> },
                        { _, _ -> },
                        params.engine,
                        params.locale,
                        params.voiceName
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.d(TAG,
                        "warmNavigationCache: كلمة $word فشلت" +
                        " — تُترك بلا كاش", t)
                }
            }
        }
    }

    /**
     * يكتشف اللغات عبر كل المحركات إن انقضت مدة صلاحية الذاكرة أو لم تُبنَ
     * بعد. يعمل في الخلفية دائماً ([AppDispatchers.io])
     * ولا يرمي؛ تعثّر الاكتشاف يُبقي الحد الأدنى
     * ar/en مضموناً في القوائم.
     */
    private suspend fun maybeRefreshDiscovery() {
        discoveryMutex.withLock {
            if (!catalog.needsRefresh(DISCOVERY_TTL_MS)) return
            val discovered = runCatching {
                VoiceCatalog.discoverAllLanguagesAcrossEngines(
                    applicationContext
                )
            }.getOrDefault(emptyMap())
            catalog.applyDiscovery(discovered)
            Log.d(TAG,
                "maybeRefreshDiscovery: ${discovered.size} لغة" +
                " عبر كل المحركات المثبتة")
        }
    }

    /** إطلاق تحديث الاكتشاف دون انتظار
     * (يُستدعى من دوال الاستعلام المتزامنة). */
    private fun refreshDiscoveryIfNeeded() {
        if (!catalog.needsRefresh(DISCOVERY_TTL_MS)) return
        serviceScope.launch {
            try {
                maybeRefreshDiscovery()
            } catch (t: Throwable) {
                Log.w(TAG, "تحديث اللغات الخلفي فشل", t)
            }
        }
    }

    /**
     * يُحدّد هدف التحويل للطلب الحالي.
     *
     * الأشرطة (سرعة/نبرة/صوت) داخل حوار اللغة تُطبَّق دائماً بغضّ النظر عن
     * حالة checkbox «التحويل التلقائي»؛ لأن المستخدم قد يعدّل شريطاً يتوقع
     * أن يسمع الفرق فوراً.
     *
     * التبديل التلقائي للمحرك/الصوت (engine) فقط هو ما يتطلب تفعيل checkbox.
     * القراءة مباشرة من الخريطة الديناميكية (getEnginePreferenceForLanguage)
     * بلا أي افتراض ضمني: كل لغة تُقرأ بمفتاحها الموحّد، واللغة بلا إعداد
     * (أو بلا تحويل فعلي) تُرجع null ولا يُتلاعب بنصها.
     */
    internal fun resolveConvertTarget(requestLang: String?): ConvertTarget? {
        val normLang = normalizeLanguageCode(requestLang)
        val prefs = settings.getEnginePreferenceForLanguage(
            normLang ?: "und"
        )

        val rate = prefs.rate
        val pitch = prefs.pitch
        val volume = prefs.volume

        // المحرك/الصوت فقط يتطلبان تفعيل التحويل التلقائي.
        val autoConvert = settings.isAutoConvertEnabled()
        val engine = if (autoConvert) prefs.engine else null
        val voiceName = if (autoConvert) prefs.voiceName else null

        // إن لم يُعدّل المستخدم أي شريط ولا يوجد محرك
        // مختار → نعتمد الإعدادات العامة.
if (rate == 1.0f && pitch == 1.0f && volume == 1.0f
            && engine == null && voiceName == null
        ) return null

        // **بند 2.7:** كانت الوجهة (locale) مضبوطة على null دائماً فتُترك
        // لغةُ صوت التحويل لمحركِه الافتراضية لا للغة النص الطالبة، وتفشل
        // حراسة matchesRequest في التعرف على التطابق (ترجع true دائماً).
        // يبني الآن Localeً فعلياً من لغة الطلب المطبَّعة (ISO-2) عبر المسار
        // الطبيعي: يُركَّز صوتُ التحويل على لغة النص نفسها وتعمل الحراسة.
        val locale = if (normLang.isNullOrEmpty()) {
            null
        } else {
            Locale.forLanguageTag(normLang)
        }

        return ConvertTarget(
            convertEngine = engine,
            convertLocale = locale,
            convertRate = rate,
            convertPitch = pitch,
            convertVolume = volume,
            convertVoiceName = voiceName
        )
    }

    /** بيانات هدف التحويل التلقائي المرفوعة إلى [SystemVoiceProvider]. */
    data class ConvertTarget(
        // المحرك/الصوت null عند عدم تفعيل التبديل (يتزامن مع المحدد يدوياً).
        // الوجهة (locale) هي لغة النص الطالبة نفسها (بند 2.7) — تُمرَّر
        // للمزوّد ليُركّز صوت التحويل عليها، وتُستعمل في حراسة matchesRequest
        // بشرط أن تطابق لغة النص الفعلية (منع إعادة توجيه النص الإنجليزي
        // إلى محرك/لغة عربية وبالعكس).
        val convertEngine: String?,
        val convertLocale: Locale?,
        val convertRate: Float,
        val convertPitch: Float,
        val convertVolume: Float,
        // اسم الصوت المختار داخل المحرك (اختياري — يُطبَّق إن وُجد بالمحرك).
        val convertVoiceName: String?
    )
}

