package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.VoiceIdContract

/**
 * نتيجة حل الصوت مع تحديد مصدر القرار (الأمر 5).
 */
data class ResolvedVoice(
    val voiceId: String?,
    val source: String
)

/**
 * سياسة حل الأصوات الموحدة (الأمر 5):
 * تضمن عدم طغيان صوت التطبيق المخصص أو جهة الاتصال على فئة الأرقام أو الإيموجي.
 */
object VoiceResolutionPolicy {

    const val SOURCE_CONTACT = "contact"
    const val SOURCE_APP = "app"
    const val SOURCE_CATEGORY_NUMBER = "category_number"
    const val SOURCE_CATEGORY_NUMBER_PARAMS = "category_number_params"
    const val SOURCE_CATEGORY_EMOJI = "category_emoji"
    const val SOURCE_CATEGORY = "category"
    const val SOURCE_LANGUAGE_DEFAULT = "language_default"

    /**
     * استخراج كود لغة معرّف الصوت لتحديد ما إذا كان متطابقاً مع لغة المقطع.
     */
    fun languageOfVoiceId(voiceId: String?): String? {
        if (voiceId.isNullOrBlank()) return null
        val lower = voiceId.lowercase()
        if (lower.startsWith("ar-") || lower.startsWith("ar_") ||
            lower == "ar" || lower.contains(":ar-") ||
            lower.contains(":ar_") || lower.contains(":ara")
        ) {
            return LanguageCode.AR.tag
        }
        if (lower.startsWith("en-") || lower.startsWith("en_") ||
            lower == "en" || lower.contains(":en-") ||
            lower.contains(":en_") || lower.contains(":eng")
        ) {
            return LanguageCode.EN.tag
        }
        if (lower.contains("laila") || lower.contains("maged") ||
            lower.contains("tarik")
        ) {
            return LanguageCode.AR.tag
        }
        if (lower.contains("samantha") || lower.contains("ava") ||
            lower.contains("tom")
        ) {
            return LanguageCode.EN.tag
        }
        return null
    }

    /**
     * هل تطابق لغة صوت الفئة لغة المقطع المطلوب؟
     */
    fun isVoiceLanguageMatch(voiceId: String?, languageTag: String): Boolean {
        if (voiceId.isNullOrBlank()) return true
        val voiceLang = languageOfVoiceId(voiceId) ?: return true
        val segCanonical = when {
            LanguageCode.isArabic(languageTag) -> LanguageCode.AR.tag
            LanguageCode.isEnglish(languageTag) -> LanguageCode.EN.tag
            else -> languageTag.substringBefore('-').substringBefore('_')
                .trim().lowercase()
        }
        return voiceLang.equals(segCanonical, ignoreCase = true)
    }

    fun resolve(
        role: SpeechRole,
        languageTag: String,
        categoryVoice: String?,
        appVoice: String?,
        contactVoice: String?,
        languageDefaultVoice: String?
    ): ResolvedVoice {
        return when (role) {
            SpeechRole.NUMBER -> {
                val match = isVoiceLanguageMatch(categoryVoice, languageTag)
                if (!categoryVoice.isNullOrBlank() && match) {
                    ResolvedVoice(categoryVoice, SOURCE_CATEGORY_NUMBER)
                } else if (!contactVoice.isNullOrBlank()) {
                    ResolvedVoice(contactVoice, SOURCE_CONTACT)
                } else if (!appVoice.isNullOrBlank()) {
                    ResolvedVoice(appVoice, SOURCE_APP)
                } else {
                    ResolvedVoice(
                        languageDefaultVoice,
                        if (!categoryVoice.isNullOrBlank()) {
                            SOURCE_CATEGORY_NUMBER_PARAMS
                        } else {
                            SOURCE_LANGUAGE_DEFAULT
                        }
                    )
                }
            }
            SpeechRole.EMOJI -> {
                // صوت فئة الإيموجي دائماً ولا يعلوه صوت التطبيق أو جهة الاتصال
                if (!categoryVoice.isNullOrBlank()) {
                    ResolvedVoice(categoryVoice, SOURCE_CATEGORY_EMOJI)
                } else if (!contactVoice.isNullOrBlank()) {
                    ResolvedVoice(contactVoice, SOURCE_CONTACT)
                } else if (!appVoice.isNullOrBlank()) {
                    ResolvedVoice(appVoice, SOURCE_APP)
                } else {
                    ResolvedVoice(
                        languageDefaultVoice, SOURCE_LANGUAGE_DEFAULT
                    )
                }
            }
            SpeechRole.TEXT -> {
                when {
                    !contactVoice.isNullOrBlank() -> {
                        ResolvedVoice(contactVoice, SOURCE_CONTACT)
                    }
                    !appVoice.isNullOrBlank() -> {
                        ResolvedVoice(appVoice, SOURCE_APP)
                    }
                    !categoryVoice.isNullOrBlank() -> {
                        ResolvedVoice(categoryVoice, SOURCE_CATEGORY)
                    }
                    else -> {
                        ResolvedVoice(
                            languageDefaultVoice,
                            SOURCE_LANGUAGE_DEFAULT
                        )
                    }
                }
            }
        }
    }
}
