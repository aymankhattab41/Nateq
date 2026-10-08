package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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
}
