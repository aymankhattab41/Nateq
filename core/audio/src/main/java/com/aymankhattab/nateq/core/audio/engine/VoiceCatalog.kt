package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.audio.providers.VoiceDescriptor
import com.aymankhattab.nateq.core.audio.providers.VoiceProvider
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.VoiceIdContract
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** محرك TTS يوفّر لغةً محددة، مع الأصوات المتاحة له داخلها. */
data class EngineWithVoices(
    val enginePackage: String,
    val engineLabel: String,
    /** الأصوات (android.speech.tts.Voice) التي يقدّمها هذا
     *  المحرك لهذه اللغة. */
    val voices: List<Voice>
)

/**
 * يجمّع كل الأصوات المتاحة من كل المزودين (النشطين/المُهيّئين فقط)
 * في قائمة واحدة موحّدة، كما يدير اكتشاف اللغات المتاحة فعلياً عبر
 * كل محركات TTS المثبتة (ديناميكياً بدل قائمة ar/en الثابتة). يُعتمد
 * في الاكتشاف حصرياً على نتيجة [TextToSpeech.getVoices] الفعلية لكل
 * محرك — فلا تُعرض لغةُ لم تُرجعها getVoices حتى لو أعلن المحرك دعمها
 * نظرياً — مع فحص إضافي isLanguageAvailable يستبعد لغات البيانات غير
 * المثبتة ([TextToSpeech.LANG_MISSING_DATA]).
 */
class VoiceCatalog(private val providers: List<VoiceProvider>) {

