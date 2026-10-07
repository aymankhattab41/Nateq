package com.aymankhattab.nateq.core.audio.providers

import android.content.SharedPreferences

object EngineVoiceFingerprint {
    private const val MAX_NAMES = 200
    private const val KEY_PREFIX = "fp_voices_"

    enum class Verdict {
        MATCH, MISMATCH, UNKNOWN
    }

    fun store(
        prefs: SharedPreferences,
        enginePackage: String,
        voiceNames: Set<String>
    ) {
        val names = if (voiceNames.size <= MAX_NAMES) {
            voiceNames.toSet()
        } else {
            voiceNames.take(MAX_NAMES).toSet()
        }
        val joined = names.joinToString("|")
        prefs.edit().putString(KEY_PREFIX + enginePackage, joined).apply()
    }

    fun load(prefs: SharedPreferences, enginePackage: String): Set<String> {
        val raw = prefs.getString(KEY_PREFIX + enginePackage, null)
            ?: return emptySet()
        if (raw.isEmpty()) return emptySet()
        return raw.split("|").filter { it.isNotEmpty() }.toSet()
    }

    fun isBoundToExpectedEngine(
        requestedEngine: String,
        actualVoiceNames: List<String>,
        fingerprint: Set<String>
    ): Verdict {
        if (fingerprint.isNotEmpty()) {
            val intersection = actualVoiceNames.any { actualName ->
                fingerprint.any { fpName ->
                    actualName.contains(fpName) || fpName.contains(actualName)
                }
            }
            if (intersection) return Verdict.MATCH
            val hasGoogleTag = actualVoiceNames.any {
                it.contains("com.google.android.tts")
            }
            if (hasGoogleTag && requestedEngine != "com.google.android.tts") {
                return Verdict.MISMATCH
            }
            return Verdict.MISMATCH
        }
        return Verdict.UNKNOWN
    }
}

