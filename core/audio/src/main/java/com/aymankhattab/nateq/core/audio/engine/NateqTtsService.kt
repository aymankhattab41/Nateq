package com.aymankhattab.nateq.core.audio.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.os.Build
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.core.audio.accessibility.A11yVoiceResolver
import com.aymankhattab.nateq.core.audio.accessibility.AccessibilityBridge
import com.aymankhattab.nateq.core.audio.accessibility.FeatureFlags
import com.aymankhattab.nateq.core.audio.accessibility.RepeatSpellController
import com.aymankhattab.nateq.core.audio.accessibility.SelectorPacks
import com.aymankhattab.nateq.core.audio.announcement.ArabicSpeechNormalizer
import com.aymankhattab.nateq.core.audio.announcement.prepareArabicForEngine
import com.aymankhattab.nateq.core.engine.EngineMarkers
import com.aymankhattab.nateq.core.engine.SingleLetterGuard
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import com.aymankhattab.nateq.core.data.SettingsChangeProvider
import com.aymankhattab.nateq.engine.NumberWordDetector
import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.aymankhattab.nateq.core.audio.announcement.InterruptionSensors
import com.aymankhattab.nateq.core.audio.providers.EmbeddedProviders
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.audio.providers.SystemVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VoiceDescriptor
import com.aymankhattab.nateq.core.audio.providers.VoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VocalizerEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VocalizerModelCatalog
import com.aymankhattab.nateq.core.common.AppDispatchers
import com.aymankhattab.nateq.core.data.EffectiveVoice
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.SpeechLock
import com.aymankhattab.nateq.core.engine.AudioExpansionLevels
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.TextProcessor
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.VoiceIdContract
import dagger.hilt.android.AndroidEntryPoint

import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
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
import kotlinx.coroutines.withTimeoutOrNull
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
 * في معامل LORD (تفضيل التطبيق) ثم مضاعف السرعة العام (إن فعّله المستخدم)،
 * ثم يُقصّ على نطاقٍ آمن (0.1..6.0). عند اتباع سرعة القارئ يكون
 * المعامل = 1.0 فيُبقى الخرج = سرعة القارئ × المضاعف.
 */
internal fun computeFinalSpeechRate(
    readerRate: Float,
    nateqMultiplier: Float,
    boost: Float = 1.0f
): Float = (readerRate * nateqMultiplier * boost).coerceIn(0.1f, 6.0f)

/**
 * مستوى الصوت النهائي لمقطع القراءة: مستوى الصوت الأساسي (تفضيل التطبيق)
 * مضروباً في مضاعف الصوت العام (إن فعّله المستخدم)، ثم يُقصّ على النطاق
 * الكامل (0.0..1.0) — أعلى مستوى متاح للنظام بلا تشويه (كسب >1 يُقصف).
 */
