package com.aymankhattab.nateq.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.aymankhattab.nateq.providers.EnginePicker
import com.aymankhattab.nateq.providers.VoiceDescriptor
import com.aymankhattab.nateq.providers.VoiceProvider
import com.aymankhattab.nateq.util.LocaleUtils
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** محرك TTS يوفّر لغةً محددة، مع الأصوات المتاحة له داخلها. */
data class EngineWithVoices(
    val enginePackage: String,
    val engineLabel: String,
    /** الأصوات (android.speech.tts.Voice) التي يقدّمها هذا المحرك لهذه اللغة. */
    val voices: List<Voice>
)

/**
 * يجمّع كل الأصوات المتاحة من كل المزودين (النشطين/المُهيّئين فقط)
 * في قائمة واحدة موحّدة، كما يدير اكتشاف اللغات المتاحة فعلياً عبر
 * كل محركات TTS المثبتة (ديناميكياً بدل قائمة ar/en الثابتة) مع بقاء
 * العربية والإنجليزية كحد أدنى مضمون دائماً مهما تعثر الاكتشاف.
 */
class VoiceCatalog(private val providers: List<VoiceProvider>) {

    companion object {
        private const val TAG = "NATEQ_TTS"

        /** مهلة استجابة المحرك الواحد أثناء الاكتشاف (ثوانٍ) — بعض المحركات تعلّق. */
        private const val ENGINE_PROBE_TIMEOUT_MS = 10_000L

        /**
         * يكتشف فعلياً كل اللغات المتاحة عبر كل محركات TTS المثبتة في النظام:
         * يبني لكل محرك نسخة مؤقتة من [TextToSpeech] ويسألها [getVoices]، ثم
         * يغلقه فوراً ([shutdown]) مهما كانت النتيجة حتى لا تُسرّب موارد.
         *
         * النتيجة: languageTag -> قائمة المحركات التي توفّر اللغة، وكل محرك
         * يحمل أصواته لهذه اللغة مجمّعةً تحت اللسان نفسه (بلا تكرار محركات).
         */
        suspend fun discoverAllLanguagesAcrossEngines(context: Context): Map<String, List<EngineWithVoices>> {
            val engines = EnginePicker.installedEngines(context)
            // lang -> engine -> voices
            val grouped = mutableMapOf<String, MutableMap<String, MutableList<Voice>>>()
            engines.forEach { engine ->
                val voices = runCatching {
                    probeEngineVoices(context, engine.packageName)
                }.getOrDefault(emptyList())
                for (voice in voices) {
                    val lang = LocaleUtils.normalizeLanguageCode(voice.locale?.language)
                    if (lang.isBlank()) continue
                    grouped.getOrPut(lang) { LinkedHashMap() }
                        .getOrPut(engine.packageName) { mutableListOf() }
                        .add(voice)
                }
            }
            return grouped.mapValues { (_, byEngine) ->
                byEngine.map { (pkg, engineVoices) ->
                    val label = engines.firstOrNull { it.packageName == pkg }?.label ?: pkg
                    EngineWithVoices(pkg, label, engineVoices.toList())
                }
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
            val result: List<Voice>? = withTimeoutOrNull(ENGINE_PROBE_TIMEOUT_MS) {
                suspendCancellableCoroutine<List<Voice>> { cont ->
                    @Suppress("DEPRECATION")
                    var probe: TextToSpeech? = null
                    val finished = AtomicBoolean(false)
                    val created = runCatching {
                        probe = TextToSpeech(context, { status ->
                            // حارس: النسخة تغلق مرة واحدة فقط مهما تكرر استدعاء المستمع.
                            if (finished.getAndSet(true)) {
                                runCatching { probe?.shutdown() }
                                return@TextToSpeech
                            }
                            try {
                                if (status != TextToSpeech.SUCCESS) {
                                    cont.resume(emptyList())
                                } else {
                                    @Suppress("DEPRECATION")
                                    val voices = runCatching { probe?.getVoices().orEmpty() }
                                        .getOrDefault(emptySet())
                                    cont.resume(voices.toList())
                                }
                            } catch (_: Throwable) {
                                cont.resume(emptyList())
                            } finally {
                                runCatching { probe?.shutdown() }
                            }
                        }, enginePackage)
                    }
                    if (created.isFailure) {
                        // محرك غير قابل للربط (حزمة غير صالحة أو منزوعة): لا أصوات.
                        cont.resume(emptyList())
                    }
                    // إن أُغلق الاكتشاف (مهلة/إلغاء) نغلق النسخة المعلقة.
                    cont.invokeOnCancellation {
                        if (finished.getAndSet(true)) return@invokeOnCancellation
                        runCatching { probe?.shutdown() }
                    }
                }
            }
            return result ?: emptyList()
        }
    }

    /**
     * ذاكرة الاكتشاف داخل عملية المحرك (:tts) — تُبنى خلفياً عند إنشاء الخدمة
     * ومرة كل عهد فتح قوائم الأصوات، وحين تكون فارغة تعود القوائم للحد الأدنى
     * المضمون (العربية/الإنجليزية) فتبقى الخدمة تعمل دائماً.
     */
    @Volatile
    private var discoveredByLanguage: Map<String, List<EngineWithVoices>>? = null

    @Volatile
    private var lastDiscoveryAtMs = 0L

    /** يُحدّث ذاكرة الاكتشاف (يستدعيها المتصل بعد اكتشاف خلفي). */
    fun applyDiscovery(map: Map<String, List<EngineWithVoices>>) {
        discoveredByLanguage = map
        lastDiscoveryAtMs = System.currentTimeMillis()
    }

    /** هل الاكتشاف مُعدَم أو انتهت صلاحيته (بعد مرور ttlMs)؟ */
    fun needsRefresh(ttlMs: Long): Boolean =
        lastDiscoveryAtMs == 0L || System.currentTimeMillis() - lastDiscoveryAtMs > ttlMs

    suspend fun allAvailableVoices(locale: Locale): List<VoiceDescriptor> =
        providers
            .filter { it.isConfigured() }
            .flatMap { it.listVoices(locale) }

    fun findProvider(providerId: String): VoiceProvider? =
        providers.find { it.providerId == providerId }

    /**
     * اللغات المدعومة إجمالاً (تُستخدم في onIsLanguageAvailable).
     * تُبنى ديناميكياً من نتيجة الاكتشاف عبر كل المحركات، مع بقاء العربية
     * والإنجليزية كحد أدنى مضمون دائماً حتى لو لم يُكتشف أي محرك إضافي.
     */
    fun supportedLocales(): List<Locale> {
        val languages = LinkedHashSet<String>()
        discoveredByLanguage?.keys?.forEach { languages.add(it) }
        languages.add("ar") // الحد الأدنى المضمون دائماً
        languages.add("en")
        return languages.map { Locale.forLanguageTag(it) }.sortedBy { it.language }
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
    private val offlineFeature = setOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)

    private fun voiceNameFor(locale: Locale): String {
        // أسماء الأصوات المعلنة في tts_engine.xml هي "ar-EG"/"en-US" للغتين
        // الأساسيتين، وهي نفسها المعرّفات التي يخزنها تطبيقنا في الإعدادات
        // (بطارية/رسائل/متصل/فئات) والمعرّفات التي يُنتجها SystemVoiceProvider.
        // للّغات المكتشفة حديثاً (fr/de/zh/…) نُصدِر "<lang>-local" كاسم صوت
        // موحّد يُمكّن النظام من حفظ اختيار المستخدم لهذه اللغات؛ النطق الفعلي
        // يذهب إلى المحرك الطرفي عبر خريطة التحويل (desiredVoiceName/engine).
        return when (locale.language.lowercase(java.util.Locale.ROOT)) {
            "ar" -> "ar-EG"
            "en" -> "en-US"
            else -> "${locale.language.lowercase(java.util.Locale.ROOT)}-local"
        }
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