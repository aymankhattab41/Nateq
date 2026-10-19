package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.VoiceIdContract

/**
 * حل لغة الإيموجي الموحد (الأمر 4):
 * يحدد لغة اسم الإيموجي وصوته لضمان تطابقهما دائماً وعدم تخمين اللغة عشوائياً.
 */
object EmojiLanguageResolver {

    fun resolve(
        emojiSetting: String?,
        contextLanguage: String,
        emojiVoiceId: String? = null,
        fallbackLanguage: String = LanguageCode.AR.tag
    ): String {
        val setting = emojiSetting?.uppercase()?.trim() ?: "AUTO"
        return when (setting) {
            "AR" -> LanguageCode.AR.tag
            "EN" -> LanguageCode.EN.tag
            else -> { // AUTO
                val voiceLang = languageOfVoiceId(emojiVoiceId)
                if (voiceLang != null) {
                    voiceLang
                } else if (LanguageCode.isArabic(contextLanguage)) {
                    LanguageCode.AR.tag
                } else if (LanguageCode.isEnglish(contextLanguage)) {
                    LanguageCode.EN.tag
                } else {
                    if (LanguageCode.isEnglish(fallbackLanguage)) {
                        LanguageCode.EN.tag
                    } else {
                        LanguageCode.AR.tag
                    }
                }
            }
        }
    }

    private fun languageOfVoiceId(voiceId: String?): String? {
        if (voiceId.isNullOrBlank()) return null
        VoiceIdContract.normalize(voiceId)?.let { norm ->
            if (norm.startsWith("en", ignoreCase = true)) {
                return LanguageCode.EN.tag
            }
            if (norm.startsWith("ar", ignoreCase = true)) {
                return LanguageCode.AR.tag
            }
        }
        val raw = voiceId.substringAfter(':', missingDelimiterValue = "")
        if (raw.startsWith("en", ignoreCase = true)) return LanguageCode.EN.tag
        if (raw.startsWith("ar", ignoreCase = true)) return LanguageCode.AR.tag
        return null
    }
}
