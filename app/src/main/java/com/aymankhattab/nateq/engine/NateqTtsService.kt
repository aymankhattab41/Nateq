package com.aymankhattab.nateq.engine

import android.content.Intent
import android.os.Build
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.aymankhattab.nateq.providers.SystemVoiceProvider
import com.aymankhattab.nateq.settings.SettingsRepository
import com.aymankhattab.nateq.util.LocaleUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var settings: SettingsRepository
    private lateinit var catalog: VoiceCatalog
    private lateinit var requestHandler: SynthesisRequestHandler
    private lateinit var textProcessor: TextProcessor

    @Volatile private var currentJob: kotlinx.coroutines.Job? = null

    @Volatile private var currentLanguage = arrayOf("ar", "", "")

    override fun onCreate() {
        // مهم: TextToSpeechService.onCreate() يستدعي onLoadLanguage()/onIsLanguageAvailable()
        // قبل انتهاء استدعاء super.onCreate()، لذلك يجب تهيئة كل الـ lateinit
        // كأول شيء هنا (قبل super.onCreate()) وإلا تنهار الخدمة في حلقة على الإنشاء.
        // applicationContext متاح فور إنشاء كائن الخدمة، وإنشاء هذه الكائنات النقية
        // (غير المرتبطة بدورة حياة Android) آمن تماماً في هذا الموضع.
        // عند الاستدعاء من TalkBack/النظام بُني الكائن عبر Hilt (Hilt_...) فيكون
        // settingsRepository محقوناً؛ ونبني بقية الشبكة بعناية قبل super.
        // حماية ثانية: إن فشل الحقن لأي سبب نتراجع لكائن محلي حتى لا تنهار
        // الخدمة قبل super.onCreate() في حلقة (طبّاق لتوقيت TextToSpeechService).
        settings = if (::settingsRepository.isInitialized) settingsRepository
        else SettingsRepository(applicationContext)

        val dict = if (::pronunciationDictionary.isInitialized) pronunciationDictionary
        else PronunciationDictionary(applicationContext)

        val providers = listOf(SystemVoiceProvider(applicationContext, settings))
        catalog = VoiceCatalog(providers)
        requestHandler = SynthesisRequestHandler(catalog, settings)
        textProcessor = TextProcessor(applicationContext, settings, dict)

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
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        Log.d(TAG, "onIsLanguageAvailable() lang=$lang country=$country variant=$variant")
        refreshDiscoveryIfNeeded()
        if (lang == null) return TextToSpeech.LANG_NOT_SUPPORTED

        val normLang = normalizeLanguageCode(lang)
        val normCountry = normalizeCountryCode(country)
        val locales = catalog.supportedLocales()

        // تطابق تام لأي لغة تدعمها الأصوات الفعلية (بعد تطبيع الكود)
        if (locales.any { it.language == normLang }) {
            return if (normCountry != null && locales.any { it.language == normLang && it.country == normCountry }) {
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> = currentLanguage

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        Log.d(TAG, "onLoadLanguage() lang=$lang country=$country variant=$variant")
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
    private fun normalizeLanguageCode(code: String?): String = LocaleUtils.normalizeLanguageCode(code)

    /**
     * يطبّع كود البلد من ISO-3 (EGY, USA) إلى ISO-2 (EG, US).
     * موحّد في [LocaleUtils.normalizeCountryCode].
     */
    private fun normalizeCountryCode(code: String?): String? = LocaleUtils.normalizeCountryCode(code)

    // =====================================================
    //  الأصوات (Voices) — المصدر الوحيد لقائمة
    //  "تعيين الصوت الخاص بلغة النص المنطوق" في إعدادات TTS.
    //  بدون هذه الدوال يُعيد TextToSpeechService الأب قائمة فارغة.
    // =====================================================

    override fun onGetVoices(): MutableList<Voice> {
        Log.d(TAG, "onGetVoices() CALLED")
        refreshDiscoveryIfNeeded()
        val voices = catalog.supportedVoices()
        Log.d(TAG, "onGetVoices() returning ${voices.size} voices: ${voices.map { it.name }}")
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
        @Suppress("UNUSED_VARIABLE") val voice = catalog.supportedVoices().find { it.name == voiceName }
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
        Log.d(TAG, "onGetDefaultVoiceNameFor() lang=$lang -> norm=$normLang -> $name")
        return name
    }

    override fun onStop() {
        currentJob?.cancel()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        // سجلّ مجرّد: طول النص واللغة فقط (النص قد يحوي OTP/حساسيات يقرؤها TalkBack).
        val reqText = request.charSequenceText?.toString()
        Log.d(TAG, "onSynthesizeText() len=${reqText?.length} lang=${request.language}")

        // تطبيع لغة الطلب من ISO-3 (eng, ara) إلى ISO-2 (en, ar) حتى يبقى
        // حل الصوت والكتالوج متسقين مع اللغتين المدعومتين (العربية/الإنجليزية).
        val normLanguage = normalizeLanguageCode(request.language)
        val normCountry = normalizeCountryCode(request.country)
        val languageTag = Locale.forLanguageTag(
            if (normCountry.isNullOrEmpty()) normLanguage else "$normLanguage-$normCountry"
        ).toLanguageTag()

        // التخليق يُنفَّذ على Coroutine داخل serviceScope (IO) وهو غير حاجز
        // بالكامل: onSynthesizeText ترجع فوراً ويستلم النظام الصوت لاحقاً عبر
        // callbacks من خيط المزوّد — السلوك القياسي لمحركات TTS غير المتزامنة
        // (MultiTTS/espeak). لو علِق المحرك الطرفي تُنهي مهله الداخلية المتكيّفة
        // (1.5–8 ث داخل SystemVoiceProvider) الطلبَ بدل تعليق الخيط بلا سقف.
        // إلغاء onStop() يُبطل currentJob فتتوقف استجابة الصوت فوراً.
        // (يبدأ callback.start() لاحقاً بمعدل العينات الفعلي من المزوّد،
        //  لتعامل ملفات 24k/44.1k بسرعةٍ ونبرةٍ صحيحة.)
        currentJob = serviceScope.launch {
            try {
                // إعادة تحميل الإعدادات من القرص لأن `:tts` process منفصل
                // عن عملية الإعدادات (SettingsActivity)، وSharedPreferences لا يتشارك
                // عبر العمليات. بدون reload() تبقى القيم القديمة محشوة في الذاكرة.
                settings.reload()
                // **التحويل التلقائي بين اللغات**:
                // نفحص لغة الطلب ونحسب هدف التحويل. قيم الصوت (rate/pitch/volume)
                // من حوار اللغة تُطبَّق دائماً، بينما تُفعَّل المحرك/اللغة فقط عند
                // تفعيل checkbox (تُدار داخلياً في resolveConvertTarget).
                val autoConvert = requestHandler.isAutoConvertEnabled()
                val convertTarget = resolveConvertTarget(request.language)

                val voice = requestHandler.resolveVoiceForLocale(languageTag)
                val provider = voice?.let { catalog.findProvider(it.providerId) }
                // السرعة: نجمع بين قناة قارئ الشاشة وقناة إعداد LORD نفسه.
                // - إذا ضبط المستخدم في سرعة LORD للغة/الافتراضية قيمةً مخزّنة
                //   (≠1.0) فالأولوية لها حتى يؤثر إعداد LORD فعلاً.
                // - وإلا (الافتراضي 1.0) نستعمل سرعة القارئ (request.getSpeechRate())
                //   فيُتبع النظامُ/القارئ عندما لا يعرّف LORD قيمةً خاصة.
                val lordRate = requestHandler.getSpeechRate(languageTag)
                val reqRate = request.getSpeechRate().toFloat()
                val speechRate: Float =
                    if (lordRate != 1.0f) lordRate else if (reqRate > 0f) reqRate else lordRate
                val pitch = requestHandler.getPitch(languageTag)
                val volume = requestHandler.getVolume(languageTag)

                // عند التفعيل نغلب إعدادات التحويل (السرعة/النبرة/الصوت) ونتجاهل
                // صوت كتالوج LORD ضمنياً — نقدّم للمزوّد محركاً ولغةً محددين.
                if (convertTarget != null) {
                    Log.d(TAG, "onSynthesizeText AUTO-CONVERT lang=$languageTag engine=${convertTarget.convertEngine} loc=${convertTarget.convertLocale} rate=${convertTarget.convertRate}")
                }
                Log.d(TAG, "onSynthesizeText lang=$languageTag lordRate=$lordRate reqRate=$reqRate usedRate=$speechRate voice=${voice?.id} provider=${provider?.providerId} autoConvert=$autoConvert")

                if (voice == null || provider == null) {
                    Log.e(TAG, "onSynthesizeText لا صوت/مزود: voice=$voice provider=$provider")
                    callback.error()
                    return@launch
                }

                // Process text through TextProcessor (numbers, dates, currencies, etc.)
                val processedText = textProcessor.process(request.charSequenceText.toString(), languageTag)

                // **توجيه locale حسب لغة النص:** engine/locale من التحويل لا يُمرَّران
                // إلا إذا كانت لغة الهدف تطابق لغة النص الطالبة. هذا يمنع إعادة توجيه
                // النص الإنجليزي إلى محرك/لغة عربية (locale=ar) وبالعكس، مع بقاء
                // أشرطة السرعة/النبرة/الصوت تُطبّق دائماً على النص نفسه.
                val convertLang = convertTarget?.convertLocale?.language
                val matchesRequest = convertLang == null || normLanguage == convertLang
                        || (normLanguage == "ar" && convertLang == "ara")
                        || (normLanguage == "en" && convertLang == "eng")

                val finalRate = convertTarget?.let { it.convertRate } ?: speechRate
                val finalPitch = convertTarget?.let { it.convertPitch } ?: pitch
                val finalVolume = convertTarget?.let { it.convertVolume } ?: volume
                val finalEngine = if (matchesRequest) convertTarget?.let { it.convertEngine } else null
                val finalLocale = if (matchesRequest) convertTarget?.let { it.convertLocale } else null
                val finalVoiceName = if (matchesRequest) convertTarget?.let { it.convertVoiceName } else null

                // تخليق الصوت الفعلي عبر المزوّد. يُبلّغنا التنسيق (معدل عينات/قنوات) قبل
                // أول شريحة، فنبدأ callback.start بالقيم الفعلية بدل 22050 الثابتة التي
                // كانت تجعل Android يشغّل ملفات 24k/44.1k بسرعة ونبرة خاطئتين.
                var started = false
                provider.synthesize(processedText, voice, finalRate, finalPitch, finalVolume, { sampleRateInHz, channelCount ->
                    if (!started) {
                        callback.start(
                            /* sampleRateInHz = */ sampleRateInHz,
                            /* audioFormat = */ android.media.AudioFormat.ENCODING_PCM_16BIT,
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
                            /* audioFormat = */ android.media.AudioFormat.ENCODING_PCM_16BIT,
                            /* channelCount = */ 1
                        )
                        started = true
                    }
                    // المنهج المُثبَت (كما في TtsService الرسمي لـ espeak-ng/MultiTTS):
                    // لا يجوز تمرير كامل المخزن المؤقت دفعةً واحدة؛ يُقسَّم إلى أجزاء
                    // بمقدار callback.getMaxBufferSize() وإلا يرفض النظام التخليق
                    // ويهبط الصوت. نقسّم كل دفعة من المزوّد احتراماً لقيود الـ callback.
                    // المعامل الثاني (validLength) هو طول البيانات الصالح الصريح —
                    // فقد تكون مصفوفة الشريحة بحجم أكبر من بياناتها الفعلية (مسبح
                    // مُعاد استخدامه)، فيُمسح حتى length فقط.
                    val maxBytes = callback.maxBufferSize
                    var offset = 0
                    while (offset < validLength) {
                        val bytesToWrite = minOf(maxBytes, validLength - offset)
                        callback.audioAvailable(chunk, offset, bytesToWrite)
                        offset += bytesToWrite
                    }
                }, finalEngine, finalLocale, finalVoiceName)
                callback.done()
            } catch (e: CancellationException) {
                // إلغاء صريح (onStop): لا نكمل ولا نُطلق خطأً زائفاً — النظام
                // يعرف أن النطق أُوقف عمداً وسيكون على اتصاله مع onStop.
                Log.d(TAG, "onSynthesizeText cancelled")
            } catch (e: Exception) {
                callback.error()
            }
        }
        // لا ننتظر انتهاء التخليق (لا runBlocking): الإرجاع فوري والـ callbacks
        // تُستلم لاحقاً من خيط المزوّد — النطق غير حاجز بالكامل كما هو موثّق أعلاه.
    }

    /**
     * يكتشف اللغات عبر كل المحركات إن انقضت مدة صلاحية الذاكرة أو لم تُبنَ
     * بعد. يعمل في الخلفية دائماً ([Dispatchers.IO]) ولا يرمي؛ تعثّر الاكتشاف
     * يُبقي الحد الأدنى ar/en مضموناً في القوائم.
     */
    private suspend fun maybeRefreshDiscovery() {
        if (!catalog.needsRefresh(DISCOVERY_TTL_MS)) return
        val discovered = runCatching {
            VoiceCatalog.discoverAllLanguagesAcrossEngines(applicationContext)
        }.getOrDefault(emptyMap())
        catalog.applyDiscovery(discovered)
        Log.d(TAG, "maybeRefreshDiscovery: ${discovered.size} لغة عبر كل المحركات المثبتة")
    }

    /** إطلاق تحديث الاكتشاف دون انتظار (يُستدعى من دوال الاستعلام المتزامنة). */
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
        val prefs = settings.getEnginePreferenceForLanguage(requestLang ?: "und")

        val rate = prefs.rate
        val pitch = prefs.pitch
        val volume = prefs.volume

        // المحرك/الصوت فقط يتطلبان تفعيل التحويل التلقائي.
        val autoConvert = settings.isAutoConvertEnabled()
        val engine = if (autoConvert) prefs.engine else null
        val voiceName = if (autoConvert) prefs.voiceName else null

        // إن لم يُعدّل المستخدم أي شريط ولا يوجد محرك مختار → نعتمد الإعدادات العامة.
        if (rate == 1.0f && pitch == 1.0f && volume == 1.0f && engine == null) return null

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
