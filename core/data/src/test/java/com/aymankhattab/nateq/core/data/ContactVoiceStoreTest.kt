package com.aymankhattab.nateq.core.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارس ع٤١-ب10: مخزن أصوات جهات الاتصال وترميزه وحسم الأولوية.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ContactVoiceStoreTest {

    private fun store(): ContactVoiceStore =
        ContactVoiceStore.create(ApplicationProvider.getApplicationContext())

    @Test
    fun codec_roundTrips() {
        val map = mapOf(
            "+966500000000" to VoiceProfile(
                engine = "com.example.tts",
                voiceId = "ara-SAU-Salma",
                speechRate = 1.2f,
                pitch = 0.9f,
                volume = 0.8f
            )
        )
        val decoded = VoiceProfileCodec.decode(VoiceProfileCodec.encode(map))
        assertEquals(map, decoded)
    }

    @Test
    fun codec_emptyAndPartial() {
        assertTrue(VoiceProfileCodec.decode(null).isEmpty())
        val decoded = VoiceProfileCodec.decode(
            VoiceProfileCodec.encode(
                mapOf("a" to VoiceProfile(voiceId = "v"))
            )
        )
        assertEquals("v", decoded["a"]?.voiceId)
        assertNull(decoded["a"]?.speechRate)
    }

    @Test
    fun store_putFindRemove() {
        val s = store()
        s.put("+966500000000", VoiceProfile(voiceId = "v1"))
        assertEquals(
            "v1",
            s.find(listOf("0500000000", "+966500000000"))?.voiceId
        )
        assertTrue(s.remove("+966500000000"))
        assertNull(s.find(listOf("+966500000000")))
        assertFalse(s.remove("+966500000000"))
    }

    @Test
    fun resolver_contactMultipliesRateAndPitchOverCategoryAndApp() {
        val base = EffectiveVoice()
        val contact = VoiceProfile(
            voiceId = "contactVoice", speechRate = 1.5f, pitch = 1.2f
        )
        val resolved = VoiceProfileResolver.effective(
            base = base,
            category = VoiceProfile(speechRate = 1.1f, pitch = 0.9f),
            appProfile = VoiceProfile(speechRate = 1.0f),
            contact = contact
        )
        assertEquals("contactVoice", resolved.voiceId)
        // 1.1 * 1.0 * 1.5 = 1.65
        assertEquals(1.65f, resolved.speechRate!!, 0.001f)
        // 0.9 * 1.2 = 1.08
        assertEquals(1.08f, resolved.pitch!!, 0.001f)
        assertNull(resolved.volume)
    }

    @Test
    fun resolver_appProfileBetweenCategoryAndContact() {
        val base = EffectiveVoice(
            engine = null, voiceId = null,
            speechRate = 1.0f, pitch = 1.0f, volume = 1.0f
        )
        val resolved = VoiceProfileResolver.effective(
            base = base,
            category = VoiceProfile(voiceId = "cat", volume = 0.5f),
            appProfile = VoiceProfile(voiceId = "app", pitch = 1.3f),
            contact = VoiceProfile(volume = 0.2f)
        )
        assertEquals("app", resolved.voiceId)
        assertEquals(1.0f, resolved.speechRate!!, 0.001f)
        assertEquals(1.3f, resolved.pitch!!, 0.001f)
        assertEquals(0.2f, resolved.volume!!, 0.001f)
    }

    @Test
    fun resolver_engineChangeResetsVoiceIdUnlessSpecified() {
        val base = EffectiveVoice(engine = "eng1", voiceId = "v1")
        val app = VoiceProfile(engine = "eng2")
        val resolved = VoiceProfileResolver.effective(
            base = base,
            appProfile = app
        )
        assertEquals("eng2", resolved.engine)
        assertNull(resolved.voiceId)
    }
}
