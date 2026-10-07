package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nuance.android.vocalizer.VocalizerAudioSettings
import com.nuance.android.vocalizer.VocalizerVersion
import com.nuance.android.vocalizer.VocalizerVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class VocalizerEmbeddedVoiceProviderTest {

    private lateinit var context: Context
    private lateinit var provider: VocalizerEmbeddedVoiceProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = VocalizerEmbeddedVoiceProvider(context)
    }

    @Test
    fun testProviderMetadata() {
        assertEquals("vocalizer_embedded", provider.providerId)
        assertTrue(provider.displayName.contains("Vocalizer"))
    }

    @Test
    fun testVocalizerVoiceModel() {
        val voice = VocalizerVoice()
        assertNotNull(voice)
        assertEquals(22050, voice.sampleRate)
    }

    @Test
    fun testVocalizerAudioSettings() {
        val settings = VocalizerAudioSettings(
            22050,
            VocalizerAudioSettings.MONO
        )
        assertEquals(22050, settings.mRate)
        assertEquals(VocalizerAudioSettings.MONO, settings.mChannels)
        assertEquals(false, settings.isStereo)
    }

    @Test
    fun testVocalizerVersion() {
        val version = VocalizerVersion()
        version.mMajor = 2
        version.mMinor = 2
        version.mBuild = 0
        assertTrue(version.toString().startsWith("2.2.0"))
    }
}
