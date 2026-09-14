package com.aymankhattab.nateq.core.audio.engine

import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

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

    /** يُميّز سبب إلغاء [currentJob]: إيقاف صريح (onStop) أم استباق بطلبٍ جديد.
     *  عند الإيقاف لا نُنشئ خطأً زائفاً (النظام يعرف أنه أُوقف عمداً)، وعند
     *  الاستباق نُنهي callback الطلب القديم حتى لا يعلق طابور النظام فينتقل
     *  للطلب الجديد. */
    @Volatile private var stopping = false

    @Volatile private var currentLanguage = arrayOf(LanguageCode.AR.tag, "", "")

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

        // اكتشاف اللغات المتاحة عبر كل محركات TTS المثبتة كخلفية: يملأ ذاكرة
        // الكتالوج دون أن يُعقّل إنشاء الخدمة أبداً؛ وحتى لو تعذّر يبقى حد
        // ar/en المضمون قائماً فتبقى الخدمة تُنطق دائماً.
        serviceScope.launch {
            try {
                maybeRefreshDiscovery()
            } catch (t: Throwable) {
                Log.w(TAG, "الاكتشاف الخلفي الأولي للغات فشل", t)
            }
        }
        // تدفئة كاش PCM بكلمات التنقل الشائعة (بند ب.txt 3.5-1): بعد أن يمتلئ
        // كتالوجُ اللغات بالأصوات تُخلَّق كلماتُ التنقل عبر المسار الكامل
        // فيُخزَّن نطقُها مسبقاً ويُبثّ من الذاكرة عند أول مطالبة — تعثرٌ في
        // جملةٍ يتركها بلا كاش ولا يمسّ النطق.
        serviceScope.launch {
            try {
                maybeRefreshDiscovery()
                warmNavigationCache()
            } catch (t: Throwable) {
                Log.w(TAG, "تدفئة كاش كلمات التنقل فشلت", t)
            }
        }

        // ملفوف بحمايات حتى لا تنهار الخدمة عند أي خطأ تهيئة — لو انهارت هنا
        // يرفض نظام سامسونج المحرك برسالة "يستمر التطبيق في التوقف".
        try {
            super.onCreate()
        } catch (t: Throwable) {
            Log.e(TAG, "super.onCreate() threw", t)
        }
    }

