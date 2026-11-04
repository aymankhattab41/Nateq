package com.aymankhattab.nateq.settings

import java.util.Locale

/** خيار صوت معروض في قوائم الأصوات: الاسم الفعلي (يُخزَّن في الإعدادات
 *  ويُطابِق صوت الـ Voice الاسمَ في المحرك المربوط عند النطق) والتسمية
 *  المعروضة للمستخدم. */
internal data class VoiceOption(
    val name: String,
    val label: String
)

/** صف محرك يقدّم أصواتاً للغة — نسخة مبسطة خالية من أندرويد من
 *  [EngineWithVoices] حتى يبقى [EngineVoicesCatalog] نقياً وقابلاً
 *  للاختبار على الـ JVM مباشرةً. */
internal data class EngineVoicesRow(
    val enginePackage: String,
    val engineLabel: String,
    val voices: List<VoiceOption>
)

/**
 * كتالوج أصوات محركات TTS المكتشفة (لغة ← محركات ← أصوات) مع سقوطٍ
 * لأصوات اللورد المنطقية الثابتة (ar-EG/en-US) — يخدم صفوف فئات
 * الأصوات وشاشات الرسائل والمتصل والبطارية. مرجعٌ حيّ: اكتشافُ
 * المحركات يستغرق ثوانٍ فيتم خلفياً ثم [update] يحدّث الخريطة دون
 * إعادة بناء الواجهات.
 */
internal class EngineVoicesCatalog(
    initial: Map<String, List<EngineVoicesRow>> = emptyMap(),
    private val arabicVoiceLabel: String,
    private val englishVoiceLabel: String
) {

    @Volatile
    private var byLanguage: Map<String, List<EngineVoicesRow>> = initial

    @Volatile
    private var unavailable: Map<String, String> = emptyMap()

    private val loadingEngines =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** يحدّث الخريطة بعد اكتمال الاكتشاف الخلفي للمحركات. */
    fun update(
        discovery: Map<String, List<EngineVoicesRow>>,
        unavailableMap: Map<String, String> = emptyMap()
    ) {
        byLanguage = discovery
        unavailable = unavailableMap
    }

    /** يُسجّل أو يُلغي حالة جارٍ التحميل لمحرك معين. */
    fun setLoading(engine: String, loading: Boolean) {
        if (loading) {
            loadingEngines.add(engine)
        } else {
            loadingEngines.remove(engine)
        }
    }

    /** يضيف أصواتاً جديدة لمحرك معين تحت لغة معينة في الكتالوج. */
    @Synchronized
    fun addVoicesForEngine(
        language: String,
        enginePackage: String,
        engineLabel: String,
        voices: List<VoiceOption>
    ) {
        val currentRows = byLanguage[language].orEmpty().toMutableList()
        val existingIndex = currentRows.indexOfFirst {
            it.enginePackage == enginePackage
        }
        val newRow = EngineVoicesRow(enginePackage, engineLabel, voices)
        if (existingIndex >= 0) {
            currentRows[existingIndex] = newRow
        } else {
            currentRows.add(newRow)
        }
        val mutableMap = byLanguage.toMutableMap()
        mutableMap[language] = currentRows
        byLanguage = mutableMap
    }

    /**
     * يعيد سبب غياب أصوات المحرك:
     * - «جارٍ التحميل» إن كان قيد الجلب.
     * - «المحرك غير جاهز: <السبب>» إن كان المحرك معطلاً أو تعذرت قراءته.
     * - «لا توجد أصوات لهذا المحرك بهذه اللغة» إن كان جاهزاً لكن لا أصوات له.
     */
    fun reasonFor(engine: String, language: String): String? {
        if (loadingEngines.contains(engine)) {
            return "جارٍ التحميل"
        }
        val unavailReason = unavailable[engine]
        if (unavailReason != null) {
            return "المحرك غير جاهز: $unavailReason"
        }
        val engines = byLanguage[language].orEmpty()
        val row = engines.firstOrNull { it.enginePackage == engine }
        if (row != null && row.voices.isEmpty()) {
            return "لا توجد أصوات لهذا المحرك بهذه اللغة"
        }
        if (row == null) {
            return "لا توجد أصوات لهذا المحرك بهذه اللغة"
        }
        return null
    }

    /** اللغات المعروضة: العربية والإنجليزية أولاً ثم الباقي أبجدياً. */
    internal fun languages(): List<String> {
        val rest = byLanguage.keys
            .filterNot { it == LANGUAGE_AR || it == LANGUAGE_EN }
            .sorted()
        return listOf(LANGUAGE_AR, LANGUAGE_EN) + rest
    }

    /** تسمية اللغة المعروضة باسمها المحلي (العربية/English/…). */
    internal fun languageDisplayName(language: String): String {
        val locale = Locale.forLanguageTag(language)
        return runCatching {
            locale.getDisplayLanguage(locale)
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: language
    }

    /** أصوات اللغة لمحركٍ محدد (أو مُسطَّحة عبر كل محركات اللغة عند null).
     *  إن كان engine != null كانت القائمة فارغة فأعد قائمة فارغة.
     *  عند engine == null لا يوجد fallback أصوات — يُترك للواجهة التعامل
     *  مع الحالة الفارغة (ع٤٠-1: إزالة الصوت الوهمي ar-EG/en-US). */
    internal fun voicesFor(
        language: String,
        engine: String?
    ): List<VoiceOption> {
        val engines = byLanguage[language].orEmpty()
        val picked = if (engine != null) {
            engines.filter { it.enginePackage == engine }
        } else {
            engines
        }
        return picked
            .flatMap { row ->
                row.voices.map { voice ->
                    if (engine == null) {
                        voice.copy(
                            label = "${row.engineLabel}: ${voice.label}"
                        )
                    } else {
                        voice
                    }
                }
            }
            .distinctBy { it.name }
    }

    /** لغة صوتٍ مكتشفٍ محفوظ عبر البحث في خريطة الاكتشاف (أو null إن لم
     *  يُعرَف) — يُعتمد للاستدلال اللغوي عند غياب لغةٍ محفوظة صراحة. */
    internal fun languageOfVoice(voiceName: String): String? {
        if (voiceName.isBlank()) return null
        for ((language, rows) in byLanguage) {
            if (rows.any { row -> row.voices.any { it.name == voiceName } }) {
                return language
            }
        }
        return null
    }

    /** يستدل لغة صوتٍ محفوظ: الصيغ المنطقية (ar-EG/en-US/-local القديمة)
     *  تُحسم مباشرة، والأسماء المكتشفة تُبحث في الخريطة؛ null = مجهول. */
    internal fun languageForSavedVoice(savedVoice: String): String? =
        when {
            savedVoice.contains("nateq-ar", ignoreCase = true) ||
                savedVoice.equals("ar-local", ignoreCase = true) ||
                savedVoice.equals("ar-EG", ignoreCase = true) -> "ar"
            savedVoice.contains("nateq-en", ignoreCase = true) ||
                savedVoice.equals("en-local", ignoreCase = true) ||
                savedVoice.equals("en-US", ignoreCase = true) -> "en"
            else -> languageOfVoice(savedVoice)
        }

    private companion object {
        const val LANGUAGE_AR = "ar"
        const val LANGUAGE_EN = "en"
        const val DEFAULT_AR_NAME = "ar-EG"
        const val DEFAULT_EN_NAME = "en-US"
    }
}