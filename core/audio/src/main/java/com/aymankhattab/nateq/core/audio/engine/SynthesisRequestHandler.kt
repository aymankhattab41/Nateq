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

    /**
     * سرعة النطق الفعّالة للغةٍ وفئةٍ معاً — الأولوية:
     * 1) شريط الفئة الصريح (غير الافتراضية) إن عيّنه المستخدم.
     * 2) شريط اللغة الصريح إن وُجد.
     * 3) شريط الفئة (يرث الافتراضية عند عدم تعيينه).
     * 4) السرعة العامة الافتراضية.
     */
    fun getSpeechRateFor(languageTag: String, category: String?): Float {
        val cat = category?.takeIf { it.isNotBlank() }
            ?: SettingsRepository.VOICE_CATEGORY_DEFAULT
        if (cat != SettingsRepository.VOICE_CATEGORY_DEFAULT &&
            settings.hasExplicitSpeechRateForCategory(cat)
        ) {
            return settings.getSpeechRateForCategory(cat)
        }
        return settings.getSpeechRateOrNull(languageTag)
            ?: settings.getSpeechRateForCategory(cat)
                .takeIf { it > 0f }
            ?: settings.getDefaultSpeechRate()
    }

    /**
     * نبرة النطق الفعّالة للغةٍ وفئةٍ معاً.
     */
    fun getPitchFor(languageTag: String, category: String?): Float {
        val cat = category?.takeIf { it.isNotBlank() }
            ?: SettingsRepository.VOICE_CATEGORY_DEFAULT
        if (cat != SettingsRepository.VOICE_CATEGORY_DEFAULT &&
            settings.hasExplicitPitchForCategory(cat)
        ) {
            return settings.getPitchForCategory(cat)
        }
        return settings.getPitchOrNull(languageTag)
            ?: settings.getPitchForCategory(cat)
                .takeIf { it > 0f }
            ?: settings.getDefaultPitch()
    }

    /**
     * مستوى الصوت الفعّال للغةٍ وفئةٍ معاً.
     */
    fun getVolumeFor(languageTag: String, category: String?): Float {
        val cat = category?.takeIf { it.isNotBlank() }
            ?: SettingsRepository.VOICE_CATEGORY_DEFAULT
        if (cat != SettingsRepository.VOICE_CATEGORY_DEFAULT &&
            settings.hasExplicitVolumeForCategory(cat)
        ) {
            return settings.getVolumeForCategory(cat)
        }
        return settings.getVolumeOrNull(languageTag)
            ?: settings.getVolumeForCategory(cat)
                .takeIf { it > 0f }
            ?: settings.getDefaultVolume()
    }

    fun getSpeechRate(languageTag: String): Float =
        getSpeechRateFor(
            languageTag,
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        )

    fun getPitch(languageTag: String): Float =
        getPitchFor(
            languageTag,
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        )

    fun getVolume(languageTag: String): Float =
        getVolumeFor(
            languageTag,
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        )

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