override fun onDestroy() {
        // إلغاء كل العمليات اللاتزامنية المعلّقة للخدمة حتى لا تتسرب مع عمر
        // عملية المحرك، ثم إغلاق النطق الجاري إن وُجد.
        currentJob?.cancel()
        serviceScope.cancel()
        synthesisScope.cancel()
        synthesisExecutor.shutdown()
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

    override fun onSynthesizeText(
        request: SynthesisRequest?,
        callback: SynthesisCallback?
    ) {
        if (request == null || callback == null) return
        // سجلّ مجرّد: طول النص واللغة فقط
        // (النص قد يحوي OTP/حساسيات يقرؤها TalkBack).
        val reqText = request.charSequenceText?.toString()
        Log.d(TAG,
            "onSynthesizeText() len=${reqText?.length}" +
            " lang=${request.language}")

        // تطبيع لغة الطلب من ISO-3 (eng, ara) إلى ISO-2 (en, ar) حتى يبقى
        // حل الصوت والكتالوج متسقين مع اللغتين المدعومتين (العربية/الإنجليزية).
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
        val job = synthesisScope.launch {
            try {
                // إعادة تحميل الإعدادات من القرص لأن `:tts`
                // process منفصل عن عملية الإعدادات
                // (SettingsActivity)، وSharedPreferences لا
                // يتشارك عبر العمليات. بدون reload() تبقى
                // القيم القديمة محشوة في الذاكرة.
                settings.reload()
                // **بند 17 — النصوص المختلطة واللغات:**
                // 1) تقسيم النص المختلط الكتابات (عربي/إنجليزي/غيرها) إلى مقاطع
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

                if (segments.size == 1) {
                    // **بند 2.5:** حتى النص المفرد تُستعمل لغةُ المقطع
                    // المكتشفة للتوجيه لا لغةُ الطلب الأصلية — كلمةٌ إنجليزية
                    // وحيدة («Settings»، «Cancel») داخل واجهة عربية لم تعد
                    // تُنطق بصوت/محرك العربية أو تتعثّر: تُوجَّه لمحركها
                    // المناسب فوراً.
                    synthesizeSingle(
                        semanticText, segments[0].languageTag,
                        callback, request
                    )
                } else {
                    // نص مختلط الكتابات: نطق كل مقطع بلغته/محركه ثم مزج الصوت
                    // بمعدلٍ موحّد عبر بثٍّ واحد (مونو).
                    synthesizeMixed(segments, callback)
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
        try {
            // انتظار متزامن على خيط التخليق حتى اكتمال التوليف (معيار AOSP)؛
            // التقاط Throwable يُبقي الخدمة حية حتى لو قذف التخليق خطأً
            // غير متوقع — لا انهيار لخيط النظام إطلاقاً.
            runBlocking { job.join() }
        } catch (t: Throwable) {
            Log.w(TAG, "onSynthesizeText join interrupted", t)
        } finally {
            currentJob = null
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
        request: SynthesisRequest
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
        val convertTarget = resolveConvertTarget(request.language)
        // السرعة: نجمع بين قناة قارئ الشاشة وقناة إعداد LORD نفسه.
        // - تفضيل LORD الصريح لهذه اللغة أولاً — يحتسب ولو كان 1.0x (قد يريده
        //   المستخدم «طبيعياً» بينما السرعة العامة 1.5x).
        // - ثم السرعة العامة المخزّنة (≠1.0) حتى يؤثر إعداد «ناطق» فعلاً.
        // - وإلا (لم يعرّف LORD شيئاً) نستعمل سرعة القارئ
        //   (request.getSpeechRate())
        //   فيُتبع النظامُ/القارئ ولا يُعطَّل قارئ شاشة النظام بلا تفضيل LORD.
        val explicitLordRate =
            requestHandler.getExplicitLanguageRate(languageTag)
        val lordRate = requestHandler.getSpeechRate(languageTag)
        val reqRate = request.getSpeechRate().toFloat()
        val speechRate: Float = when {
            explicitLordRate != null -> explicitLordRate
            lordRate != 1.0f -> lordRate
            reqRate > 0f -> reqRate
            else -> lordRate
        }
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
            " lordRate=$lordRate reqRate=$reqRate" +
            " usedRate=$speechRate voice=${voice.id}" +
            " provider=${provider.providerId}" +
            " autoConvert=$autoConvert")

        // Process text through TextProcessor (numbers, dates, currencies, etc.)
        val processedText = textProcessor.process(rawText, languageTag)

        // **توجيه locale حسب لغة النص:** engine/locale من التحويل لا يُمرَّران
        // إلا إذا كانت لغة الهدف تطابق لغة النص الطالبة. هذا يمنع إعادة توجيه
        // النص الإنجليزي إلى محرك/لغة عربية (locale=ar) وبالعكس، مع بقاء
        // أشرطة السرعة/النبرة/الصوت تُطبّق دائماً على النص نفسه.
        val convertLang = convertTarget?.convertLocale?.language
        val normLanguage = normalizeLanguageCode(request.language)
        val matchesRequest = convertLang == null || normLanguage == convertLang
                || (normLanguage == LanguageCode.AR.tag && convertLang == "ara")
                || (normLanguage == LanguageCode.EN.tag && convertLang == "eng")

        val finalRate = convertTarget?.let { it.convertRate } ?: speechRate
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
        segment: Segment
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
        val finalRate = convert?.convertRate ?: segRate
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
        segment: Segment
    ): SegmentAudio? {
        val params = resolveSegmentParams(segment) ?: return null
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

    /** يعيد معاينة شريحةِ مقطعٍ محلي إلى المعيار الموحّد ويبثّها بالترتيب —
     *  الشيفرة المشتركة بين المسارين المتسلسل والمتوازي. المخزنُ من المسبح
     *  يُردّ في finally على كل المصائر (المتلقي ينسخ الشريحة عبر
     *  audioAvailable).
     *
     *  [phase] يحمل الطورَ الكسري لإعادة العينات عبر دفعاتِ المقطع نفسه
     *  (بند 2.7): نسخة [PcmResampler.convertInto] الحاملة للطور تكون
     *  مستمرةً فلا تتقبّط عند حدود الدفعة، ويُمرّر المتصل [phase] نفسَه
     *  لكلَّ دفعاتِ مقطعٍ واحدٍ (LongArray(2) طازج لكل مقطعٍ بعد ضبط
     *  معدله وقنواته) فيبقى الاتساقُ موكولاً إليه. */
    private fun emitMixedChunk(
        chunk: ByteArray,
        validLength: Int,
        nativeRate: Int,
        nativeChannels: Int,
        phase: LongArray,
        maxBytes: Int,
        started: () -> Boolean,
        markStarted: () -> Unit,
        callback: SynthesisCallback
    ) {
        val rate = if (nativeRate > 0) nativeRate else MIN_UNIFIED_RATE
        val required = PcmResampler.convertedByteCount(
            chunk, 0, validLength, rate,
            nativeChannels, MIXED_UNIFIED_RATE
        )
        if (required <= 0) return
        // +2 بايت: النافذةُ المستمرة قد تُخرج فريماً زائداً عن التقدير
        // الطازج (نصفَ فريمٍ متبقياً عبر حدود الدفعة) فلا يُقصّ صوتٌ
        // عند كل فتحة بين دفعتين.
        val mono = pcmBufferPool.acquire(required + 2)
        try {
            val written = PcmResampler.convertInto(
                chunk, 0, validLength, rate,
                nativeChannels, MIXED_UNIFIED_RATE, mono, 0, phase
            )
            if (written <= 0) return
            if (!started()) {
                callback.start(
                    /* sampleRateInHz = */ MIXED_UNIFIED_RATE,
                    /* audioFormat = */ PCM_16BIT,
                    /* channelCount = */ 1
                )
                markStarted()
            }
            var offset = 0
            while (offset < written) {
                val bytesToWrite = minOf(
                    maxBytes, written - offset
                )
                callback.audioAvailable(mono, offset, bytesToWrite)
                offset += bytesToWrite
            }
        } finally {
            pcmBufferPool.release(mono)
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
        callback: SynthesisCallback
    ) {
        var started = false
        val maxBytes = callback.maxBufferSize
        for (segment in segments) {
            val params = resolveSegmentParams(segment) ?: continue
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
                            nativeChannels, phase, maxBytes,
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
        callback: SynthesisCallback
    ) {
        val audios = coroutineScope {
            segments.map { segment ->
                async(AppDispatchers.io) {
                    synthesizeSegmentRaw(segment)
                }
            }.map { it.await() }
        }
        var started = false
        val maxBytes = callback.maxBufferSize
        for (audio in audios) {
            if (audio == null) continue
            val phase = LongArray(2)
            for (chunk in audio.chunks) {
                emitMixedChunk(
                    chunk, chunk.size,
                    audio.nativeRate, audio.nativeChannels,
                    phase, maxBytes, { started }, { started = true },
                    callback
                )
            }
        }
        if (!started) {
            callback.error()
            return
        }
        callback.done()
    }

    /** النص المختلط الكتابات: لكل مقطعٍ لغوي يُعالَج النص بدليل لغته (العربية
     *  بقنواتها الكاملة وسواها بالتنظيف فقط)، ويُحل صوت المقطع من كتالوجه أو من
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
        callback: SynthesisCallback
    ) {
        val totalChars = segments.sumOf { it.text.length }
        if (segments.size > 1 && totalChars <= PARALLEL_MAX_CHARS) {
            synthesizeParallel(segments, callback)
        } else {
            synthesizeMixedSequential(segments, callback)
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
            if (stopping) break
            val words = if (lang == LanguageCode.AR.tag) {
                navigationWordsAr
            } else {
                navigationWordsEn
            }
            val probe = Segment(words.firstOrNull() ?: "نعم", lang)
            val params = resolveSegmentParams(probe) ?: continue
            for (word in words) {
                if (stopping) break
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
        if (!catalog.needsRefresh(DISCOVERY_TTL_MS)) return
        val discovered = runCatching {
            VoiceCatalog.discoverAllLanguagesAcrossEngines(applicationContext)
        }.getOrDefault(emptyMap())
        catalog.applyDiscovery(discovered)
        Log.d(TAG,
            "maybeRefreshDiscovery: ${discovered.size} لغة" +
            " عبر كل المحركات المثبتة")
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
    private fun resolveConvertTarget(requestLang: String?): ConvertTarget? {
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
            && engine == null
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
