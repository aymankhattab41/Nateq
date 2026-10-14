package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
    }

    @Test
    fun inspectZip_vocalizerPackage_detectsEngineAndVoice() {
        val zipFile = tempFolder.newFile("vocalizer_maged.zip")
        createMockZip(
            zipFile,
            listOf(
                "ve_pipeline_maged.hdr",
                "languages/ara/speech/components/maged.dat"
            )
        )

        val (engine, voice) = VoicePackageImporter.inspectZip(zipFile)
        assertEquals(VoicePackageImporter.ENGINE_VOCALIZER, engine)
        assertEquals("maged", voice)
    }

    /**
     * حزمة بنمط محرّك قديم (ملفات .bvcu/22k): المحرّك أُزيل فيرجع
     * `ENGINE_UNKNOWN`، لكن استكشاف اسم الصوت من أسماء الملفات باقٍ.
     */
    @Test
    fun inspectZip_legacyEngineStylePackage_reportsUnknownEngine() {
        val zipFile = tempFolder.newFile("nizar.zip")
        val base = "legacy/zIKZf7gAdYV5J6rtVR6E8l0w4EQpvmW0h48FhxjqSU="
        createMockZip(
            zipFile,
            listOf(
                "$base/logfile.txt",
                "$base/voicelist",
                "$base/nizar22k_hq/ar_sa_hd_nizar_22k_lf.bvcu.inf",
                "$base/nizar22k_hq/nizar_lf/nizar_22k_lf.bvcu"
            )
        )

        val (engine, voice) = VoicePackageImporter.inspectZip(zipFile)
        assertEquals(VoicePackageImporter.ENGINE_UNKNOWN, engine)
        assertEquals("Nizar", voice)
    }

    @Test
    fun importFromZipFile_extractsFilesAndDiscoversVoice() = runBlocking {
        val zipFile = tempFolder.newFile("vocalizer_test.zip")
        createMockZip(
            zipFile,
            listOf(
                "ve_pipeline_maged.hdr",
                "languages/ara/speech/components/maged.dat"
            )
        )

        val result = VoicePackageImporter.importFromZipFile(context, zipFile)
        assertTrue(result.isSuccess)
        assertEquals(
            VoicePackageImporter.ENGINE_VOCALIZER,
            result.engineType
        )
        assertEquals("maged", result.voiceName)
        assertNotNull(result.targetDir)
        assertTrue(result.extractedFilesCount >= 2)
    }

    @Test
    fun importFromZipFile_handlesCorruptFileGracefully() = runBlocking {
        val corruptFile = tempFolder.newFile("corrupt.zip")
        corruptFile.writeText("not a valid zip file")

        val result = VoicePackageImporter.importFromZipFile(
            context,
            corruptFile
        )
        assertFalse(result.isSuccess)
    }

    private fun createMockZip(zipFile: File, entries: List<String>) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (entryPath in entries) {
                val entry = ZipEntry(entryPath)
                zos.putNextEntry(entry)
                if (entryPath.endsWith(".inf")) {
                    val content = "speaker = \"Nizar\"\nISOCode = \"ar-SA\"\n"
                    zos.write(content.toByteArray(Charsets.UTF_8))
                } else if (!entryPath.endsWith("/")) {
                    val dummy = "dummy binary content"
                    zos.write(dummy.toByteArray(Charsets.UTF_8))
                }
                zos.closeEntry()
            }
        }
    }
}
