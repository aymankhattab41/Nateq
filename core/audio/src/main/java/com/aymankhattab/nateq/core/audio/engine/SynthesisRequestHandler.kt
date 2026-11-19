package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.core.audio.providers.VoiceDescriptor
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * يحدّد صوت القراءة المناسب لطلب نطق معيّن،
 * بناءً على تفضيلات المستخدم المحفوظة لكل لغة («صوت القراءة لكل لغة»).
 */
class SynthesisRequestHandler(
    private val catalog: VoiceCatalog,
    private val settings: SettingsRepository
) {
    suspend fun resolveVoiceForLocale(languageTag: String): VoiceDescriptor? {
        val locale = java.util.Locale.forLanguageTag(languageTag)
        val voices = catalog.allAvailableVoices(locale)

        // 1) صوت القراءة المفضّل المحفوظ لهذه اللغة
        val preferredVoiceId = settings.getPreferredVoiceId(languageTag)
            ?: settings.getVoiceForLanguage(languageTag)
        preferredVoiceId?.let { id ->
            voices.find { it.id == id }?.let { return it }
        }

        // 2) تراجع: الصوت الافتراضي للغة (حتى لا يصمت TalkBack أبداً)
        val defaultName = catalog.defaultVoiceNameForLanguage(locale.language)
        defaultName?.let { name ->
            voices.find { it.id == name }?.let { return it }
        }

        // 3) ملاذ أخير: أول صوت متاح لهذه اللغة
        return voices.firstOrNull()
    }

    /**
     * سرعة النطق الفعّالة للغةٍ وفئةٍ معاً — الأولوية:
     * 1) تجاوز الفئة الصريح إن عيّنه المستخدم.
     * 2) شريط اللغة الصريح إن وُجد.
     * 3) السرعة العامة.
     */
    fun getSpeechRateFor(languageTag: String, category: String?): Float {
        val cat = category?.takeIf { it.isNotBlank() }
        if (cat != null && settings.hasExplicitSpeechRateForCategory(cat)) {
            return settings.getSpeechRateForCategory(cat)
        }
        return settings.getSpeechRateOrNull(languageTag)
            ?: settings.getDefaultSpeechRate()
    }

    /** نبرة النطق الفعّالة للغةٍ وفئةٍ معاً. */
    fun getPitchFor(languageTag: String, category: String?): Float {
        val cat = category?.takeIf { it.isNotBlank() }
        if (cat != null && settings.hasExplicitPitchForCategory(cat)) {
            return settings.getPitchForCategory(cat)
        }
        return settings.getPitchOrNull(languageTag)
            ?: settings.getDefaultPitch()
    }

    /** مستوى الصوت الفعّال للغةٍ وفئةٍ معاً. */
    fun getVolumeFor(languageTag: String, category: String?): Float {
        val cat = category?.takeIf { it.isNotBlank() }
        if (cat != null && settings.hasExplicitVolumeForCategory(cat)) {
            return settings.getVolumeForCategory(cat)
        }
        return settings.getVolumeOrNull(languageTag)
            ?: settings.getDefaultVolume()
    }

    fun getSpeechRate(languageTag: String): Float =
        getSpeechRateFor(languageTag, null)

    fun getPitch(languageTag: String): Float =
        getPitchFor(languageTag, null)

    fun getVolume(languageTag: String): Float =
        getVolumeFor(languageTag, null)

    /** القيمة الصريحة لسرعة هذه اللغة إن عيّنها المستخدم — null إن لم يعيّن. */
    fun getExplicitLanguageRate(languageTag: String): Float? =
        settings.getSpeechRateOrNull(languageTag)

    fun getSpeechRateForCategory(category: String): Float =
        settings.getSpeechRateForCategory(category)

    fun isAutoConvertEnabled(): Boolean = settings.isAutoConvertEnabled()

    fun getPitchForCategory(category: String): Float =
        settings.getPitchForCategory(category)

    fun getVolumeForCategory(category: String): Float =
        settings.getVolumeForCategory(category)
}
