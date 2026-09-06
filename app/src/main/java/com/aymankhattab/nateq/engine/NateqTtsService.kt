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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

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
class NateqTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "NATEQ_TTS"
    }

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
        settings = SettingsRepository(applicationContext)

        val providers = listOf(SystemVoiceProvider(applicationContext))
        catalog = VoiceCatalog(providers)
        requestHandler = SynthesisRequestHandler(catalog, settings)
        textProcessor = TextProcessor(applicationContext)

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

        callback.start(
            /* sampleRateInHz = */ 22050,
            /* audioFormat = */ android.media.AudioFormat.ENCODING_PCM_16BIT,
            /* channelCount = */ 1
        )

        // التخليق يتم على IO thread عبر Coroutine، لكن onSynthesizeText نفسها
        // نظام أندرويد بيستدعيها بالفعل على خيط عامل (worker thread) مخصص،
        // فاستخدام runBlocking هنا آمن ولا يجمّد الواجهة الرئيسية.
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

                provider.synthesize(processedText, voice, finalRate, finalPitch, finalVolume, { chunk ->
                    // المنهج المُثبَت (كما في TtsService الرسمي لـ espeak-ng/MultiTTS):
                    // لا يجوز تمرير كامل المخزن المؤقت دفعةً واحدة؛ يُقسَّم إلى أجزاء
                    // بمقدار callback.getMaxBufferSize() وإلا يرفض النظام التخليق
                    // ويهبط الصوت. نقسّم كل دفعة من المزوّد احتراماً لقيود الـ callback.
                    val maxBytes = callback.maxBufferSize
                    var offset = 0
                    while (offset < chunk.size) {
                        val bytesToWrite = minOf(maxBytes, chunk.size - offset)
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

        // ننتظر انتهاء المهمة لأن onSynthesizeText متوقع أن تكون متزامنة
        // من وجهة نظر واجهة TextToSpeechService. نحدّد سقفاً زمنياً (45 ثانية)
        // حتى لا تعلق الخدمة إلى الأبد إذا علّق المحرك الطرفي.
        runBlocking { withTimeoutOrNull(45_000) { currentJob?.join() } }
    }

    /**
     * يُحدّد هدف التحويل للطلب الحالي.
     *
     * الأشرطة (سرعة/نبرة/صوت) داخل حوار اللغة تُطبَّق دائماً بغضّ النظر عن
     * حالة checkbox «التحويل التلقائي»؛ لأن المستخدم قد يعدّل شريطاً يتوقع
     * أن يسمع الفرق فوراً.
     *
     * التبديل التلقائي للمحرك/اللغة (engine/locale) فقط هو ما يتطلب تفعيل
     * checkbox.
     */
    private fun resolveConvertTarget(requestLang: String?): ConvertTarget? {
        val reqLang = normalizeLanguageCode(requestLang)
        val slot = if (reqLang == "ar") 1 else if (reqLang == "en") 2 else 1

        val rate = if (slot == 1) settings.getConvertRate1() else settings.getConvertRate2()
        val pitch = if (slot == 1) settings.getConvertPitch1() else settings.getConvertPitch2()
        val volume = if (slot == 1) settings.getConvertVolume1() else settings.getConvertVolume2()

        // المحرك/اللغة فقط تتطلب تفعيل التحويل التلقائي.
        val autoConvert = settings.isAutoConvertEnabled()
        val engine = if (autoConvert) {
            if (slot == 1) settings.getConvertEngine1() else settings.getConvertEngine2()
        } else null
        val langTag = if (autoConvert) {
            if (slot == 1) settings.getConvertLanguageTag1() else settings.getConvertLanguageTag2()
        } else null
        val voiceName = if (autoConvert) {
            if (slot == 1) settings.getConvertVoice1() else settings.getConvertVoice2()
        } else null

        // إن لم يُعدّل المستخدم أي شريط ولا يوجد محرك مختار → ن relies على الإعدادات العامة.
        val hasAdjustment = (rate != 1.0f) || (pitch != 1.0f) || (volume != 1.0f)
        if (!hasAdjustment && engine == null) return null

        val tag = if (langTag != null) Locale.forLanguageTag(langTag) else null

        return ConvertTarget(
            convertEngine = engine,
            convertLocale = if (tag != null) tag else null,
            convertRate = rate,
            convertPitch = pitch,
            convertVolume = volume,
            convertVoiceName = voiceName
        )
    }

    /** بيانات هدف التحويل التلقائي المرفوعة إلى [SystemVoiceProvider]. */
    data class ConvertTarget(
        // المحرك/اللغة null عند عدم تفعيل التبديل (يتزامن مع المحدد يدوياً).
        val convertEngine: String?,
        val convertLocale: Locale?,
        val convertRate: Float,
        val convertPitch: Float,
        val convertVolume: Float,
        // اسم الصوت المختار داخل المحرك (اختياري — يُطبَّق إن وُجد بالمحرك).
        val convertVoiceName: String?
    )
}
