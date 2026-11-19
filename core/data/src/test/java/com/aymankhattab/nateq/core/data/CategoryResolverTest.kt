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
    fun `resolveCategory uses category values without default inheritance`() {
        val prefs = FakeCategoryVoicePrefs(
            engines = mutableMapOf(
                SettingsRepository.VOICE_CATEGORY_TIME to "org.nobody.multitts"
            ),
            voices = mutableMapOf(
                SettingsRepository.VOICE_CATEGORY_TIME to "voice_time_1"
            ),
            rates = mutableMapOf(
                SettingsRepository.VOICE_CATEGORY_TIME to 1.3f
            ),
            pitches = mutableMapOf(
                SettingsRepository.VOICE_CATEGORY_TIME to 0.9f
            ),
            volumes = mutableMapOf(
                SettingsRepository.VOICE_CATEGORY_TIME to 0.8f
            )
        )

        val resolved = CategoryResolver.resolveCategory(
            SettingsRepository.VOICE_CATEGORY_TIME,
            prefs
        )

        assertEquals("org.nobody.multitts", resolved.engine)
        assertEquals("voice_time_1", resolved.voiceId)
        assertEquals(1.3f, resolved.speechRate, 0.01f)
        assertEquals(0.9f, resolved.pitch, 0.01f)
        assertEquals(0.8f, resolved.volume, 0.01f)
    }

    @Test
    fun `resolveCategory keeps unset category independent from legacy default`() {
        val defaultCat = SettingsRepository.VOICE_CATEGORY_DEFAULT
        val prefs = FakeCategoryVoicePrefs(
            engines = mutableMapOf(defaultCat to "com.acapela.tts"),
            voices = mutableMapOf(defaultCat to "ara-SAU-Mehdi"),
            rates = mutableMapOf(defaultCat to 1.5f),
            pitches = mutableMapOf(defaultCat to 0.7f),
            volumes = mutableMapOf(defaultCat to 0.6f)
        )

        val resolved = CategoryResolver.resolveCategory(
            SettingsRepository.VOICE_CATEGORY_TIME,
            prefs
        )

        assertNull(resolved.engine)
        assertNull(resolved.voiceId)
        assertEquals(1.0f, resolved.speechRate, 0.01f)
        assertEquals(1.0f, resolved.pitch, 0.01f)
        assertEquals(1.0f, resolved.volume, 0.01f)
    }

    @Test
    fun `resolveCategory resolves explicit category and infers engine`() {
        val notifCat = SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
        val prefs = FakeCategoryVoicePrefs(
            voices = mutableMapOf(notifCat to "ar-xa-x-laila"),
            rates = mutableMapOf(notifCat to 1.5f),
            volumes = mutableMapOf(notifCat to 0.7f)
        )

        val resolved = CategoryResolver.resolveCategory(notifCat, prefs)

        assertEquals("com.vocalizer.tts", resolved.engine)
        assertEquals("ar-xa-x-laila", resolved.voiceId)
        assertEquals(1.5f, resolved.speechRate, 0.01f)
        assertEquals(1.0f, resolved.pitch, 0.01f)
        assertEquals(0.7f, resolved.volume, 0.01f)
    }

    @Test
    fun `resolveCategory never resolves to Google unless explicitly chosen`() {
        val prefs = FakeCategoryVoicePrefs()

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
            "acapela_embedded",
            CategoryResolver.inferEngineFromVoice("Mehdi (العربية)")
        )
        assertEquals(
            "acapela_embedded",
            CategoryResolver.inferEngineFromVoice("acapela_embedded:Salma")
        )
        assertEquals(
            "acapela_embedded",
            CategoryResolver.inferEngineFromVoice("Salma") { voice ->
                if (voice == "Salma") "acapela_embedded" else null
            }
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
