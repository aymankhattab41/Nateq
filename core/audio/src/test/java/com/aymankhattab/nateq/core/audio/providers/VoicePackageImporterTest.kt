package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoicePackageImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EngineStatusRegistry.clear()
        cleanStorage()
    }

    @After
    fun tearDown() {
        EngineStatusRegistry.clear()
        cleanStorage()
    }

    private fun cleanStorage() {
        val acBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        if (acBase.exists()) acBase.deleteRecursively()

        val vocBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        if (vocBase.exists()) vocBase.deleteRecursively()
    }

    private fun createZip(outFile: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
    }

    @Test
    fun importZip_detectsAcapelaPackage() = runBlocking {
        val zipFile = File(tempFolder.root, "acapela_voice.zip")
        createZip(
            zipFile,
            mapOf(
                "mehdi.inf" to "Speaker=Mehdi\nIsoCode=ara".toByteArray(),
                "mehdi.dat" to ByteArray(15_000)
            )
        )

        val result = VoicePackageImporter.importZip(
            context,
            Uri.fromFile(zipFile),
            "acapela_voice.zip"
        )

        assertTrue(result.success)
        assertEquals("Acapela", result.engineName)
        assertTrue(result.importedVoices.any { it.contains("Mehdi") })

        val status = EngineStatusRegistry.statusOf(
            AcapelaEmbeddedVoiceProvider.PROVIDER_ID
        )
        assertEquals(
            EngineStatusRegistry.State.VOICES_PRESENT,
            status?.state
        )
    }

    @Test
    fun importZip_detectsVocalizerPackage() = runBlocking {
        val zipFile = File(tempFolder.root, "vocalizer_voice.zip")
        createZip(
            zipFile,
            mapOf(
                "languages/ara/mulan.hdr" to ByteArray(100),
                "languages/ara/mulan.clsk" to ByteArray(100)
            )
        )

        val result = VoicePackageImporter.importZip(
            context,
            Uri.fromFile(zipFile),
            "vocalizer_voice.zip"
        )

        assertTrue(result.success)
        assertEquals("Vocalizer", result.engineName)
        assertTrue(result.importedVoices.contains("mulan"))

        val status = EngineStatusRegistry.statusOf(
            VocalizerEmbeddedVoiceProvider.PROVIDER_ID
        )
        assertEquals(
            EngineStatusRegistry.State.VOICES_PRESENT,
            status?.state
        )
    }

    @Test
    fun importZip_failsOnUnsupportedZipContent() = runBlocking {
        val zipFile = File(tempFolder.root, "random.zip")
        createZip(
            zipFile,
            mapOf(
                "readme.txt" to "Not a voice package".toByteArray()
            )
        )

        val result = VoicePackageImporter.importZip(
            context,
            Uri.fromFile(zipFile),
            "random.zip"
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage?.isNotBlank() == true)
    }

    @Test
    fun importZip_failsOnCorruptedZipFile() = runBlocking {
        val corruptFile = File(tempFolder.root, "corrupted.zip")
        corruptFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        val result = VoicePackageImporter.importZip(
            context,
            Uri.fromFile(corruptFile),
            "corrupted.zip"
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage?.isNotBlank() == true)
    }
}
