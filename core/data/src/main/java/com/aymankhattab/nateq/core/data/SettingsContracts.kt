package com.aymankhattab.nateq.core.data

import com.aymankhattab.nateq.core.engine.SynthesisConfig
import com.aymankhattab.nateq.engine.LanguageSpeechPrefs

/**
 * [LanguagePrefs] â€” Ù†Ø·Ø§Ù‚ Ù„ØºØ© Ø§Ù„ØªØ·Ø¨ÙŠÙ‚ ÙˆÙ„ØºØ© Ù†Ø·Ù‚ Ø§Ù„Ø¥Ø¹Ù„Ø§Ù†Ø§Øª (Ø§Ù„Ø¨Ù†Ø¯ 4).
 * ÙŠÙ†ÙÙ‘Ø°Ù‡Ø§ SettingsRepository.
 */
interface LanguagePrefs {

    /** Ù„ØºØ© ÙˆØ§Ø¬Ù‡Ø© Ø§Ù„ØªØ·Ø¨ÙŠÙ‚ (Ø±Ù…Ø² Ù…Ø«Ù„ "ar" Ø£Ùˆ "en")ØŒ null = Ù„ØºØ© Ø§Ù„Ù†Ø¸Ø§Ù…. */
    fun getAppLanguage(): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù„ØºØ© ÙˆØ§Ø¬Ù‡Ø© Ø§Ù„ØªØ·Ø¨ÙŠÙ‚ (null = Ù„ØºØ© Ø§Ù„Ù†Ø¸Ø§Ù…). */
    fun setAppLanguage(language: String?)

    /** Ù„ØºØ© Ù†Ø·Ù‚ Ø§Ù„Ø¥Ø¹Ù„Ø§Ù†Ø§Øª Ø§Ù„ØµÙˆØªÙŠØ©ØŒ null = Ù„ØºØ© Ø§Ù„ØªØ·Ø¨ÙŠÙ‚. */
    fun getAnnouncementSpeechLanguage(): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù„ØºØ© Ù†Ø·Ù‚ Ø§Ù„Ø¥Ø¹Ù„Ø§Ù†Ø§Øª (null = Ù„ØºØ© Ø§Ù„ØªØ·Ø¨ÙŠÙ‚). */
    fun setAnnouncementSpeechLanguage(language: String?)

    /** Ù„ØºØ© Ø§Ù„Ù†Ø·Ù‚ Ø§Ù„Ø§Ø­ØªÙŠØ§Ø·ÙŠØ© Ù„Ù„Ù†Øµ Ø§Ù„Ù„Ø§ØªÙŠÙ†ÙŠ Ø§Ù„Ù‚ØµÙŠØ± ØºÙŠØ± Ø§Ù„Ù…ØªØ­Ø³ÙŽÙ‘Ù… Ø¶Ù…Ù† Ø·Ù„Ø¨Ù Ø¹Ø±Ø¨ÙŠ
     *  (Ø¨Ù†Ø¯ Ø§Ù„Ù„ØºØ© Ø§Ù„Ø«Ø§Ù†ÙŠØ©): ÙƒÙ„Ù…Ø© Ù…Ø«Ù„ Â«BonjourÂ» Ù„Ø§ ÙŠØ­Ø³Ù…Ù‡Ø§ Ø§Ù„ÙƒØ§Ø´Ù ÙØªÙÙ†Ø·Ù‚
     *  Ø¨Ù‡Ø°Ù‡ Ø§Ù„Ù„ØºØ© Ø¹ÙˆØ¶ Ø§Ù„Ø¥Ù†Ø¬Ù„ÙŠØ²ÙŠØ© Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠØ©. Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠ "en". */
    fun getSecondaryLanguage(): String

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù„ØºØ© Ø§Ù„Ù†Ø·Ù‚ Ø§Ù„Ø§Ø­ØªÙŠØ§Ø·ÙŠØ© Ù„Ù„Ù†Øµ Ø§Ù„Ù„Ø§ØªÙŠÙ†ÙŠ ØºÙŠØ± Ø§Ù„Ù…ØªØ­Ø³ÙŽÙ‘Ù…. */
    fun setSecondaryLanguage(language: String)

    /** Ù‡Ù„ ØªØµÙÙŠØ© Ø¥Ø¸Ù‡Ø§Ø± Ù„ØºØ§Øª Ù…Ø­Ø¯Ø¯Ø© ÙÙ‚Ø· Ù…ÙØ¹Ù„Ø©ØŸ (Ø§Ù„Ø£Ù…Ø± 2) */
    fun isLanguageFilterEnabled(): Boolean

    /** ØªØ¹ÙŠÙŠÙ† Ø­Ø§Ù„Ø© ØªÙØ¹ÙŠÙ„ ØªØµÙÙŠØ© Ø§Ù„Ù„ØºØ§Øª. */
    fun setLanguageFilterEnabled(enabled: Boolean)

    /** Ù…Ø¬Ù…ÙˆØ¹Ø© Ø±Ù…ÙˆØ² Ø§Ù„Ù„ØºØ§Øª Ø§Ù„Ù…Ø³Ù…ÙˆØ­ Ø¨Ø¥Ø¸Ù‡Ø§Ø±Ù‡Ø§. */
    fun getLanguageFilterSet(): Set<String>

    /** ØªØ¹ÙŠÙŠÙ† Ù…Ø¬Ù…ÙˆØ¹Ø© Ø±Ù…ÙˆØ² Ø§Ù„Ù„ØºØ§Øª Ø§Ù„Ù…Ø³Ù…ÙˆØ­ Ø¨Ø¥Ø¸Ù‡Ø§Ø±Ù‡Ø§. */
    fun setLanguageFilterSet(languages: Set<String>)

    /** Ù‡Ù„ ØªØµÙÙŠØ© Ø¥Ø¸Ù‡Ø§Ø± Ù…Ø­Ø±ÙƒØ§Øª Ù…Ø­Ø¯Ø¯Ø© ÙÙ‚Ø· Ù…ÙØ¹Ù„Ø©ØŸ (Ø§Ù„Ø£Ù…Ø± 3) */
    fun isEngineFilterEnabled(): Boolean

    /** ØªØ¹ÙŠÙŠÙ† Ø­Ø§Ù„Ø© ØªÙØ¹ÙŠÙ„ ØªØµÙÙŠØ© Ø§Ù„Ù…Ø­Ø±ÙƒØ§Øª. */
    fun setEngineFilterEnabled(enabled: Boolean)

    /** Ù…Ø¬Ù…ÙˆØ¹Ø© Ù…Ø¹Ø±Ù‘ÙØ§Øª ÙˆØ­Ø²Ù… Ø§Ù„Ù…Ø­Ø±ÙƒØ§Øª Ø§Ù„Ù…Ø³Ù…ÙˆØ­ Ø¨Ø¥Ø¸Ù‡Ø§Ø±Ù‡Ø§. */
    fun getEngineFilterSet(): Set<String>

    /** ØªØ¹ÙŠÙŠÙ† Ù…Ø¬Ù…ÙˆØ¹Ø© Ù…Ø¹Ø±Ù‘ÙØ§Øª ÙˆØ­Ø²Ù… Ø§Ù„Ù…Ø­Ø±ÙƒØ§Øª Ø§Ù„Ù…Ø³Ù…ÙˆØ­ Ø¨Ø¥Ø¸Ù‡Ø§Ø±Ù‡Ø§. */
    fun setEngineFilterSet(engines: Set<String>)

    fun isFilterLanguagesEnabled(): Boolean = isLanguageFilterEnabled()
    fun setFilterLanguagesEnabled(enabled: Boolean) =
        setLanguageFilterEnabled(enabled)
    fun getFilterLanguages(): Set<String> = getLanguageFilterSet()
    fun setFilterLanguages(languages: Set<String>) =
        setLanguageFilterSet(languages)

    fun isFilterEnginesEnabled(): Boolean = isEngineFilterEnabled()
    fun setFilterEnginesEnabled(enabled: Boolean) =
        setEngineFilterEnabled(enabled)
    fun getFilterEngines(): Set<String> = getEngineFilterSet()
    fun setFilterEngines(engines: Set<String>) =
        setEngineFilterSet(engines)
}

/**
 * [SynthesisPrefs] â€” Ù†Ø·Ø§Ù‚ Ø§Ù„Ù†Ø·Ù‚ ÙˆØ§Ù„ØªØ±Ø¬ÙŠØ¹: Ø§Ù„Ø³Ø±Ø¹Ø©/Ø§Ù„Ù†Ø¨Ø±Ø©/Ù…Ø³ØªÙˆÙ‰ Ø§Ù„ØµÙˆØª
 * ÙˆØ§Ù„Ø£ØµÙˆØ§Øª Ø§Ù„Ù…ÙØ¶Ù‘Ù„Ø© Ù„ÙƒÙ„ Ù„ØºØ©ØŒ Ù…Ø¹ Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠØ§Øª. ÙŠØªØ¶Ù…Ù† [SynthesisConfig]
 * Ùˆ[VoicePrefsProvider] Ù„ÙŠÙƒØ³Ùˆ Ø­Ø§Ø¬Ø§Øª Ø®Ø· Ø§Ù„Ø¥Ù†ØªØ§Ø¬ ÙˆÙ…Ø²ÙˆÙ‘Ø¯ÙŠ Ø§Ù„Ø£ØµÙˆØ§Øª Ø¨Ù†Ø·Ø§Ù‚ ÙˆØ§Ø­Ø¯
 * (Ø§Ù„Ø¨Ù†Ø¯ 4). ÙŠÙ†ÙÙ‘Ø°Ù‡Ø§ SettingsRepository.
 */
