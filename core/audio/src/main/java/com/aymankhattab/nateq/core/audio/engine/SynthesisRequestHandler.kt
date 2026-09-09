package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.core.audio.providers.VoiceDescriptor
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

    fun getSpeechRate(languageTag: String): Float {
        // تفضيل هذه اللغة الصريح إن حُفظ (حتى لو كان 1.0x)، وإلا الرجوع إلى
        // السرعة العامة الافتراضية — لا يُعتبر 1.0x «غياباً» فيُفقد اختيار
        // المستخدم ويُعاد تطبيق قيمة عامة أخرى فوق إرادته.
        return settings.getSpeechRateOrNull(languageTag)
            ?: settings.getDefaultSpeechRate()
    }

    fun getPitch(languageTag: String): Float =
        settings.getPitchOrNull(languageTag) ?: settings.getDefaultPitch()

    fun getVolume(languageTag: String): Float =
        settings.getVolumeOrNull(languageTag) ?: settings.getDefaultVolume()

    /** القيمة الصريحة لسرعة هذه اللغة إن عيّنها المستخدم — null إن لم يعيّن.
     *  يسمح للمستدعي بالتمييز بين «1.0x صريح» (تفضيل حقيقي) و«بلا تفضيل» — وهو
     *  ما يساويه الربط مع 1.0f بوَحدة في synthesizeSingle. */
    fun getExplicitLanguageRate(languageTag: String): Float? =
        settings.getSpeechRateOrNull(languageTag)

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
