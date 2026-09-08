package com.aymankhattab.nateq.engine

import com.aymankhattab.nateq.providers.VoiceDescriptor
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * يحدد أي صوت (من أي مزود) يجب استخدامه لطلب نطق معيّن،
 * بناءً على تفضيلات المستخدم المحفوظة لكل لغة على حدة.
 * هذا هو ما يسمح للمستخدم الكفيف بالتحكم في "الصوت الذي يقرأ به
 * قارئ الشاشة" لكل لغة بشكل مستقل (عربي/إنجليزي وغيرها).
 */
class SynthesisRequestHandler(
    private val catalog: VoiceCatalog,
    private val settings: SettingsRepository
) {
    suspend fun resolveVoiceForLocale(languageTag: String): VoiceDescriptor? {
        val locale = java.util.Locale.forLanguageTag(languageTag)
        val voices = catalog.allAvailableVoices(locale)

        // 1) الصوت المفضّل المحفوظ لهذه اللغة بالضبط
        val preferredVoiceId = settings.getPreferredVoiceId(languageTag)
        preferredVoiceId?.let { id -> voices.find { it.id == id }?.let { return it } }

        // 2) تراجع: الصوت الافتراضي للغة (حتى لا يصمت TalkBack أبداً)
        val defaultName = catalog.defaultVoiceNameForLanguage(locale.language)
        defaultName?.let { name -> voices.find { it.id == name }?.let { return it } }

        // 3) ملاذ أخير: أول صوت متاح لهذه اللغة
        return voices.firstOrNull()
    }

    fun getSpeechRate(languageTag: String): Float {
        // سرعة هذه اللغة إن حُفظت (speech_rate_en/ar)، وإلا نرجع إلى السرعة
        // الافتراضية العامة (default_speech_rate) بدل 1.0 الثابتة، حتى يؤثر
        // إعداد «السرعة الافتراضية» في شاشة ناطق على النطق الفعلي.
        val perLang = settings.getSpeechRate(languageTag)
        return if (perLang != 1.0f) perLang else settings.getDefaultSpeechRate()
    }

    fun getPitch(languageTag: String): Float {
        val perLang = settings.getPitch(languageTag)
        return if (perLang != 1.0f) perLang else settings.getDefaultPitch()
    }

    fun getVolume(languageTag: String): Float {
        val perLang = settings.getVolume(languageTag)
        return if (perLang != 1.0f) perLang else settings.getDefaultVolume()
    }

    fun getSpeechRateForCategory(category: String): Float {
        return settings.getSpeechRateForCategory(category)
    }

    fun isAutoConvertEnabled(): Boolean = settings.isAutoConvertEnabled()

    fun getPitchForCategory(category: String): Float {
        return settings.getPitchForCategory(category)
    }

    fun getVolumeForCategory(category: String): Float {
        return settings.getVolumeForCategory(category)
    }
}
