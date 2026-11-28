package com.aymankhattab.nateq.core.data

import com.aymankhattab.nateq.core.engine.PunctuationLevels

/** تفويض إعدادات قراءة النصوص ومعالجة/تحويل النص. */
internal class SettingsTextProcessingDelegate(
    private val repo: SettingsRepository
) {
    private val prefs get() = repo.prefs

    fun getPunctuationLevel(): Int =
        prefs.getInt("punctuation_level", PunctuationLevels.SOME)

    fun setPunctuationLevel(level: Int) {
        prefs.edit()
            .putInt(
                "punctuation_level",
                level.coerceIn(
                    PunctuationLevels.MIN,
                    PunctuationLevels.MAX
                )
            )
            .apply()
    }

    fun isTashkeelPreserved(): Boolean =
        prefs.getBoolean("tashkeel_preserved", false)

    fun setTashkeelPreserved(enabled: Boolean) {
        prefs.edit().putBoolean("tashkeel_preserved", enabled).apply()
    }

    fun getSingleLetterMode(): String =
        prefs.getString("single_letter_mode", "name") ?: "name"

    fun setSingleLetterMode(mode: String) {
        val sanitized = when (mode) {
            "as_written", "dictionary" -> mode
            else -> "name"
        }
        prefs.edit().putString("single_letter_mode", sanitized).apply()
    }

    fun isNumberVoiceSpelledWordsEnabled(): Boolean =
        prefs.getBoolean("number_voice_spelled_words", true)

    fun setNumberVoiceSpelledWordsEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("number_voice_spelled_words", enabled)
            .apply()
    }

    fun isTimeConversionEnabled(): Boolean =
        prefs.getBoolean("text_processing_time", true)

    fun setTimeConversionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("text_processing_time", enabled).apply()
    }

    fun isCurrencyConversionEnabled(): Boolean =
        prefs.getBoolean("text_processing_currency", true)

    fun setCurrencyConversionEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("text_processing_currency", enabled)
            .apply()
    }

    fun isUnitConversionEnabled(): Boolean =
        prefs.getBoolean("text_processing_units", true)

    fun setUnitConversionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("text_processing_units", enabled).apply()
    }

    fun isSymbolConversionEnabled(): Boolean =
        prefs.getBoolean("text_processing_symbols", true)

    fun setSymbolConversionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("text_processing_symbols", enabled).apply()
    }

    fun isPhoneConversionEnabled(): Boolean =
        prefs.getBoolean("text_processing_phones", true)

    fun setPhoneConversionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("text_processing_phones", enabled).apply()
    }

    fun isArabicNormalizationEnabled(): Boolean =
        prefs.getBoolean("arabic_normalization", true)

    fun setArabicNormalizationEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("arabic_normalization", enabled)
            .apply()
    }

    fun isUrlShorteningEnabled(): Boolean =
        repo.isAccessibilityFeatureEnabled("a11y_url_shortening")

    fun isFollowReaderRateEnabled(): Boolean =
        prefs.getBoolean("follow_reader_rate_enabled", true)

    fun setFollowReaderRateEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("follow_reader_rate_enabled", enabled)
            .apply()
    }

    fun isEgyptianSpeechProcessingEnabled(): Boolean =
        prefs.getBoolean("egyptian_speech_processing", false)

    fun setEgyptianSpeechProcessingEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("egyptian_speech_processing", enabled)
            .apply()
    }

    fun isDynamicSpeedEnabled(): Boolean =
        prefs.getBoolean("dynamic_speed_enabled", false)

    fun setDynamicSpeedEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_speed_enabled", enabled).apply()
    }

    fun isMultiVoiceReaderEnabled(): Boolean =
        prefs.getBoolean("multi_voice_reader_enabled", false)

    fun setMultiVoiceReaderEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("multi_voice_reader_enabled", enabled)
            .apply()
    }
}