interface SynthesisPrefs :
    SynthesisConfig, VoicePrefsProvider, TextProcessingPrefs {

    override fun isDynamicSpeedEnabled(): Boolean =
        super<SynthesisConfig>.isDynamicSpeedEnabled()

    override fun isMultiVoiceReaderEnabled(): Boolean =
        super<SynthesisConfig>.isMultiVoiceReaderEnabled()

    /** Ø§Ù„ØµÙˆØª Ø§Ù„Ù…ÙØ¶Ù‘Ù„ Ù„Ù„ØºØ© Ù…Ø¹ÙŠÙ‘Ù†Ø© (Ù…Ø¹Ø±Ù‘Ù ØµÙˆØª Ø¯Ø§Ø®Ù„ Ø§Ù„Ù…Ø­Ø±Ùƒ). */
    fun getPreferredVoiceId(languageTag: String): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø§Ù„ØµÙˆØª Ø§Ù„Ù…ÙØ¶Ù‘Ù„ Ù„Ù„ØºØ© Ù…Ø¹ÙŠÙ‘Ù†Ø©. */
    fun setPreferredVoiceId(languageTag: String, voiceId: String)

    /** ÙŠÙ…Ø³Ø­ Ø§Ù„ØµÙˆØª Ø§Ù„Ù…ÙØ¶Ù‘Ù„ Ù„Ù„ØºØ© Ù…Ø¹ÙŠÙ‘Ù†Ø©. */
    fun clearPreferredVoiceId(languageTag: String)

    /** Ù…Ø¹Ø¯Ù„ Ø§Ù„ÙƒÙ„Ø§Ù… Ù„Ù„ØºØ© Ù…Ø¹ÙŠÙ‘Ù†Ø© (Ù†Ø³Ø¨Ø© Ù…Ù† Ø§Ù„Ø·Ø¨ÙŠØ¹ÙŠ). */
    fun getSpeechRate(languageTag: String): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø¹Ø¯Ù„ Ø§Ù„ÙƒÙ„Ø§Ù… Ù„Ù„ØºØ© Ù…Ø¹ÙŠÙ‘Ù†Ø©. */
    fun setSpeechRate(languageTag: String, rate: Float)

    /** Ø¯Ø±Ø¬Ø© Ø§Ù„ØµÙˆØª Ù„Ù„ØºØ© Ù…Ø¹ÙŠÙ‘Ù†Ø© (Ù†ØµÙ Ù†ØºÙ…Ø©). */
    fun getPitch(languageTag: String): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø¯Ø±Ø¬Ø© Ø§Ù„ØµÙˆØª Ù„Ù„ØºØ© Ù…Ø¹ÙŠÙ‘Ù†Ø©. */
    fun setPitch(languageTag: String, pitch: Float)

    /** Ù…Ø³ØªÙˆÙ‰ Ø§Ù„ØµÙˆØª Ù„Ù„ØºØ© Ù…Ø¹ÙŠÙ‘Ù†Ø© (0..1). */
    fun getVolume(languageTag: String): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³ØªÙˆÙ‰ Ø§Ù„ØµÙˆØª Ù„Ù„ØºØ© Ù…Ø¹ÙŠÙ‘Ù†Ø©. */
    fun setVolume(languageTag: String, volume: Float)

    /** Ù…Ø¹Ø¯Ù„ Ø§Ù„ÙƒÙ„Ø§Ù… Ù„Ù„ØºØ© Ù…Ø¹ÙŠÙ‘Ù†Ø© Ø£Ùˆ null Ø¥Ù† Ù„Ù… ÙŠÙØ¶Ø¨Ø· ØµØ±Ø§Ø­Ø©Ù‹. */
    override fun getSpeechRateOrNull(languageTag: String): Float?

    /** Ø¯Ø±Ø¬Ø© Ø§Ù„ØµÙˆØª Ù„Ù„ØºØ© Ù…Ø¹ÙŠÙ‘Ù†Ø© Ø£Ùˆ null Ø¥Ù† Ù„Ù… ØªÙØ¶Ø¨Ø· ØµØ±Ø§Ø­Ø©Ù‹. */
    override fun getPitchOrNull(languageTag: String): Float?

    /** Ù…Ø³ØªÙˆÙ‰ Ø§Ù„ØµÙˆØª Ù„Ù„ØºØ© Ù…Ø¹ÙŠÙ‘Ù†Ø© Ø£Ùˆ null Ø¥Ù† Ù„Ù… ÙŠÙØ¶Ø¨Ø· ØµØ±Ø§Ø­Ø©Ù‹. */
    override fun getVolumeOrNull(languageTag: String): Float?

    /** Ù…Ø¹Ø¯Ù„ Ø§Ù„ÙƒÙ„Ø§Ù… Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠ (Ù„Ù„ØºØ§Øª Ø¨Ù„Ø§ ØªÙØ¶ÙŠÙ„ ØµØ±ÙŠØ­). */
    fun getDefaultSpeechRate(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø¹Ø¯Ù„ Ø§Ù„ÙƒÙ„Ø§Ù… Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠ. */
    fun setDefaultSpeechRate(rate: Float)

    /** Ø¯Ø±Ø¬Ø© Ø§Ù„ØµÙˆØª Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠØ© (Ù„Ù„ØºØ§Øª Ø¨Ù„Ø§ ØªÙØ¶ÙŠÙ„ ØµØ±ÙŠØ­). */
    fun getDefaultPitch(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø¯Ø±Ø¬Ø© Ø§Ù„ØµÙˆØª Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠØ©. */
    fun setDefaultPitch(pitch: Float)

    /** Ù…Ø³ØªÙˆÙ‰ Ø§Ù„ØµÙˆØª Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠ (Ù„Ù„ØºØ§Øª Ø¨Ù„Ø§ ØªÙØ¶ÙŠÙ„ ØµØ±ÙŠØ­). */
    fun getDefaultVolume(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³ØªÙˆÙ‰ Ø§Ù„ØµÙˆØª Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠ. */
    fun setDefaultVolume(volume: Float)

    /** Ù…Ø³ØªÙˆÙ‰ Ø§ØªØ³Ø§Ø¹ Ø§Ù„ØµÙˆØª (0 Ø¥ÙŠÙ‚Ø§ÙØŒ 1 Ø®ÙÙŠÙØŒ 2 Ù…ØªÙˆØ³Ø·). */
    fun getAudioExpansionLevel(): Int

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³ØªÙˆÙ‰ Ø§ØªØ³Ø§Ø¹ Ø§Ù„ØµÙˆØª. */
    fun setAudioExpansionLevel(level: Int)

    /**
     * Ù‡Ù„ Ø§Ù„ØµØ¯Ù‰ (PresetReverb) Ù…ÙØ¹Ù‘Ù„ØŸ **Ù…Ø³ØªÙ‚Ù„ÙŒÙ‘ Ø¹Ù† Ø§Ù„Ø§ØªØ³Ø§Ø¹** ÙˆÙŠØ¨Ø¯Ø£
     * Ù…ÙØ¹Ø·ÙŽÙ‘Ù„Ø§Ù‹ØŒ ÙØ§Ù„Ø£Ø³Ø§Ø³Ù Ø§Ù„ÙˆØ­ÙŠØ¯ Ù‡Ùˆ Virtualizer.
     */
    fun isAudioReverbEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø§Ù„ØµØ¯Ù‰. */
    fun setAudioReverbEnabled(enabled: Boolean)

    /**
     * ÙØ¦Ø§ØªÙ Ø§Ù„Ø¥Ø¹Ù„Ø§Ù†Ø§Øª Ø§Ù„ØªÙŠ Ø¹ÙØ·Ù‘Ù„ ÙÙŠÙ‡Ø§ Ù…Ø¤Ø«Ø±Ø§Øª Ø§Ù„ØµÙˆØª (Ø§Ù„Ø³Ø§Ø¹Ø©/Ø§Ù„Ø¨Ø·Ø§Ø±ÙŠØ©/
     * Ø§Ù„Ù…ØªØµÙ„/Ø§Ù„Ø±Ø³Ø§Ø¦Ù„/Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª). **Ù‚Ø±Ø§Ø¡Ø©Ù Ø§Ù„Ù†Øµ Ø§Ù„Ø·ÙˆÙŠÙ„ Ù„ÙŠØ³Øª Ù…Ù†Ù‡Ø§** ÙÙ„Ø§
     * ÙŠÙ…ÙƒÙ† ØªØ¹Ø·ÙŠÙ„Ù ØªØ£Ø«ÙŠØ±Ù‡Ø§. ÙˆØ§Ù„Ù‚Ø§Ø¦Ù…Ø© Ø§Ù„ÙØ§Ø±ØºØ© ØªØ¹Ù†ÙŠ: Ø§Ù„ÙƒÙ„ÙÙ‘ Ù…ÙØ¹Ù‘Ù„.
     */
    fun getAudioEffectsDisabledCategories(): Set<String>

    /** ÙŠØ¹ÙŠÙ‘Ù† ÙØ¦Ø§Øª Ø§Ù„Ø¥Ø¹Ù„Ø§Ù†Ø§Øª Ø§Ù„ØªÙŠ Ø¹ÙØ·Ù‘Ù„ ÙÙŠÙ‡Ø§ Ù…Ø¤Ø«Ø±Ø§Øª Ø§Ù„ØµÙˆØª. */
    fun setAudioEffectsDisabledCategories(categories: Set<String>)

    /** Ù‡Ù„ Ù…Ø¶Ø§Ø¹Ù Ø§Ù„Ø³Ø±Ø¹Ø© Ù…ÙØ¹Ù‘Ù„ØŸ (Ù…Ø±Ø¨Ø¹ Â«Ù…Ø¶Ø§Ø¹ÙØ© Ø§Ù„Ø³Ø±Ø¹Ø©Â») â€” ÙŠÙØ¶Ø±Ø¨ Ø¨Ø§Ù„Ø³Ø±Ø¹Ø©
     *  Ø§Ù„Ù†Ù‡Ø§Ø¦ÙŠØ© Ù„ÙƒÙ„ Ù†Ø·Ù‚ Ø¹Ø¨Ø± Ø§Ù„Ù…Ø³Ø§Ø±Ø§Øª ÙƒÙ„Ù‡Ø§ Ù‚Ø¨Ù„ Ù‚ØµÙ‘Ù‡Ø§ Ø¹Ù„Ù‰ Ø§Ù„Ø­Ø¯ Ø§Ù„Ø¢Ù…Ù†. */
    fun isSpeechBoostEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ù…Ø¶Ø§Ø¹Ù Ø§Ù„Ø³Ø±Ø¹Ø©. */
    fun setSpeechBoostEnabled(enabled: Boolean)

    /** Ù‚ÙŠÙ…Ø© Ù…Ø¶Ø§Ø¹Ù Ø§Ù„Ø³Ø±Ø¹Ø© (1.0Ã— Ø·Ø¨ÙŠØ¹ÙŠ Ø­ØªÙ‰ 2.5Ã—) â€” ØªÙØ·Ø¨Ù‚ Ø¹Ù„Ù‰ Ø§Ù„Ø³Ø±Ø¹Ø©
     *  Ø§Ù„Ù†Ù‡Ø§Ø¦ÙŠØ© Ø¹Ù†Ø¯ Ø§Ù„ØªÙØ¹ÙŠÙ„. Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠ 1.0 (Ù„Ø§ ØªØ¶Ø®ÙŠÙ…). */
    fun getSpeechBoostValue(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù‚ÙŠÙ…Ø© Ù…Ø¶Ø§Ø¹Ù Ø§Ù„Ø³Ø±Ø¹Ø© ÙÙŠ Ø§Ù„Ù†Ø·Ø§Ù‚ Ø§Ù„Ø¢Ù…Ù† (1.0..2.5). */
    fun setSpeechBoostValue(value: Float)

    /** Ù‡Ù„ Ù…Ø¶Ø§Ø¹Ù Ù…Ø³ØªÙˆÙ‰ Ø§Ù„ØµÙˆØª Ù…ÙØ¹Ù‘Ù„ØŸ (Ù…Ø±Ø¨Ø¹ Â«Ù…Ø¶Ø§Ø¹ÙØ© Ø§Ù„ØµÙˆØªÂ») â€” ÙŠÙØ¶Ø±Ø¨ Ø¨Ù…Ø³ØªÙˆÙ‰
     *  Ø§Ù„ØµÙˆØª Ø§Ù„Ù†Ù‡Ø§Ø¦ÙŠ Ù„ÙƒÙ„ Ù†Ø·Ù‚ Ø¹Ø¨Ø± Ø§Ù„Ù…Ø³Ø§Ø±Ø§Øª ÙƒÙ„Ù‡Ø§ Ø«Ù… ÙŠÙÙ‚ØµÙ‘ Ø¹Ù„Ù‰ Ø§Ù„ÙƒØ§Ù…Ù„ (1.0). */
    fun isVolumeBoostEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ù…Ø¶Ø§Ø¹Ù Ù…Ø³ØªÙˆÙ‰ Ø§Ù„ØµÙˆØª. */
    fun setVolumeBoostEnabled(enabled: Boolean)

    /** Ù‚ÙŠÙ…Ø© Ù…Ø¶Ø§Ø¹Ù Ø§Ù„ØµÙˆØª (1.0Ã— Ø·Ø¨ÙŠØ¹ÙŠ Ø­ØªÙ‰ 2.5Ã—) â€” ØªÙØ·Ø¨Ù‚ Ø¹Ù„Ù‰ Ù…Ø³ØªÙˆÙ‰
     *  Ø§Ù„ØµÙˆØª Ø§Ù„Ù†Ù‡Ø§Ø¦ÙŠ Ø¹Ù†Ø¯ Ø§Ù„ØªÙØ¹ÙŠÙ„. Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠ 1.0 (Ù„Ø§ ØªØ¶Ø®ÙŠÙ…). */
    fun getVolumeBoostValue(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù‚ÙŠÙ…Ø© Ù…Ø¶Ø§Ø¹Ù Ø§Ù„ØµÙˆØª ÙÙŠ Ø§Ù„Ù†Ø·Ø§Ù‚ Ø§Ù„Ø¢Ù…Ù† (1.0..2.5). */
    fun setVolumeBoostValue(value: Float)

    /** ØªÙØ¶ÙŠÙ„ Ù…Ø­Ø±ÙƒÙƒ/ØµÙˆØªÙƒ/Ø£Ø´Ø±Ø·ØªÙƒ Ù„Ù„ØºØ© ÙÙŠ Ø®Ø§Ø±Ø·Ø© Ø§Ù„ØªØ­ÙˆÙŠÙ„ Ø§Ù„Ø¯ÙŠÙ†Ø§Ù…ÙŠÙƒÙŠØ©. */
    fun getEnginePreferenceForLanguage(languageTag: String): LanguageSpeechPrefs

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¶ÙŠÙ„ Ø§Ù„Ù…Ø­Ø±Ùƒ/Ø§Ù„ØµÙˆØª/Ø§Ù„Ø£Ø´Ø±Ø·Ø© Ù„Ù„ØºØ© ÙÙŠ Ø§Ù„Ø®Ø§Ø±Ø·Ø© Ø§Ù„Ø¯ÙŠÙ†Ø§Ù…ÙŠÙƒÙŠØ©
     *  (Ù…Ø¯Ø®Ù„ Ù…Ø³Ø§ÙˆÙ Ù„Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠØ§Øª ÙŠÙØ­Ø°Ù). */
    fun setEnginePreferenceForLanguage(
        languageTag: String,
        engine: String?,
        voiceName: String?,
        rate: Float,
        pitch: Float,
        volume: Float
    )

    /** ÙƒÙ„ ØªÙØ¶ÙŠÙ„Ø§Øª Ø§Ù„Ù„ØºØ§Øª ÙÙŠ Ø®Ø§Ø±Ø·Ø© Ø§Ù„ØªØ­ÙˆÙŠÙ„ Ø§Ù„Ø¯ÙŠÙ†Ø§Ù…ÙŠÙƒÙŠØ©. */
    fun allConvertLanguagePreferences(): Map<String, LanguageSpeechPrefs>

    /** Ø§Ù„ØµÙˆØª Ø§Ù„Ù…ÙØ¶Ù‘Ù„ Ù„Ù…Ø­Ø±Ùƒ Ù†Ø·Ù‚ Ù…Ø¹ÙŠÙ‘Ù† Ù„ÙƒÙ„ Ù„ØºØ©. */
    fun getPreferredVoiceForEngine(
        engine: String,
        languageTag: String
    ): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø§Ù„ØµÙˆØª Ø§Ù„Ù…ÙØ¶Ù‘Ù„ Ù„Ù…Ø­Ø±Ùƒ Ù†Ø·Ù‚ Ù…Ø¹ÙŠÙ‘Ù† Ù„ÙƒÙ„ Ù„ØºØ©. */
    fun setPreferredVoiceForEngine(
        engine: String,
        languageTag: String,
        voiceName: String?
    )
}

/**
 * [CategoryVoicePrefs] â€” Ù†Ø·Ø§Ù‚ Ø§Ù„Ø£ØµÙˆØ§Øª/Ø§Ù„Ù…Ø­Ø±ÙƒØ§Øª/Ø§Ù„Ø£Ø´Ø±Ø·Ø© Ù„ÙƒÙ„ Ù…Ø¬Ù…ÙˆØ¹Ø© ØµÙˆØªÙŠØ©
 * (Ø§Ù„Ø²Ù…Ù†ØŒ Ø§Ù„Ø£Ø±Ù‚Ø§Ù…ØŒ Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§ØªØŒ Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠØŒ Ø§Ù„Ø¥ÙŠÙ…ÙˆØ¬ÙŠØŒ Ø§Ù„Ù…ØªØµÙ„...) (Ø§Ù„Ø¨Ù†Ø¯ 4).
 * ÙŠÙ†ÙÙ‘Ø°Ù‡Ø§ SettingsRepository.
 */
interface CategoryVoicePrefs {

    companion object {
        const val VOICE_CATEGORY_BATTERY = "battery"
        const val VOICE_CATEGORY_LORD_SYSTEM = "lord_system"
    }

    /** Ø§Ù„ØµÙˆØª Ø§Ù„Ù…ÙØ¶Ù‘Ù„ Ù„Ù…Ø¬Ù…ÙˆØ¹Ø© ØµÙˆØªÙŠØ© Ù…Ø¹ÙŠÙ‘Ù†Ø©. */
    fun getPreferredVoiceIdForCategory(category: String): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø§Ù„ØµÙˆØª Ø§Ù„Ù…ÙØ¶Ù‘Ù„ Ù„Ù…Ø¬Ù…ÙˆØ¹Ø© ØµÙˆØªÙŠØ© Ù…Ø¹ÙŠÙ‘Ù†Ø©. */
    fun setPreferredVoiceIdForCategory(category: String, voiceId: String)

    /** Ù…Ø­Ø±Ùƒ TTS Ù„Ù…Ø¬Ù…ÙˆØ¹Ø© ØµÙˆØªÙŠØ© Ù…Ø¹ÙŠÙ‘Ù†Ø© (null = Ù…Ø­Ø±Ùƒ Ø§Ù„Ù†Ø¸Ø§Ù…). */
    fun getEngineForCategory(category: String): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø­Ø±Ùƒ TTS Ù„Ù…Ø¬Ù…ÙˆØ¹Ø© ØµÙˆØªÙŠØ© Ù…Ø¹ÙŠÙ‘Ù†Ø©. */
    fun setEngineForCategory(category: String, engine: String?)

    /** Ù…Ø¹Ø¯Ù„ Ø§Ù„ÙƒÙ„Ø§Ù… Ù„Ù…Ø¬Ù…ÙˆØ¹Ø© ØµÙˆØªÙŠØ© Ù…Ø¹ÙŠÙ‘Ù†Ø©. */
    fun getSpeechRateForCategory(category: String): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø¹Ø¯Ù„ Ø§Ù„ÙƒÙ„Ø§Ù… Ù„Ù…Ø¬Ù…ÙˆØ¹Ø© ØµÙˆØªÙŠØ© Ù…Ø¹ÙŠÙ‘Ù†Ø©. */
    fun setSpeechRateForCategory(category: String, rate: Float)

    /** Ø¯Ø±Ø¬Ø© Ø§Ù„ØµÙˆØª Ù„Ù…Ø¬Ù…ÙˆØ¹Ø© ØµÙˆØªÙŠØ© Ù…Ø¹ÙŠÙ‘Ù†Ø©. */
    fun getPitchForCategory(category: String): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø¯Ø±Ø¬Ø© Ø§Ù„ØµÙˆØª Ù„Ù…Ø¬Ù…ÙˆØ¹Ø© ØµÙˆØªÙŠØ© Ù…Ø¹ÙŠÙ‘Ù†Ø©. */
    fun setPitchForCategory(category: String, pitch: Float)

    /** Ù…Ø³ØªÙˆÙ‰ Ø§Ù„ØµÙˆØª Ù„Ù…Ø¬Ù…ÙˆØ¹Ø© ØµÙˆØªÙŠØ© Ù…Ø¹ÙŠÙ‘Ù†Ø©. */
    fun getVolumeForCategory(category: String): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³ØªÙˆÙ‰ Ø§Ù„ØµÙˆØª Ù„Ù…Ø¬Ù…ÙˆØ¹Ø© ØµÙˆØªÙŠØ© Ù…Ø¹ÙŠÙ‘Ù†Ø©. */
    fun setVolumeForCategory(category: String, volume: Float)

    /** Ù‡Ù„ Ø¶ÙØ¨Ø· Ù…Ø¹Ø¯Ù„ Ø§Ù„ÙƒÙ„Ø§Ù… ØµØ±Ø§Ø­Ø©Ù‹ Ù„Ù‡Ø°Ù‡ Ø§Ù„ÙØ¦Ø©ØŸ */
    fun hasExplicitSpeechRateForCategory(category: String): Boolean = false

    /** Ù‡Ù„ Ø¶ÙØ¨Ø·Øª Ø¯Ø±Ø¬Ø© Ø§Ù„ØµÙˆØª ØµØ±Ø§Ø­Ø©Ù‹ Ù„Ù‡Ø°Ù‡ Ø§Ù„ÙØ¦Ø©ØŸ */
    fun hasExplicitPitchForCategory(category: String): Boolean = false

    /** Ù‡Ù„ Ø¶ÙØ¨Ø· Ù…Ø³ØªÙˆÙ‰ Ø§Ù„ØµÙˆØª ØµØ±Ø§Ø­Ø©Ù‹ Ù„Ù‡Ø°Ù‡ Ø§Ù„ÙØ¦Ø©ØŸ */
    fun hasExplicitVolumeForCategory(category: String): Boolean = false

    /** Ù…Ø­Ø±Ùƒ ØµÙˆØª Ø§Ù„Ø·ÙˆØ§Ø±Ø¦ (Ø§Ù„Ø£Ù…Ø± 1). */
    fun getEmergencyEngine(): String? = null

    /** ØªØ¹ÙŠÙŠÙ† Ù…Ø­Ø±Ùƒ ØµÙˆØª Ø§Ù„Ø·ÙˆØ§Ø±Ø¦. */
    fun setEmergencyEngine(engine: String?) {}

    /** Ù…Ø¹Ø±Ù‘Ù ØµÙˆØª Ø§Ù„Ø·ÙˆØ§Ø±Ø¦. */
    fun getEmergencyVoice(): String? = null

    /** ØªØ¹ÙŠÙŠÙ† Ù…Ø¹Ø±Ù‘Ù ØµÙˆØª Ø§Ù„Ø·ÙˆØ§Ø±Ø¦. */
    fun setEmergencyVoice(voice: String?) {}

    /** Ø³Ø±Ø¹Ø© ØµÙˆØª Ø§Ù„Ø·ÙˆØ§Ø±Ø¦ (0.5..3.0). */
    fun getEmergencyRate(): Float = 1.0f

    /** ØªØ¹ÙŠÙŠÙ† Ø³Ø±Ø¹Ø© ØµÙˆØª Ø§Ù„Ø·ÙˆØ§Ø±Ø¦. */
    fun setEmergencyRate(rate: Float) {}

    /** Ù†Ø¨Ø±Ø© ØµÙˆØª Ø§Ù„Ø·ÙˆØ§Ø±Ø¦ (0.5..2.0). */
    fun getEmergencyPitch(): Float = 1.0f

    /** ØªØ¹ÙŠÙŠÙ† Ù†Ø¨Ø±Ø© ØµÙˆØª Ø§Ù„Ø·ÙˆØ§Ø±Ø¦. */
    fun setEmergencyPitch(pitch: Float) {}

    /** Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ø§Ù„Ø·ÙˆØ§Ø±Ø¦ (0.0..1.0). */
    fun getEmergencyVolume(): Float = 1.0f

    /** ØªØ¹ÙŠÙŠÙ† Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ø§Ù„Ø·ÙˆØ§Ø±Ø¦. */
    fun setEmergencyVolume(volume: Float) {}

    /** Ù‡Ù„ ØªÙ… Ø­ÙØ¸ ØµÙˆØª Ø§Ù„Ø·ÙˆØ§Ø±Ø¦ ØµØ±Ø§Ø­Ø©Ù‹ØŸ */
    fun isEmergencySaved(): Boolean = false

    /** ØªØ¹ÙŠÙŠÙ† Ø¹Ù„Ù… Ø­ÙØ¸ ØµÙˆØª Ø§Ù„Ø·ÙˆØ§Ø±Ø¦. */
    fun setEmergencySaved(saved: Boolean) {}
}

/**
 * [ReadingPrefs] â€” Ù†Ø·Ø§Ù‚ Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ù†Øµ ÙˆÙ†Ø·Ù‚ Ø§Ù„Ø±Ù…ÙˆØ²: ÙˆØ¶Ø¹ Ø§Ù„Ø£Ø±Ù‚Ø§Ù…ØŒ Ù…Ø³ØªÙˆÙ‰
 * Ø¹Ù„Ø§Ù…Ø§Øª Ø§Ù„ØªØ±Ù‚ÙŠÙ…ØŒ Ø§Ù„ØªÙ‡Ø¬Ø¦Ø© Ø§Ù„Ø°ÙƒÙŠØ©ØŒ Ù†Ø·Ù‚ Ø§Ù„Ø¥ÙŠÙ…ÙˆØ¬ÙŠØŒ ÙˆÙ…Ø³ØªØ´Ø¹Ø±Ø§Øª Ø§Ù„Ø¥ÙŠÙ‚Ø§Ù (Ø§Ù„Ø¨Ù†Ø¯ 4).
 * ÙŠÙ†ÙÙ‘Ø°Ù‡Ø§ SettingsRepository.
 */
interface ReadingPrefs {

    /** ÙˆØ¶Ø¹ Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø£Ø±Ù‚Ø§Ù…: 0 Ø£Ø±Ù‚Ø§Ù…ØŒ 1 Ù‚ÙŠÙ… Ø§Ù„Ø£Ø±Ù‚Ø§Ù…ØŒ 2 Ù‚ÙŠÙ… Ø§Ù„Ø£Ø±Ù‚Ø§Ù…ØŒ 3 ØªÙ‡Ø¬Ø¦Ø©. */
    fun getNumberReadingMode(): Int

    /** ÙŠØ¹ÙŠÙ‘Ù† ÙˆØ¶Ø¹ Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø£Ø±Ù‚Ø§Ù…. */
    fun setNumberReadingMode(mode: Int)

    /** Ù„ØºØ© Ù†Ø·Ù‚ Ø§Ù„Ø£Ø±Ù‚Ø§Ù… ("ar" Ø£Ùˆ "en" ÙÙ‚Ø·). */
    fun getNumberReadingLanguage(): String

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù„ØºØ© Ù†Ø·Ù‚ Ø§Ù„Ø£Ø±Ù‚Ø§Ù… ("ar" Ø£Ùˆ "en" ÙÙ‚Ø·). */
    fun setNumberReadingLanguage(lang: String)

    /** Ù…Ø³ØªÙˆÙ‰ Ù†Ø·Ù‚ Ø¹Ù„Ø§Ù…Ø§Øª Ø§Ù„ØªØ±Ù‚ÙŠÙ…: 0 Ù„Ø§ Ø´ÙŠØ¡ØŒ 1 Ø§Ù„Ø¨Ø¹Ø¶ØŒ 2 Ø§Ù„ÙƒÙ„. */
    fun getPunctuationLevel(): Int

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³ØªÙˆÙ‰ Ù†Ø·Ù‚ Ø¹Ù„Ø§Ù…Ø§Øª Ø§Ù„ØªØ±Ù‚ÙŠÙ…. */
    fun setPunctuationLevel(level: Int)


    /** Ø¥Ø¨Ù‚Ø§Ø¡ ØªØ´ÙƒÙŠÙ„ Ø§Ù„Ù†ØµÙˆØµ Ø§Ù„Ø¹Ø±Ø¨ÙŠØ© Ø§Ù„Ù…ÙØ±Ø³Ù„Ø© Ù„Ù„Ù…Ø­Ø±Ùƒ (Ø¨Ù†Ø¯ 1.7). */
    fun isTashkeelPreserved(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø¥Ø¨Ù‚Ø§Ø¡ ØªØ´ÙƒÙŠÙ„ Ø§Ù„Ù†ØµÙˆØµ Ø§Ù„Ø¹Ø±Ø¨ÙŠØ©. */
    fun setTashkeelPreserved(enabled: Boolean)

    /** ÙˆØ¶Ø¹ Ù†Ø·Ù‚ Ø§Ù„Ø­Ø±Ù Ø§Ù„Ù…ÙØ±Ø¯ (Â«nameÂ»ØŒ Â«as_writtenÂ»ØŒ Â«dictionaryÂ»). */
    fun getSingleLetterMode(): String

    /** ÙŠØ¹ÙŠÙ‘Ù† ÙˆØ¶Ø¹ Ù†Ø·Ù‚ Ø§Ù„Ø­Ø±Ù Ø§Ù„Ù…ÙØ±Ø¯. */
    fun setSingleLetterMode(mode: String)

    /** ØªØ·Ø¨ÙŠÙ‚ ØµÙˆØª ÙØ¦Ø© Ø§Ù„Ø£Ø±Ù‚Ø§Ù… Ø¹Ù„Ù‰ Ø§Ù„Ø£Ø¹Ø¯Ø§Ø¯ Ø§Ù„Ù…ÙƒØªÙˆØ¨Ø© Ø¨Ø§Ù„Ø­Ø±ÙˆÙ. */
    fun isNumberVoiceSpelledWordsEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªØ·Ø¨ÙŠÙ‚ ØµÙˆØª ÙØ¦Ø© Ø§Ù„Ø£Ø±Ù‚Ø§Ù… Ø¹Ù„Ù‰ Ø§Ù„Ø£Ø¹Ø¯Ø§Ø¯ Ø§Ù„Ù…ÙƒØªÙˆØ¨Ø© Ø¨Ø§Ù„Ø­Ø±ÙˆÙ. */
    fun setNumberVoiceSpelledWordsEnabled(enabled: Boolean)

    /** Ø§ØªØ¨Ø§Ø¹ Ø³Ø±Ø¹Ø© Ù‚Ø§Ø±Ø¦ Ø§Ù„Ø´Ø§Ø´Ø© ÙÙŠ Ù†Ø·Ù‚ Ù†ØµÙ‡ (Ø§Ù„Ù†Ø³Ø¨Ø© Ø§Ù„Ù…Ø¦ÙˆÙŠØ© = 100 Ø·Ø¨ÙŠØ¹ÙŠ):
     *  Ø¹Ù†Ø¯ Ø§Ù„ØªÙØ¹ÙŠÙ„ ØªÙÙ‡Ù…Ù„ Ø£Ø´Ø±Ø·Ø© Ø³Ø±Ø¹Ø© LORD (Ù…Ø¶Ø±ÙˆØ¨ 1.0) ÙÙŠÙÙ†Ø·Ù‚ ÙƒÙ…Ø§ Ø¶Ø¨Ø·Ù‡
     *  Ø§Ù„Ù…Ø³ØªØ®Ø¯Ù… ÙÙŠ Ø§Ù„Ù†Ø¸Ø§Ù… Ø­Ø±ÙÙŠØ§Ù‹. */
    fun isFollowReaderRateEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø§ØªØ¨Ø§Ø¹ Ø³Ø±Ø¹Ø© Ù‚Ø§Ø±Ø¦ Ø§Ù„Ø´Ø§Ø´Ø©. */
    fun setFollowReaderRateEnabled(enabled: Boolean)

    /** ØªØ³Ø±ÙŠØ¹ Ù†Ø·Ù‚ Ø§Ù„Ù†ØµÙˆØµ Ø§Ù„Ø·ÙˆÙŠÙ„Ø© ØªØ¯Ø±ÙŠØ¬ÙŠØ§Ù‹ Ø¨Ø¹Ø¯ Ø£ÙˆÙ„ 300 Ø­Ø±Ù. */
    fun isLongTextSpeedupEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªØ³Ø±ÙŠØ¹ Ù†Ø·Ù‚ Ø§Ù„Ù†ØµÙˆØµ Ø§Ù„Ø·ÙˆÙŠÙ„Ø© ØªØ¯Ø±ÙŠØ¬ÙŠØ§Ù‹. */
    fun setLongTextSpeedupEnabled(enabled: Boolean)

    /** ØªÙØ¹ÙŠÙ„ Ù†Ø·Ù‚ Ø£Ø³Ù…Ø§Ø¡ Ø§Ù„Ø¥ÙŠÙ…ÙˆØ¬ÙŠ Ù‚Ø¨Ù„ Ø¥Ø²Ø§Ù„ØªÙ‡Ø§ Ù…Ù† Ø§Ù„Ù†Øµ. */
    fun isEmojiPronunciationEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ù†Ø·Ù‚ Ø£Ø³Ù…Ø§Ø¡ Ø§Ù„Ø¥ÙŠÙ…ÙˆØ¬ÙŠ. */
    fun setEmojiPronunciationEnabled(enabled: Boolean)

    /** Ø­Ø¯ Ø£Ù‚ØµÙ‰ Ù„Ø·ÙˆÙ„ Ø§Ù„Ù…ÙØªØ§Ø­ Ù„ØªØ·Ø¨ÙŠÙ‚ Ù‚Ø§Ø¹Ø¯Ø© Ø§Ù„Ø­Ø±Ù Ø§Ù„Ù…Ù†ÙØ±Ø¯ (0..3ØŒ Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠ 1). */
    fun getDictionaryStandaloneMaxLength(): Int = 1

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø§Ù„Ø­Ø¯ Ø§Ù„Ø£Ù‚ØµÙ‰ Ù„Ø·ÙˆÙ„ Ø§Ù„Ù…ÙØªØ§Ø­ Ù„Ù‚Ø§Ø¹Ø¯Ø© Ø§Ù„Ø­Ø±Ù Ø§Ù„Ù…Ù†ÙØ±Ø¯. */
    fun setDictionaryStandaloneMaxLength(len: Int)

    /** Ù„ØºØ© Ù†Ø·Ù‚ Ø§Ù„Ø¥ÙŠÙ…ÙˆØ¬ÙŠ: "AUTO", "AR", "EN" (Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠ "AUTO"). */
    fun getEmojiPronunciationLanguage(): String = "AUTO"

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù„ØºØ© Ù†Ø·Ù‚ Ø§Ù„Ø¥ÙŠÙ…ÙˆØ¬ÙŠ ("AUTO", "AR", "EN"). */
    fun setEmojiPronunciationLanguage(lang: String)

    /** ØªÙØ¹ÙŠÙ„ ØªØ´Ø®ÙŠØµØ§Øª Ø§Ù„Ù„ØºØ§Øª ÙÙŠ Logcat (Ø§Ù„Ø£Ù…Ø± 3). */
    fun isLanguageDiagnosticsEnabled(): Boolean = false

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ ØªØ´Ø®ÙŠØµØ§Øª Ø§Ù„Ù„ØºØ§Øª. */
    fun setLanguageDiagnosticsEnabled(enabled: Boolean)

    /** ØªØ­ÙˆÙŠÙ„ ÙƒÙ„Ù…Ø§Øª Ø§Ù„ÙØªØ±Ø© ÙÙŠ Ø¥Ø¹Ù„Ø§Ù†Ø§Øª Ø§Ù„ÙˆÙ‚Øª Ù„Ù†ÙˆÙ† ØµØ±ÙŠØ­Ø© (Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠ true). */
    fun isDayPeriodExplicitNunEnabled(): Boolean = true

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªØ­ÙˆÙŠÙ„ ÙƒÙ„Ù…Ø§Øª Ø§Ù„ÙØªØ±Ø© Ø¥Ù„Ù‰ Ù†ÙˆÙ† ØµØ±ÙŠØ­Ø©. */
    fun setDayPeriodExplicitNunEnabled(enabled: Boolean)

    /** Ø·Ø±ÙŠÙ‚Ø© ØµÙŠØ§ØºØ© ÙØªØ±Ø© Ø§Ù„ÙŠÙˆÙ… ÙÙŠ Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„ÙˆÙ‚Øª (Ø§Ù„Ø¬ÙˆÙ„Ø© 53 - Ø§Ù„Ø£Ù…Ø± 5). */
    fun getDayPeriodStyle(): String = "explicit_nun_comma"

    fun setDayPeriodStyle(style: String) {}

    /** Ø·Ø±ÙŠÙ‚Ø© Ø¹Ø±Ø¶ Ø§Ù„Ù…ÙƒØ§Ù„Ù…Ø© Ø§Ù„ÙˆØ§Ø±Ø¯Ø© (Ø§Ù„Ø¬ÙˆÙ„Ø© 53 - Ø§Ù„Ø£Ù…Ø± 6). */
    fun getIncomingCallUiMode(): String = "auto"

    fun setIncomingCallUiMode(mode: String) {}

    /** حفظ الأوصاف التلقائية للعناصر غير المسماة (خيار بند 4). */
    fun isAutoSaveUnlabeledDescriptionsEnabled(): Boolean = false

    fun setAutoSaveUnlabeledDescriptionsEnabled(enabled: Boolean) {}

    /** ØªÙØ¹ÙŠÙ„ Ø§Ù„Ø¥ÙŠÙ‚Ø§Ù Ø§Ù„ÙÙˆØ±ÙŠ Ø¨Ø§Ù„Ù‡Ø²Ø§Ø² Ø£Ø«Ù†Ø§Ø¡ Ø§Ù„Ù†Ø·Ù‚. */
    fun isShakeToStopEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø§Ù„Ø¥ÙŠÙ‚Ø§Ù Ø¨Ø§Ù„Ù‡Ø²Ø§Ø². */
    fun setShakeToStopEnabled(enabled: Boolean)

    /** ØªÙØ¹ÙŠÙ„ Ø§Ù„Ø¥Ø³ÙƒØ§Øª Ø§Ù„ÙÙˆØ±ÙŠ Ø¨Ù…Ø³ØªØ´Ø¹Ø± Ø§Ù„ØªÙ‚Ø§Ø±Ø¨ Ø£Ø«Ù†Ø§Ø¡ Ø§Ù„Ù†Ø·Ù‚. */
    fun isProximitySilenceEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø§Ù„Ø¥Ø³ÙƒØ§Øª Ø¨Ù…Ø³ØªØ´Ø¹Ø± Ø§Ù„ØªÙ‚Ø§Ø±Ø¨. */
    fun setProximitySilenceEnabled(enabled: Boolean)

    /**
     * Ù…Ø±Ø§Ù‚Ø¨Ø©ÙŒ Ø§Ø³ØªØ¨Ø§Ù‚ÙŠØ© Ù„Ù„ØªØ·Ø¨ÙŠÙ‚Ø§Øª Ø§Ù„Ù…Ø­Ù…ÙŠØ© Ø¹Ø¨Ø± UsageStats: ØªÙØ¹Ø·Ù‘Ù„ Ø®Ø¯Ù…Ø©
     * Ø¥Ù…ÙƒØ§Ù†ÙŠØ© Ø§Ù„ÙˆØµÙˆÙ„ ÙÙˆØ± Ø¨Ø¯Ø¡ Ø§Ù„ØªØ·Ø¨ÙŠÙ‚ Ø§Ù„Ù…Ø­Ù…ÙŠ Ù‚Ø¨Ù„ Ø£Ù† ÙŠÙ‚Ø±Ø£ Ø­Ø§Ù„ØªÙ‡.
     * Ù…Ø¹Ø·Ù‘Ù„Ø© Ø§ÙØªØ±Ø§Ø¶ÙŠØ§Ù‹ Ù„Ø£Ù†Ù‡Ø§ ØªØ³ØªÙ‡Ù„Ùƒ Ø§Ù„Ø¨Ø·Ø§Ø±ÙŠØ© (Ø§Ø³ØªØ·Ù„Ø§Ø¹ Ø¯ÙˆØ±ÙŠ).
     */
    fun isProactiveProtectedWatchEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø§Ù„Ù…Ø±Ø§Ù‚Ø¨Ø© Ø§Ù„Ø§Ø³ØªØ¨Ø§Ù‚ÙŠØ© Ù„Ù„ØªØ·Ø¨ÙŠÙ‚Ø§Øª Ø§Ù„Ù…Ø­Ù…ÙŠØ©. */
    fun setProactiveProtectedWatchEnabled(enabled: Boolean)
}

/**
 * [AnnouncementPrefs] â€” Ù†Ø·Ø§Ù‚ Ø§Ù„Ø¥Ø¹Ù„Ø§Ù†Ø§Øª Ø§Ù„ØµÙˆØªÙŠØ©: Ø§Ù„Ø²Ù…Ù†ØŒ Ø§Ù„Ø¨Ø·Ø§Ø±ÙŠØ©ØŒ Ø§Ù„Ù…ØªØµÙ„ØŒ
 * Ø§Ù„Ø±Ø³Ø§Ø¦Ù„ Ø§Ù„Ù†ØµÙŠØ©ØŒ Ø§Ù„ØµÙŠØºØ©/Ø§Ù„Ø±Ù†Ø©/Ø§Ù„ÙØ§ØµÙ„/Ø§Ù„Ø£ÙˆÙ‚Ø§Øª Ø§Ù„Ù‡Ø§Ø¯Ø¦Ø© ÙˆØ§Ù„ÙˆØ¯Ø¬Øª (Ø§Ù„Ø¨Ù†Ø¯ 4).
 * ÙŠÙ†ÙÙ‘Ø°Ù‡Ø§ SettingsRepository.
 */
interface AnnouncementPrefs {

    /** ØªÙØ¹ÙŠÙ„ Ø¬Ù…Ù„Ø© Ø§Ù„Ø¥Ø¹Ù„Ø§Ù†Ø§Øª Ø§Ù„ØµÙˆØªÙŠØ© Ø¯ÙØ¹Ø©Ù‹ ÙˆØ§Ø­Ø¯Ø©. */
    fun isAllAnnouncementsEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø§Ù„ÙˆØ¶Ø¹ Ø§Ù„ÙƒÙ„ÙŠ Ù„Ù„Ø¥Ø¹Ù„Ø§Ù†Ø§Øª. */
    fun setAllAnnouncementsEnabled(enabled: Boolean)

    /** ØªÙØ¹ÙŠÙ„ Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø²Ù…Ù†. */
    fun isTimeAnnouncementEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø²Ù…Ù†. */
    fun setTimeAnnouncementEnabled(enabled: Boolean)

    /** Ø§Ù„ÙØ§ØµÙ„ Ø§Ù„Ø²Ù…Ù†ÙŠ Ù„Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø²Ù…Ù† (Ø¨Ø§Ù„Ø¯Ù‚Ø§Ø¦Ù‚): 5â€“60 Ø¨Ø®Ø·ÙˆØ© 5. */
    fun getTimeAnnouncementInterval(): Int

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø§Ù„ÙØ§ØµÙ„ Ø§Ù„Ø²Ù…Ù†ÙŠ Ù„Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø²Ù…Ù†. */
    fun setTimeAnnouncementInterval(interval: Int)

    /** Ø¨Ø¯Ø§ÙŠØ© ÙˆÙ‚Øª Ø§Ù„Ù‡Ø¯ÙˆØ¡ Ù„ÙŠÙˆÙ… (0..23)ØŒ -1 = Ø¨Ù„Ø§. */
    fun getQuietStartForDay(day: Int): Int

    /** Ù†Ù‡Ø§ÙŠØ© ÙˆÙ‚Øª Ø§Ù„Ù‡Ø¯ÙˆØ¡ Ù„ÙŠÙˆÙ… (0..23)ØŒ -1 = Ø¨Ù„Ø§. */
    fun getQuietEndForDay(day: Int): Int

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø¨Ø¯Ø§ÙŠØ© ÙˆÙ‚Øª Ø§Ù„Ù‡Ø¯ÙˆØ¡ Ù„ÙŠÙˆÙ…. */
    fun setQuietStartForDay(day: Int, hour: Int)

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù†Ù‡Ø§ÙŠØ© ÙˆÙ‚Øª Ø§Ù„Ù‡Ø¯ÙˆØ¡ Ù„ÙŠÙˆÙ…. */
    fun setQuietEndForDay(day: Int, hour: Int)

    /** ØªÙØ¹ÙŠÙ„ ÙŠÙˆÙ…Ù Ø¨Ø¹ÙŠÙ†Ù‡ Ø¶Ù…Ù† Ø§Ù„Ø£ÙˆÙ‚Ø§Øª Ø§Ù„Ù‡Ø§Ø¯Ø¦Ø©. */
    fun isDayQuietEnabled(day: Int): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ ÙŠÙˆÙ…Ù Ø¨Ø¹ÙŠÙ†Ù‡ Ø¶Ù…Ù† Ø§Ù„Ø£ÙˆÙ‚Ø§Øª Ø§Ù„Ù‡Ø§Ø¯Ø¦Ø©. */
    fun setDayQuietEnabled(day: Int, enabled: Boolean)

    /**
     * Ù‚Ù†Ø§Ø¹ Ø¨ØªØ§Øª Ø³Ø§Ø¹Ø§Øª Ø§Ù„Ù‡Ø¯ÙˆØ¡ Ù„Ù„ÙŠÙˆÙ…
     * (24 Ø¨Øª: Ø¨Øª 0 = Ø³Ø§Ø¹Ø© 1 .. Ø¨Øª 23 = Ø³Ø§Ø¹Ø© 24).
     */
    fun getQuietHoursBitmaskForDay(day: Int): Int = 0

    /** ØªØ¹ÙŠÙŠÙ† Ù‚Ù†Ø§Ø¹ Ø¨ØªØ§Øª Ø³Ø§Ø¹Ø§Øª Ø§Ù„Ù‡Ø¯ÙˆØ¡ Ù„Ù„ÙŠÙˆÙ…. */
    fun setQuietHoursBitmaskForDay(day: Int, mask: Int) {}

    /** Ù‡Ù„ Ø§Ù„Ø³Ø§Ø¹Ø© Ø§Ù„Ù…Ø­Ø¯Ø¯Ø© (1..24) ØµØ§Ù…ØªØ© ÙÙŠ Ø§Ù„ÙŠÙˆÙ… Ø§Ù„Ù…Ø¹Ù†ÙŠØŸ */
    fun isHourBitSet(day: Int, hour1To24: Int): Boolean = false

    /** ØªØ¹ÙŠÙŠÙ† ØµÙ…Øª/Ù†Ø·Ù‚ Ø³Ø§Ø¹Ø© Ù…Ø­Ø¯Ø¯Ø© (1..24) ÙÙŠ Ø§Ù„ÙŠÙˆÙ… Ø§Ù„Ù…Ø¹Ù†ÙŠ. */
    fun setHourBit(day: Int, hour1To24: Int, isSilent: Boolean) {}

    /**
     * ÙØ­Øµ Ù‡Ù„ Ø§Ù„Ø³Ø§Ø¹Ø© Ø§Ù„Ù…Ø­Ø¯Ø¯Ø© ØµØ§Ù…ØªØ© Ø¨Ø§Ù„ÙƒØ§Ù…Ù„
     * Ø§Ø­ØªØ±Ø§Ù…Ø§Ù‹ Ù„Ù„ÙŠÙˆÙ… ÙˆØ§Ù„Ø£Ù…Ø³ Ø¹Ù†Ø¯ Ù…Ù†ØªØµÙ Ø§Ù„Ù„ÙŠÙ„.
     */
    fun isHourQuiet(dayOfWeek: Int, calendarHour: Int): Boolean = false

    /** ØµÙŠØºØ© Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø²Ù…Ù† (Ù…Ø«Ù„ "h" Ø£Ùˆ "hm"). */
    fun getTimeAnnouncementFormat(): String

    /** ÙŠØ¹ÙŠÙ‘Ù† ØµÙŠØºØ© Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø²Ù…Ù†. */
    fun setTimeAnnouncementFormat(format: String)

    /** ØªÙØ¹ÙŠÙ„ Ø¹Ø±Ø¶ Ø§Ù„Ø³Ø§Ø¹Ø© Ø¨Ù†Ø¸Ø§Ù… 24. */
    fun isTime24Hour(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù†Ø¸Ø§Ù… Ø§Ù„Ø³Ø§Ø¹Ø© 24. */
    fun setTime24Hour(enabled: Boolean)

    /** ØªÙØ¹ÙŠÙ„ Ø±Ù†Ø© Ø§Ù„Ø²Ù…Ù†. */
    fun isTimeChimeEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø±Ù†Ø© Ø§Ù„Ø²Ù…Ù†. */
    fun setTimeChimeEnabled(enabled: Boolean)

    /** ØªÙØ¹ÙŠÙ„ Ø±Ù†Ø© Ø±Ø£Ø³ Ø§Ù„Ø³Ø§Ø¹Ø© (:00). */
    fun isTimeChimeAt0Enabled(): Boolean
    fun setTimeChimeAt0Enabled(enabled: Boolean)

    /** ØªÙØ¹ÙŠÙ„ Ø±Ù†Ø© Ø§Ù„Ø±Ø¨Ø¹ (:15). */
    fun isTimeChimeAt15Enabled(): Boolean
    fun setTimeChimeAt15Enabled(enabled: Boolean)

    /** ØªÙØ¹ÙŠÙ„ Ø±Ù†Ø© Ø§Ù„Ù†ØµÙ (:30). */
    fun isTimeChimeAt30Enabled(): Boolean
    fun setTimeChimeAt30Enabled(enabled: Boolean)

    /** ØªÙØ¹ÙŠÙ„ Ø±Ù†Ø© Ø§Ù„Ù€ 45 Ø¯Ù‚ÙŠÙ‚Ø© (:45). */
    fun isTimeChimeAt45Enabled(): Boolean
    fun setTimeChimeAt45Enabled(enabled: Boolean)

    /** ØµÙˆØª Ø±Ù†Ø© Ø§Ù„Ø²Ù…Ù† (Ø§Ø³Ù… Ø£ØµÙ„ Ø§Ù„ØµÙˆØª). */
    fun getTimeChimeSound(): String

    /** ÙŠØ¹ÙŠÙ‘Ù† ØµÙˆØª Ø±Ù†Ø© Ø§Ù„Ø²Ù…Ù†. */
    fun setTimeChimeSound(sound: String)

    /** Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ø§Ù„Ø±Ù†Ø© (0..1). */
    fun getTimeChimeVolume(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ø§Ù„Ø±Ù†Ø©. */
    fun setTimeChimeVolume(volume: Float)

    /**
     * Ù‡Ù„ ØªØªØ¨Ø¹ Ø±Ù†Ù‘Ø©Ù Ø§Ù„Ø³Ø§Ø¹Ø©ÙŽ ØµÙˆØªÙŽ Ù†Ø·Ù‚Ù‡Ø§ØŸ Ù…ÙØ¹Ù‘Ù„Ø©ÙŒ Ø§ÙØªØ±Ø§Ø¶ÙŠØ§Ù‹ â€” ÙØ§Ù„Ù…Ø³ØªÙˆÙ‰
     * Ø§Ù„ÙŠØ¯ÙˆÙŠÙ‘ ØªØ¬Ø§ÙˆØ²ÙŒ Ù„Ù…Ù† Ø£Ø±Ø§Ø¯ ÙØµÙ„ÙŽÙ‡Ù…Ø§ØŒ Ù„Ø§ Ø§Ù„Ù‚ÙŠÙ…Ø©Ù Ø§Ù„ØªÙŠ ÙŠØ³ÙŠØ± Ø¹Ù„ÙŠÙ‡Ø§
     * Ø§Ù„Ø¬Ù…ÙŠØ¹.
     */
    fun isTimeChimeVolumeFollowsAnnouncement(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªØªØ¨Ù‘Ø¹ Ø§Ù„Ø±Ù†Ù‘Ø©Ù Ù„Ù†Ø·Ù‚ Ø§Ù„Ø³Ø§Ø¹Ø©. */
    fun setTimeChimeVolumeFollowsAnnouncement(follows: Boolean)

    /**
     * Ù…Ø³ØªÙˆÙ‰ Ø±Ù†Ù‘Ø© Ø§Ù„Ø³Ø§Ø¹Ø© **Ø§Ù„ÙØ¹Ù‘Ø§Ù„** â€” ÙˆÙ‡Ùˆ Ù…Ø§ ÙŠÙØ¨Ù†Ù‰ Ø¹Ù„ÙŠÙ‡ [AudioCue]
     * Ø¹Ù†Ø¯ Ø§Ù„Ù†Ø·Ù‚ØŒ Ù„Ø§ Ø§Ù„Ù‚ÙŠÙ…Ø© Ø§Ù„ÙŠØ¯ÙˆÙŠØ© Ø§Ù„Ù…Ø­ÙÙˆØ¸Ø©: Ø§Ù„ØªØªØ¨ÙÙ‘Ø¹Ù ÙŠÙØ¬ÙŠØ¨ Ø¨Ø­Ø¬Ù…
     * Ø§Ù„Ù†Ø·Ù‚ Ù†ÙØ³Ù‡ØŒ ÙˆØ¥Ù„Ø§ ÙØ¨Ø§Ù„Ø­Ø¬Ù… Ø§Ù„ÙŠØ¯ÙˆÙŠÙ‘.
     */
    fun getEffectiveTimeChimeVolume(): Float

    /** Ù…Ø³Ø§Ø± URI Ù„Ù…Ù„Ù Ø±Ù†Ø© Ø§Ù„Ø³Ø§Ø¹Ø© Ø§Ù„Ù…Ø®ØµØµ (Ø£Ùˆ ÙØ§Ø±Øº Ø¥Ù† Ù„Ù… ÙŠÙØ­Ø¯Ø¯). */
    fun getCustomChimeUri(): String

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³Ø§Ø± URI Ù„Ù…Ù„Ù Ø±Ù†Ø© Ø§Ù„Ø³Ø§Ø¹Ø© Ø§Ù„Ù…Ø®ØµØµ. */
    fun setCustomChimeUri(uri: String)

    /** Ù…Ø³Ø§Ø± URI Ù„Ù„Ù…Ù„Ù Ø§Ù„Ù…Ø®ØµØµ Ù„Ø±Ù†Ø© Ø§Ù„Ø±Ø¨Ø¹ (:15) â€” ÙØ§Ø±Øº Ø¥Ù† Ù„Ù… ÙŠÙØ­Ø¯Ø¯. */
    fun getCustomChimeUri15(): String

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³Ø§Ø± URI Ù„Ù„Ù…Ù„Ù Ø§Ù„Ù…Ø®ØµØµ Ù„Ø±Ù†Ø© Ø§Ù„Ø±Ø¨Ø¹ (:15). */
    fun setCustomChimeUri15(uri: String)

    /** Ù…Ø³Ø§Ø± URI Ù„Ù„Ù…Ù„Ù Ø§Ù„Ù…Ø®ØµØµ Ù„Ø±Ù†Ø© Ø§Ù„Ù†ØµÙ (:30) â€” ÙØ§Ø±Øº Ø¥Ù† Ù„Ù… ÙŠÙØ­Ø¯Ø¯. */
    fun getCustomChimeUri30(): String

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³Ø§Ø± URI Ù„Ù„Ù…Ù„Ù Ø§Ù„Ù…Ø®ØµØµ Ù„Ø±Ù†Ø© Ø§Ù„Ù†ØµÙ (:30). */
    fun setCustomChimeUri30(uri: String)

    /** Ù…Ø³Ø§Ø± URI Ù„Ù„Ù…Ù„Ù Ø§Ù„Ù…Ø®ØµØµ Ù„Ø±Ù†Ø© Ø§Ù„Ù€ 45 Ø¯Ù‚ÙŠÙ‚Ø© (:45) â€” ÙØ§Ø±Øº Ø¥Ù† Ù„Ù… ÙŠÙØ­Ø¯Ø¯. */
    fun getCustomChimeUri45(): String

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³Ø§Ø± URI Ù„Ù„Ù…Ù„Ù Ø§Ù„Ù…Ø®ØµØµ Ù„Ø±Ù†Ø© Ø§Ù„Ù€ 45 Ø¯Ù‚ÙŠÙ‚Ø© (:45). */
    fun setCustomChimeUri45(uri: String)

    /** ØªÙØ¹ÙŠÙ„ Ø§Ù„Ø¯Ù‚Ø© Ø§Ù„Ù‚ØµÙˆÙ‰ Ù„Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„ÙˆÙ‚Øª (setAlarmClock Ø¨Ø¯Ù„
     *  setExactAndAllowWhileIdle). */
    fun isTimeAlarmMaxPrecisionEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø§Ù„Ø¯Ù‚Ø© Ø§Ù„Ù‚ØµÙˆÙ‰ Ù„Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„ÙˆÙ‚Øª. */
    fun setTimeAlarmMaxPrecisionEnabled(enabled: Boolean)

    /** ØªÙØ¹ÙŠÙ„ Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø¨Ø·Ø§Ø±ÙŠØ©. */
    fun isBatteryAnnouncementEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø¨Ø·Ø§Ø±ÙŠØ©. */
    fun setBatteryAnnouncementEnabled(enabled: Boolean)

    /** Ù…Ø³ØªÙˆÙŠØ§Øª Ø§Ù„Ø¨Ø·Ø§Ø±ÙŠØ© Ø§Ù„ØªÙŠ ØªÙØ¹Ù„Ù† Ø¹Ù†Ø¯ Ø¨Ù„ÙˆØºÙ‡Ø§. */
    fun getBatteryAnnouncementLevels(): Set<String>

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³ØªÙˆÙŠØ§Øª Ø§Ù„Ø¨Ø·Ø§Ø±ÙŠØ© Ø§Ù„Ù…ÙØ¹Ù„Ù†Ø©. */
    fun setBatteryAnnouncementLevels(levels: Set<String>)

    /** Ø§Ù„ØµÙˆØª Ø§Ù„Ù…ÙØ¶Ù‘Ù„ Ù„Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø¨Ø·Ø§Ø±ÙŠØ©. */
    fun getBatteryAnnouncementVoiceId(): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† ØµÙˆØª Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø¨Ø·Ø§Ø±ÙŠØ©. */
    fun setBatteryAnnouncementVoiceId(voiceId: String?)

    /** Ù…Ø¹Ø¯Ù„ ÙƒÙ„Ø§Ù… Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø¨Ø·Ø§Ø±ÙŠØ©. */
    fun getBatteryAnnouncementRate(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø¹Ø¯Ù„ ÙƒÙ„Ø§Ù… Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø¨Ø·Ø§Ø±ÙŠØ©. */
    fun setBatteryAnnouncementRate(rate: Float)

    /** Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø¨Ø·Ø§Ø±ÙŠØ©. */
    fun getBatteryAnnouncementVolume(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø¨Ø·Ø§Ø±ÙŠØ©. */
    fun setBatteryAnnouncementVolume(volume: Float)

    /** Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ø§Ù„ØªÙ„Ù…ÙŠØ­ Ø§Ù„ØµÙˆØªÙŠ Ù„Ù„Ø¨Ø·Ø§Ø±ÙŠØ©. */
    fun getBatteryCueVolume(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª ØªÙ„Ù…ÙŠØ­ Ø§Ù„Ø¨Ø·Ø§Ø±ÙŠØ©. */
    fun setBatteryCueVolume(volume: Float)

    /** ÙˆØ¶Ø¹ Ø§Ù„ØªÙ„Ù…ÙŠØ­ Ø§Ù„ØµÙˆØªÙŠ Ù„Ù„Ø¨Ø·Ø§Ø±ÙŠØ© (0..2). */
    fun getBatterySoundCueMode(): Int

    /** ÙŠØ¹ÙŠÙ‘Ù† ÙˆØ¶Ø¹ Ø§Ù„ØªÙ„Ù…ÙŠØ­ Ø§Ù„ØµÙˆØªÙŠ Ù„Ù„Ø¨Ø·Ø§Ø±ÙŠØ©. */
    fun setBatterySoundCueMode(mode: Int)

    /** ØªÙØ¹ÙŠÙ„ Ø¥Ø¹Ù„Ø§Ù† Ø§ÙƒØªÙ…Ø§Ù„ Ø§Ù„Ø´Ø­Ù†. */
    fun isChargingCompleteAnnouncementEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø¥Ø¹Ù„Ø§Ù† Ø§ÙƒØªÙ…Ø§Ù„ Ø§Ù„Ø´Ø­Ù†. */
    fun setChargingCompleteAnnouncementEnabled(enabled: Boolean)

    /** ØªÙØ¹ÙŠÙ„ Ø¥Ø¹Ù„Ø§Ù† ÙØµÙ„ Ø§Ù„Ø´Ø§Ø­Ù†. */
    fun isChargingDisconnectAnnouncementEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø¥Ø¹Ù„Ø§Ù† ÙØµÙ„ Ø§Ù„Ø´Ø§Ø­Ù†. */
    fun setChargingDisconnectAnnouncementEnabled(enabled: Boolean)

    /** ØªÙØ¹ÙŠÙ„ Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„. */
    fun isCallerAnnouncementEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„. */
    fun setCallerAnnouncementEnabled(enabled: Boolean)

    fun isCallerLockScreenPrivacyEnabled(): Boolean

    fun setCallerLockScreenPrivacyEnabled(enabled: Boolean)

    /** Ù†Ø·Ù‚ Ø§Ø³Ù… Ø§Ù„Ù…ØªØµÙ„ Ø§Ù„Ø«Ø§Ù†ÙŠ Ø§Ù„ÙˆØ§Ø±Ø¯ Ø£Ø«Ù†Ø§Ø¡ Ù…ÙƒØ§Ù„Ù…Ø© Ù†Ø´Ø·Ø© (Ù…ÙƒØ§Ù„Ù…Ø© Ø§Ù†ØªØ¸Ø§Ø±). */
    fun isCallerAnnouncementDuringCallEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù†Ø·Ù‚ Ø§Ø³Ù… Ø§Ù„Ù…ØªØµÙ„ Ø§Ù„Ø«Ø§Ù†ÙŠ Ø§Ù„ÙˆØ§Ø±Ø¯ Ø£Ø«Ù†Ø§Ø¡ Ù…ÙƒØ§Ù„Ù…Ø© Ù†Ø´Ø·Ø©. */
    fun setCallerAnnouncementDuringCallEnabled(enabled: Boolean)

    /** Ù‡Ù„ ØªÙØ®ÙØ¶ Ù†ØºÙ…Ø© Ø§Ù„Ø±Ù†ÙŠÙ† Ø£Ø«Ù†Ø§Ø¡ Ù†Ø·Ù‚ Ø§Ø³Ù… Ø§Ù„Ù…ØªØµÙ„ØŸ */
    fun isCallerRingDuckingEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø®ÙØ¶ Ù†ØºÙ…Ø© Ø§Ù„Ø±Ù†ÙŠÙ† Ø£Ø«Ù†Ø§Ø¡ Ù†Ø·Ù‚ Ø§Ø³Ù… Ø§Ù„Ù…ØªØµÙ„. */
    fun setCallerRingDuckingEnabled(enabled: Boolean)

    /** Ù†Ø³Ø¨Ø© Ø®ÙØ¶ Ø§Ù„Ø±Ù†ÙŠÙ† (10..90 Ø¨Ø§Ù„Ù…Ø¦Ø© Ù…Ù† Ù…Ø³ØªÙˆÙ‰ Ø§Ù„Ø±Ù†ÙŠÙ† Ø§Ù„Ø£ØµÙ„ÙŠ). */
    fun getCallerRingDuckPercent(): Int

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù†Ø³Ø¨Ø© Ø®ÙØ¶ Ø§Ù„Ø±Ù†ÙŠÙ†. */
    fun setCallerRingDuckPercent(percent: Int)

    /** Ø¹Ø¯Ø¯ Ù…Ø±Ø§Øª ØªÙƒØ±Ø§Ø± Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„. */
    fun getCallerAnnouncementRepeat(): Int

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø¹Ø¯Ø¯ Ù…Ø±Ø§Øª ØªÙƒØ±Ø§Ø± Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„. */
    fun setCallerAnnouncementRepeat(repeat: Int)

    /** Ø¹Ø¯Ø¯ Ù…Ø±Ø§Øª ØªÙƒØ±Ø§Ø± Ø¥Ø¹Ù„Ø§Ù† Ù…ÙƒØ§Ù„Ù…Ø© Ø§Ù„Ø§Ù†ØªØ¸Ø§Ø±. */
    fun getCallWaitingRepeat(): Int

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø¹Ø¯Ø¯ Ù…Ø±Ø§Øª ØªÙƒØ±Ø§Ø± Ø¥Ø¹Ù„Ø§Ù† Ù…ÙƒØ§Ù„Ù…Ø© Ø§Ù„Ø§Ù†ØªØ¸Ø§Ø±. */
    fun setCallWaitingRepeat(repeat: Int)

    /** Ø§Ù„ÙØ§ØµÙ„ Ø§Ù„Ø²Ù…Ù†ÙŠ Ø¨ÙŠÙ† ØªÙƒØ±Ø§Ø±Ø§Øª Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„ (Ø¨Ø§Ù„Ø«ÙˆØ§Ù†ÙŠ). */
    fun getCallerAnnouncementIntervalSeconds(): Int

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø§Ù„ÙØ§ØµÙ„ Ø¨ÙŠÙ† ØªÙƒØ±Ø§Ø±Ø§Øª Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„. */
    fun setCallerAnnouncementIntervalSeconds(seconds: Int)

    /** ØµÙˆØª Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„ Ù„Ù„ØºØ© Ø§Ù„Ø¹Ø±Ø¨ÙŠØ©. */
    fun getCallerAnnouncementArabicVoiceId(): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† ØµÙˆØª Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„ Ù„Ù„ØºØ© Ø§Ù„Ø¹Ø±Ø¨ÙŠØ©. */
    fun setCallerAnnouncementArabicVoiceId(voiceId: String?)

    /** ØµÙˆØª Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„ Ù„Ù„ØºØ© Ø§Ù„Ø¥Ù†Ø¬Ù„ÙŠØ²ÙŠØ©. */
    fun getCallerAnnouncementEnglishVoiceId(): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† ØµÙˆØª Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„ Ù„Ù„ØºØ© Ø§Ù„Ø¥Ù†Ø¬Ù„ÙŠØ²ÙŠØ©. */
    fun setCallerAnnouncementEnglishVoiceId(voiceId: String?)

    /** Ù…Ø¹Ø¯Ù„ ÙƒÙ„Ø§Ù… Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„. */
    fun getCallerAnnouncementRate(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø¹Ø¯Ù„ ÙƒÙ„Ø§Ù… Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„. */
    fun setCallerAnnouncementRate(rate: Float)

    /** Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„. */
    fun getCallerAnnouncementVolume(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ù…ØªØµÙ„. */
    fun setCallerAnnouncementVolume(volume: Float)

    /** Ù‡Ù„ ØªÙÙ†Ø·Ù‚ Ø¬Ù…Ù„Ø©ÙŒ **Ù‚Ø¨Ù„** Ø§Ø³Ù… Ø§Ù„Ù…ØªØµÙ„/Ø±Ù‚Ù…Ù‡ØŸ */
    fun isCallerPrefixEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø¬Ù…Ù„Ø© Ù…Ø§ Ù‚Ø¨Ù„ Ø§Ø³Ù… Ø§Ù„Ù…ØªØµÙ„. */
    fun setCallerPrefixEnabled(enabled: Boolean)

    /** Ù†Øµ Ø§Ù„Ø¬Ù…Ù„Ø© Ø§Ù„ØªÙŠ ØªÙÙ†Ø·Ù‚ Ù‚Ø¨Ù„ Ø§Ø³Ù… Ø§Ù„Ù…ØªØµÙ„/Ø±Ù‚Ù…Ù‡ (Ø§Ù„Ù…Ø³ØªØ®Ø¯Ù… ÙŠÙƒØªØ¨Ù‡). */
    fun getCallerPrefixText(): String

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù†Øµ Ø§Ù„Ø¬Ù…Ù„Ø© Ø§Ù„ØªÙŠ ØªÙÙ†Ø·Ù‚ Ù‚Ø¨Ù„ Ø§Ø³Ù… Ø§Ù„Ù…ØªØµÙ„/Ø±Ù‚Ù…Ù‡. */
    fun setCallerPrefixText(text: String?)

    /** Ù‡Ù„ ØªÙÙ†Ø·Ù‚ Ø¬Ù…Ù„Ø©ÙŒ **Ø¨Ø¹Ø¯** Ø§Ø³Ù… Ø§Ù„Ù…ØªØµÙ„/Ø±Ù‚Ù…Ù‡ØŸ */
    fun isCallerSuffixEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø¬Ù…Ù„Ø© Ù…Ø§ Ø¨Ø¹Ø¯ Ø§Ø³Ù… Ø§Ù„Ù…ØªØµÙ„. */
    fun setCallerSuffixEnabled(enabled: Boolean)

    /** Ù†Øµ Ø§Ù„Ø¬Ù…Ù„Ø© Ø§Ù„ØªÙŠ ØªÙÙ†Ø·Ù‚ Ø¨Ø¹Ø¯ Ø§Ø³Ù… Ø§Ù„Ù…ØªØµÙ„/Ø±Ù‚Ù…Ù‡ (Ø§Ù„Ù…Ø³ØªØ®Ø¯Ù… ÙŠÙƒØªØ¨Ù‡). */
    fun getCallerSuffixText(): String

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù†Øµ Ø§Ù„Ø¬Ù…Ù„Ø© Ø§Ù„ØªÙŠ ØªÙÙ†Ø·Ù‚ Ø¨Ø¹Ø¯ Ø§Ø³Ù… Ø§Ù„Ù…ØªØµÙ„/Ø±Ù‚Ù…Ù‡. */
    fun setCallerSuffixText(text: String?)

    /** Ù‚Ø§Ù„Ø¨ Ù†Øµ Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø±Ø³Ø§Ø¦Ù„ (Ù…Ø¹ Ø³Ù„Ø³Ù„Ø© Ø§Ù„Ø§Ø³ØªØ¨Ø¯Ø§Ù„). */
    fun getSmsAnnouncementTemplate(): String

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù‚Ø§Ù„Ø¨ Ù†Øµ Ø¥Ø¹Ù„Ø§Ù† Ø§Ù„Ø±Ø³Ø§Ø¦Ù„. */
    fun setSmsAnnouncementTemplate(template: String?)

    /** ÙˆØ¶Ø¹ Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø±Ø³Ø§Ø¦Ù„ Ø§Ù„Ù†ØµÙŠØ© (Ù…Ø«Ù„ "caller" Ø£Ùˆ "full"). */
    fun getSmsReadingMode(): String

    /** ÙŠØ¹ÙŠÙ‘Ù† ÙˆØ¶Ø¹ Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø±Ø³Ø§Ø¦Ù„ Ø§Ù„Ù†ØµÙŠØ©. */
    fun setSmsReadingMode(mode: String)

    /** ØµÙˆØª Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø±Ø³Ø§Ø¦Ù„ Ø§Ù„Ù†ØµÙŠØ©. */
    fun getSmsReadingVoiceId(): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† ØµÙˆØª Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø±Ø³Ø§Ø¦Ù„ Ø§Ù„Ù†ØµÙŠØ©. */
    fun setSmsReadingVoiceId(voiceId: String?)

    /** Ù…Ø¹Ø¯Ù„ ÙƒÙ„Ø§Ù… Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø±Ø³Ø§Ø¦Ù„ Ø§Ù„Ù†ØµÙŠØ©. */
    fun getSmsReadingRate(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø¹Ø¯Ù„ ÙƒÙ„Ø§Ù… Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø±Ø³Ø§Ø¦Ù„ Ø§Ù„Ù†ØµÙŠØ©. */
    fun setSmsReadingRate(rate: Float)

    /** Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø±Ø³Ø§Ø¦Ù„ Ø§Ù„Ù†ØµÙŠØ©. */
    fun getSmsReadingVolume(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø±Ø³Ø§Ø¦Ù„ Ø§Ù„Ù†ØµÙŠØ©. */
    fun setSmsReadingVolume(volume: Float)
}

/**
 * [DevicePrefs] â€” Ù†Ø·Ø§Ù‚ Ø§Ù„Ø¬Ù‡Ø§Ø² ÙˆØ§Ù„Ø®ØµÙˆØµÙŠØ© ÙˆØ§Ù„Ø·Ø§Ù‚Ø©: Ù‚ÙÙ„ Ø§Ù„Ø´Ø§Ø´Ø©ØŒ ØªÙˆÙÙŠØ± Ø§Ù„Ø·Ø§Ù‚Ø©ØŒ
 * ØµØ­Ø© Ø§Ù„Ø¬Ù‡Ø§Ø²ØŒ ØªØ·Ø¨ÙŠÙ‚Ø§Øª Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª Ø§Ù„Ù…Ù‚Ø±ÙˆØ¡Ø©ØŒ ÙˆØ§Ù„Ø£ÙˆÙ„ Ù…Ø±Ø© (Ø§Ù„Ø¨Ù†Ø¯ 4).
 * ÙŠÙ†ÙÙ‘Ø°Ù‡Ø§ SettingsRepository.
 */
interface DevicePrefs {

    /** ØªÙ‚ÙŠÙŠØ¯ Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø¥Ø¹Ù„Ø§Ù†Ø§Øª Ø¹Ù†Ø¯ Ù‚ÙÙ„ Ø§Ù„Ø´Ø§Ø´Ø©. */
    fun isLockScreenPrivacyEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙ‚ÙŠÙŠØ¯ Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø¥Ø¹Ù„Ø§Ù†Ø§Øª Ø¹Ù†Ø¯ Ù‚ÙÙ„ Ø§Ù„Ø´Ø§Ø´Ø©. */
    fun setLockScreenPrivacyEnabled(enabled: Boolean)

    /** Ù‡Ù„ Ø´Ø§Ø´Ø© Ø§Ù„Ø¬Ù‡Ø§Ø² Ù…Ù‚ÙÙ„Ø© ÙØ¹Ù„ÙŠØ§Ù‹ Ø§Ù„Ø¢Ù†ØŸ */
    fun isDeviceScreenLocked(): Boolean

    /** ØªÙØ¹ÙŠÙ„ ÙˆØ¶Ø¹ ØªÙˆÙÙŠØ± Ø§Ù„Ø·Ø§Ù‚Ø© (Ø®ÙØ¶ Ø§Ù„Ø¥Ø¹Ù„Ø§Ù†Ø§Øª). */
    fun isPowerSaverModeEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ ÙˆØ¶Ø¹ ØªÙˆÙÙŠØ± Ø§Ù„Ø·Ø§Ù‚Ø©. */
    fun setPowerSaverModeEnabled(enabled: Boolean)

    /** Ø­Ø¯ Ø¨Ø·Ø§Ø±ÙŠØ© ØªÙØ¹ÙŠÙ„ ØªÙˆÙÙŠØ± Ø§Ù„Ø·Ø§Ù‚Ø© (Ø¨Ø§Ù„Ù†Ø³Ø¨Ø©). */
    fun getPowerSaverBatteryThreshold(): Int

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø­Ø¯ Ø¨Ø·Ø§Ø±ÙŠØ© ØªÙˆÙÙŠØ± Ø§Ù„Ø·Ø§Ù‚Ø©. */
    fun setPowerSaverBatteryThreshold(threshold: Int)

    /** Ø¨Ù†ÙˆØ¯ ØµØ­Ø© Ø§Ù„Ø¬Ù‡Ø§Ø² Ø§Ù„Ù…ÙÙ†Ø§Ø¯Ù‰ Ø¨Ù‡Ø§. */
    fun getDeviceHealthItems(): Set<String>

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø¨Ù†ÙˆØ¯ ØµØ­Ø© Ø§Ù„Ø¬Ù‡Ø§Ø². */
    fun setDeviceHealthItems(items: Set<String>)

    /** ØªÙØ¹ÙŠÙ„ Ù‚Ø±Ø§Ø¡Ø© Ø¥Ø´Ø¹Ø§Ø±Ø§Øª Ø§Ù„ØªØ·Ø¨ÙŠÙ‚Ø§Øª Ø§Ù„Ù…Ø®ØªØ§Ø±Ø©. */
    fun isNotificationReadingEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª. */
    fun setNotificationReadingEnabled(enabled: Boolean)

    /** ÙˆØ¶Ø¹ Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª: "full"=Ù…ÙØ¹Ù‘Ù„ØŒ "off"=Ù…Ø¹Ø·Ù‘Ù„ØŒ "source"=Ù…ØµØ¯Ø± ÙÙ‚Ø·. */
    fun getNotificationReadingMode(): String

    /** ÙŠØ¹ÙŠÙ‘Ù† ÙˆØ¶Ø¹ Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª. */
    fun setNotificationReadingMode(mode: String)

    /** Ø­Ø²Ù… Ø§Ù„ØªØ·Ø¨ÙŠÙ‚Ø§Øª Ø§Ù„Ù…Ø®ØªØ§Ø±Ø© Ù„Ù‚Ø±Ø§Ø¡Ø© Ø¥Ø´Ø¹Ø§Ø±Ø§ØªÙ‡Ø§. */
    fun getNotificationAppsSelection(): Set<String>

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø­Ø²Ù… Ø§Ù„ØªØ·Ø¨ÙŠÙ‚Ø§Øª Ø§Ù„Ù…Ø®ØªØ§Ø±Ø© Ù„Ù‚Ø±Ø§Ø¡Ø© Ø¥Ø´Ø¹Ø§Ø±Ø§ØªÙ‡Ø§. */
    fun setNotificationAppsSelection(pkgs: Set<String>)

    /** Ù‡Ù„ ÙŠØ¬Ø¨ Ù‚Ø±Ø§Ø¡Ø© Ø¥Ø´Ø¹Ø§Ø± ØªØ·Ø¨ÙŠÙ‚Ù Ø¨Ø¹ÙŠÙ†Ù‡ØŸ */
    fun shouldReadNotificationApp(pkg: String): Boolean

    /** Ù‡Ù„ Ø§ÙƒØªÙ…Ù„ Ø¥Ø¹Ø¯Ø§Ø¯ Ø§Ù„ØªØ´ØºÙŠÙ„ Ø§Ù„Ø£ÙˆÙ„ØŸ */
    fun isFirstRunSetupCompleted(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† Ø§ÙƒØªÙ…Ø§Ù„ ØªØ´ØºÙŠÙ„ Ø§Ù„Ø¥Ø¹Ø¯Ø§Ø¯ Ø§Ù„Ø£ÙˆÙ„. */
    fun setFirstRunSetupCompleted(completed: Boolean)
}

/**
 * [ConvertPrefs] â€” Ù†Ø·Ø§Ù‚ Ø§Ù„ØªØ­ÙˆÙŠÙ„ Ø§Ù„ØªÙ„Ù‚Ø§Ø¦ÙŠ: ØªÙØ¹ÙŠÙ„Ù‡ ÙˆØ³Ù„ÙˆØªØ§ Ø§Ù„Ù„ØºØ©/Ø§Ù„Ù…Ø­Ø±Ùƒ
 * ÙˆØµÙŠØ­Ø§ØªÙ‡Ù…Ø§ (Ø§Ù„Ø¨Ù†Ø¯ 4). ÙŠÙ†ÙÙ‘Ø°Ù‡Ø§ SettingsRepository.
 */
interface ConvertPrefs {

    /** ØªÙØ¹ÙŠÙ„ Ø§Ù„ØªØ­ÙˆÙŠÙ„ Ø§Ù„ØªÙ„Ù‚Ø§Ø¦ÙŠ Ø¥Ù„Ù‰ Ù„ØºØ§Øª Ø£Ø®Ø±Ù‰ Ø£Ø«Ù†Ø§Ø¡ Ø§Ù„Ù†Ø·Ù‚. */
    fun isAutoConvertEnabled(): Boolean

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø§Ù„ØªØ­ÙˆÙŠÙ„ Ø§Ù„ØªÙ„Ù‚Ø§Ø¦ÙŠ. */
    fun setAutoConvertEnabled(enabled: Boolean)

    /** Ù…Ø­Ø±Ùƒ Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø£ÙˆÙ„ Ù„Ù„ØªØ­ÙˆÙŠÙ„ Ø§Ù„ØªÙ„Ù‚Ø§Ø¦ÙŠ. */
    fun getConvertEngine1(): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø­Ø±Ùƒ Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø£ÙˆÙ„. */
    fun setConvertEngine1(pkg: String?)

    /** Ù„ØºØ© Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø£ÙˆÙ„. */
    fun getConvertLanguageTag1(): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù„ØºØ© Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø£ÙˆÙ„. */
    fun setConvertLanguageTag1(langTag: String?)

    /** ØµÙˆØª Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø£ÙˆÙ„. */
    fun getConvertVoice1(): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† ØµÙˆØª Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø£ÙˆÙ„. */
    fun setConvertVoice1(voiceId: String?)

    /** Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø£ÙˆÙ„. */
    fun getConvertVolume1(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø£ÙˆÙ„. */
    fun setConvertVolume1(volume: Float)

    /** Ù†Ø¨Ø±Ø© Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø£ÙˆÙ„. */
    fun getConvertPitch1(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù†Ø¨Ø±Ø© Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø£ÙˆÙ„. */
    fun setConvertPitch1(pitch: Float)

    /** Ù…Ø¹Ø¯Ù„ Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø£ÙˆÙ„. */
    fun getConvertRate1(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø¹Ø¯Ù„ Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø£ÙˆÙ„. */
    fun setConvertRate1(rate: Float)

    /** Ù…Ø­Ø±Ùƒ Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø«Ø§Ù†ÙŠ Ù„Ù„ØªØ­ÙˆÙŠÙ„ Ø§Ù„ØªÙ„Ù‚Ø§Ø¦ÙŠ. */
    fun getConvertEngine2(): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø­Ø±Ùƒ Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø«Ø§Ù†ÙŠ. */
    fun setConvertEngine2(pkg: String?)

    /** Ù„ØºØ© Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø«Ø§Ù†ÙŠ. */
    fun getConvertLanguageTag2(): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù„ØºØ© Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø«Ø§Ù†ÙŠ. */
    fun setConvertLanguageTag2(langTag: String?)

    /** ØµÙˆØª Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø«Ø§Ù†ÙŠ. */
    fun getConvertVoice2(): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† ØµÙˆØª Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø«Ø§Ù†ÙŠ. */
    fun setConvertVoice2(voiceId: String?)

    /** Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø«Ø§Ù†ÙŠ. */
    fun getConvertVolume2(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø³ØªÙˆÙ‰ ØµÙˆØª Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø«Ø§Ù†ÙŠ. */
    fun setConvertVolume2(volume: Float)

    /** Ù†Ø¨Ø±Ø© Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø«Ø§Ù†ÙŠ. */
    fun getConvertPitch2(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù†Ø¨Ø±Ø© Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø«Ø§Ù†ÙŠ. */
    fun setConvertPitch2(pitch: Float)

    /** Ù…Ø¹Ø¯Ù„ Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø«Ø§Ù†ÙŠ. */
    fun getConvertRate2(): Float

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…Ø¹Ø¯Ù„ Ø§Ù„Ø³Ù„ÙˆØª Ø§Ù„Ø«Ø§Ù†ÙŠ. */
    fun setConvertRate2(rate: Float)
}

/**
 * [CallerNamesStore] â€” Ù†Ø·Ø§Ù‚ Ø£Ø³Ù…Ø§Ø¡ Ø§Ù„Ù…ØªØµÙ„ÙŠÙ† Ø§Ù„Ù…Ø®ØµÙ‘Øµ (PII) ÙÙŠ Ø§Ù„Ù…Ø®Ø²Ù† Ø§Ù„Ù…Ø´ÙØ±
 * â€” Ø¹Ø²Ù„ÙŒ Ù…Ù†Ø·Ù‚ÙŠ Ù„Ù„Ø¨ÙŠØ§Ù†Ø§Øª Ø§Ù„Ø­Ø³Ø§Ø³Ø© Ø¹Ù† Ø¨Ø§Ù‚ÙŠ Ø§Ù„Ø¥Ø¹Ø¯Ø§Ø¯Ø§Øª (Ø§Ù„Ø¨Ù†Ø¯ 4).
 * ÙŠÙ†ÙÙ‘Ø°Ù‡Ø§ SettingsRepository.
 */
interface CallerNamesStore {

    /** Ø®Ø§Ø±Ø·Ø© Ø£Ø³Ù…Ø§Ø¡ Ø§Ù„Ù…ØªØµÙ„ÙŠÙ† Ø§Ù„Ù…Ø®ØµÙ‘Øµ (Ø±Ù‚Ù… -> Ø§Ù„Ø§Ø³Ù…). */
    fun getCustomCallerNames(): Map<String, String>

    /** ÙŠØ³ØªØ¨Ø¯Ù„ Ø®Ø§Ø±Ø·Ø© Ø£Ø³Ù…Ø§Ø¡ Ø§Ù„Ù…ØªØµÙ„ÙŠÙ† Ø§Ù„Ù…Ø®ØµÙ‘Øµ Ø¨Ø§Ù„ÙƒØ§Ù…Ù„. */
    fun setCustomCallerNames(names: Map<String, String>)
}

/**
 * [UpdatePrefs] â€” Ù†Ø·Ø§Ù‚ Ø§Ù„ØªØ­Ø¯ÙŠØ«Ø§Øª: Ù…ÙØªØ§Ø­ GitHub PAT Ù„Ù„Ù…Ø³ØªÙˆØ¯Ø¹ Ø§Ù„Ø®Ø§Øµ.
 * ÙŠÙ†ÙÙ‘Ø°Ù‡Ø§ SettingsRepository.
 */
interface UpdatePrefs {

    /** Ù…ÙØªØ§Ø­ GitHub PAT (Personal Access Token) Ù„Ù„Ù…Ø³ØªÙˆØ¯Ø¹ Ø§Ù„Ø®Ø§Øµ.
     *  null Ø¥Ù† Ù„Ù… ÙŠÙØ¶Ø¨Ø·. */
    fun getGitHubUpdatePat(): String?

    /** ÙŠØ¹ÙŠÙ‘Ù† Ù…ÙØªØ§Ø­ GitHub PAT (null = Ø­Ø°Ù). */
    fun setGitHubUpdatePat(token: String?)

    /** Ù…Ø¹Ø±Ù‘Ù Ø§Ù„ØªÙ†Ø²ÙŠÙ„ Ø§Ù„Ù†Ø´Ø· ÙÙŠ DownloadManager (-1 Ø¥Ù† Ù„Ù… ÙŠÙˆØ¬Ø¯). */
    fun getActiveUpdateDownloadId(): Long

    /** Ø±Ø§Ø¨Ø· Ø§Ù„ØªØ­Ø¯ÙŠØ« Ø§Ù„Ù†Ø´Ø· Ø¥Ù† ÙˆÙØ¬Ø¯ ØªÙ†Ø²ÙŠÙ„ Ø¬Ø§Ø±Ù. */
    fun getActiveUpdateApkUrl(): String?

    /** Ø¨ØµÙ…Ø© SHA-256 Ø§Ù„Ù…ØªÙˆÙ‚Ø¹Ø© Ù„Ù„ØªÙ†Ø²ÙŠÙ„ Ø§Ù„Ù†Ø´Ø· Ø¥Ù† ÙˆÙØ¬Ø¯Øª. */
    fun getActiveUpdateExpectedSha256(): String?

    /** Ø­ÙØ¸ Ø¨ÙŠØ§Ù†Ø§Øª Ø§Ù„ØªÙ†Ø²ÙŠÙ„ Ø§Ù„Ù†Ø´Ø· Ù„Ø§Ø³ØªØ¹Ø§Ø¯ØªÙ‡Ø§ Ø¹Ù†Ø¯ Ø¹ÙˆØ¯Ø© Ø§Ù„Ù…Ø³ØªØ®Ø¯Ù…. */
    fun setActiveUpdateDownload(
        downloadId: Long,
        apkUrl: String,
        expectedSha256: String?
    )

    /** Ù…Ø³Ø­ Ø¨ÙŠØ§Ù†Ø§Øª Ø§Ù„ØªÙ†Ø²ÙŠÙ„ Ø§Ù„Ù†Ø´Ø· Ø¨Ø¹Ø¯ Ø§Ù„Ø§ÙƒØªÙ…Ø§Ù„ Ø£Ùˆ Ø§Ù„Ø¥Ù„ØºØ§Ø¡. */
    fun clearActiveUpdateDownload()
}

/**
 * [TextProcessingPrefs] â€” Ù†Ø·Ø§Ù‚ Ù…ÙØ§ØªÙŠØ­ Ù…Ø±Ø§Ø­Ù„ Ù…Ø¹Ø§Ù„Ø¬Ø© Ø§Ù„Ù†ØµÙˆØµ.
 * ÙŠÙ†ÙÙ‘Ø°Ù‡Ø§ SettingsRepository.
 */
interface TextProcessingPrefs :
    com.aymankhattab.nateq.core.engine.TextProcessingPreferences {

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ ØªØ­ÙˆÙŠÙ„ Ø§Ù„Ø£ÙˆÙ‚Ø§Øª ÙˆØ§Ù„ØªÙˆØ§Ø±ÙŠØ®. */
    fun setTimeConversionEnabled(enabled: Boolean)

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ ØªØ­ÙˆÙŠÙ„ Ø§Ù„Ø¹Ù…Ù„Ø§Øª ÙˆØ§Ù„Ù…Ø¨Ø§Ù„Øº. */
    fun setCurrencyConversionEnabled(enabled: Boolean)

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ ØªØ­ÙˆÙŠÙ„ ÙˆØ­Ø¯Ø§Øª Ø§Ù„Ù‚ÙŠØ§Ø³. */
    fun setUnitConversionEnabled(enabled: Boolean)

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ ØªØ­ÙˆÙŠÙ„ Ø§Ù„Ø±Ù…ÙˆØ². */
    fun setSymbolConversionEnabled(enabled: Boolean)

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ ØªØ­ÙˆÙŠÙ„ Ø£Ø±Ù‚Ø§Ù… Ø§Ù„Ù‡ÙˆØ§ØªÙ. */
    fun setPhoneConversionEnabled(enabled: Boolean)

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ ØªØ·Ø¨ÙŠØ¹ Ø§Ù„Ø­Ø±ÙˆÙ Ø§Ù„Ø¹Ø±Ø¨ÙŠØ©. */
    fun setArabicNormalizationEnabled(enabled: Boolean)

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø§Ù„Ù…Ø¹Ø§Ù„Ø¬Ø© Ø§Ù„Ù…ØµØ±ÙŠØ©/Ø§Ù„ØªØ´ÙƒÙŠÙ„ Ø§Ù„ØªÙ„Ù‚Ø§Ø¦ÙŠ Ø§Ù„Ø®ÙÙŠÙ. */
    fun setEgyptianSpeechProcessingEnabled(enabled: Boolean)

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø§Ù„Ø³Ø±Ø¹Ø© Ø§Ù„Ø¯ÙŠÙ†Ø§Ù…ÙŠÙƒÙŠØ© Ø­Ø³Ø¨ Ø·ÙˆÙ„ Ø§Ù„Ø¬Ù…Ù„Ø©/Ø§Ù„Ù…Ø­ØªÙˆÙ‰. */
    fun setDynamicSpeedEnabled(enabled: Boolean)

    /** ÙŠØ¹ÙŠÙ‘Ù† ØªÙØ¹ÙŠÙ„ Ø§Ù„Ù‚Ø§Ø±Ø¦ Ù…ØªØ¹Ø¯Ø¯ Ø§Ù„Ø£ØµÙˆØ§Øª (Ø­ÙˆØ§Ø±/Ø³Ø±Ø¯). */
    fun setMultiVoiceReaderEnabled(enabled: Boolean)
}

/**
 * [AccessibilityPrefs] â€” Ù†Ø·Ø§Ù‚ Ø¥Ø¹Ø¯Ø§Ø¯Ø§Øª Ø¥Ù…ÙƒØ§Ù†ÙŠØ© Ø§Ù„ÙˆØµÙˆÙ„ ÙˆØ®Ø¯Ù…Ø§Øª Ø§Ù„Ø´Ø§Ø´Ø©.
 * ÙŠÙ†ÙÙ‘Ø°Ù‡Ø§ SettingsRepository.
 */
interface AccessibilityPrefs {

    /** Ù‡Ù„ Ø§Ù„Ù…ÙŠØ²Ø© Ø§Ù„Ù…Ø¹Ø·Ø§Ø© Ù…ÙØ¹Ù‘Ù„Ø© ÙÙŠ Ø§Ù„Ø¥Ø¹Ø¯Ø§Ø¯Ø§ØªØŸ */
    fun isAccessibilityFeatureEnabled(featureKey: String): Boolean

    /** ØªØ¹ÙŠÙŠÙ† Ø­Ø§Ù„Ø© ØªÙØ¹ÙŠÙ„ Ù…ÙŠØ²Ø© Ø¥Ù…ÙƒØ§Ù†ÙŠØ© Ø§Ù„ÙˆØµÙˆÙ„. */
    fun setAccessibilityFeatureEnabled(featureKey: String, enabled: Boolean)

    /** Ù‡Ù„ Ø®Ø¯Ù…Ø§Øª Ø§Ù„Ø´Ø§Ø´Ø© Ù…ØªÙˆÙ‚ÙØ© Ù…Ø¤Ù‚ØªØ§Ù‹ØŸ */
    fun isA11yPaused(): Boolean

    /** ØªØ¹ÙŠÙŠÙ† Ø­Ø§Ù„Ø© Ø§Ù„Ø¥ÙŠÙ‚Ø§Ù Ø§Ù„Ù…Ø¤Ù‚Øª Ù„Ø®Ø¯Ù…Ø§Øª Ø§Ù„Ø´Ø§Ø´Ø©. */
    fun setA11yPaused(paused: Boolean)

    /** ÙˆÙ‚Øª Ø§Ù†ØªÙ‡Ø§Ø¡ Ø§Ù„Ø¥ÙŠÙ‚Ø§Ù Ø§Ù„Ù…Ø¤Ù‚Øª Ø¨Ø§Ù„Ù…Ù„Ù„ÙŠ Ø«Ø§Ù†ÙŠØ© (Epoch). */
    fun getA11yPausedUntil(): Long

    /** ØªØ¹ÙŠÙŠÙ† ÙˆÙ‚Øª Ø§Ù†ØªÙ‡Ø§Ø¡ Ø§Ù„Ø¥ÙŠÙ‚Ø§Ù Ø§Ù„Ù…Ø¤Ù‚Øª. */
    fun setA11yPausedUntil(untilEpoch: Long)

    /** Ù…Ø³ØªÙˆÙ‰ Ø§Ù„Ø¥ÙŠÙ‚Ø§Ù Ø§Ù„Ù…Ø¤Ù‚Øª (1 = Ø®ÙÙŠÙØŒ 2 = Ø¥ÙŠÙ‚Ø§Ù ÙƒØ§Ù…Ù„). */
    fun getA11yPauseLevel(): Int

    /** ØªØ¹ÙŠÙŠÙ† Ù…Ø³ØªÙˆÙ‰ Ø§Ù„Ø¥ÙŠÙ‚Ø§Ù Ø§Ù„Ù…Ø¤Ù‚Øª. */
    fun setA11yPauseLevel(level: Int)

    /** Ù‚Ø§Ø¦Ù…Ø© Ø§Ù„Ø­Ø²Ù… Ø§Ù„Ù…Ø³Ù…ÙˆØ­Ø© Ù„Ø®Ø¯Ù…Ø§Øª Ø§Ù„Ø´Ø§Ø´Ø©. */
    fun getA11yAllowedPackages(): Set<String>?

    /** ØªØ¹ÙŠÙŠÙ† Ù‚Ø§Ø¦Ù…Ø© Ø§Ù„Ø­Ø²Ù… Ø§Ù„Ù…Ø³Ù…ÙˆØ­Ø© Ù„Ø®Ø¯Ù…Ø§Øª Ø§Ù„Ø´Ø§Ø´Ø©. */
    fun setA11yAllowedPackages(packages: Set<String>?)

    /** Ù‡Ù„ ÙŠÙØ³Ù…Ø­ Ù„Ø¬Ù…ÙŠØ¹ Ø§Ù„ØªØ·Ø¨ÙŠÙ‚Ø§Øª Ø¯ÙˆÙ† ØªÙ‚ÙŠÙŠØ¯ Ø¨Ø§Ù„Ù‚Ø§Ø¦Ù…Ø© Ø§Ù„Ø¨ÙŠØ¶Ø§Ø¡ØŸ */
    fun isA11yAllowAllApps(): Boolean

    /** ØªØ¹ÙŠÙŠÙ† Ø§Ù„Ø³Ù…Ø§Ø­ Ù„Ø¬Ù…ÙŠØ¹ Ø§Ù„ØªØ·Ø¨ÙŠÙ‚Ø§Øª. */
    fun setA11yAllowAllApps(allowAll: Boolean)

    /** Ù‡Ù„ Ø§Ù„Ø¥ÙŠÙ‚Ø§Ù Ø§Ù„Ù…Ø¤Ù‚Øª Ø§Ù„Ø°ÙƒÙŠ Ø£Ø«Ù†Ø§Ø¡ Ø§Ù„Ù…ÙƒØ§Ù„Ù…Ø§Øª Ù…ÙØ¹Ù‘Ù„ØŸ */
    fun isA11ySmartPauseCallEnabled(): Boolean

    /** ØªØ¹ÙŠÙŠÙ† ØªÙØ¹ÙŠÙ„ Ø§Ù„Ø¥ÙŠÙ‚Ø§Ù Ø§Ù„Ù…Ø¤Ù‚Øª Ø§Ù„Ø°ÙƒÙŠ Ø£Ø«Ù†Ø§Ø¡ Ø§Ù„Ù…ÙƒØ§Ù„Ù…Ø§Øª. */
    fun setA11ySmartPauseCallEnabled(enabled: Boolean)

    /** Ù‡Ù„ Ø§Ù„Ø¥ÙŠÙ‚Ø§Ù Ø§Ù„Ù…Ø¤Ù‚Øª Ø§Ù„Ø°ÙƒÙŠ Ø£Ø«Ù†Ø§Ø¡ Ø§Ù„Ø£Ù„Ø¹Ø§Ø¨ Ù…ÙØ¹Ù‘Ù„ØŸ */
    fun isA11ySmartPauseGameEnabled(): Boolean

    /** ØªØ¹ÙŠÙŠÙ† ØªÙØ¹ÙŠÙ„ Ø§Ù„Ø¥ÙŠÙ‚Ø§Ù Ø§Ù„Ù…Ø¤Ù‚Øª Ø§Ù„Ø°ÙƒÙŠ Ø£Ø«Ù†Ø§Ø¡ Ø§Ù„Ø£Ù„Ø¹Ø§Ø¨. */
    fun setA11ySmartPauseGameEnabled(enabled: Boolean)

    /**
     * Ø¬Ø¯ÙˆÙ„Ù Ø±Ø¨Ø· Ø§Ø®ØªØµØ§Ø±Ø§Øª Ø§Ù„Ù…ÙØ§ØªÙŠØ­ Ù…ÙØ±Ù…ÙŽÙ‘Ø²Ø§Ù‹ØŒ Ø£Ùˆ null Ù„Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠ.
     */
    fun getA11yShortcutBindings(): String?

    /**
     * ØªØ¹ÙŠÙŠÙ† Ø¬Ø¯ÙˆÙ„ Ø±Ø¨Ø· Ø§Ø®ØªØµØ§Ø±Ø§Øª Ø§Ù„Ù…ÙØ§ØªÙŠØ­ (null ÙŠÙØ¹ÙŠØ¯ Ø§Ù„Ø§ÙØªØ±Ø§Ø¶ÙŠ).
     */
    fun setA11yShortcutBindings(encoded: String?)

    /**
     * ØªÙƒÙˆÙŠÙ† Ø¬Ù‡Ø§Ø±Ø© Ø§Ù„ØµÙˆØª Ù„ÙØ¦Ø© Ø­Ø¯Ø« Ù…Ø¹ÙŠÙ†Ø© (Ø¹Ù¤Ù¢-2).
     */
    fun getCategoryLoudnessConfig(category: String?): CategoryLoudnessConfig

    /**
     * ØªØ¹ÙŠÙŠÙ† ØªÙƒÙˆÙŠÙ† Ø¬Ù‡Ø§Ø±Ø© Ø§Ù„ØµÙˆØª Ù„ÙØ¦Ø© Ø­Ø¯Ø« Ù…Ø¹ÙŠÙ†Ø© (Ø¹Ù¤Ù¢-2).
     */
    fun setCategoryLoudnessConfig(
        category: String,
        config: CategoryLoudnessConfig
    )

    /** Ù‡Ù„ Ø±ÙØ¹ Ø§Ù„ØµÙˆØª Ù„Ù„Ø£Ù‚ØµÙ‰ Ù…ÙØ¹Ù‘Ù„ Ù„Ù‡Ø°Ù‡ Ø§Ù„ÙØ¦Ø©ØŸ */
    fun isLoudnessForceMaxEnabled(category: String?): Boolean

    /** ØªØ¹ÙŠÙŠÙ† Ø±ÙØ¹ Ø§Ù„ØµÙˆØª Ù„Ù„Ø£Ù‚ØµÙ‰ Ù„Ù‡Ø°Ù‡ Ø§Ù„ÙØ¦Ø©. */
    fun setLoudnessForceMaxEnabled(category: String, enabled: Boolean)

    /**
     * Ù‡Ù„ ØªØ¬Ø§ÙˆØ² Ø§Ù„ÙˆØ¶Ø¹ Ø§Ù„ØµØ§Ù…Øª Ù…ÙØ¹Ù‘Ù„ Ù„Ù‡Ø°Ù‡ Ø§Ù„ÙØ¦Ø©ØŸ
     */
    fun isLoudnessBypassSilentEnabled(category: String?): Boolean

    /** ØªØ¹ÙŠÙŠÙ† ØªØ¬Ø§ÙˆØ² Ø§Ù„ÙˆØ¶Ø¹ Ø§Ù„ØµØ§Ù…Øª Ù„Ù‡Ø°Ù‡ Ø§Ù„ÙØ¦Ø©. */
    fun setLoudnessBypassSilentEnabled(category: String, enabled: Boolean)

    /** ÙˆØ¶Ø¹ ØªÙŠØ§Ø± Ø§Ù„ØµÙˆØª Ù„ÙØ¦Ø© Ø§Ù„Ø­Ø¯Ø«. */
    fun getLoudnessStreamMode(category: String?): StreamMode

    /** ØªØ¹ÙŠÙŠÙ† ÙˆØ¶Ø¹ ØªÙŠØ§Ø± Ø§Ù„ØµÙˆØª Ù„ÙØ¦Ø© Ø§Ù„Ø­Ø¯Ø«. */
    fun setLoudnessStreamMode(category: String, mode: StreamMode)

    /**
     * Ø³Ù‚Ù Ø§Ù„ØµÙˆØª Ù„Ø³Ù…Ø§Ø¹Ø§Øª Ø§Ù„Ø±Ø£Ø³ ÙˆØ§Ù„Ø¨Ù„ÙˆØªÙˆØ« ÙƒÙ†Ø³Ø¨Ø© Ù…Ø¦ÙˆÙŠØ© (0..100).
     */
    fun getLoudnessHeadsetCapPercent(category: String?): Int

    /**
     * ØªØ¹ÙŠÙŠÙ† Ø³Ù‚Ù Ø§Ù„ØµÙˆØª Ù„Ø³Ù…Ø§Ø¹Ø§Øª Ø§Ù„Ø±Ø£Ø³ ÙˆØ§Ù„Ø¨Ù„ÙˆØªÙˆØ«.
     */
    fun setLoudnessHeadsetCapPercent(category: String, percent: Int)

    /**
     * Ø­ÙØ¸ Ù…Ø³ØªÙˆÙ‰ Ø§Ù„ØµÙˆØª ÙˆØªÙŠØ§Ø± Ø§Ù„Ù‚Ù†Ø§Ø© Ø§Ù„Ù…Ø¹Ù„Ù‚Ø© Ù‚Ø¨Ù„ Ø§Ù„Ø±ÙØ¹ (Ø§Ø³ØªØ¹Ø§Ø¯Ø© Ø¢Ù…Ù†Ø©).
     */
    fun savePendingBoost(stream: Int, originalVolume: Int)

    /**
     * Ù…Ø³Ø­ Ù…Ø¤Ø´Ø± Ø§Ù„Ø±ÙØ¹ Ø§Ù„Ù…Ø¹Ù„Ù‚ Ø¨Ø¹Ø¯ Ø§Ù„Ø§Ø³ØªØ¹Ø§Ø¯Ø© Ø§Ù„Ù†Ø§Ø¬Ø­Ø©.
     */
    fun clearPendingBoost()

    /**
     * Ø§Ø³ØªØ¹Ù„Ø§Ù… ØªÙŠØ§Ø± Ø§Ù„ØµÙˆØª Ø§Ù„Ù…Ø±ÙÙˆØ¹ Ø§Ù„Ù…Ø¹Ù„Ù‚ (-1 Ø¥Ù† Ù„Ù… ÙŠÙˆØ¬Ø¯).
     */
    fun getPendingBoostStream(): Int

    /**
     * Ø§Ø³ØªØ¹Ù„Ø§Ù… Ù…Ø³ØªÙˆÙ‰ Ø§Ù„ØµÙˆØª Ø§Ù„Ø£ØµÙ„ÙŠ Ø§Ù„Ù…Ø¹Ù„Ù‚ (-1 Ø¥Ù† Ù„Ù… ÙŠÙˆØ¬Ø¯).
     */
    fun getPendingBoostVolume(): Int

    /** ÙƒØ³Ø¨ PCM Ø¨Ø§Ù„Ø¯ÙŠØ³ÙŠØ¨Ù„ Ù„Ø£Ø­Ø¯Ø§Ø« Ø§Ù„Ù†Ø¸Ø§Ù… Ù„Ù„Ù…Ø­Ø±ÙƒØ§Øª Ø§Ù„Ù…Ø¯Ù…Ø¬Ø© (Ø¹Ù¤Ù¢-3). */
    fun getEventPcmGainDb(): Float

    /** ØªØ¹ÙŠÙŠÙ† ÙƒØ³Ø¨ PCM Ø¨Ø§Ù„Ø¯ÙŠØ³ÙŠØ¨Ù„ Ù„Ø£Ø­Ø¯Ø§Ø« Ø§Ù„Ù†Ø¸Ø§Ù… (Ø¹Ù¤Ù¢-3). */
    fun setEventPcmGainDb(gainDb: Float)
}