internal fun computeFinalVolume(
    volume: Float,
    boost: Float = 1.0f
): Float = (volume * boost).coerceIn(0f, 1f)


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

        private const val PCM_16BIT = AudioFormat.ENCODING_PCM_16BIT
        private const val EMPTY_TEXT_SAMPLE_RATE = 16_000

        /**
         * مهلة الطلب الواحد كاملةً — ميزانيةُ تخليق نصّه
         * مضافاً إليها تكلفةُ الوحدة، لا تقديرٌ مستقلّ عنها.
         *
         * **جذرُ انقطاع النص الطويل:** كانت هنا `20s + 4ms/حرف` بينما
         * ميزانيةُ التخليق داخل المزوّد `3s + 30ms/حرف`، فالمهلةُ
         * الخارجية كانت أقصرَ دائماً من ميزانية-piece واحدة. ثم جُرِّب
         * حلٌّ ثانٍ (تقسيم 200 حرف) فاشتدّ الخلل: صارت ميزانيةُ
         * قطعةٍ واحدة 8s بينما الطلبُ كلُّه 32s، فيُقطع بعد أوّل
         * مقاطع. الصنفُ واحد: مهلةٌ خارجية أقصرُ من ميزانيتها
         * الداخلية. الآن المصدرُ واحد — [SynthesisBudget.requestTimeoutMs]
         * يضمن أن المهلة الخارجية ≥ ميزانية المزوّد دائماً، والنصُّ
         * يُسلَّم طلباً واحداً متّصلاً بلا تقسيم.
         */
        internal fun synthesisTimeoutMs(charCount: Int): Long =
            SynthesisBudget.requestTimeoutMs(charCount)

        /**
         * مهلة الطلب من **أطوال المقاطع الفعلية** لا من مجموع حروف
         * النص — وهو مصدرُ الانقطاع الحقيقي.
         *
         * **لماذا مجموعُ الحروف مخطئ؟** النصُّ يُقسَّم إلى عدة مقاطع
         * (`LanguageSegmenter` يفصل الأرقام واللاتينية عن العربية،
         * و`splitOnFailureBoundary` يقطع الطويل عند حدود الجُمل)، وكل
         * مقطعٍ يُخلَّق بميزانيةٍ مستقلّة في `SystemVoiceProvider`. غير
         * أن المهلة كانت تُحسب كأن النصَّ **قطعةٌ واحدة**:
         * `requestTimeoutMs(total)` = ميزانيةُ الحروف + تكلفةُ وحدة،
         * أي `piece(800)+2s` ≈ 25 ثانية، بينما العملُ الفعلي ثماني
         * وحدات = `8 × (piece(100)+2s)` ≈ 80 ثانية. فالعجزُ 55s
         * يقتل الطلبَ في منتصفه بـ`callback.error()` — وهو عرضُ
         * «يقرأ أطولَ لكنه لا يكتمل» تماماً. وكلما كثُرَت المقاطع
         * (نصٌّ غنيٌّ بالأرقام) ازداد العجز، فكان القصُّ يتناسب مع
         * كثافة الأرقام — وهذه بالضبط بصمةُ العطل.
         *
         * الصوابُ هو [SynthesisBudget.unitsTimeoutMs] القائم أصلاً
         * للمقاطع: مجموعُ ميزانيةِ كل مقطعٍ + تكلفةُ كل وحدة + هامش.
         * فالمهلةُ الآن **تشتقّ من الشجرة الفعلية للتنفيذ** ولا تسبق
         * عملها أبداً — وهو العقدُ الحاكم نفسه.
         *
         * ملاحظة: تحقّقُ الإلغاء المتبادل (`instance.stop()` على كامل
         * المجمّعة عند إلغاء طلب) يجعلالتنفيذ رتيباً تسلسلياً في
         * الحالة الحرجة، فمجموعُ الوحدات هو الاحتمالُ الأسوأ الآمن
         * — ولا يُستخدم أقلُّ منه.
         */
        internal fun synthesisTimeoutMsForSegments(
            segmentCharCounts: List<Int>
        ): Long = SynthesisBudget.unitsTimeoutMs(segmentCharCounts)

        /**
         * هل يُسلَّم النصُّ إلى **المسار الأحادي** ([synthesizeSingle])
         * أم إلى **المسار المتسلسل المقسَّم** ([synthesizeMixed])؟
         *
         * المعيار: مقطعٌ واحدٌ **ضمن** عتبة تعثّر المحرّكات
         * ([SEGMENT_SPLIT_FALLBACK_CHARS]) → أحادي؛ وإلا (متعددُ
         * المقاطع، أو مقطعٌ واحدٌ تجاوز العتبة) → مختلط.
         *
         * **لماذا؟** النصُّ العربي/الإنجليزي الخالص الطويل يبقى مقطعاً
         * واحداً فيُسلَّم كاملاً إلى المحرّك عبر `synthesizeToFile`
         * الواحد، والمحرّكاتُ تعجز عن ما فوق العتبة فتبتره أو تعلّقه
         * بلا خطأ فيُسمع جزءٌ ثم ينقطع. أما المسارُ المتسلسل فيقسّم
         * المقطع عند حدوده اللغوية ([splitOnFailureBoundary]) ويبثّ
         * جزءاً جزءاً ويشتقّ الميزانية من الأجزاء الفعلية — وهو
         * الحارسُ نفسُه الذي يحمي المقاطع المتعددة.
         */
        internal fun shouldSynthesizeSingle(
            segmentCharCounts: List<Int>
        ): Boolean =
            segmentCharCounts.size == 1 &&
                segmentCharCounts[0] <= SEGMENT_SPLIT_FALLBACK_CHARS

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

    /** قاموس النطق الشخصي — سنجلتون Hilt موحَّد مع نسخة الواجهة، ويُرصد طابعه
     *  على القرص عند كل تطبيق (في [PronunciationDictionary.apply]) ليلتقط
     *  التعديلات القادمة من عملية الواجهة المنفصلة عن عملية :tts. */
    @Inject
    lateinit var pronunciationDictionary: PronunciationDictionary

    /** يلتقط حفظ القاموس من عملية الواجهة ويحدّث لقطة عملية المحرك فوراً. */
    private val dictionaryChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action !=
                PronunciationDictionary.ACTION_DICTIONARY_CHANGED
            ) {
                return
            }
            val dictionary = if (::pronunciationDictionary.isInitialized) {
                pronunciationDictionary
            } else {
                PronunciationDictionary.shared(applicationContext)
            }
            dictionary.reload()
        }
    }

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
    // معالج النصوص (والقاموس بداخله: Keystore I/O + JSON) كسول: لا يُبنى
    // في onCreate على الرئيسي، بل عند أول نطق فعلي أو في التدفئة الخلفية.
    private val textProcessor: TextProcessor by lazy {
        val dict = if (::pronunciationDictionary.isInitialized) {
            pronunciationDictionary
        } else {
            PronunciationDictionary(applicationContext)
        }
        TextProcessor(applicationContext, settings, dict)
    }

    /** مقسم النصوص المختلطة الكتابات (منطق نقي مشترك بلا حالة). */
    private val segmenter = LanguageSegmenter()

    /** مسبح مخازن PCM الخاصة بمسار البث الموحّد (بند تسريع النطق): شريحة
     *  المزوّد تُعاد معاينتها في مخزنٍ من المسبح ويُبث فوراً ثم يُعاد ليسكن
     *  بثَ المقطع التالي — بلا إنشاء مصفوفة لكل شريحة من كل مقطع. آمن لأن
     *  [SynthesisCallback.audioAvailable] يستهلك المخزن قبل عودته (عقد
     *  [SystemVoiceProvider] نفسه مع متلقيه). */
    private val pcmBufferPool = BytePool()

    /** مدير مؤثرات اتساع الصوت المدمجة لنطق القارئ (بنود 2-3). */
    private val audioEffectManager = AudioEffectManager()


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

    /** رقم الجيل التزايدي للطلبات — يمنع سباق onStop المتأخر
     *  (الجولة 50 - الأمر 3 البند 7). */
    private val requestGeneration = AtomicLong(0L)

    /** الرحلة اللاتزامنية للتخليق النشط مع رقم جيلها. */
    @Volatile private var activeJob: kotlinx.coroutines.Job? = null
    @Volatile private var activeJobGeneration: Long = 0L

    @androidx.annotation.VisibleForTesting
    internal fun getRequestGenerationForTesting(): AtomicLong =
        requestGeneration

    @androidx.annotation.VisibleForTesting
    internal fun setActiveJobForTesting(
        gen: Long, job: kotlinx.coroutines.Job?
    ) {
        activeJobGeneration = gen
        activeJob = job
    }

    @androidx.annotation.VisibleForTesting
    internal fun getActiveJobForTesting(): kotlinx.coroutines.Job? =
        activeJob

    @androidx.annotation.VisibleForTesting
    internal fun getActiveJobGenerationForTesting(): Long =
        activeJobGeneration

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

    @Volatile private var interruptedBySensor = false

    /** مراقب تغييرات الإعدادات لتسخين صوت الأرقام عند تعديلها. */
    private val settingsChangeObserver = object : ContentObserver(
        Handler(Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) {
            serviceScope.launch(AppDispatchers.io) {
                warmNumberVoiceCache()
            }
        }
    }

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
        settings = SettingsRepository.create(applicationContext)

        val providers = listOf(
            SystemVoiceProvider(applicationContext, settings),
            EmbeddedProviders.vocalizer(applicationContext),
        )
        catalog = VoiceCatalog(providers)
        requestHandler = SynthesisRequestHandler(catalog, settings)

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
                // بناء القاموس/المعالج الكسول هنا على IO: فإن وصل طلب
                // حقيقي أثناء البناء أُلغيت التدفئة وبناه الطلب بنفسه.
                runCatching { textProcessor }
                maybeRefreshDiscovery()
                warmNavigationCache()
                warmNumberVoiceCache()
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
        // رصد الإسكات الفوري (هز/تقارب): يُسجَّل أثناء دورة النطق النشط
        // فقط في onSynthesizeText حفظاً للبطارية ويُلغى فور انتهائها.

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
        ContextCompat.registerReceiver(
            this,
            dictionaryChangedReceiver,
            IntentFilter(PronunciationDictionary.ACTION_DICTIONARY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        runCatching {
            contentResolver.registerContentObserver(
                SettingsChangeProvider.uri(), false, settingsChangeObserver
            )
        }
    }

    override fun onDestroy() {
        // إلغاء كل العمليات اللاتزامنية المعلّقة للخدمة حتى لا تتسرب مع عمر
        // عملية المحرك، ثم إغلاق النطق الجاري إن وُجد.
        activeJob?.cancel()
        runCatching {
            contentResolver.unregisterContentObserver(settingsChangeObserver)
        }
        warmupJob?.cancel()
        serviceScope.cancel()
        synthesisScope.cancel()
        synthesisExecutor.shutdown()
        audioEffectManager.releaseAll()
        // إلغاء تسجيل مستشعرات الهز/التقارب كي لا تبقى مرصودةً بعد تدمير
        // الخدمة (تسجيلها في onCreate يليه إلغاؤه هنا — بند 8).
        stopInterruptionMonitoring()
        // إلغاء مستقبل الحزم (بند 6.5) — لا يبقى مستمعاً بعد تدمير
        // الخدمة فيتجمع بثّاتُ تثبيت/إزالة الحزم على كائن مردود.
        runCatching { unregisterReceiver(packageEventsReceiver) }
        runCatching { unregisterReceiver(dictionaryChangedReceiver) }
        // إغلاق موارد المزوّدين (TextToSpeech المربوط بالمحرك الخارجي + مراقب
        // الإنترنت + منفّذ الخلفية) كي لا تبقى روابط Binder IPC معلقة بعد
        // تدمير الخدمة — حارس isInitialized لمسارات التدمير
        // المبكر قبل onCreate.
        if (::catalog.isInitialized) catalog.shutdown()
        // ع٢٩-2 (5): إغلاق نسخ المزوّدات المدمجة المشتركة وإلغاء
        // مراجعها في السجل — فلا تبقى نسخة أصلية حيّة بعد تدمير
        // الخدمة، وتعاد الإنشاء لاحقاً من إنشاءٍ حيٍّ عند الحاجة.
        EmbeddedProviders.shutdownAll()
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
        if (lang.isNullOrBlank()) return TextToSpeech.LANG_NOT_SUPPORTED

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
        if (lang.isNullOrBlank()) return TextToSpeech.LANG_NOT_SUPPORTED
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
        val inCatalog = catalog.supportedVoices().any { it.name == voiceName }
        val inContract = VoiceIdContract.declaredVoiceNames().any {
            it.equals(voiceName, ignoreCase = true)
        }
        return if (inCatalog || inContract) {
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
        } ?: VoiceIdContract.declaredVoiceNames().find {
            it.equals(voiceName, ignoreCase = true)
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
        val normLang = normalizeLanguageCode(lang).ifBlank {
            LanguageCode.AR.tag
        }
        val preferred = runCatching {
            settings.getPreferredVoiceId(normLang)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val validCatalogVoice = catalog.supportedVoices().find {
            it.name == preferred
        }?.name
        val name = validCatalogVoice
            ?: catalog.defaultVoiceNameForLanguage(normLang)
            ?: VoiceIdContract.createId(normLang)
        Log.d(TAG,
            "onGetDefaultVoiceNameFor() lang=$lang" +
            " -> norm=$normLang -> $name")
        return name
    }

    override fun onStop() {
        // إيقاف صريح: نلغي جيل الطلب السابق فقط لمنع سباق الطلب الأحدث
        val prevGen = requestGeneration.get()
        requestGeneration.incrementAndGet()
        interruptedBySensor = false
        if (activeJobGeneration == prevGen) {
            activeJob?.cancel()
        }
    }

    /** رصد الهز/التقارب أثناء رحلة التخليق: يُسجَّل أثناء النطق النشط فقط
     *  لتوفير البطارية، ويُلغى فور انتهاء الرحلة. */
    private fun startInterruptionMonitoring() {
        if (interruptionSensors == null) {
            interruptionSensors = InterruptionSensors(
                shakeEnabled = {
                    runCatching { settings.isShakeToStopEnabled() }
                        .getOrDefault(false)
                },
                proximityEnabled = {
                    runCatching { settings.isProximitySilenceEnabled() }
                        .getOrDefault(false)
                },
                onInterrupt = { interruptSynthesis() }
            )
        }
        interruptionSensors?.start(applicationContext)
    }


    /** إيقاف رصد الهز/التقارب فور انتهاء رحلة التخليق أو عند onDestroy. */
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
        interruptedBySensor = true
        val prevGen = requestGeneration.get()
        requestGeneration.incrementAndGet()
        if (activeJobGeneration == prevGen) {
            activeJob?.cancel()
        }
    }

    override fun onSynthesizeText(
        request: SynthesisRequest?,
        callback: SynthesisCallback?
    ) {
        if (request == null || callback == null) return
        val myGen = requestGeneration.incrementAndGet()
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
        interruptedBySensor = false

        // **بند قفل النطق العابر:** نرفع علم «نطق جارٍ» عبر content://…
        // /speaking فيبدأ متحدث الإعلانات (AnnouncementSpeaker) بتأجيل
        // إعلاناته حتى يكتمل هذا التخليق — فيتسلسل صوت الإعلان بعد قراءة
        // قارئ الشاشة بدل تراكبه فوقها. يُخفض في finally أدناه.
        SpeechLock.setSpeaking(applicationContext, true)

        // رصد الإسكات الفوري أثناء التخليق فقط حفظاً للبطارية
        val shake = runCatching { settings.isShakeToStopEnabled() }
            .getOrDefault(false)
        val proximity = runCatching { settings.isProximitySilenceEnabled() }
            .getOrDefault(false)
        if (shake || proximity) {
            startInterruptionMonitoring()
        }

        // **بند 8 — إغلاق نافذة الاستباق:** تُبنى الرحلة LAZY فلا تبدأ
        //  جدولتُها على خيط التخليق قبل إسنادها إلى [currentJob] (كانت تبدأ
        //  فوراً فيسبق إلغاءٌ من مستشعر/onStop إسنادَها فيُفلت الرحلة)؛
        //  نُشغّلها صريحاً بعد الإسناد مباشرةً فتنغلق النافذة تماماً.
        val audioSessionId = request.params?.getInt(
            TextToSpeech.Engine.KEY_PARAM_SESSION_ID,
            0
        ) ?: 0

        val rawText = request.charSequenceText?.toString().orEmpty()
        val reqVoiceName = request.voiceName?.takeIf { it.isNotBlank() }
        val startUptime = SystemClock.uptimeMillis()
        val lastProgressAt = AtomicLong(startUptime)
        val lastPieceDurationMs = AtomicLong(0L)
        val ttfbMs = AtomicLong(0L)
        val preprocessDurationMs = AtomicLong(0L)
        val planningDurationMs = AtomicLong(0L)
        val segmentMetrics =
            Collections.synchronizedList(
                ArrayList<SpeechLatencyTracker.SegmentMetric>()
            )
        val monitoredCallback = MonitoredSynthesisCallback(callback) { bytes ->
            lastProgressAt.set(SystemClock.uptimeMillis())
            if (bytes > 0) {
                val dur = (bytes * 1000L) / 44100L
                lastPieceDurationMs.set(dur)
                ttfbMs.compareAndSet(
                    0L, SystemClock.uptimeMillis() - startUptime
                )
            }
        }
        // **المهلة تُشتقّ من المقاطع الفعلية** بعد التفتيت أدناه لا من
        // مجموع حروف النص — فانظر [synthesisTimeoutMsForSegments]
        // فأصلُ الانقطاع.
        //
        // وهي **ذرّيةٌ متغيّرة** (AtomicLong) لا `var` عادية: الخيطُ
        // المنتظر يقرأها (`withTimeoutOrNull`) بينما خيطُ التخليق
        // يكتبها بعد التقطيع. فمع `var` كانت القراءةُ قد تقع على القيمة
        // القديمة — وهو الخللُ عينُه الذي نحاربه.
        val timeoutHolder = AtomicLong(synthesisTimeoutMs(rawText.length))
        fun timeoutMs(): Long = timeoutHolder.get()
        val job = synthesisScope.launch(start = CoroutineStart.LAZY) {
            try {
                // إعادة تحميل الإعدادات من القرص لأن `:tts`
                // process منفصل عن عملية الإعدادات
                // (SettingsActivity)، وSharedPreferences لا
                // يتشارك عبر العمليات. بدون reload() تبقى
                // القيم القديمة محشوة في الذاكرة.
                settings.reload()
                val expansionLevel = runCatching {
                    settings.getAudioExpansionLevel()
                }.getOrDefault(AudioExpansionLevels.DEFAULT)
                // **بند V2:** الصدى مفتاحٌ مستقلّ عن الاتساع (افتراضياً
                // مُعطَّل)، فيُقرأ كلَّ تخليقٍ بعد إعادة تحميل الإعدادات.
                audioEffectManager.setReverbEnabled(
                    runCatching { settings.isAudioReverbEnabled() }
                        .getOrDefault(false)
                )
                // **بند V3:** فئاتُ الإعلانات التي عُطّل فيها التأثير —
                // وتبقى قراءةُ النص الطويل (الفئة العامة) خارجَها
                // عمداً فلا تعطيلَ لها.
                audioEffectManager.setDisabledCategories(
                    runCatching {
                        settings.getAudioEffectsDisabledCategories()
                    }.getOrDefault(emptySet())
                )
                // الفئة تُمرَّر من AnnouncementSpeaker في PARAM_CATEGORY،
                // وغيابُها يعني النطق العام (بلا تعطيل خاص).
                val effectCategory = request.params?.getString(
                    AudioEffectManager.PARAM_CATEGORY
                ).orEmpty()
                val targetPkg =
                    AccessibilityBridge.resolveForegroundPackage()
                val senderHint =
                    AccessibilityBridge.currentSenderHint()
                val activeAppVoice = resolveActiveAppVoice(
                    effectCategory = effectCategory,
                    targetPackage = targetPkg,
                    senderName = senderHint
                )
                val appProfilesOn = runCatching {
                    settings.isAccessibilityFeatureEnabled(
                        FeatureFlags.FEATURE_APP_PROFILES
                    )
                }.getOrDefault(false)
                val contactVoicesOn = runCatching {
                    settings.isAccessibilityFeatureEnabled(
                        FeatureFlags.FEATURE_CONTACT_VOICES
                    )
                }.getOrDefault(false)
                val diagReason = when {
                    effectCategory.isNotEmpty() ->
                        "الفئة غير فارغة ($effectCategory)"
                    !appProfilesOn && !contactVoicesOn ->
                        "الميزة مغلقة"
                    targetPkg.isNullOrBlank() && senderHint.isNullOrBlank() ->
                        "لا توجد حزمة أو مرسل"
                    activeAppVoice == null ->
                        "لا يوجد ملف للحزمة $targetPkg أو المرسل $senderHint"
                    else ->
                        "مطبّق: محرك=${activeAppVoice.engine}, " +
                            "صوت=${activeAppVoice.voiceId}"
                }
                Log.i(
                    "NateqTtsService",
                    "TTS utterance voice: pkg=$targetPkg, " +
                        "sender=$senderHint, $diagReason"
                )
                // الاستدعاءُ بلا شرطِ مستوى: الصدى قد يكون مفعّلاً
                // والاتساع مُطفأً (فصلٌ تامّ بين المفتاحين).
                if (audioSessionId > 0) {
                    audioEffectManager.attach(
                        audioSessionId,
                        expansionLevel,
                        effectCategory
                    )
                }
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
                if (rawText.isBlank()) {
                    callback.start(EMPTY_TEXT_SAMPLE_RATE, PCM_16BIT, 1)
                    callback.done()
                    return@launch
                }
                // ع٢٩-12: توسيع «قراءة المزيد» تلقائياً قبل المعالجة الدلالية
                var processedText = rawText
                val startPreprocess = SystemClock.uptimeMillis()
                val readMoreEnabled = runCatching {
                    FeatureFlags.isActive(
                        FeatureFlags.FEATURE_READ_MORE_EXPAND,
                        context = applicationContext,
                        settings = settings
                    )
                }.getOrDefault(false)
                val fgPkg = AccessibilityBridge.foregroundPackage()
                val isPkgAllowed = fgPkg != null &&
                    settings.getReadMoreAllowedPackages().contains(fgPkg)
                val isTruncated = SelectorPacks.looksTruncated(rawText)
                if (isTruncated) {
                    Log.w(
                        TAG,
                        "[READMORE] Speech detected truncated text " +
                            "(enabled=$readMoreEnabled, " +
                            "allowed=$isPkgAllowed, len=${rawText.length})"
                    )
                }
                if (readMoreEnabled && isPkgAllowed && isTruncated) {
                    val expanded = AccessibilityBridge.expandReadMore(
                        rawText,
                        timeoutMs = 200L
                    )
                    val cleanRaw = SelectorPacks.cleanBidi(rawText).trim()
                    val isValid = if (expanded != null) {
                        val cleanExp = SelectorPacks.cleanBidi(expanded).trim()
                        cleanExp.isNotEmpty() && (
                            cleanExp.length > cleanRaw.length ||
                            !SelectorPacks.looksTruncated(cleanExp) ||
                            SelectorPacks.isTextMatchingMessage(
                                rawText,
                                expanded
                            )
                        )
                    } else false

                    if (expanded != null && isValid) {
                        processedText = expanded
                        Log.w(
                            TAG,
                            "[READMORE] Expanded: ${rawText.length} -> " +
                                "${expanded.length} chars"
                        )
                    } else {
                        Log.w(
                            TAG,
                            "[READMORE] Expansion result: expanded=" +
                                "${expanded != null}, len=${expanded?.length}"
                        )
                    }
                }
                Log.d(TAG, "[DICT] Service before: $processedText")
                val semanticText = SpeechEntryPoints.prepare(
                    processedText,
                    SpeechEntryPoints.Source.SCREEN_READER,
                    applicationContext,
                    languageTag
                )
                processedText = semanticText
                val endPreprocess = SystemClock.uptimeMillis()
                preprocessDurationMs.set(endPreprocess - startPreprocess)
                Log.d(TAG, "[DICT] Service after: $processedText")
                // ع٤١-ب4: سجلّ «ما قيل» لمسار قارئ الشاشة (TalkBack) بعد
                // تجهيز النص النهائي وقبل أي معالجة خاصة بالمحرك.
                val cleanForHistory = semanticText
                    .replace(EngineMarkers.NUMBER_MARK_START, "")
                    .replace(EngineMarkers.NUMBER_MARK_END, "")
                RepeatSpellController.updateLastSpokenText(
                    cleanForHistory,
                    source = RepeatSpellController.Source.READER
                )
                // **سرعة القارئ (معامل مُوحّد للمسارين):** نسبةُ
                // request.getSpeechRate() المئوية (100 = طبيعي) تتحول هنا مرة
                // واحدة إلى معامل (readerRate) يُمرَّر للمسار الأحادي
                // (synthesizeSingle) والمسارات المختلطة (synthesizeMixed) —
                // فلا يختلف صوتُ النص المختلط عن الأحادي، ولا يرى المسار
                // المختلط request إطلاقاً.
                val reqRate = request.getSpeechRate().toFloat()
                val readerRate = readerRateFromPercent(reqRate)

                val secondaryLanguage = runCatching {
                    settings.getSecondaryLanguage()
                }.getOrDefault(LanguageCode.EN.tag)
                val numberLanguage = runCatching {
                    settings.getNumberReadingLanguage()
                }.getOrDefault(LanguageCode.AR.tag)
                val emojiEnabled = runCatching {
                    settings.isEmojiPronunciationEnabled()
                }.getOrDefault(false)

                val startPlanning = SystemClock.uptimeMillis()
                val planItems = SpeechPlanner.plan(
                    rawText = semanticText,
                    fallbackLanguage = languageTag,
                    secondaryLanguage = secondaryLanguage,
                    numberLanguage = numberLanguage,
                    emojiPronunciationEnabled = emojiEnabled,
                    dictionary = pronunciationDictionary,
                    settings = settings,
                    defaultRate = readerRate,
                    segmenter = segmenter
                )
                val segments = if (planItems.isEmpty()) {
                    listOf(Segment(semanticText, languageTag))
                } else {
                    planItems.map { item ->
                        Segment(item.text, item.languageTag, item.role)
                    }
                }
                val endPlanning = SystemClock.uptimeMillis()
                planningDurationMs.set(endPlanning - startPlanning)

                // **حارسُ المقطع الأحادي الطويل:** المقطعُ الواحد إن
                // تجاوز عتبة تعثّر المحرّكات لا يُسلَّم كاملاً إلى
                // [synthesizeSingle] فيبتره المحرّك بلا خطأ، بل يُوجَّه
                // للمسار المتسلسل المقسَّم كسائر النصوص الطويلة.
                if (shouldSynthesizeSingle(segments.map { it.text.length })) {
                    // **بند 2.5:** حتى النص المفرد تُستعمل لغةُ المقطع
                    // المكتشفة للتوجيه لا لغةُ الطلب الأصلية — كلمةٌ إنجليزية
                    // وحيدة («Settings»، «Cancel») داخل واجهة عربية لم تعد
                    // تُنطق بصوت/محرك العربية أو تتعثّر: تُوجَّه لمحركها
                    // المناسب فوراً.
                    synthesizeSingle(
                        segments[0].text, segments[0].languageTag,
                        monitoredCallback, readerRate, effectCategory,
                        segments[0].role, activeAppVoice, reqVoiceName
                    )
                } else {
                    // نص مختلط الكتابات: نطق كل مقطع بلغته/محركه ثم مزج الصوت
                    // بمعدلٍ موحّد عبر بثٍّ واحد (مونو).
                    //
                    // **تصحيحُ الميزانية:** كل مقطعٍ يُخلَّق بميزانيته
                    // المستقلّة، فالمهلةُ يجب أن تجمعها لا أن تحسب
                    // النصَّ قطعةً واحدة. بدون هذا يسبق السقفُ العملَ
                    // الحقيقي فيقطعه (وهي بصمةُ «يقرأ أطولَ ولا
                    // يكتمل»). وتُحسب على أطوال المقاطع **بعد** تقطيع
                    // الفشل المحتمل (`splitOnFailureBoundary`)، لأن ذلك
                    // يزيد عدد الوحدات.
                    val unitCounts = segments.flatMap { segment ->
                        splitOnFailureBoundary(segment.text)
                            .map { it.length }
                    }
                    val correctMs =
                        synthesisTimeoutMsForSegments(unitCounts)
                    // لا نخفض المهلة أبداً: لو سبق السقفُ previous
                    // لَبقي محسوباً على الحروف كلها وهو أوسعُ احتياطاً،
                    // وإن كان أعلى فهو الصوابُ (احترامُ العقد).
                    timeoutHolder.set(
                        maxOf(timeoutHolder.get(), correctMs)
                    )
                    Log.d(TAG,
                        "budget: ${segments.size} segments -> " +
                            "${unitCounts.size} units, " +
                            "$correctMs ms (was ${timeoutHolder.get()})"
                    )
                    synthesizeMixed(
                        segments, readerRate, monitoredCallback,
                        effectCategory, activeAppVoice, reqVoiceName,
                        onFirstByteEmitted = {
                            ttfbMs.compareAndSet(
                                0L, SystemClock.uptimeMillis() - startUptime
                            )
                        },
                        onSegmentMetric = { metric ->
                            segmentMetrics.add(metric)
                        }
                    )
                }
            } catch (e: CancellationException) {
                // إبطال صريح: الإيقاف (onStop) معروف للنظام فلا نُطلق خطأً
                // زائفاً، أما الاستباقُ فلم يعد وارداً مع النمط الحاجز (يُبقي
                // النظامُ خيطَ التخليق حتى العودة) ويبقى التحوط للسلامة.
                if (interruptedBySensor) {
                    Log.d(TAG, "onSynthesizeText interrupted by sensor")
                    runCatching { callback.done() }
                } else if (myGen < requestGeneration.get()) {
                    // بند 2.4: خروج صامت عند الإيقاف الحقيقي — استدعاء
                    // error() هنا خطأٌ زائف يخرق عقد AOSP (النظام ألغى
                    // الطلب بنفسه عبر onStop فلا ينتظر إخطاراً آخر).
                    Log.d(TAG, "onSynthesizeText cancelled (stop gen $myGen)")
                } else {
                    Log.d(
                        TAG,
                        "onSynthesizeText cancelled (preempt gen $myGen)"
                    )
                    // بند 2.3: الاستباق وحده يُغلق الـ callback دائماً
                    // (تحوّط من رمي النظام InterruptedException/عدم تحلّه
                    // عند الإيقاف) حتى لا يعلق طابور النظام بطلبٍ ميت أو
                    // يبقى AudioTrack مفتوحاً.
                    runCatching { callback.error() }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                runCatching { callback.error() }
            }
        }
        if (myGen < requestGeneration.get()) {
            Log.d(TAG, "onSynthesizeText stopped before start ($myGen)")
            return
        }
        activeJob = job
        activeJobGeneration = myGen
        job.start()
        try {
            // انتظار متزامن على خيط التخليق حتى اكتمال التوليف (معيار AOSP)؛
            // مع حارس خمول التقدّم (ع٤٢-7): لا تُلغى المهمة إلا عند انقطاع
            // الصوت لمدّة تتجاوز مهلة الخمول، أو تجاوز السقف المطلق.
            runBlocking {
                while (!job.isCompleted) {
                    val joined = withTimeoutOrNull(500L) {
                        job.join()
                    } != null
                    if (joined || job.isCompleted) {
                        break
                    }
                    val now = SystemClock.uptimeMillis()
                    val idleElapsed = now - lastProgressAt.get()
                    val currentIdleLimit = SynthesisBudget.idleTimeoutMs(
                        lastPieceDurationMs.get()
                    )
                    if (idleElapsed >= currentIdleLimit) {
                        Log.w(
                            TAG,
                            "onSynthesizeText timed out " +
                                "(idle=${idleElapsed}ms > " +
                                "${currentIdleLimit}ms, " +
                                "${rawText.length} chars)"
                        )
                        job.cancel()
                        runCatching { callback.error() }
                        break
                    }
                    val totalElapsed = now - startUptime
                    if (totalElapsed >=
                        SynthesisBudget.ABSOLUTE_REQUEST_TIMEOUT_MS
                    ) {
                        Log.w(
                            TAG,
                            "onSynthesizeText absolute timeout: " +
                                "${totalElapsed}ms"
                        )
                        job.cancel()
                        runCatching { callback.error() }
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onSynthesizeText join interrupted", t)
        } finally {
            val totalDurationMs = SystemClock.uptimeMillis() - startUptime
            val ttfb = if (ttfbMs.get() > 0L) ttfbMs.get() else totalDurationMs
            SpeechLatencyTracker.recordUtterance(
                SpeechLatencyTracker.UtteranceMetric(
                    text = rawText,
                    preprocessDurationMs = preprocessDurationMs.get(),
                    planningDurationMs = planningDurationMs.get(),
                    segments = segmentMetrics.toList(),
                    timeToFirstByteMs = ttfb,
                    totalDurationMs = totalDurationMs
                )
            )
            stopInterruptionMonitoring()
            if (activeJobGeneration == myGen) {
                activeJob = null
            }
            if (audioSessionId > 0) {
                audioEffectManager.detach(audioSessionId)
            }
            // **بند قفل النطق العابر:** نهاية التخليق (نجاح/خطأ/إلغاء) تخفض
            // علم «نطق جارٍ» — يُنبَّه المراقبون (متحدث الإعلانات) فوراً
            // فينطلق الإعلانُ المؤجَّل خلف القراءة.
            SpeechLock.setSpeaking(applicationContext, false)
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

    /** قيمة مضاعف السرعة الحالية (1.0 عند التعطيل) — مضاعف عام يضرب
     *  السرعة النهائية في مساري القارئ (الأحادي والمختلط) فوق معامل LORD. */
    private fun currentSpeechBoost(): Float {
        val enabled = runCatching { settings.isSpeechBoostEnabled() }
            .getOrDefault(false)
        return if (enabled) {
            runCatching { settings.getSpeechBoostValue() }
                .getOrDefault(1.0f)
        } else {
            1.0f
        }
    }

    /** قيمة مضاعف الصوت الحالية (1.0 عند التعطيل) — مضاعف عام يضرب
     *  مستوى الصوت النهائي في مساري القارئ ثم يُقصّ على الكامل. */
    private fun currentVolumeBoost(): Float {
        val enabled = runCatching { settings.isVolumeBoostEnabled() }
            .getOrDefault(false)
        return if (enabled) {
            runCatching { settings.getVolumeBoostValue() }
                .getOrDefault(1.0f)
        } else {
            1.0f
        }
    }

    /** المسار الأحادي (نص بلغةٍ واحدة) — نفس التدفق التفصيلي السابق حرفياً:
     *  حل الصوت (مع تراجع الجهاز الافتراضي)، التحويل التلقائي، أشرطة اللغة، ثم
     *  تخليق وبث مباشر عبر المزوّد بتقسيم المخزن المؤقت المُثبَت. */
    private suspend fun synthesizeSingle(
        rawText: String,
        languageTag: String,
        callback: SynthesisCallback,
        readerRate: Float,
        effectCategory: String = "",
        role: SpeechRole = SpeechRole.TEXT,
        appVoice: EffectiveVoice? = null,
        reqVoiceName: String? = null
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
        // الرقم البحت أو الإيموجي مع صوتٍ محفوظ لفئته
        val convertTarget =
            emojiCategoryTarget(Segment(rawText, languageTag, role))
                ?: numbersCategoryTarget(Segment(rawText, languageTag, role))
                ?: resolveConvertTarget(languageTag)
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
        val speechRate = computeFinalSpeechRate(
            readerRate, nateqMultiplier, currentSpeechBoost()
        )
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

        val basePitch = convertTarget?.let { it.convertPitch } ?: pitch
        val baseVolume = convertTarget?.let { it.convertVolume } ?: volume
        val appRate = appVoice?.speechRate
        val finalRate = if (appRate != null) {
            (speechRate * appRate).coerceIn(0.1f, 6.0f)
        } else {
            speechRate
        }
        val appPitch = appVoice?.pitch
        val finalPitch = if (appPitch != null) {
            (basePitch * appPitch).coerceIn(0.2f, 2.0f)
        } else {
            basePitch
        }
        val appVol = appVoice?.volume
        val finalVolume = if (appVol != null) {
            computeFinalVolume(appVol, currentVolumeBoost())
        } else {
            computeFinalVolume(baseVolume, currentVolumeBoost())
        }
        // توجيه المحرك/الصوت: يفضّل هدف التحويل المطابق، وإلا تفضيل لغة النص
        // نفسه (سارٍ دائماً بلا ربط بحالة «التحويل التلقائي»). **يُجرى قبل
        // المعالجة** ليُمرَّر المحركُ المحسوم إلى [TextProcessor.process] —
        // فالمحركات التي تفهم التشكيل العربي تحتفظ به في الناتج (بند التشكيل).
        val defaultCategoryEngine = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        )
        val routed = LanguageSpeechRouter.route(
            matchesRequest = matchesRequest,
            convertEngine = convertTarget?.convertEngine,
            convertVoiceName = convertTarget?.convertVoiceName,
            perLanguageEngine = settings.getEngineForLanguage(languageTag),
            perLanguageVoiceName = settings.getVoiceForLanguage(languageTag),
            defaultEngine = defaultCategoryEngine
        )
        val applyVoice = if (
            isAppVoiceMatchingLanguage(appVoice, languageTag)
        ) {
            appVoice
        } else {
            if (appVoice != null) {
                Log.i(
                    "NateqTtsService",
                    "App voice language mismatch for single segment: " +
                        "voiceLang=${appVoice.voiceLanguage}, tag=$languageTag"
                )
            }
            null
        }
        val finalEngine = applyVoice?.engine ?: routed.engine
        val finalLocale = if (matchesRequest) {
            convertTarget?.convertLocale
                ?: Locale.forLanguageTag(languageTag)
        } else {
            Locale.forLanguageTag(languageTag)
        }
        val preferredForEngine = finalEngine?.let {
            settings.getPreferredVoiceForEngine(it, languageTag)
        }
        val finalVoiceName = if (
            !convertTarget?.convertVoiceName.isNullOrBlank()
        ) {
            convertTarget!!.convertVoiceName
        } else if (applyVoice?.engine != null &&
            applyVoice.engine != routed.engine
        ) {
            applyVoice.voiceId
        } else if (finalEngine != null) {
            if (routed.engine == finalEngine &&
                !routed.voiceName.isNullOrBlank()
            ) {
                routed.voiceName
            } else {
                preferredForEngine
            }
        } else {
            applyVoice?.voiceId
                ?: routed.voiceName
                ?: settings.getPreferredVoiceId(languageTag)
                ?: VoiceIdContract.createId(languageTag)
        }

        // Process text through TextProcessor
        // (numbers, dates, currencies, etc.)
        val processedText = if (role == SpeechRole.EMOJI) {
            rawText
        } else {
            textProcessor.process(
                rawText, languageTag, finalEngine,
                applyDictionary = false
            )
        }
        val speechText = prepareArabicForEngine(
            processedText, languageTag, finalEngine
        )
        if (runCatching {
            settings.isLanguageDiagnosticsEnabled()
        }.getOrDefault(false)) {
            Log.d(
                "NateqLang",
                "[TTS-SINGLE] text='$speechText', role=$role, " +
                    "tag='$languageTag', engine='$finalEngine', " +
                    "voice='$finalVoiceName'"
            )
        }

        // تخليق الصوت الفعلي عبر المزوّد. يُبلّغنا التنسيق
        // (معدل عينات/قنوات) قبل أول شريحة، فنبدأ
        // callback.start بالقيم الفعلية بدل 22050 الثابتة
        // التي كانت تجعل Android يشغّل ملفات 24k/44.1k
        // بسرعة ونبرة خاطئتين.
        //
        // **بند النص الطويل (إصلاح التوقّف في المنتصف):** الطلب
        // الواحد الطويل كان يُبتره المحرك أو يعلّقه فيتوقف النطق
        // عند حدٍّ ثابت. كان الحلُّ تقسيمَ النص إلى مقاطع 200
        // حرف، لكن ذلك كان يعالج العَرَض لا السبب: مقطعٌ يفشل
        // يُسقط كلماتٍ كاملةً بصمت، ويُقطع الكلام عند حدود القطع.
        // السببُ الجذريُّ مُصلَح في [SynthesisBudget] (ميزانيةُ
        // الطلب الواحدة تغطي ميزانية كلِّ قطعةٍ داخلها) و
        // [SystemVoiceProvider] (إتمامٌ بالطول المُعلَن في رأس WAV)،
        // فيُسلَّم النصُّ كلُّه طلباً واحداً متّصلاً كما كان قبل
        // v106. و`start` يُمرَّر مرّةً واحدة و`done()` بعد آخر شريحة.
        var started = false
        try {
            val targetProvider = if (finalEngine != null) {
                catalog.findProvider(finalEngine) ?: provider
            } else {
                provider
            }
            val isVoc = finalEngine ==
                VocalizerEmbeddedVoiceProvider.PROVIDER_ID ||
                targetProvider.providerId ==
                VocalizerEmbeddedVoiceProvider.PROVIDER_ID
            val isGeneric = finalVoiceName.isNullOrBlank() ||
                finalVoiceName.equals("Device Default", true) ||
                finalVoiceName.equals("ar-EG", true) ||
                finalVoiceName.equals("en-US", true) ||
                finalVoiceName.equals("ar", true) ||
                finalVoiceName.equals("en", true)
            val isVocVoice = if (isVoc && !finalVoiceName.isNullOrBlank()) {
                VocalizerModelCatalog.ITEMS.any { item ->
                    item.voiceName.equals(finalVoiceName, ignoreCase = true) ||
                        item.id.equals(finalVoiceName, ignoreCase = true) ||
                        finalVoiceName.contains(
                            item.voiceName,
                            ignoreCase = true
                        ) ||
                        (item.voiceName.equals("Laila", true) &&
                            finalVoiceName.contains("leila", true)) ||
                        (item.voiceName.equals("Tarik", true) &&
                            finalVoiceName.contains("tariq", true))
                }
            } else false
            val resolvedVoiceName = if (isVoc && (isGeneric || !isVocVoice)) {
                val installedAr = VocalizerModelCatalog.ITEMS.firstOrNull {
                    LanguageCode.isArabic(it.languageCategory) &&
                        VocalizerModelCatalog.isVoiceInstalled(
                            applicationContext, it
                        )
                }?.voiceName
                val installedEn = VocalizerModelCatalog.ITEMS.firstOrNull {
                    LanguageCode.isEnglish(it.languageCategory) &&
                        VocalizerModelCatalog.isVoiceInstalled(
                            applicationContext, it
                        )
                }?.voiceName
                if (LanguageCode.isArabic(languageTag)) {
                    installedAr ?: "Tarik"
                } else {
                    installedEn ?: "Samantha"
                }
            } else if (isVoc && !finalVoiceName.isNullOrBlank()) {
                val matchedItem = VocalizerModelCatalog.ITEMS.firstOrNull {
                    it.voiceName.equals(finalVoiceName, ignoreCase = true) ||
                        it.id.equals(finalVoiceName, ignoreCase = true) ||
                        finalVoiceName.contains(
                            it.voiceName,
                            ignoreCase = true
                        ) ||
                        (it.voiceName.equals("Laila", true) &&
                            finalVoiceName.contains("leila", true)) ||
                        (it.voiceName.equals("Tarik", true) &&
                            finalVoiceName.contains("tariq", true))
                }
                matchedItem?.voiceName ?: finalVoiceName
            } else {
                finalVoiceName
            }
            val singleCategory = effectCategory.takeIf { it.isNotEmpty() }
                ?: when (role) {
                    SpeechRole.NUMBER ->
                        SettingsRepository.VOICE_CATEGORY_NUMBERS
                    SpeechRole.EMOJI ->
                        SettingsRepository.VOICE_CATEGORY_EMOJI
                    else -> null
                }
            targetProvider.synthesize(
                speechText, voice, finalRate, finalPitch, finalVolume,
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
                    // ضمانة: إن لم يبلّغ المزوّد بالتنسيق مطلقاً
                    // نبدأ بالقيم الافتراضية قبل أول بايت حتى
                    // يبقى التخليق صالحاً دائماً.
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
                    // بمقدار callback.getMaxBufferSize() وإلا
                    // يرفض النظام التخليق ويهبط الصوت. نقسّم كل
                    // دفعة من المزوّد احتراماً لقيود الـ callback.
                    // المعامل الثاني (validLength) هو طول البيانات
                    // الصالح الصريح — فقد تكون مصفوفة الشريحة
                    // بحجم أكبر من بياناتها الفعلية (مسبح مُعاد
                    // استخدامه)، فيُمسح حتى length فقط.
                    val maxBytes = callback.maxBufferSize
                    var offset = 0
                    while (offset < validLength) {
                        val bytesToWrite =
                            minOf(maxBytes, validLength - offset)
                        callback.audioAvailable(
                            chunk, offset, bytesToWrite
                        )
                        offset += bytesToWrite
                    }
                }, finalEngine, finalLocale, resolvedVoiceName,
                singleCategory
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // تعذُّر التخليق: يُسقط النصُّ كلُّه بخطأٍ صريح أسفله
            // (فلا يُنسى`)started` صامتاً ولا يُبثّ ناقصاً.
            Log.w(
                TAG,
                "synthesizeSingle: فشل تخليق النص — يُعلن الخطأ",
                t
            )
        }

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

    /**
     * حسم ملف التطبيق النشط لقراءة الشاشة (ع٤٢-11، ع٤٥).
     * يُستثنى إعلانات النظام الحاملة لفئة صريحة في effectCategory.
     */
    internal fun resolveActiveAppVoice(
        effectCategory: String,
        targetPackage: String? =
            AccessibilityBridge.resolveForegroundPackage(),
        senderName: String? =
            AccessibilityBridge.currentSenderHint()
    ): EffectiveVoice? = if (effectCategory.isEmpty()) {
        val ctx = runCatching { applicationContext }.getOrNull() ?: this
        A11yVoiceResolver.resolve(
            context = ctx,
            settings = settings,
            packageName = targetPackage,
            senderName = senderName
        )
    } else {
        null
    }

    internal fun isAppVoiceMatchingLanguage(
        appVoice: EffectiveVoice?,
        languageTag: String
    ): Boolean {
        if (appVoice == null) return false
        val segRaw = languageTag.takeWhile { it.isLetter() }
            .lowercase(Locale.ROOT)
        val segLang = when (segRaw) {
            "ara" -> "ar"
            "eng" -> "en"
            else -> segRaw
        }
        val voiceRaw = appVoice.voiceLanguage?.takeWhile { it.isLetter() }
            ?.lowercase(Locale.ROOT)
            ?: appVoice.voiceId?.let { id ->
                val sub = id.substringBefore('-')
                    .substringBefore('_')
                    .takeWhile { it.isLetter() }
                    .lowercase(Locale.ROOT)
                if (sub.length in 2..3) sub else null
            }
        val voiceLang = when (voiceRaw) {
            "ara" -> "ar"
            "eng" -> "en"
            else -> voiceRaw
        }
        return voiceLang == null || voiceLang == segLang
    }

    /** وصفُ مقطعٍ محلول جاهز للتخليق (بعد تجهيز النص وحل الصوت والتحويل
     *  والتوجيه) — مشترك بين المسارين المتسلسل والمتوازي لتُبنى أشرطةُ
     *  الصوت ومفاتيحُ الكاش نفسها. */
    internal class SegmentParams(
        val text: String,
        val voice: VoiceDescriptor,
        val provider: VoiceProvider,
        val speechRate: Float,
        val pitch: Float,
        val volume: Float,
        val engine: String?,
        val locale: Locale?,
        val voiceName: String?,
        val category: String? = null
    )

    /** صوتُ مقطعٍ واحد مخلَّق (شريحاته PCM الخام بلا معاينة) — حصيلة
     *  [synthesizeSegmentRaw] للمسار المتوازي. */
    internal class SegmentAudio(
        val nativeRate: Int,
        val nativeChannels: Int,
        val chunks: List<ByteArray>
    )

    /** يحلّ مقطعاً لغوياً كاملاً: تجهيز النص بدليل لغته، حل الصوت مع سقوط
     *  الجهاز الافتراضي، خلاصة أشرطة التحويل (السرعة/النبرة/الصوت)، ثم توجيه
     *  المحرك/اللغة عبر [LanguageSpeechRouter] — خريطةٌ واحدة يعتمدها المساران
     *  المتسلسل والمتوازي فتتطابق أصواتُ النطق ومفاتيحُ الكاش بينهما.
     *  يرجع null عند غياب مزودٍ للمقطع (يُسقَط وحده كدأب المسار المختلط). */
    internal suspend fun resolveSegmentParams(
        segment: Segment,
        readerRate: Float,
        appVoice: EffectiveVoice? = null,
        reqVoiceName: String? = null,
        effectCategory: String = ""
    ): SegmentParams? {
        val segTag = segment.languageTag
        val segRate = requestHandler.getSpeechRate(segTag)
        val segPitch = requestHandler.getPitch(segTag)
        val segVolume = requestHandler.getVolume(segTag)
        // الرقم البحت أو الإيموجي مع صوتٍ محفوظ لفئته
        val convert = emojiCategoryTarget(segment)
            ?: numbersCategoryTarget(segment)
            ?: resolveConvertTarget(segTag)
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
        val computedRate = computeFinalSpeechRate(
            readerRate, nateqMultiplier, currentSpeechBoost()
        )
        val appRate = if (segment.role == SpeechRole.TEXT) {
            appVoice?.speechRate
        } else {
            null
        }
        val finalRate = if (appRate != null) {
            (computedRate * appRate).coerceIn(0.1f, 6.0f)
        } else {
            computedRate
        }
        val basePitch = convert?.convertPitch ?: segPitch
        val appPitch = if (segment.role == SpeechRole.TEXT) {
            appVoice?.pitch
        } else {
            null
        }
        val finalPitch = if (appPitch != null) {
            (basePitch * appPitch).coerceIn(0.2f, 2.0f)
        } else {
            basePitch
        }
        val baseVolume = convert?.convertVolume ?: segVolume
        val appVol = if (segment.role == SpeechRole.TEXT) {
            appVoice?.volume
        } else {
            null
        }
        val finalVolume = if (appVol != null) {
            computeFinalVolume(appVol, currentVolumeBoost())
        } else {
            computeFinalVolume(baseVolume, currentVolumeBoost())
        }
        val defaultCategoryEngine = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        )
        val routed = LanguageSpeechRouter.route(
            matchesRequest = matches,
            convertEngine = convert?.convertEngine,
            convertVoiceName = convert?.convertVoiceName,
            perLanguageEngine = settings.getEngineForLanguage(segTag),
            perLanguageVoiceName = settings.getVoiceForLanguage(segTag),
            defaultEngine = defaultCategoryEngine
        )
        val applyVoice = if (
            segment.role == SpeechRole.TEXT &&
            isAppVoiceMatchingLanguage(appVoice, segTag)
        ) {
            appVoice
        } else {
            if (appVoice != null && segment.role == SpeechRole.TEXT) {
                Log.i(
                    "NateqTtsService",
                    "App voice language mismatch for segment: " +
                        "voiceLang=${appVoice.voiceLanguage}, tag=$segTag"
                )
            }
            null
        }
        val categoryVoice = when (segment.role) {
            SpeechRole.EMOJI -> settings.getPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_EMOJI
            )
            SpeechRole.NUMBER -> settings.getPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
            SpeechRole.TEXT -> null
        }
        val categoryEngine = when (segment.role) {
            SpeechRole.EMOJI -> settings.getEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_EMOJI
            )
            SpeechRole.NUMBER -> settings.getEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
            SpeechRole.TEXT -> null
        }
        val resolvedVoice = VoiceResolutionPolicy.resolve(
            role = segment.role,
            languageTag = segTag,
            categoryVoice = categoryVoice,
            appVoice = applyVoice?.voiceId,
            contactVoice = null,
            languageDefaultVoice = routed.voiceName
                ?: settings.getPreferredVoiceId(segTag),
            categoryEngine = categoryEngine
        )
        val targetEngine = when (resolvedVoice.source) {
            VoiceResolutionPolicy.SOURCE_CATEGORY_EMOJI,
            VoiceResolutionPolicy.SOURCE_CATEGORY_NUMBER -> {
                categoryEngine ?: defaultCategoryEngine ?: routed.engine
            }
            VoiceResolutionPolicy.SOURCE_APP -> {
                applyVoice?.engine ?: routed.engine
            }
            else -> routed.engine
        }
        val preferredForEngine = targetEngine?.let {
            settings.getPreferredVoiceForEngine(it, segTag)
        }
        val targetVoiceName = resolvedVoice.voiceId
            ?: preferredForEngine
            ?: VoiceIdContract.createId(segTag)
        // **بند التشكيل الشرطي:** تُمرَّر حزمةُ المحرك المحسومة إلى
        // [TextProcessor.process] — المحركات التي تفهم التشكيل العربي
        // تحتفظ به في الناتج (مسار الاستعادة) بدل تجريده دائماً.
        val processed = if (segment.role == SpeechRole.EMOJI) {
            segment.text
        } else {
            textProcessor.process(
                segment.text, segTag, targetEngine,
                applyDictionary = false
            )
        }
        val speechText = prepareArabicForEngine(
            processed, segTag, targetEngine
        )
        val (voice, foundProvider) = resolveVoiceWithFallback(segTag)
        val provider = if (targetEngine != null) {
            catalog.findProvider(targetEngine) ?: foundProvider
        } else {
            foundProvider
        }
        if (provider == null) {
            Log.w(TAG,
            "synthesizeMixed: لا مزود لمقطع $segTag —" +
            " يُسقط وحده: ${segment.text}")
            return null
        }
        val isVoc = targetEngine ==
            VocalizerEmbeddedVoiceProvider.PROVIDER_ID ||
            provider.providerId ==
            VocalizerEmbeddedVoiceProvider.PROVIDER_ID
        val isGeneric = targetVoiceName.isNullOrBlank() ||
            targetVoiceName.equals("Device Default", true) ||
            targetVoiceName.equals("ar-EG", true) ||
            targetVoiceName.equals("en-US", true) ||
            targetVoiceName.equals("ar", true) ||
            targetVoiceName.equals("en", true)
        val isVocVoice = if (isVoc && !targetVoiceName.isNullOrBlank()) {
            VocalizerModelCatalog.ITEMS.any { item ->
                item.voiceName.equals(targetVoiceName, ignoreCase = true) ||
                    item.id.equals(targetVoiceName, ignoreCase = true) ||
                    targetVoiceName.contains(
                        item.voiceName,
                        ignoreCase = true
                    ) ||
                    (item.voiceName.equals("Laila", true) &&
                        targetVoiceName.contains("leila", true)) ||
                    (item.voiceName.equals("Tarik", true) &&
                        targetVoiceName.contains("tariq", true))
            }
        } else false
        val resolvedVoiceName = if (isVoc && (isGeneric || !isVocVoice)) {
            val installedAr = VocalizerModelCatalog.ITEMS.firstOrNull {
                LanguageCode.isArabic(it.languageCategory) &&
                    VocalizerModelCatalog.isVoiceInstalled(
                        applicationContext, it
                    )
            }?.voiceName
            val installedEn = VocalizerModelCatalog.ITEMS.firstOrNull {
                LanguageCode.isEnglish(it.languageCategory) &&
                    VocalizerModelCatalog.isVoiceInstalled(
                        applicationContext, it
                    )
            }?.voiceName
            if (LanguageCode.isArabic(segTag)) {
                installedAr ?: "Tarik"
            } else {
                installedEn ?: "Samantha"
            }
        } else if (isVoc && !targetVoiceName.isNullOrBlank()) {
            val matchedItem = VocalizerModelCatalog.ITEMS.firstOrNull {
                it.voiceName.equals(targetVoiceName, ignoreCase = true) ||
                    it.id.equals(targetVoiceName, ignoreCase = true) ||
                    targetVoiceName.contains(it.voiceName, ignoreCase = true) ||
                    (it.voiceName.equals("Laila", true) &&
                        targetVoiceName.contains("leila", true)) ||
                    (it.voiceName.equals("Tarik", true) &&
                        targetVoiceName.contains("tariq", true))
            }
            matchedItem?.voiceName ?: targetVoiceName
        } else {
            targetVoiceName
        }
        if (runCatching {
            settings.isLanguageDiagnosticsEnabled()
        }.getOrDefault(false)) {
            Log.d(
                "NateqLang",
                "[TTS-SEG] text='$speechText', role=${segment.role}, " +
                    "tag='$segTag', engine='$targetEngine', " +
                    "voice='$resolvedVoiceName', " +
                    "source='${resolvedVoice.source}'"
            )
        }
        val segmentCategory = when (segment.role) {
            SpeechRole.EMOJI -> SettingsRepository.VOICE_CATEGORY_EMOJI
            SpeechRole.NUMBER -> SettingsRepository.VOICE_CATEGORY_NUMBERS
            SpeechRole.TEXT -> effectCategory.takeIf { it.isNotEmpty() }
        }
        return SegmentParams(
            speechText, voice, provider, finalRate, finalPitch,
            finalVolume, targetEngine,
            if (matches) {
                convert?.convertLocale
                    ?: Locale.forLanguageTag(segTag)
            } else {
                Locale.forLanguageTag(segTag)
            },
            resolvedVoiceName,
            segmentCategory
        )
    }

    /** يخلّق مقطعاً واحداً ويجمع شريحاته الخام (نسخٌ مستقلة لأن المزوّد يعيد
     *  كل شريحةٍ لمسبحه بعد ندائها) — خطوةُ العمل الموازي في
     *  [synthesizeParallel].
     *
     *  **حارس عدم الإسقاط الصامت (الإصلاح الجذري):** كان الفشل يُعيد
     *  `null` فيُسقط المقطعُ بصمت — كلماتٌ كاملة تختفي بلا صوت ولا
     *  خطأ، فيبدو للنصّ «تحسّنٌ» لكنه لا يكتمل (شكواه المُبلَّغة بعد
     *  1.6.1). الآن يُقسَّم المقطعُ المتعثّر إلى أنصاف عند الفشل
     *  ([splitOnFailureBoundary]) فيُنطق كلُّ ما يُنطق منه، ولا يُنسى
     *  نصٌّ إلا بخطأٍ صريح لغيره. */
    /**
     * تنفيذ تخليق المقطع الفعلي مع تقسيم الأجزاء عند الفشل.
     */
    private suspend fun doSynthesizeSegment(
        segment: Segment,
        readerRate: Float,
        effectCategory: String = "",
        appVoice: EffectiveVoice? = null,
        reqVoiceName: String? = null,
        onResolved: ((engine: String?, voice: String?) -> Unit)? = null
    ): SegmentAudio? {
        val params = resolveSegmentParams(
            segment, readerRate, appVoice, reqVoiceName, effectCategory
        ) ?: return null
        onResolved?.invoke(params.engine, params.voiceName ?: params.voice.id)
        val chunks = ArrayList<ByteArray>()
        var nativeRate = 0
        var nativeChannels = 1
        val pieceBudgets = splitOnFailureBoundary(params.text)
        if (pieceBudgets.size > 1) {
            Log.w(
                TAG,
                "synthesizeSegment: المقطع ${segment.languageTag}" +
                    " (${params.text.length} حرفاً) سيُقسَّم إلى" +
                    " ${pieceBudgets.size} عند الفشل فقط"
            )
        }
        for (piece in pieceBudgets) {
            val produced = try {
                var pieceRate = 0
                var pieceChannels = 1
                params.provider.synthesize(
                    piece,
                    params.voice,
                    params.speechRate,
                    params.pitch,
                    params.volume,
                    { sampleRateInHz, channelCount ->
                        pieceRate = sampleRateInHz
                        pieceChannels = channelCount
                    },
                    { chunk, validLength ->
                        if (validLength > 0) {
                            chunks.add(chunk.copyOf(validLength))
                        }
                    },
                    params.engine,
                    params.locale,
                    params.voiceName,
                    params.category ?: effectCategory
                )
                if (pieceRate > 0) {
                    nativeRate = pieceRate
                    nativeChannels = pieceChannels
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "synthesizeSegment: جزء ${piece.length} حرفاً" +
                        " من مقطع ${segment.languageTag} فشل" +
                        " — يُتخطّى صراحةً لا بصمت",
                    t
                )
                false
            }
            if (!produced && pieceBudgets.size == 1) {
                return null
            }
        }
        return if (chunks.isEmpty()) null
        else SegmentAudio(nativeRate, nativeChannels, chunks)
    }

    /**
     * تخليق مقطع مع إعادة المحاولة مرة واحدة بعد 120ms عند الفشل، ثم
     * التراجع لصوت اللغة العادي، وتسجيل المقاطع المحذوفة
     * (الجولة 50 - الأمر 3 البند 1).
     */
    internal suspend fun synthesizeSegmentRaw(
        segment: Segment,
        readerRate: Float,
        effectCategory: String = "",
        appVoice: EffectiveVoice? = null,
        reqVoiceName: String? = null,
        onMetric: ((SpeechLatencyTracker.SegmentMetric) -> Unit)? = null
    ): SegmentAudio? {
        val startUptime = SystemClock.uptimeMillis()
        var attempts = 1
        var resolvedEngine: String? = null
        var resolvedVoiceId: String? = null

        var audio = doSynthesizeSegment(
            segment, readerRate, effectCategory, appVoice, reqVoiceName
        ) { eng, v ->
            resolvedEngine = eng
            resolvedVoiceId = v
        }

        if (audio == null) {
            attempts = 2
            delay(120L)
            audio = doSynthesizeSegment(
                segment, readerRate, effectCategory, appVoice, reqVoiceName
            ) { eng, v ->
                resolvedEngine = eng
                resolvedVoiceId = v
            }
        }

        if (audio == null) {
            attempts = 3
            val fallbackSegment = segment.copy(role = SpeechRole.TEXT)
            audio = doSynthesizeSegment(
                fallbackSegment, readerRate, "", null, null
            ) { eng, v ->
                resolvedEngine = eng
                resolvedVoiceId = v
            }
        }

        val endUptime = SystemClock.uptimeMillis()
        val success = (audio != null)
        if (!success) {
            Log.w(TAG, "مقطع محذوف: ${segment.text}")
            SpeechLatencyTracker.recordDroppedSegment(segment.text)
        }

        onMetric?.invoke(
            SpeechLatencyTracker.SegmentMetric(
                text = segment.text,
                engine = resolvedEngine,
                voiceId = resolvedVoiceId,
                startUptimeMs = startUptime,
                endUptimeMs = endUptime,
                durationMs = endUptime - startUptime,
                attempts = attempts,
                success = success
            )
        )

        return audio
    }

    private fun isCriticalNumericSegment(segment: Segment): Boolean {
        if (segment.role == SpeechRole.NUMBER) return true
        if (segment.isNumericOnly()) return true
        if (segment.text.any { it.isDigit() || it in '\u0660'..'\u0669' }) {
            return true
        }
        return NumberWordDetector.hasNumberWord(segment.text)
    }

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
        callback: SynthesisCallback,
        effectCategory: String = "",
        appVoice: EffectiveVoice? = null,
        reqVoiceName: String? = null,
        onFirstByteEmitted: (() -> Unit)? = null,
        onSegmentMetric: ((SpeechLatencyTracker.SegmentMetric) -> Unit)? = null
    ) {
        var started = false
        var skippedSegments = 0
        var hadCriticalDrop = false
        val maxBytes = callback.maxBufferSize
        val crossfader = ChunkCrossfader(CROSSFADE_FRAMES)
        for (segment in segments) {
            val segStart = SystemClock.uptimeMillis()
            val params = resolveSegmentParams(
                segment, readerRate, appVoice, reqVoiceName, effectCategory
            )
            if (params == null) {
                skippedSegments++
                if (isCriticalNumericSegment(segment)) {
                    hadCriticalDrop = true
                }
                Log.w(
                    TAG,
                    "synthesizeMixed: لا مزود لمقطع " +
                        "${segment.languageTag} — يُعدّ متجاوَزاً"
                )
                continue
            }
            var nativeRate = 0
            var nativeChannels = 1
            val phase = LongArray(2)
            for (piece in splitOnFailureBoundary(params.text)) {
                var producedHere = false
                try {
                    params.provider.synthesize(
                        piece,
                        params.voice,
                        params.speechRate,
                        params.pitch,
                        params.volume,
                        { sampleRateInHz, channelCount ->
                            nativeRate = sampleRateInHz
                            nativeChannels = channelCount
                        },
                        { chunk, validLength ->
                            producedHere = true
                            emitMixedChunk(
                                chunk, validLength, nativeRate,
                                nativeChannels, phase, crossfader,
                                maxBytes, { started },
                                {
                                    started = true
                                    onFirstByteEmitted?.invoke()
                                },
                                callback
                            )
                        },
                        params.engine,
                        params.locale,
                        params.voiceName,
                        params.category ?: effectCategory
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(
                        TAG,
                        "synthesizeMixed: جزء ${piece.length} حرفاً من" +
                            " مقطع ${segment.languageTag} فشل تخليقه",
                        t
                    )
                }
                if (!producedHere) {
                    skippedSegments++
                    if (isCriticalNumericSegment(segment)) {
                        hadCriticalDrop = true
                    }
                }
            }
            val segEnd = SystemClock.uptimeMillis()
            onSegmentMetric?.invoke(
                SpeechLatencyTracker.SegmentMetric(
                    text = segment.text,
                    engine = params.engine,
                    voiceId = params.voice.id,
                    startUptimeMs = segStart,
                    endUptimeMs = segEnd,
                    durationMs = segEnd - segStart,
                    attempts = 1,
                    success = !hadCriticalDrop
                )
            )
        }
        if (skippedSegments > 0) {
            Log.e(
                TAG,
                "synthesizeMixed: ${skippedSegments} جزءاً لم يُنطق" +
                    " — النصُّ الناطق ناقص (${segments.size} مقطعاً)"
            )
        }
        if (hadCriticalDrop) {
            callback.error()
            return
        }
        emitCrossfadeTail(
            crossfader, maxBytes, { started },
            {
                started = true
                onFirstByteEmitted?.invoke()
            },
            callback
        )
        if (!started) {
            callback.error()
            return
        }
        callback.done()
    }

    internal suspend fun synthesizeParallel(
        segments: List<Segment>,
        readerRate: Float,
        callback: SynthesisCallback,
        effectCategory: String = "",
        appVoice: EffectiveVoice? = null,
        reqVoiceName: String? = null,
        onFirstByteEmitted: (() -> Unit)? = null,
        onSegmentMetric: ((SpeechLatencyTracker.SegmentMetric) -> Unit)? = null
    ): Unit = coroutineScope {
        val semaphore = Semaphore(3)
        val deferreds = segments.map { segment ->
            async(AppDispatchers.io) {
                semaphore.withPermit {
                    synthesizeSegmentRaw(
                        segment, readerRate, effectCategory,
                        appVoice, reqVoiceName, onSegmentMetric
                    )
                }
            }
        }
        var started = false
        val maxBytes = callback.maxBufferSize
        val crossfader = ChunkCrossfader(CROSSFADE_FRAMES)
        var hadCriticalDrop = false

        for ((index, deferred) in deferreds.withIndex()) {
            val segment = segments[index]
            val audio = deferred.await()
            if (audio == null) {
                if (isCriticalNumericSegment(segment)) {
                    hadCriticalDrop = true
                    Log.w(
                        TAG,
                        "synthesizeParallel: dropped critical segment " +
                            segment.text
                    )
                    break
                }
                continue
            }
            val phase = LongArray(2)
            for (chunk in audio.chunks) {
                emitMixedChunk(
                    chunk, chunk.size,
                    audio.nativeRate, audio.nativeChannels,
                    phase, crossfader, maxBytes,
                    { started },
                    {
                        started = true
                        onFirstByteEmitted?.invoke()
                    },
                    callback
                )
            }
        }
        if (hadCriticalDrop) {
            callback.error()
            return@coroutineScope
        }
        emitCrossfadeTail(
            crossfader, maxBytes, { started },
            {
                started = true
                onFirstByteEmitted?.invoke()
            },
            callback
        )
        if (!started) {
            callback.error()
            return@coroutineScope
        }
        callback.done()
    }

    private suspend fun synthesizeMixed(
        segments: List<Segment>,
        readerRate: Float,
        callback: SynthesisCallback,
        effectCategory: String = "",
        appVoice: EffectiveVoice? = null,
        reqVoiceName: String? = null,
        onFirstByteEmitted: (() -> Unit)? = null,
        onSegmentMetric: ((SpeechLatencyTracker.SegmentMetric) -> Unit)? = null
    ) {
        val totalChars = segments.sumOf { it.text.length }
        if (segments.size > 1 && totalChars <= PARALLEL_MAX_CHARS) {
            synthesizeParallel(
                segments, readerRate, callback, effectCategory,
                appVoice, reqVoiceName, onFirstByteEmitted,
                onSegmentMetric
            )
        } else {
            synthesizeMixedSequential(
                segments, readerRate, callback, effectCategory,
                appVoice, reqVoiceName, onFirstByteEmitted,
                onSegmentMetric
            )
        }
    }

    private val commonNumberWords = listOf(
        "صفر", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة",
        "ستة", "سبعة", "ثمانية", "تسعة", "عشرة",
        "0", "1", "2", "3", "010", "011", "012", "015"
    )

    private suspend fun warmNumberVoiceCache() {
        val probe = Segment("واحد", LanguageCode.AR.tag, SpeechRole.NUMBER)
        val params = resolveSegmentParams(probe, 1.0f) ?: return
        for (word in commonNumberWords) {
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
                    params.voiceName,
                    SettingsRepository.VOICE_CATEGORY_NUMBERS
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.d(TAG, "warmNumberVoiceCache: word $word failed", t)
            }
        }
    }

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

    /** إعداد هدف فئة نطق الإيموجي للمقطع ذي الدور EMOJI. */
    internal fun emojiCategoryTarget(segment: Segment): ConvertTarget? {
        if (segment.role != SpeechRole.EMOJI) return null
        val voiceId = runCatching {
            settings.getPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_EMOJI
            )
        }.getOrNull() ?: return null
        val engine = runCatching {
            settings.getEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_EMOJI
            )
        }.getOrNull()
        val rate = runCatching {
            settings.getSpeechRateForCategory(
                SettingsRepository.VOICE_CATEGORY_EMOJI
            )
        }.getOrDefault(1.0f)
        val pitch = runCatching {
            settings.getPitchForCategory(
                SettingsRepository.VOICE_CATEGORY_EMOJI
            )
        }.getOrDefault(1.0f)
        val volume = runCatching {
            settings.getVolumeForCategory(
                SettingsRepository.VOICE_CATEGORY_EMOJI
            )
        }.getOrDefault(1.0f)
        val locale = if (segment.languageTag.isNullOrEmpty()) {
            null
        } else {
            Locale.forLanguageTag(segment.languageTag)
        }
        return ConvertTarget(
            convertEngine = engine,
            convertLocale = locale,
            convertRate = rate,
            convertPitch = pitch,
            convertVolume = volume,
            convertVoiceName = voiceId
        )
    }

    /** هدف التحويل لفئة الأرقام للمقطع الرقمي البحت — المسار الموازي
     *  المطلوب أن يُستدعى بدل [resolveConvertTarget] حصراً للمقاطع الرقمية
     *  البحتة: إن حفظ المستخدم صوتاً مخصصاً لفئة الأرقام
     *  ([SettingsRepository.VOICE_CATEGORY_NUMBERS]) يُلزَم الرقمُ البحت
     *  بصوتِ الفئة ومحركِها وأشرطتها — متجاوزاً الصوت العام للغة — بنفس
     *  منطق الاستعلامات في [TimeAnnouncementManager] (سرعة/نبرة/مستوى ثم
     *  الصوت ثم المحرك). بلا صوتٍ محفوظ للفئة → null فتبقى
     *  حلول [resolveConvertTarget] للغة كما كانت (لا كسر للتجربة
     *  الافتراضية). لا يتقيد بـ checkbox التحويل التلقائي لأن فئة الأرقام
     *  صوتٌ صريحٌ يُنطق به في الإعلانات على أي حال. */
    internal fun numbersCategoryTarget(segment: Segment): ConvertTarget? {
        if (segment.role != SpeechRole.NUMBER && !segment.isNumericOnly()) {
            return null
        }
        val voiceId = runCatching {
            settings.getPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
        }.getOrNull() ?: return null
        val engine = runCatching {
            settings.getEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
        }.getOrNull()
        val rate = runCatching {
            settings.getSpeechRateForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
        }.getOrDefault(1.0f)
        val pitch = runCatching {
            settings.getPitchForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
        }.getOrDefault(1.0f)
        val volume = runCatching {
            settings.getVolumeForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
        }.getOrDefault(1.0f)
        val locale = if (segment.languageTag.isNullOrEmpty()) {
            null
        } else {
            Locale.forLanguageTag(segment.languageTag)
        }
        val matches = VoiceResolutionPolicy.isVoiceLanguageMatch(
            voiceId, segment.languageTag
        )
        return ConvertTarget(
            convertEngine = if (matches) engine else null,
            convertLocale = locale,
            convertRate = rate,
            convertPitch = pitch,
            convertVolume = volume,
            convertVoiceName = if (matches) voiceId else null
        )
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

/**
 * مغلّف آمن لمراقب تقدّم دفق الصوت في
 * [SynthesisCallback]:
 * 1) يضمن استدعاء start مرة واحدة وقبل
 *    أول دفعة صوت.
 * 2) يقسّم دفعات audioAvailable بحيث لا
 *    تتجاوز maxBufferSize أبداً.
 * 3) يضمن استدعاء done أو error مرة
 *    واحدة حتمية.
 * 4) يرصد التقدّم لتصفير عدّاد خمول
 *    حارس التخليق المتكيّف.
 */
internal class MonitoredSynthesisCallback(
    private val delegate: SynthesisCallback,
    private val onProgress: (bytesWritten: Int) -> Unit
) : SynthesisCallback by delegate {

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val finished = java.util.concurrent.atomic.AtomicBoolean(false)

    val hasStarted: Boolean
        get() = started.get()

    override fun start(
        sampleRateInHz: Int,
        audioFormat: Int,
        channelCount: Int
    ): Int {
        if (finished.get()) return TextToSpeech.ERROR
        if (!started.compareAndSet(false, true)) {
            return TextToSpeech.SUCCESS
        }
        onProgress(0)
        return delegate.start(sampleRateInHz, audioFormat, channelCount)
    }

    override fun audioAvailable(
        buffer: ByteArray?,
        offset: Int,
        length: Int
    ): Int {
        if (finished.get() || buffer == null || length <= 0) {
            return TextToSpeech.SUCCESS
        }
        if (!started.get()) {
            start(22050, AudioFormat.ENCODING_PCM_16BIT, 1)
        }
        onProgress(length)
        val maxChunk = delegate.maxBufferSize
        var currentOffset = offset
        var remaining = length
        var result = TextToSpeech.SUCCESS
        while (remaining > 0) {
            val toWrite = minOf(maxChunk, remaining)
            result = delegate.audioAvailable(buffer, currentOffset, toWrite)
            if (result != TextToSpeech.SUCCESS) break
            currentOffset += toWrite
            remaining -= toWrite
        }
        return result
    }

    override fun done(): Int {
        if (!started.get()) {
            error()
            return TextToSpeech.ERROR
        }
        if (!finished.compareAndSet(false, true)) {
            return TextToSpeech.SUCCESS
        }
        onProgress(0)
        return delegate.done()
    }

    override fun error() {
        if (!finished.compareAndSet(false, true)) {
            return
        }
        onProgress(0)
        delegate.error()
    }

    override fun error(errorCode: Int) {
        if (!finished.compareAndSet(false, true)) {
            return
        }
        onProgress(0)
        delegate.error(errorCode)
    }
}

