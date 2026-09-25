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

    /** يحدّث الخريطة بعد اكتمال الاكتشاف الخلفي للمحركات. */
    fun update(discovery: Map<String, List<EngineVoicesRow>>) {
        byLanguage = discovery
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
     *  إن لم يقدّم المحرك/اللغة أصواتاً نرجع الأصوات المنطقية الثابتة
     *  (ar-EG/en-US) حتى لا تُعرض قائمة فارغة فيبقى النطق الافتراضي
     *  قابلاً للاختيار — ولغاتٍ أخرى بلا أصوات تُرجع القائمة الفارغة. */
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
        val flat = picked
            .flatMap { row ->
                row.voices.map { voice ->
                    if (engine == null) {
                        voice.copy(label = "${row.engineLabel}: ${voice.label}")
                    } else {
                        voice
                    }
                }
            }
            .distinctBy { it.name }
        if (flat.isNotEmpty()) return flat
        return fallbackVoices(language)
    }

    /** الأصوات المنطقية الثابتة للّغتين المضمونتين. */
    internal fun fallbackVoices(language: String): List<VoiceOption> =
        when (language) {
            LANGUAGE_AR -> listOf(
                VoiceOption(DEFAULT_AR_NAME, arabicVoiceLabel)
            )
            LANGUAGE_EN -> listOf(
                VoiceOption(DEFAULT_EN_NAME, englishVoiceLabel)
            )
            else -> emptyList()
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