    companion object {
        private const val TAG = "NATEQ_TTS"

        /** مهلة استجابة المحرك الواحد أثناء الاكتشاف (ثوانٍ) —
         *  بعض المحركات تعلّق. */
        private const val ENGINE_PROBE_TIMEOUT_MS = 10_000L

        /**
         * يكتشف فعلياً كل اللغات المتاحة عبر كل محركات TTS المثبتة في النظام:
         * يبني لكل محرك نسخة مؤقتة من [TextToSpeech] ويسألها [getVoices]، ثم
         * يغلقه فوراً ([shutdown]) مهما كانت النتيجة حتى لا تُسرّب موارد.
         * لكل لغة مرشّحة يفحص [TextToSpeech.isLanguageAvailable] فيستبعد أي
         * لغة تعود [TextToSpeech.LANG_MISSING_DATA] (بيانات غير مثبتة).
         *
         * النتيجة: languageTag -> قائمة المحركات التي توفّر اللغة، وكل محرك
         * يحمل أصواته لهذه اللغة مجمّعةً تحت اللسان نفسه (بلا تكرار محركات).
         */
        suspend fun discoverAllLanguagesAcrossEngines(
            context: Context
        ): Map<String, List<EngineWithVoices>> {
            // نستبعد قارئات الشاشة (TalkBack/Jieshuo/Talkman…)
            // من مساهمة اللغات: يسجّلون أنفسهم محركات TTS لكن
            // قرارهم (getVoices/isLanguageAvailable) يعلن لغات
            // نظريةً (eSpeak مثلاً) بلا بيانات مثبتة فعلياً
            // على الجهاز، فتظهر في القائمة لغاتٌ لا تُنطق.
            // يبقى الاختيار اليدوي صريحاً لهم.
            val engines = EnginePicker.installedEngines(context)
                .filterNot { EnginePicker.isScreenReader(it.packageName) }
            // الفحص بالتوازي (كل محرك في مهمة IO مستقلة): كان متتابعاً فتبلغ
            // مدة الفحص N×10 ث بعشرة محركات بطيئة، الآن أقصى انتظار كحدود
            // المحرك الأبطأ نفسه (~10 ث) فتنفتح شاشة المحول بسرعة.
            val voicesByEngine = try {
                coroutineScope {
                    engines
                        .map { engine ->
                            async(Dispatchers.IO) {
                                engine.packageName to probeEngineVoices(
                                    context, engine.packageName
                                )
                            }
                        }
                        .awaitAll()
                        .toMap()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // إلغاء تعاوني (onStop/استباق): لا نصطاده مع بقية الأخطاء.
                throw e
            } catch (_: Throwable) {
                emptyMap<String, List<Voice>>()
            }
            return groupVoicesByLanguage(
                engines.map { it.packageName to it.label },
                voicesByEngine
            )
        }

        /**
         * يبني خريطة (languageTag -> المحركات التي توفرها) من أصوات المحركات
         * الفعلية فقط (بعد فلترة [filterVoicesWithInstalledData]) — أي لغة لم
         * تُرجعها getVoices لا تُدرج إطلاقاً حتى لو أعلن المحرك دعمها نظرياً.
         * دالة نقية قابلة للاختبار الآلي.
         */
        fun groupVoicesByLanguage(
            engineLabels: List<Pair<String, String>>,
            voicesByEngine: Map<String, List<Voice>>
        ): Map<String, List<EngineWithVoices>> {
            // lang -> engine -> voices
            val grouped = mutableMapOf<
            String, MutableMap<String, MutableList<Voice>>
        >()
            for ((pkg, voices) in voicesByEngine) {
                for (voice in voices) {
val lang = LocaleUtils.normalizeLanguageCode(
                        voice.locale?.language
                    )
                    if (lang.isBlank()) continue
                    grouped.getOrPut(lang) { LinkedHashMap() }
                        .getOrPut(pkg) { mutableListOf() }
                        .add(voice)
                }
            }
            val labelByPkg = engineLabels.toMap()
            return grouped.mapValues { (_, byEngine) ->
                byEngine.map { (pkg, engineVoices) ->
                    EngineWithVoices(
                        pkg,
                        labelByPkg[pkg] ?: pkg,
                        engineVoices.toList()
                    )
                }
            }
        }

        /**
         * فحص إضافي صريح لتوفر البيانات الصوتية: يستبعد أي أصواتٍ لغتُها تعود
         * من دالة التوفر (عادةً [TextToSpeech.isLanguageAvailable]) بنتيجة
         * [TextToSpeech.LANG_MISSING_DATA] — بياناتُها غير مثبتة على الجهاز
         * بعد — ويستبعد أيضاً أي صوتَ غير جاهز محلياً: صوته يحمل ميزة
         * [TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED] (لم يُنزَّل بعد)
         * أو صوته يتطلب اتصالَ شبكة ([Voice.isNetworkConnectionRequired]):
         * بعض المحركات (جوجل خاصةً) تُعلن اللسانَ متاحاً في التوفر لكن صوتَها
         * المطابق يُخلَّق عبر الشبكة لا من ملفات محلية مثبّتة، فيُستظهر في
         * القائمة لغةٌ لا تُنطق (توزّع "متاح" وليس جاهزاً للعمل الفعلي).
         * المعايير الثلاثة معاً تحجب اللغة من واجهة الاختيار تماماً (لا
         * تُعرض حتى معطّلة/رمادية) مع بقاء صندوق المحركات اليدوي كما هو.
         * دالة نقية قابلة للاختبار الآلي.
         */
        fun filterVoicesWithInstalledData(
            voices: List<Voice>,
            languageAvailability: (Locale) -> Int
        ): List<Voice> {
            val missingLangs = voices
                .mapNotNull { voice ->
                    LocaleUtils.normalizeLanguageCode(voice.locale?.language)
                        .takeIf { it.isNotBlank() }
                }
                .distinct()
                .filter { locale ->
                    val availability = languageAvailability(
                        Locale.forLanguageTag(locale)
                    )
                    availability == TextToSpeech.LANG_MISSING_DATA
                }
                .toSet()
            val isNotInstalled = { voice: Voice ->
                voice.features.orEmpty().contains(
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED
                ) ||
                    voice.isNetworkConnectionRequired
            }
            if (missingLangs.isEmpty() && voices.none(isNotInstalled)) {
                return voices
            }
            return voices.filter { voice ->
                val lang = LocaleUtils.normalizeLanguageCode(
                    voice.locale?.language
                )
                lang !in missingLangs && !isNotInstalled(voice)
            }
        }

        /**
         * يسبر محركاً واحداً: نسخة مؤقتة تُربط بالمحرك المعيّن مباشرةً
         * (منشئ ثلاثي المعاملات) ثم استعلام الأصوات ثم إغلاق إجباري.
         *
         * الربط المباشر ضروري: الربط عبر المُنشئ الافتراضي ثم
         * `setEngineByPackageName` ملحوظ كـ Deprecated وغير موثوق على كل
         * الأجهزة — خصوصاً حين يكون محرك النطق الافتراضي هو حزمة LORD نفسها
         * (التطبيق محرك TTS أصلياً)، إذ كان الاكتشاف يعود بأصوات المحرك
         * الافتراضي (فارغة) بدل المحرك المقصود فيتسرب غيابُ لغات.
         */
        @Suppress("DEPRECATION")
        private suspend fun probeEngineVoices(
            context: Context,
            enginePackage: String
        ): List<Voice> {
            val result: List<Voice>? = withTimeoutOrNull(
                ENGINE_PROBE_TIMEOUT_MS
            ) {
                suspendCancellableCoroutine<List<Voice>> { cont ->
                    @Suppress("DEPRECATION")
                    var probe: TextToSpeech? = null
                    val finished = AtomicBoolean(false)
                    val created = runCatching {
                        probe = TextToSpeech(context, { status ->
                            // حارس: النسخة تغلق مرة واحدة فقط مهما
                            // تكرر استدعاء المستمع.
                            if (finished.getAndSet(true)) {
                                runCatching { probe?.shutdown() }
                                return@TextToSpeech
                            }
                            try {
                                if (status != TextToSpeech.SUCCESS) {
                                    cont.resume(emptyList())
                                } else {
                                    cont.resume(probeInstalledVoices(probe))
                                }
                            } catch (_: Throwable) {
                                cont.resume(emptyList())
                            } finally {
                                runCatching { probe?.shutdown() }
                            }
                        }, enginePackage)
                    }
                    if (created.isFailure) {
                        // محرك غير قابل للربط (حزمة غير صالحة
                        // أو منزوعة): لا أصوات.
                        cont.resume(emptyList())
                    }
                    // إن أُغلق الاكتشاف (مهلة/إلغاء) نغلق النسخة المعلقة.
                    cont.invokeOnCancellation {
                        if (finished.getAndSet(true)) {
                            return@invokeOnCancellation
                        }
                        runCatching { probe?.shutdown() }
                    }
                }
            }
            return result ?: emptyList()
        }

        /**
         * الأصوات المثبتة فعلياً لنسخة السبر: يقرأ [getVoices] ثم يفلترها
         * عبر [filterVoicesWithInstalledData] (بيانات غير مثبتة تُحجب).
         * مستخرجة خارج عمق لامبدا [TextToSpeech] لتقليل التعشيش والتزاماً
         * بحدود الطول (80 حرفاً لكل سطر).
         */
        @Suppress("DEPRECATION")
        private fun probeInstalledVoices(probe: TextToSpeech?): List<Voice> {
            // الاعتماد على النتيجة الفعلية لـ getVoices فقط،
            // مع فحص صريح لكل لغة: البيانات غير المثبتة
            // (LANG_MISSING_DATA) تُحجب من القائمة النهائية.
            val voices = runCatching { probe?.getVoices().orEmpty() }
                .getOrDefault(emptySet())
            return filterVoicesWithInstalledData(
                voices.toList()
            ) { locale ->
                val availability = runCatching {
                    probe?.isLanguageAvailable(locale)
                }.getOrNull()
                availability ?: TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    /**
     * ذاكرة الاكتشاف داخل عملية المحرك (:tts) — تُبنى خلفياً عند إنشاء الخدمة
     * ومرة كل عهد فتح قوائم الأصوات، وحين تكون فارغة تعود القوائم للحد الأدنى
     * المضمون (العربية/الإنجليزية) فتبقى الخدمة تعمل دائماً.
     */
    @Volatile
    private var discoveredByLanguage:
        Map<String, List<EngineWithVoices>>? = null

    @Volatile
    private var lastDiscoveryAtMs = 0L

    /** يُحدّث ذاكرة الاكتشاف (يستدعيها المتصل بعد اكتشاف خلفي). */
    fun applyDiscovery(map: Map<String, List<EngineWithVoices>>) {
        discoveredByLanguage = map
        lastDiscoveryAtMs = System.currentTimeMillis()
    }

    /** هل الاكتشاف مُعدَم أو انتهت صلاحيته (بعد مرور ttlMs)؟ */
    fun needsRefresh(ttlMs: Long): Boolean =
        lastDiscoveryAtMs == 0L ||
            System.currentTimeMillis() - lastDiscoveryAtMs > ttlMs

    /**
     * محركات TTS المثبّتة المكتشفة التي توفّر لغةً معيّنة فعلياً، أو null
     * إن لم يكتمل الاكتشاف بعد. تُستخدم في بناء سلسلة التراجع لكل لغة
     * ([SystemVoiceProvider]) فلا يُحاوَل محركٌ لا ينطق اللغة أصلاً؛ لما
     * تبقى الذاكرة فارغة يعود null فيتراجع المتصل لقائمة المثبّتة كلها.
     * تطبيع اللغة بنفس قاعدة الاكتشاف (ISO-2) ليطابق مفتاح الخريطة.
     */
    fun discoveredEnginePackagesFor(languageTag: String): List<String>? {
        val lang = LocaleUtils.normalizeLanguageCode(languageTag)
        return discoveredByLanguage?.get(lang)?.map { it.enginePackage }
    }

    suspend fun allAvailableVoices(locale: Locale): List<VoiceDescriptor> =
        providers
            .filter { it.isConfigured() }
            .flatMap { it.listVoices(locale) }

    fun findProvider(providerId: String): VoiceProvider? =
        providers.find { it.providerId == providerId }

    /** إغلاق نهائي لكل المزودين (الاتصالات الخارجية لمن يحتاجها) — يُستدعى من
     *  [NateqTtsService.onDestroy] حتى لا تبقى روابط Binder IPC معلقة بعد تدمير
     *  الخدمة. استدعاءات لاحقة لا أثر لها (كل مزود يضمن التسامح). */
    fun shutdown() {
        providers.forEach { provider ->
            runCatching { provider.shutdown() }
        }
    }

    /**
     * اللغات المدعومة إجمالاً (تُستخدم في onIsLanguageAvailable).
     * تُبنى ديناميكياً من نتيجة الاكتشاف عبر كل المحركات، مع بقاء العربية
     * والإنجليزية كحد أدنى مضمون دائماً حتى لو لم يُكتشف أي محرك إضافي.
     */
    fun supportedLocales(): List<Locale> {
        val languages = LinkedHashSet<String>()
        discoveredByLanguage?.keys?.forEach { languages.add(it) }
        languages.add(LanguageCode.AR.tag) // الحد الأدنى المضمون دائماً
        languages.add(LanguageCode.EN.tag)
        return languages
            .map { Locale.forLanguageTag(it) }
            .sortedBy { it.language }
    }

    /**
     * قائمة الأصوات (android.speech.tts.Voice) المُعلنة للنظام.
     * هذا هو المصدر الوحيد الذي يقرأ منه النظام في شاشة
     * "تعيين الصوت الخاص بلغة النص المنطوق" (onGetVoices).
     * تُشتق من اللغات المدعومة فعلياً حتى لا تظهر أصوات لا تُنطق.
     *
     * ملاحظة مهمة (سامسونج/بعض المَشغلين): الأصوات يجب أن تُعلن صراحةً
     * بـ KEY_FEATURE_EMBEDDED_SYNTHESIS (offline) وإلا تُصفَّى وتُهمَل من
     * قائمة اللغات. ولا يُضاف KEY_FEATURE_NOT_INSTALLED أبداً.
     *
     * الثابت معيَّن deprecated في المنصة الحديثة (لأن التخليق المدمج صار
     * افتراضياً)، لكن إزالته تفاقم تصفية سامسونج لقائمة الأصوات، لذا نحتفظ
     * به مع كتم تحذير الإهمال المحدَّد.
     */
    @Suppress("DEPRECATION")
    private val offlineFeature = setOf(
        TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS
    )

    private fun voiceNameFor(locale: Locale): String {
        // أسماء الأصوات المعلنة في tts_engine.xml هي "ar-EG"/"en-US" للغتين
        // الأساسيتين، وهي نفسها المعرّفات التي يخزنها تطبيقنا في الإعدادات
        // (بطارية/رسائل/متصل/فئات) والمعرّفات التي يُنتجها SystemVoiceProvider.
        // للّغات المكتشفة حديثاً (fr/de/zh/…) نُصدِر "<lang>-local" كاسم صوت
        // موحّد يُمكّن النظام من حفظ اختيار المستخدم لهذه اللغات؛ النطق الفعلي
        // يذهب إلى المحرك الطرفي عبر خريطة التحويل (desiredVoiceName/engine).
        // الصيغة كلها مولّدة من عقد واحد ([VoiceIdContract]) يشارك المزوّد
        // في استخدامه فلا تنحرف الأسماء المعلنة عن معرّفات الواصفات مجدداً.
        return VoiceIdContract.createId(locale.language)
    }

    fun supportedVoices(): List<Voice> =
        supportedLocales().map { locale ->
            Voice(
                voiceNameFor(locale),
                locale,
                Voice.QUALITY_HIGH,
                Voice.LATENCY_LOW,
                false, // requiresNetworkConnection = false (محلي بالكامل)
                offlineFeature
            )
        }

    /** معرّف الصوت الافتراضي للغة (يُطابق أسماء supportedVoices) */
    fun defaultVoiceNameForLanguage(language: String): String? =
        supportedLocales()
            .firstOrNull { it.language == language }
            ?.let { voiceNameFor(it) }
}