package com.aymankhattab.nateq.settings

import com.aymankhattab.nateq.util.VoiceIdContract

/** ع٤٠-2: منطق نقي لكشف أزواج (محرك، صوت) غير المتسقة في لغة واحدة. */
object EngineLanguageConsistency {

    fun mismatchedLanguages(
        entries: List<Triple<String, String?, String?>>,
    ): List<Triple<String, String, String>> =
        entries.mapNotNull { (lang, engine, voice) ->
        if (engine.isNullOrEmpty() || voice.isNullOrEmpty()) {
            null
        } else if (!VoiceIdContract.isVoiceBelongingToEngine(engine, voice)) {
            Triple(lang, voice, engine)
        } else {
            null
        }
    }
}
