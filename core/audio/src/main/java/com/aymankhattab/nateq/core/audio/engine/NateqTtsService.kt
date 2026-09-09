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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    private lateinit var settings: SettingsRepository
    private lateinit var catalog: VoiceCatalog
    private lateinit var requestHandler: SynthesisRequestHandler
    private lateinit var textProcessor: TextProcessor

    /** مقسم النصوص المختلطة الكتابات (منطق نقي مشترك بلا حالة). */
    private val segmenter = LanguageSegmenter()

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
        else SettingsRepository(applicationContext)

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
        // إيقاف صريح (لكن هذا الخيط فرعيٌّ عبر كل الخدمات
        // الأساسية): نرفع العلم ثم نلغي التخليق الجاري —
        // الطلبات اللاحقة تُلغى كاستباق لا كإيقاف.
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

        // التخليق يُنفَّذ على Coroutine داخل serviceScope (IO)
        // وهو غير حاجز بالكامل: onSynthesizeText ترجع فوراً
        // ويستلم النظام الصوت لاحقاً عبر callbacks من خيط
        // المزوّد — السلوك القياسي لمحركات TTS غير
        // المتزامنة (MultiTTS/espeak). لو علِق المحرك
        // الطرفي تُنهي مهله الداخلية المتكيّفة
        // (1.5–8 ث داخل SystemVoiceProvider) الطلبَ بدل
        // تعليق الخيط بلا سقف.
        // **معالجة السباق:** عند وصول طلبٍ جديد تُلغى
        // الرحلة السابقة النشطة قبل إطلاق الجديدة حتى لا
        // تتكدس الكوروتينات ولا تتزامن طلبات الصوت عبر
        // البث الصوتي (يستبِق الأحدثُ الأقدم — سلوك قارئ
        // الشاشة عند التمرير السريع). إلغاءٌ من onStop()
        // يُبطل currentJob فتتوقف استجابة الصوت فوراً؛
        // وإلغاءٌ بالاستباق يُنهي callback الطلب القديم
        // بـ error() فينتقل طابور النظام للطلب الجديد
        // (دونها يعلق الـ queue فلا يُنطق شيء).
        // (يبدأ callback.start() لاحقاً بمعدل العينات
        // الفعلي من المزوّد، لتعامل ملفات 24k/44.1k
        // بسرعةٍ ونبرةٍ صحيحة.)
        stopping = false
        currentJob?.cancel()
        currentJob = serviceScope.launch {
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
                val rawText = request.charSequenceText.toString()
                val segments = segmenter.segment(rawText, languageTag)

                if (segments.size == 1) {
                    // نص بلغةٍ واحدة: نفس التدفق التفصيلي السابق حرفياً بلا أي
                    // تغيير سلوكي — صفر تكلفة للمسار الأكثر شيوعاً.
                    synthesizeSingle(rawText, languageTag, callback, request)
                } else {
                    // نص مختلط الكتابات: نطق كل مقطع بلغته/محركه ثم مزج الصوت
                    // بمعدلٍ موحّد عبر بثٍّ واحد (مونو).
                    synthesizeMixed(segments, callback)
                }
            } catch (e: CancellationException) {
                // إبطال صريح: الإيقاف (onStop) معروف للنظام فلا نُطلق خطأً
                // زائفاً، أما الاستباق بطلبٍ جديد فنُنهي الـ callback حتى لا
                // يعلق طابور النظام بطلبٍ ميت فيُحرر الصوت للطلب الأحدث.
                if (stopping) {
                    Log.d(TAG, "onSynthesizeText cancelled (stop)")
                } else {
                    Log.d(TAG, "onSynthesizeText cancelled (preempt)")
                    try {
                        callback.error()
                    } catch (_: Exception) {
                        // الخدمة قد تكون في طريقها للإيقاف — لا شيء نفعله.
                    }
                }
            } catch (e: Exception) {
                callback.error()
            }
        }
        // لا ننتظر انتهاء التخليق (لا runBlocking): الإرجاع
        // فوري والـ callbacks تُستلم لاحقاً من خيط المزوّد
        // — النطق غير حاجز بالكامل كما هو موثّق أعلاه.
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
     * مقطعٌ يعجز محركُه عن التخليق يُسقط وحده (يُسجَّل ويُكمل البقية) بدل قطع
     * النطق كلياً؛ وإن فشل الكل تُرك callback.error() كملاذٍ أخير. */
    private suspend fun synthesizeMixed(
        segments: List<Segment>,
        callback: SynthesisCallback
    ) {
        var started = false
        val maxBytes = callback.maxBufferSize
        for (segment in segments) {
            val segTag = segment.languageTag
            val processed = textProcessor.process(segment.text, segTag)
            val (voice, foundProvider) = resolveVoiceWithFallback(segTag)
            val provider = foundProvider
            if (provider == null) {
                Log.w(TAG,
                "synthesizeMixed: لا مزود لمقطع $segTag —" +
                " يُسقط وحده: ${segment.text}")
                continue
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
            val finalEngine = routed.engine
            val finalLocale = if (matches) convert?.convertLocale else null
            val finalVoiceName = routed.voiceName

            // بث المقطع فور إنتاجه: شريحة المزوّد تُعاد معاينتها إلى المعيار
            // الموحّد وتُدفع للـ callback مباشرةً — لا تُجمَع مع مقاطع أخرى ولا
            // يبقى مرجعها بعد عودة المعالج (المزوّد يعيد شريحته للمسبح بعدها).
            var nativeRate = 0
            var nativeChannels = 1
            try {
                provider.synthesize(
                    processed,
                    voice,
                    finalRate,
                    finalPitch,
                    finalVolume,
                    { sampleRateInHz, channelCount ->
                        nativeRate = sampleRateInHz
                        nativeChannels = channelCount
                    },
                    { chunk, validLength ->
                        // إن لم يُبلِّغ المزوّد بالمعدل قبل الشريحة
                        // (مسارات طارئة) نعتبره معيار LORD الأساسي
                        // لئلا يُبثّ معدلٌ غير معلوم.
                        val rate = if (nativeRate > 0) {
                            nativeRate
                        } else {
                            MIN_UNIFIED_RATE
                        }
                        val mono = PcmResampler.convert(
                            chunk, rate, nativeChannels, MIXED_UNIFIED_RATE
                        )
                        if (mono.isEmpty()) return@synthesize
                        if (!started) {
                            callback.start(
                                /* sampleRateInHz = */ MIXED_UNIFIED_RATE,
                                /* audioFormat = */ PCM_16BIT,
                                /* channelCount = */ 1
                            )
                            started = true
                        }
                        var offset = 0
                        while (offset < mono.size) {
                            val bytesToWrite = minOf(
                                maxBytes, mono.size - offset
                            )
                            callback.audioAvailable(mono, offset, bytesToWrite)
                            offset += bytesToWrite
                        }
                    },
                    finalEngine,
                    finalLocale,
                    finalVoiceName
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG,
                "synthesizeMixed: مقطع $segTag فشل تخليقه —" +
                " يُسقط وحده", t)
            }
        }
        if (!started) {
            callback.error()
            return
        }
        callback.done()
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
        val prefs = settings.getEnginePreferenceForLanguage(
            requestLang ?: "und"
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

        return ConvertTarget(
            convertEngine = engine,
            convertLocale = null,
            convertRate = rate,
            convertPitch = pitch,
            convertVolume = volume,
            convertVoiceName = voiceName
        )
    }

    /** بيانات هدف التحويل التلقائي المرفوعة إلى [SystemVoiceProvider]. */
    data class ConvertTarget(
        // المحرك/الصوت null عند عدم تفعيل التبديل (يتزامن مع المحدد يدوياً).
        // الوجهة (locale) لم تعد تُخزَّن صراحةً: المحرك + اسم الصوت داخل
        // [getEnginePreferenceForLanguage] يحددان لغة النطق الفعلية وتُقرأ
        // الخريطة بمفتاح لغة النص الطالب نفسها.
        val convertEngine: String?,
        val convertLocale: Locale?,
        val convertRate: Float,
        val convertPitch: Float,
        val convertVolume: Float,
        // اسم الصوت المختار داخل المحرك (اختياري — يُطبَّق إن وُجد بالمحرك).
        val convertVoiceName: String?
    )
}
