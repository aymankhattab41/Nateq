package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class SherpaOnnxVoiceProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var provider: SherpaOnnxVoiceProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = SherpaOnnxVoiceProvider(context)
    }

    @Test
    fun testProviderMetadata() {
        assertEquals("sherpa_onnx_embedded", provider.providerId)
        assertTrue(provider.displayName.contains("Sherpa-ONNX"))
    }

    @Test
    fun testNotConfiguredWhenNoModels() {
        val emptyDir = tempFolder.newFolder("empty_models")
        val emptyProvider = SherpaOnnxVoiceProvider(context, emptyDir)
        assertFalse(emptyProvider.isConfigured())
    }

    @Test
    fun testModelDiscoveryAndListVoices() = runBlocking {
        val modelsDir = tempFolder.newFolder("models")
        val modelSubDir = File(modelsDir, "vits-piper-ar_JO-kareem-medium")
        modelSubDir.mkdirs()

        File(modelSubDir, "ar_JO-kareem-medium.onnx").writeText("dummy_onnx")
        File(modelSubDir, "tokens.txt").writeText("dummy_tokens")

        val customProvider = SherpaOnnxVoiceProvider(context, modelsDir)
        assertTrue(customProvider.isConfigured())

        val models = customProvider.discoverModels()
        assertEquals(1, models.size)
        assertEquals("ar_JO-kareem-medium", models[0].displayName)
        assertEquals(Locale.forLanguageTag("ar-JO"), models[0].locale)

        val arVoices = customProvider.listVoices(Locale.forLanguageTag("ar"))
        assertEquals(1, arVoices.size)
        assertEquals(
            "sherpa_vits-piper-ar_jo-kareem-medium",
            arVoices[0].id
        )

        val enVoices = customProvider.listVoices(Locale.forLanguageTag("en"))
        assertEquals(0, enVoices.size)
    }

    @Test
    fun testPiperMultipleModelsDirectDiscovery() = runBlocking {
        val piperDir = tempFolder.newFolder("Piper")

        File(piperDir, "ar_JO-kareem-medium.onnx").writeText("ar_onnx")
        File(piperDir, "ar_JO-kareem-medium.tokens.txt").writeText("ar_tokens")

        File(piperDir, "en_US-lessac-high.onnx").writeText("en_onnx")
        File(piperDir, "en_US-lessac-high.tokens.txt").writeText("en_tokens")

        val provider = SherpaOnnxVoiceProvider(context, piperDir)
        assertTrue(provider.isConfigured())

        val models = provider.discoverModels()
        assertEquals(2, models.size)

        val arVoices = provider.listVoices(Locale.forLanguageTag("ar"))
        assertEquals(1, arVoices.size)
        assertEquals("ar_JO-kareem-medium", arVoices[0].displayName)

        val enVoices = provider.listVoices(Locale.forLanguageTag("en"))
        assertEquals(1, enVoices.size)
        assertEquals("en_US-lessac-high", enVoices[0].displayName)
    }
}
