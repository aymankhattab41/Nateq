package com.aymankhattab.nateq.settings

import android.util.Log
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.VoiceIdContract

/**
 * منطق نقي لكشف وإصلاح أزواج (محرك، صوت) غير المتسقة (الأمر 2).
 */
internal object EngineLanguageConsistency {

    private const val TAG = "EngineLanguageConsistency"

    data class RepairResult(
        val categoryOrLang: String,
        val oldEngine: String?,
        val oldVoice: String?,
        val newEngine: String?,
        val newVoice: String?,
        val action: Action
    ) {
        enum class Action {
            RESTORED_KNOWN,
            REPAIRED_ENGINE,
            BACKED_UP_AND_CLEARED,
            ALREADY_CONSISTENT
        }
    }

    internal fun mismatchedLanguages(
        entries: List<Triple<String, String?, String?>>,
    ): List<Triple<String, String, String>> =
        entries.mapNotNull { (lang, engine, voice) ->
            if (engine.isNullOrEmpty() || voice.isNullOrEmpty()) {
                null
            } else if (!VoiceIdContract.isVoiceBelongingToEngine(
                    engine, voice
                )
            ) {
                Triple(lang, voice, engine)
            } else {
                null
            }
        }

    /**
     * إصلاح واستعادة كل الأصوات المتناقضة مع محركاتها في الإعدادات:
     * - الأرقام: إن كانت بلا صوت أو مع محرك Acapela تُستعاد إلى
     *   Leila على Vocalizer.
     * - الإنجليزية: إن كانت مع محرك لا يملك Karen تُصلح إلى Vocalizer
     *   مع Karen.
     * - أي فئة أخرى: إن كان صوتها لا يتبع محركها، يُبحث عن المحرك
     *   الذي يملكه ويُعدّل المحرك مع بقاء الصوت. وإن لم يملكه أي محرك
     *   يُنسخ احتياطياً ويُمسح.
     */
    internal fun repairAllInconsistentVoices(
        settings: SettingsRepository,
        catalog: EngineVoicesCatalog? = null,
        availableEngines: List<String> = listOf("vocalizer_embedded")
    ): List<RepairResult> {
        val results = mutableListOf<RepairResult>()

        val vocalizerAvailable =
            availableEngines.contains("vocalizer_embedded") &&
                (catalog == null ||
                    catalog.voicesFor("ar", "vocalizer_embedded")
                        .isNotEmpty() ||
                    catalog.voicesFor("en", "vocalizer_embedded")
                        .isNotEmpty())

        // 1. الحالة الراهنة الصريحة للأرقام
        val numberVoice = settings.getPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )
        val numberEngine = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )
        val isNumberMismatch = !numberVoice.isNullOrBlank() &&
            !numberEngine.isNullOrBlank() &&
            !VoiceIdContract.isVoiceBelongingToEngine(
                numberEngine, numberVoice
            )
        val isNumberMissingOrAcapela = numberVoice.isNullOrBlank() ||
            (numberVoice == "ara-SAU-Leila" &&
                numberEngine != "vocalizer_embedded")

        if (vocalizerAvailable) {
            val availableArVoices = catalog?.voicesFor(
                "ar", "vocalizer_embedded"
            )?.map { it.name }.orEmpty()
            val targetVoice: String = when {
                availableArVoices.isNotEmpty() &&
                    !availableArVoices.any { v ->
                        v.equals("ara-SAU-Leila", ignoreCase = true) ||
                            v.equals("Laila", ignoreCase = true)
                    } -> availableArVoices.first()
                else -> "ara-SAU-Leila"
            }
            val isNumberUnavailableInVocalizer = catalog != null &&
                availableArVoices.isNotEmpty() &&
                numberEngine == "vocalizer_embedded" &&
                !numberVoice.isNullOrBlank() &&
                !availableArVoices.any { v ->
                    v.equals(numberVoice, ignoreCase = true) ||
                        (numberVoice.contains("leila", ignoreCase = true) &&
                            v.contains("laila", ignoreCase = true))
                }

            if (isNumberMismatch || isNumberMissingOrAcapela ||
                isNumberUnavailableInVocalizer
            ) {
                settings.setEngineForCategory(
                    SettingsRepository.VOICE_CATEGORY_NUMBERS,
                    "vocalizer_embedded"
                )
                settings.setPreferredVoiceIdForCategory(
                    SettingsRepository.VOICE_CATEGORY_NUMBERS,
                    targetVoice,
                    "vocalizer_embedded"
                )
                results.add(
                    RepairResult(
                        categoryOrLang =
                            SettingsRepository.VOICE_CATEGORY_NUMBERS,
                        oldEngine = numberEngine,
                        oldVoice = numberVoice,
                        newEngine = "vocalizer_embedded",
                        newVoice = targetVoice,
                        action = RepairResult.Action.RESTORED_KNOWN
                    )
                )
                Log.i(
                    TAG,
                    "Restored numbers voice to $targetVoice on" +
                        " vocalizer_embedded"
                )
            }
        } else if (numberEngine == "vocalizer_embedded") {
            settings.setEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS,
                null
            )
            settings.setPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS,
                ""
            )
            results.add(
                RepairResult(
                    categoryOrLang =
                        SettingsRepository.VOICE_CATEGORY_NUMBERS,
                    oldEngine = numberEngine,
                    oldVoice = numberVoice,
                    newEngine = null,
                    newVoice = null,
                    action = RepairResult.Action.BACKED_UP_AND_CLEARED
                )
            )
            Log.i(
                TAG,
                "Cleared unavailable vocalizer_embedded from numbers"
            )
        }

        // 2. الحالة الراهنة الصريحة للغة الإنجليزية
        val enVoice = settings.getPreferredVoiceId("en")
        val enEngine = settings.getEngineForLanguage("en")
        if (vocalizerAvailable) {
            if (enVoice == "eng-USA-Karen" &&
                enEngine != "vocalizer_embedded"
            ) {
                settings.setEngineForLanguage("en", "vocalizer_embedded")
                settings.setPreferredVoiceId("en", "eng-USA-Karen")
                results.add(
                    RepairResult(
                        categoryOrLang = "lang_en",
                        oldEngine = enEngine,
                        oldVoice = enVoice,
                        newEngine = "vocalizer_embedded",
                        newVoice = "eng-USA-Karen",
                        action = RepairResult.Action.RESTORED_KNOWN
                    )
                )
                Log.i(
                    TAG,
                    "Restored English voice to eng-USA-Karen on" +
                        " vocalizer_embedded"
                )
            }
        } else if (enEngine == "vocalizer_embedded") {
            settings.setEngineForLanguage("en", null)
            if (enVoice == "eng-USA-Karen") {
                settings.setPreferredVoiceId("en", "")
            }
            results.add(
                RepairResult(
                    categoryOrLang = "lang_en",
                    oldEngine = enEngine,
                    oldVoice = enVoice,
                    newEngine = null,
                    newVoice = if (enVoice == "eng-USA-Karen") null
                    else enVoice,
                    action = RepairResult.Action.BACKED_UP_AND_CLEARED
                )
            )
            Log.i(
                TAG,
                "Cleared unavailable vocalizer_embedded from English"
            )
        }

        // 3. فحص كافة الفئات
        val categories = SettingsRepository.ALL_VOICE_CATEGORIES +
            listOf(
                SettingsRepository.VOICE_CATEGORY_DEFAULT,
                SettingsRepository.VOICE_CATEGORY_EMOJI
            )

        for (cat in categories) {
            if (cat == SettingsRepository.VOICE_CATEGORY_NUMBERS) continue
            val voice = settings.getPreferredVoiceIdForCategory(cat)
            val engine = settings.getEngineForCategory(cat)
            if (voice.isNullOrBlank() || engine.isNullOrBlank()) continue

            if (!VoiceIdContract.isVoiceBelongingToEngine(engine, voice)) {
                val owningEngine = findOwningEngine(
                    voice, catalog, availableEngines
                )
                if (owningEngine != null) {
                    settings.setEngineForCategory(cat, owningEngine)
                    settings.setPreferredVoiceIdForCategory(
                        cat, voice, owningEngine
                    )
                    results.add(
                        RepairResult(
                            categoryOrLang = cat,
                            oldEngine = engine,
                            oldVoice = voice,
                            newEngine = owningEngine,
                            newVoice = voice,
                            action = RepairResult.Action.REPAIRED_ENGINE
                        )
                    )
                    Log.i(
                        TAG,
                        "Repaired $cat: kept $voice, set engine $owningEngine"
                    )
                } else {
                    settings.setVoiceRepairBackup(cat, "$engine|$voice")
                    settings.setPreferredVoiceIdForCategory(cat, "")
                    results.add(
                        RepairResult(
                            categoryOrLang = cat,
                            oldEngine = engine,
                            oldVoice = voice,
                            newEngine = engine,
                            newVoice = null,
                            action = RepairResult.Action.BACKED_UP_AND_CLEARED
                        )
                    )
                    Log.w(TAG, "Backed up and cleared unowned $voice for $cat")
                }
            }
        }

        return results
    }

    private fun findOwningEngine(
        voiceId: String,
        catalog: EngineVoicesCatalog?,
        availableEngines: List<String>
    ): String? {
        val fromCat = catalog?.engineForVoice(voiceId)
        if (fromCat != null && fromCat in availableEngines) return fromCat

        val inferred = VoiceIdContract.inferEngineForVoice(voiceId)
        if (inferred != null && inferred in availableEngines) return inferred

        return null
    }
}
