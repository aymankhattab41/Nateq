package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VocalizerDownloaderTest {

    private lateinit var context: Context
    private lateinit var testDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDir = File(context.filesDir, "VocalizerEx2")
        testDir.mkdirs()
    }

    private fun createTestZip(
        file: File,
        entries: Map<String, ByteArray>
    ) {
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
    }

    @Test
    fun getVocalizerBaseDir_createsDirAndReturnsValidDirectory() {
        val dir = VocalizerDownloader.getVocalizerBaseDir(context)
        assertNotNull(dir)
        assertTrue(dir.exists())
    }

    @Test
    fun catalog_containsArabicAndEnglishVoices() {
        val items = VocalizerModelCatalog.ITEMS
        assertTrue(items.isNotEmpty())

        val arabicVoices = items.filter { it.languageCategory == "ar" }
        assertTrue(arabicVoices.size >= 4)
        assertTrue(arabicVoices.any { it.voiceName == "Laila" })
        assertTrue(arabicVoices.any { it.voiceName == "Maged" })
        assertTrue(arabicVoices.any { it.voiceName == "Tarik" })
        assertTrue(arabicVoices.any { it.voiceName == "Mariam" })

        val englishVoices = items.filter { it.languageCategory == "en" }
        assertTrue(englishVoices.isNotEmpty())
        assertTrue(englishVoices.any { it.voiceName == "Ava" })
        assertTrue(englishVoices.any { it.voiceName == "Samantha" })
        assertTrue(englishVoices.any { it.voiceName == "Tom" })
    }

    @Test
    fun catalog_arabicVoicesFollowUaeRegionConvention() {
        // معيار التطبيق للعربية الناطقية الجهوية هو ar-AE (كما في
        // AnnouncementSpeaker.resolveFallbackLocale) لا ar-SA.
        val arabic = VocalizerModelCatalog.ITEMS.filter {
            it.languageCategory == "ar"
        }
        assertTrue(arabic.isNotEmpty())
        for (item in arabic) {
            assertEquals("ar", item.locale.language)
            assertEquals("AE", item.locale.country)
        }
    }

    @Test
    fun isVoiceInstalled_detectsPresenceOfHeaderFile() {
        val lailaItem = VocalizerModelCatalog.ITEMS.first {
            it.id == "vocalizer-ara-sau-laila-compact"
        }

        assertFalse(VocalizerModelCatalog.isVoiceInstalled(testDir, lailaItem))
        assertFalse(VocalizerModelCatalog.isVoiceInstalled(context, lailaItem))

        val dummyHdr = File(testDir, lailaItem.headerFileName)
        dummyHdr.writeText("DUMMY_HDR_CONTENT")

        assertTrue(VocalizerModelCatalog.isVoiceInstalled(testDir, lailaItem))
        assertTrue(VocalizerModelCatalog.isVoiceInstalled(context, lailaItem))
    }

    @Test
    fun installedHeaderFile_acceptsDifferentVersionOfSameFamily() {
        // الكتالوج يتوقّع عادةً رقم إصدار يخالف ما في الحزمة المنزّلة
        // (مثلاً ..._2-0-0.hdr في الكتالوج و..._1-0-0.hdr في الأرشيف)؛
        // فيجب أن يُقبل الرأس بالعائلة لا بالتطابق الحرفي وإلا بدا
        // التحميل ناقصاً وفشل التثبيت.
        val tarik = VocalizerModelCatalog.ITEMS.first {
            it.id == "vocalizer-ara-sau-tarik-compact"
        }
        assertTrue(tarik.headerFileName.endsWith("_2-0-0.hdr"))

        val actualName =
            "ve_pipeline_arw_tarik_22_embedded-compact_1-0-0.hdr"
        val actual = File(testDir, actualName)
        actual.writeText("DUMMY_HDR_CONTENT")

        assertTrue(VocalizerModelCatalog.isVoiceInstalled(testDir, tarik))
        assertEquals(
            actualName,
            VocalizerModelCatalog.installedHeaderFile(testDir, tarik)?.name
        )
    }

    @Test
    fun isVoiceInstalled_rejectsHeaderOfAnotherVoice() {
        val tarik = VocalizerModelCatalog.ITEMS.first {
            it.id == "vocalizer-ara-sau-tarik-compact"
        }
        val otherVoice = File(
            testDir,
            "ve_pipeline_arw_maged_22_embedded-compact_1-0-0.hdr"
        )
        otherVoice.writeText("DUMMY_HDR_CONTENT")

        assertFalse(VocalizerModelCatalog.isVoiceInstalled(testDir, tarik))
        assertNull(VocalizerModelCatalog.installedHeaderFile(testDir, tarik))
    }

    @Test
    fun deleteVoice_removesHeaderAndDatFiles() {
        val lailaItem = VocalizerModelCatalog.ITEMS.first {
            it.id == "vocalizer-ara-sau-laila-compact"
        }

        val dummyHdr = File(testDir, lailaItem.headerFileName)
        dummyHdr.writeText("DUMMY_HDR")
        val dummyDat = File(testDir, "arw_laila_test.dat")
        dummyDat.writeText("DUMMY_DAT")

        assertTrue(dummyHdr.exists())
        assertTrue(dummyDat.exists())

        val deleted = VocalizerDownloader.deleteVoice(context, lailaItem)
        assertTrue(deleted)
        assertFalse(dummyHdr.exists())
        assertFalse(dummyDat.exists())
    }

    @Test
    fun unzipToDirectory_extractsValidFilesAndReturnsResults() {
        val zipFile = File(context.cacheDir, "test_valid.zip")
        createTestZip(
            zipFile,
            mapOf(
                "voice.hdr" to "HEADER".toByteArray(),
                "voice.dat" to "DATA".toByteArray()
            )
        )

        val target = File(context.cacheDir, "out_valid")
        target.mkdirs()
        try {
            val res = VocalizerDownloader.unzipToDirectory(zipFile, target)
            assertEquals(2, res.count)
            assertTrue(res.fileNames.contains("voice.hdr"))
            assertTrue(res.fileNames.contains("voice.dat"))
            assertTrue(File(target, "voice.hdr").exists())
            assertTrue(File(target, "voice.dat").exists())
        } finally {
            zipFile.delete()
            target.deleteRecursively()
        }
    }

    @Test
    fun unzipToDirectory_emptyZipReturnsZeroCount() {
        val zipFile = File(context.cacheDir, "test_empty.zip")
        createTestZip(zipFile, emptyMap())

        val target = File(context.cacheDir, "out_empty")
        target.mkdirs()
        try {
            val res = VocalizerDownloader.unzipToDirectory(zipFile, target)
            assertEquals(0, res.count)
            assertTrue(res.fileNames.isEmpty())
        } finally {
            zipFile.delete()
            target.deleteRecursively()
        }
    }

    @Test
    fun unzipToDirectory_rejectsZipSlipTraversal() {
        val zipFile = File(context.cacheDir, "test_slip.zip")
        createTestZip(
            zipFile,
            mapOf(
                "../evil.txt" to "EVIL".toByteArray(),
                "safe.dat" to "SAFE".toByteArray()
            )
        )

        val target = File(context.cacheDir, "out_slip")
        target.mkdirs()
        try {
            val res = VocalizerDownloader.unzipToDirectory(zipFile, target)
            assertEquals(1, res.count)
            assertTrue(res.fileNames.contains("safe.dat"))
            assertFalse(File(target.parentFile, "evil.txt").exists())
        } finally {
            zipFile.delete()
            target.deleteRecursively()
        }
    }
}
