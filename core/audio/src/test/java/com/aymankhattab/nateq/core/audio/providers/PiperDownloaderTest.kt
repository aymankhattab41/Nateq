package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class PiperDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testCatalogItemsContainArabicAndEnglish() {
        val items = PiperModelCatalog.ITEMS
        assertTrue(items.isNotEmpty())

        val arabic = items.filter { it.languageCategory == "ar" }
        assertTrue(arabic.isNotEmpty())
        assertTrue(
            arabic.any { it.id == "vits-piper-ar_JO-kareem-medium" }
        )

        val english = items.filter { it.languageCategory == "en" }
        assertTrue(english.isNotEmpty())
        assertTrue(
            english.any { it.id == "vits-piper-en_US-lessac-medium" }
        )
    }

    @Test
    fun testCatalogUrls() {
        val item = PiperModelCatalog.ITEMS.first {
            it.id == "vits-piper-ar_JO-kareem-medium"
        }
        val onnxUrl = item.getOnnxUrl()
        val tokensUrl = item.getTokensUrl()

        assertTrue(onnxUrl.contains("huggingface.co"))
        assertTrue(onnxUrl.endsWith(".onnx"))
        assertTrue(tokensUrl.endsWith("tokens.txt"))
    }

    @Test
    fun testIsModelInstalledCheck() {
        val baseDir = tempFolder.newFolder("Piper")
        val item = PiperModelCatalog.ITEMS.first()

        assertFalse(PiperModelCatalog.isModelInstalled(baseDir, item))

        val modelDir = File(baseDir, item.modelDirName)
        modelDir.mkdirs()
        val onnx = File(modelDir, item.modelFileName)
        val tokens = File(modelDir, item.tokensFileName)

        onnx.writeBytes(ByteArray(2048))
        tokens.writeBytes(ByteArray(100))

        assertTrue(PiperModelCatalog.isModelInstalled(baseDir, item))
    }

    @Test
    fun testDeleteVoice() {
        val baseDir = PiperDownloader.getPiperBaseDir(context)
        val item = PiperModelCatalog.ITEMS.first()

        val modelDir = File(baseDir, item.modelDirName)
        modelDir.mkdirs()
        File(modelDir, item.modelFileName).writeBytes(ByteArray(100))

        assertTrue(modelDir.exists())
        val deleted = PiperDownloader.deleteVoice(context, item)
        assertTrue(deleted)
        assertFalse(modelDir.exists())
    }
}
