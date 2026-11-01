package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import com.aymankhattab.nateq.core.audio.announcement.EventVoiceTrace
import com.aymankhattab.nateq.core.data.EffectiveVoice
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.VoiceIdContract

/**
 * نتيجة حسم صوت المقطع (محرك وصوت ومصدر) موحدة للمعاينة والقراءة الحية.
 */
data class ResolvedSegmentVoice(
    val engine: String?,
    val voiceName: String?,
    val source: String
)

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
    const val SOURCE_EMERGENCY = "emergency"

    /**
     * استخراج كود لغة معرّف الصوت لتحديد ما إذا كان متطابقاً مع لغة المقطع.
     */
    fun languageOfVoiceId(voiceId: String?): String? {
        if (voiceId.isNullOrBlank()) return null
        val lower = voiceId.lowercase()
        if (lower.startsWith("ar-") || lower.startsWith("ar_") ||
            lower.startsWith("ara-") || lower.startsWith("ara_") ||
            lower == "ar" || lower == "ara" || lower.contains(":ar-") ||
            lower.contains(":ar_") || lower.contains(":ara") ||
            lower.contains("arabic")
        ) {
            return LanguageCode.AR.tag
        }
        if (lower.startsWith("en-") || lower.startsWith("en_") ||
            lower.startsWith("eng-") || lower.startsWith("eng_") ||
            lower == "en" || lower == "eng" || lower.contains(":en-") ||
            lower.contains(":en_") || lower.contains(":eng") ||
            lower.contains("english")
        ) {
            return LanguageCode.EN.tag
        }
        if (lower.contains("laila") || lower.contains("leila") ||
            lower.contains("maged") || lower.contains("majed") ||
            lower.contains("tarik") || lower.contains("tariq") ||
            lower.contains("mariam") || lower.contains("maryam")
        ) {
            return LanguageCode.AR.tag
        }
        if (lower.contains("samantha") || lower.contains("ava") ||
            lower.contains("tom") || lower.contains("serena") ||
            lower.contains("daniel") || lower.contains("oliver")
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

    /**
     * هل ينتمي معرّف الصوت إلى المحرك المعين؟
     */
    fun isVoiceBelongingToEngine(
        engine: String?,
        voiceId: String?,
        knownVoices: List<String>? = null
    ): Boolean = VoiceIdContract.isVoiceBelongingToEngine(
        engine,
        voiceId,
        knownVoices
    )

    fun resolve(
        role: SpeechRole,
        languageTag: String,
        categoryVoice: String?,
        appVoice: String?,
        contactVoice: String?,
        languageDefaultVoice: String?,
        categoryEngine: String? = null,
        emergencyVoice: String? = null
    ): ResolvedVoice {
        var effectiveCategoryVoice = categoryVoice
        if (!categoryVoice.isNullOrBlank() &&
            !categoryEngine.isNullOrBlank()
        ) {
            if (!isVoiceBelongingToEngine(categoryEngine, categoryVoice)) {
                effectiveCategoryVoice = null
                EventVoiceTrace.record(
                    category = role.name.lowercase(),
                    savedVoiceId = categoryVoice,
                    resolvedEngine = categoryEngine,
                    dispatchedVoiceId = null,
                    fallbackReason = "صوت لا يتبع المحرك"
                )
            }
        }

        return when (role) {
            SpeechRole.NUMBER -> {
                val match = isVoiceLanguageMatch(
                    effectiveCategoryVoice,
                    languageTag
                )
                if (!effectiveCategoryVoice.isNullOrBlank() && match) {
                    ResolvedVoice(
                        effectiveCategoryVoice,
                        SOURCE_CATEGORY_NUMBER
                    )
                } else if (!contactVoice.isNullOrBlank()) {
                    ResolvedVoice(contactVoice, SOURCE_CONTACT)
                } else if (!appVoice.isNullOrBlank()) {
                    ResolvedVoice(appVoice, SOURCE_APP)
                } else if (!languageDefaultVoice.isNullOrBlank()) {
                    ResolvedVoice(
                        languageDefaultVoice,
                        if (!effectiveCategoryVoice.isNullOrBlank()) {
                            SOURCE_CATEGORY_NUMBER_PARAMS
                        } else {
                            SOURCE_LANGUAGE_DEFAULT
                        }
                    )
                } else if (!emergencyVoice.isNullOrBlank()) {
                    ResolvedVoice(emergencyVoice, SOURCE_EMERGENCY)
                } else {
                    ResolvedVoice(
                        null,
                        if (!effectiveCategoryVoice.isNullOrBlank()) {
                            SOURCE_CATEGORY_NUMBER_PARAMS
                        } else {
                            SOURCE_LANGUAGE_DEFAULT
                        }
                    )
                }
            }
            SpeechRole.EMOJI -> {
                // صوت فئة الإيموجي دائماً ولا يعلوه صوت التطبيق أو جهة الاتصال
                if (!effectiveCategoryVoice.isNullOrBlank()) {
                    ResolvedVoice(
                        effectiveCategoryVoice,
                        SOURCE_CATEGORY_EMOJI
                    )
                } else if (!contactVoice.isNullOrBlank()) {
                    ResolvedVoice(contactVoice, SOURCE_CONTACT)
                } else if (!appVoice.isNullOrBlank()) {
                    ResolvedVoice(appVoice, SOURCE_APP)
                } else if (!languageDefaultVoice.isNullOrBlank()) {
                    ResolvedVoice(
                        languageDefaultVoice, SOURCE_LANGUAGE_DEFAULT
                    )
                } else if (!emergencyVoice.isNullOrBlank()) {
                    ResolvedVoice(emergencyVoice, SOURCE_EMERGENCY)
                } else {
                    ResolvedVoice(null, SOURCE_LANGUAGE_DEFAULT)
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
                    !effectiveCategoryVoice.isNullOrBlank() -> {
                        ResolvedVoice(
                            effectiveCategoryVoice,
                            SOURCE_CATEGORY
                        )
                    }
                    !languageDefaultVoice.isNullOrBlank() -> {
                        ResolvedVoice(
                            languageDefaultVoice,
                            SOURCE_LANGUAGE_DEFAULT
                        )
                    }
                    !emergencyVoice.isNullOrBlank() -> {
                        ResolvedVoice(
                            emergencyVoice,
                            SOURCE_EMERGENCY
                        )
                    }
                    else -> {
                        ResolvedVoice(
                            null,
                            SOURCE_LANGUAGE_DEFAULT
                        )
                    }
                }
            }
        }
    }

    @Volatile
    private var defaultContext: Context? = null

    fun init(context: Context) {
        defaultContext = context.applicationContext
    }

    fun resolveSegmentVoice(
        role: SpeechRole,
        languageTag: String
    ): ResolvedSegmentVoice {
        val ctx = defaultContext
        val repo = if (ctx != null) {
            SettingsRepository.create(ctx)
        } else {
            null
        }
        return if (repo != null) {
            resolveSegmentVoice(role, languageTag, repo)
        } else {
            ResolvedSegmentVoice(
                engine = null,
                voiceName = VoiceIdContract.createId(languageTag),
                source = SOURCE_LANGUAGE_DEFAULT
            )
        }
    }

    fun resolveSegmentVoice(
        role: SpeechRole,
        languageTag: String,
        context: Context
    ): ResolvedSegmentVoice = resolveSegmentVoice(
        role,
        languageTag,
        SettingsRepository.create(context)
    )

    fun resolveSegmentVoice(
        role: SpeechRole,
        languageTag: String,
        settings: SettingsRepository,
        appVoice: EffectiveVoice? = null,
        contactVoice: String? = null,
        emergencyVoice: String? = null
    ): ResolvedSegmentVoice {
        val categoryVoice = when (role) {
            SpeechRole.EMOJI -> settings.getPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_EMOJI
            )
            SpeechRole.NUMBER -> settings.getPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
            SpeechRole.TEXT -> null
        }
        val categoryEngine = when (role) {
            SpeechRole.EMOJI -> settings.getEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_EMOJI
            )
            SpeechRole.NUMBER -> settings.getEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
            SpeechRole.TEXT -> null
        }?.takeIf { VisibleEngines.isVisible(it) }
        val defaultCategoryEngine = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        )?.takeIf { VisibleEngines.isVisible(it) }
        val routed = LanguageSpeechRouter.route(
            matchesRequest = true,
            convertEngine = null,
            convertVoiceName = null,
            perLanguageEngine = settings.getEngineForLanguage(languageTag),
            perLanguageVoiceName = settings.getVoiceForLanguage(languageTag),
            defaultEngine = defaultCategoryEngine
        )
        val resolvedVoice = resolve(
            role = role,
            languageTag = languageTag,
            categoryVoice = categoryVoice,
            appVoice = appVoice?.voiceId,
            contactVoice = contactVoice,
            languageDefaultVoice = routed.voiceName
                ?: settings.getPreferredVoiceId(languageTag),
            categoryEngine = categoryEngine,
            emergencyVoice = emergencyVoice
        )
        val targetEngine = when (resolvedVoice.source) {
            SOURCE_CATEGORY_EMOJI,
            SOURCE_CATEGORY_NUMBER -> {
                categoryEngine ?: defaultCategoryEngine ?: routed.engine
            }
            SOURCE_APP -> {
                appVoice?.engine ?: routed.engine
            }
            else -> routed.engine
        }
        val preferredForEngine = targetEngine?.let {
            settings.getPreferredVoiceForEngine(it, languageTag)
        }
        val targetVoiceName = resolvedVoice.voiceId
            ?: preferredForEngine
            ?: VoiceIdContract.createId(languageTag)

        return ResolvedSegmentVoice(
            engine = targetEngine,
            voiceName = targetVoiceName,
            source = resolvedVoice.source
        )
    }
}
