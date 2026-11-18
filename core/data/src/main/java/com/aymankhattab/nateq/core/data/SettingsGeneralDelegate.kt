package com.aymankhattab.nateq.core.data

import com.aymankhattab.nateq.core.engine.AudioExpansionLevels

/** تفويض الإعدادات العامة (سرعة/نبرة/صوت افتراضية وتعزيزات الصوت). */
internal class SettingsGeneralDelegate(
    private val repo: SettingsRepository
) {
    private val prefs get() = repo.prefs

    fun getDefaultSpeechRate(): Float =
        if (prefs.contains("default_speech_rate")) {
            prefs.getFloat("default_speech_rate", 1.0f)
        } else {
            repo.getSpeechRateForCategory(
                SettingsRepository.VOICE_CATEGORY_DEFAULT
            )
        }

    fun setDefaultSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0f, 2f)
        prefs.edit()
            .putFloat("default_speech_rate", clamped)
            .putFloat(
                "speech_rate_${SettingsRepository.VOICE_CATEGORY_DEFAULT}",
                clamped
            )
            .apply()
    }

    fun getDefaultPitch(): Float =
        if (prefs.contains("default_pitch")) {
            prefs.getFloat("default_pitch", 1.0f)
        } else {
            repo.getPitchForCategory(
                SettingsRepository.VOICE_CATEGORY_DEFAULT
            )
        }

    fun setDefaultPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0f, 2f)
        prefs.edit()
            .putFloat("default_pitch", clamped)
            .putFloat(
                "pitch_${SettingsRepository.VOICE_CATEGORY_DEFAULT}",
                clamped
            )
            .apply()
    }

    fun getDefaultVolume(): Float =
        if (prefs.contains("default_volume")) {
            prefs.getFloat("default_volume", 1.0f)
        } else {
            repo.getVolumeForCategory(
                SettingsRepository.VOICE_CATEGORY_DEFAULT
            )
        }

    fun setDefaultVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        prefs.edit()
            .putFloat("default_volume", clamped)
            .putFloat(
                "volume_${SettingsRepository.VOICE_CATEGORY_DEFAULT}",
                clamped
            )
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
        prefs.edit()
            .putFloat("speech_boost_value", value.coerceIn(1f, 2.5f))
            .apply()
    }

    fun isVolumeBoostEnabled(): Boolean =
        prefs.getBoolean("volume_boost_enabled", false)

    fun setVolumeBoostEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("volume_boost_enabled", enabled).apply()
    }

    fun getVolumeBoostValue(): Float =
        prefs.getFloat("volume_boost_value", 1.0f)

    fun setVolumeBoostValue(value: Float) {
        prefs.edit()
            .putFloat("volume_boost_value", value.coerceIn(1f, 2.5f))
            .apply()
    }
}
