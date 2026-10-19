package com.aymankhattab.nateq.core.audio.engine

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
    const val SOURCE_CATEGORY_EMOJI = "category_emoji"
    const val SOURCE_CATEGORY = "category"
    const val SOURCE_LANGUAGE_DEFAULT = "language_default"

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
                // صوت فئة الأرقام دائماً ولا يعلوه صوت التطبيق أو جهة الاتصال
                if (!categoryVoice.isNullOrBlank()) {
                    ResolvedVoice(categoryVoice, SOURCE_CATEGORY_NUMBER)
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
