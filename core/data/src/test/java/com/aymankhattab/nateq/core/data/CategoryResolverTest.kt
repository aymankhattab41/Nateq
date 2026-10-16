package com.aymankhattab.nateq.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryResolverTest {

    private class FakeCategoryVoicePrefs(
        val engines: MutableMap<String, String?> = mutableMapOf(),
        val voices: MutableMap<String, String?> = mutableMapOf(),
        val rates: MutableMap<String, Float> = mutableMapOf(),
        val pitches: MutableMap<String, Float> = mutableMapOf(),
        val volumes: MutableMap<String, Float> = mutableMapOf()
    ) : CategoryVoicePrefs {
        override fun getPreferredVoiceIdForCategory(category: String): String? =
            voices[category]

        override fun setPreferredVoiceIdForCategory(
            category: String,
            voiceId: String
        ) {
            voices[category] = voiceId
        }

        override fun getEngineForCategory(category: String): String? =
            engines[category]

        override fun setEngineForCategory(category: String, engine: String?) {
            engines[category] = engine
        }

        override fun getSpeechRateForCategory(category: String): Float =
            rates[category] ?: 1.0f

        override fun setSpeechRateForCategory(category: String, rate: Float) {
            rates[category] = rate
        }

        override fun getPitchForCategory(category: String): Float =
            pitches[category] ?: 1.0f

        override fun setPitchForCategory(category: String, pitch: Float) {
            pitches[category] = pitch
        }

        override fun getVolumeForCategory(category: String): Float =
            volumes[category] ?: 1.0f

        override fun setVolumeForCategory(category: String, volume: Float) {
            volumes[category] = volume
        }

        override fun hasExplicitSpeechRateForCategory(
            category: String
        ): Boolean = rates.containsKey(category)

        override fun hasExplicitPitchForCategory(
            category: String
        ): Boolean = pitches.containsKey(category)

        override fun hasExplicitVolumeForCategory(
            category: String
        ): Boolean = volumes.containsKey(category)
    }

    @Test
    fun `resolveCategory inherits everything from default when unset`() {
        val defaultCat = SettingsRepository.VOICE_CATEGORY_DEFAULT
        val prefs = FakeCategoryVoicePrefs(
            engines = mutableMapOf(
                defaultCat to "org.nobody.multitts"
            ),
            voices = mutableMapOf(
                defaultCat to "voice_global_1"
            ),
            rates = mutableMapOf(
                defaultCat to 1.3f
            ),
            pitches = mutableMapOf(
                defaultCat to 0.9f
            ),
            volumes = mutableMapOf(
                defaultCat to 0.8f
            )
        )

        val resolved = CategoryResolver.resolveCategory(
            SettingsRepository.VOICE_CATEGORY_TIME,
            prefs
        )

        assertEquals("org.nobody.multitts", resolved.engine)
        assertEquals("voice_global_1", resolved.voiceId)
        assertEquals(1.3f, resolved.speechRate, 0.01f)
        assertEquals(0.9f, resolved.pitch, 0.01f)
        assertEquals(0.8f, resolved.volume, 0.01f)
    }

    @Test
    fun `resolveCategory overrides specific parameters while inheriting`() {
        val defaultCat = SettingsRepository.VOICE_CATEGORY_DEFAULT
        val prefs = FakeCategoryVoicePrefs(
            engines = mutableMapOf(
                defaultCat to "com.acapela.tts"
            ),
            voices = mutableMapOf(
                defaultCat to "ara-SAU-Mehdi"
            ),
            rates = mutableMapOf(
                defaultCat to 1.0f,
                SettingsRepository.VOICE_CATEGORY_BATTERY to 1.5f
            ),
            pitches = mutableMapOf(
                defaultCat to 1.0f
            ),
            volumes = mutableMapOf(
                defaultCat to 1.0f,
                SettingsRepository.VOICE_CATEGORY_BATTERY to 0.7f
            )
        )

        val resolved = CategoryResolver.resolveCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY,
            prefs
        )

        assertEquals("com.acapela.tts", resolved.engine)
        assertEquals("ara-SAU-Mehdi", resolved.voiceId)
        assertEquals(1.5f, resolved.speechRate, 0.01f)
        assertEquals(1.0f, resolved.pitch, 0.01f)
        assertEquals(0.7f, resolved.volume, 0.01f)
    }

    @Test
    fun `resolveCategory infers engine from voice name when engine unset`() {
        val defaultCat = SettingsRepository.VOICE_CATEGORY_DEFAULT
        val notifCat = SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
        val prefs = FakeCategoryVoicePrefs(
            voices = mutableMapOf(
                defaultCat to "ara-SAU-Mehdi",
                notifCat to "ar-xa-x-laila"
            )
        )

        val defaultResolved = CategoryResolver.resolveCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT,
            prefs
        )
        val notifResolved = CategoryResolver.resolveCategory(
            SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS,
            prefs
        )

        assertEquals("com.acapela.tts", defaultResolved.engine)
        assertEquals("ara-SAU-Mehdi", defaultResolved.voiceId)
        assertEquals("com.vocalizer.tts", notifResolved.engine)
        assertEquals("ar-xa-x-laila", notifResolved.voiceId)
    }

    @Test
    fun `resolveCategory never resolves to Google unless explicitly chosen`() {
        val prefs = FakeCategoryVoicePrefs(
            voices = mutableMapOf(
                SettingsRepository.VOICE_CATEGORY_DEFAULT to null
            )
        )

        val resolved = CategoryResolver.resolveCategory(
            SettingsRepository.VOICE_CATEGORY_TIME,
            prefs
        )

        assertNull(resolved.engine)
        assertNull(resolved.voiceId)
    }

    @Test
    fun `inferEngineFromVoice supports all known voice formats`() {
        assertEquals(
            "com.acapela.tts",
            CategoryResolver.inferEngineFromVoice("ara-SAU-Mehdi")
        )
        assertEquals(
            "com.acapela.tts",
            CategoryResolver.inferEngineFromVoice("ara-EGY-Salma")
        )
        assertEquals(
            "com.vocalizer.tts",
            CategoryResolver.inferEngineFromVoice("ar-xa-x-laila")
        )
        assertEquals(
            "com.vocalizer.tts",
            CategoryResolver.inferEngineFromVoice("ara-sau-maged")
        )
        assertEquals(
            "com.samsung.SMT",
            CategoryResolver.inferEngineFromVoice("ar-x-smn#female_1")
        )
        assertEquals(
            "com.google.android.tts",
            CategoryResolver.inferEngineFromVoice("ar-xa-x-isc-local")
        )
        assertEquals(
            "com.googlecode.eyesfree.espeak",
            CategoryResolver.inferEngineFromVoice("espeak:ar")
        )
        assertEquals(
            "com.acapelagroup.android.tts",
            CategoryResolver.inferEngineFromVoice(
                "com.acapelagroup.android.tts:Salma"
            )
        )
        assertEquals(
            "org.nobody.multitts",
            CategoryResolver.inferEngineFromVoice("org.nobody.multitts:ar-1")
        )
    }
}
