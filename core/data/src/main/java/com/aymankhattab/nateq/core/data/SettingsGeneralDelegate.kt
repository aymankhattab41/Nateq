package com.aymankhattab.nateq.core.data

import com.aymankhattab.nateq.core.engine.AudioExpansionLevels

/**
 * تفويض الإعدادات العامة (سرعة/نبرة/صوت افتراضية وتعزيزات الصوت).
 *
 * المصدر الحاكم للأشرطة العامة هو `default_speech_rate/pitch/volume`.
 * مفاتيح الفئة القديمة (`speech_rate_default` إلخ) تُقرأ فقط للتوافق
 * ولا تُكتب بعد الآن؛ كي لا تتغلب على القيم العامة.
 */
internal class SettingsGeneralDelegate(
    private val repo: SettingsRepository
) {
    private val prefs get() = repo.prefs

    fun getDefaultSpeechRate(): Float = when {
        prefs.contains("default_speech_rate") ->
            prefs.getFloat("default_speech_rate", 1.0f)
        prefs.contains("speech_rate_default") ->
            prefs.getFloat("speech_rate_default", 1.0f)
        else -> 1.0f
    }

    fun setDefaultSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0f, 2f)
        prefs.edit()
            .putFloat("default_speech_rate", clamped)
            .apply()
    }

    fun getDefaultPitch(): Float = when {
        prefs.contains("default_pitch") ->
            prefs.getFloat("default_pitch", 1.0f)
        prefs.contains("pitch_default") ->
            prefs.getFloat("pitch_default", 1.0f)
        else -> 1.0f
    }

    fun setDefaultPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0f, 2f)
        prefs.edit()
            .putFloat("default_pitch", clamped)
            .apply()
    }

    fun getDefaultVolume(): Float = when {
        prefs.contains("default_volume") ->
            prefs.getFloat("default_volume", 1.0f)
        prefs.contains("volume_default") ->
            prefs.getFloat("volume_default", 1.0f)
        else -> 1.0f
    }

    fun setDefaultVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        prefs.edit()
            .putFloat("default_volume", clamped)
            .apply()
    }

    fun getAudioExpansionLevel(): Int =
        prefs.getInt(
            "audio_expansion_level",
            AudioExpansionLevels.DEFAULT
        )

    fun setAudioExpansionLevel(level: Int) {
        val safe = level.coerceIn(
            AudioExpansionLevels.MIN,
            AudioExpansionLevels.MAX
        )
        prefs.edit().putInt("audio_expansion_level", safe).apply()
    }

    fun isAudioReverbEnabled(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_AUDIO_REVERB_ENABLED, false)

    fun setAudioReverbEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(SettingsRepository.KEY_AUDIO_REVERB_ENABLED, enabled)
            .apply()
    }

    fun getAudioEffectsDisabledCategories(): Set<String> =
        prefs.getStringSet(
            SettingsRepository.KEY_AUDIO_EFFECTS_DISABLED_CATEGORIES,
            mutableSetOf<String>()
        ).orEmpty()
            .filter { it in SettingsRepository.KNOWN_EFFECT_CATEGORIES }
            .toSet()

    fun setAudioEffectsDisabledCategories(categories: Set<String>) {
        prefs.edit().putStringSet(
            SettingsRepository.KEY_AUDIO_EFFECTS_DISABLED_CATEGORIES,
            categories
                .filter { it in SettingsRepository.KNOWN_EFFECT_CATEGORIES }
                .toSet()
        ).apply()
    }

    fun isSpeechBoostEnabled(): Boolean =
        prefs.getBoolean("speech_boost_enabled", false)

    fun setSpeechBoostEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("speech_boost_enabled", enabled).apply()
    }

    fun getSpeechBoostValue(): Float =
        prefs.getFloat("speech_boost_value", 1.0f)

    fun setSpeechBoostValue(value: Float) {
        val clamped = value.coerceIn(1.0f, 2.5f)
        prefs.edit().putFloat("speech_boost_value", clamped).apply()
    }

    fun isVolumeBoostEnabled(): Boolean =
        prefs.getBoolean("volume_boost_enabled", false)

    fun setVolumeBoostEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("volume_boost_enabled", enabled).apply()
    }

    fun getVolumeBoostValue(): Float =
        prefs.getFloat("volume_boost_value", 1.0f)

    fun setVolumeBoostValue(value: Float) {
        val clamped = value.coerceIn(1.0f, 2.5f)
        prefs.edit().putFloat("volume_boost_value", clamped).apply()
    }

    fun isSpeechBoostOnSpeaker(): Boolean =
        prefs.getBoolean("speech_boost_on_speaker", false)

    fun setSpeechBoostOnSpeaker(enabled: Boolean) {
        prefs.edit().putBoolean("speech_boost_on_speaker", enabled).apply()
    }
}
