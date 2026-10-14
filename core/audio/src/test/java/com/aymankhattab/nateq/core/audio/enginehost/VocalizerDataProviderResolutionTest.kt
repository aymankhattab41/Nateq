package com.aymankhattab.nateq.core.audio.enginehost

import com.aymankhattab.nateq.core.audio.providers.VocalizerCfDecryptor
import com.nuance.android.vocalizer.VocalizerEngine
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ع٣٧-3: حارس تسطيح أسماء الأصول ومفتاح ملفات .dat الخارجية
 * في [VocalizerDataProvider] (المسار الذي يمرّ به openAssetFile).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VocalizerDataProviderResolutionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() {
        VocalizerDataProvider.closeAll()
    }

    @Test
    fun flattenAssetName_matchesOriginalNuanceRules() {
        assertEquals(
            "clm.xmf",
            VocalizerDataProvider.flattenAssetName("vocalizer/clm")
        )
        assertEquals(
            "synth_med_fxd_bet3f22.xmf",
            VocalizerDataProvider.flattenAssetName(
                "vocalizer/synth/med/fxd/bet3f22"
            )
        )
        assertEquals(
            "rex_rur.xmf",
            VocalizerDataProvider.flattenAssetName("vocalizer/rex/rur")
        )
        assertEquals(
            "sysdct.xmf",
            VocalizerDataProvider.flattenAssetName("vocalizer/sysdct")
        )
        assertEquals(
            "clm.xmf",
            VocalizerDataProvider.flattenAssetName("clm")
        )
        assertEquals(
            "clm.xmf",
            VocalizerDataProvider.flattenAssetName("vocalizer/clm.dat")
        )
    }

    @Test
    fun engineDelegatesFlattenToProvider() {
        assertEquals(
            "clm.xmf",
            VocalizerEngine.flattenAssetName("vocalizer/clm")
        )
    }

    @Test
    fun openDataFile_normalizesKeyAndTracksFd() {
        val dir = tempFolder.newFolder("VocalizerEx2")
        val voice = dir.resolve("arw_laila.dat")
        voice.writeText("voice-data")

        val scan = DataFolderScanner.scan(dir)
        VocalizerDataProvider.init(scan.datFiles)

        val info = VocalizerDataProvider.openDataFile("arw_laila")
        assertNotNull(info)
        assertTrue(info!!.descriptor >= 0)
        assertEquals(voice.length(), info.length)
        assertTrue(VocalizerDataProvider.hasOpenFile(info.descriptor))

        VocalizerDataProvider.closeAssetFile(info.descriptor)
        assertFalse(VocalizerDataProvider.hasOpenFile(info.descriptor))
    }

    @Test
    fun openDataFile_acceptsNameWithDatExtensionAndSlash() {
        val dir = tempFolder.newFolder("VocalizerEx2")
        dir.resolve("arw_laila.dat").writeText("voice-data")

        VocalizerDataProvider.init(
            DataFolderScanner.scan(dir).datFiles
        )

        val withExt =
            VocalizerDataProvider.openDataFile("arw_laila.dat")
        assertNotNull(withExt)
        VocalizerDataProvider.closeAssetFile(withExt!!.descriptor)
    }

    @Test
    fun openDataFile_rejectsExcludedAndUnknownNames() {
        val dir = tempFolder.newFolder("VocalizerEx2")
        dir.resolve("clm.dat").writeText("shared")

        VocalizerDataProvider.init(
            DataFolderScanner.scan(dir).datFiles
        )

        assertNull(VocalizerDataProvider.openDataFile("clm"))
        assertNull(VocalizerDataProvider.openDataFile("vocalizer/clm"))
        assertNull(VocalizerDataProvider.openDataFile("missing_voice"))
        assertNull(VocalizerDataProvider.openDataFile(null))
    }

    @Test
    fun openDataFile_decryptsCfEncryptedVoiceIntoCache() {
        val dir = tempFolder.newFolder("vdata_enc")
        val cacheDir = tempFolder.newFolder("cache_plain")

        val plain = buildPlain()
        val cipher = plain.copyOf()
        VocalizerCfDecryptor.xorInPlace(cipher, 0, cipher.size)
        assertTrue(VocalizerCfDecryptor.isEncrypted(cipher, 4))

        dir.resolve("arw_laila_embedded-high_2-0-1.dat")
            .writeBytes(cipher)

        val provider = VocalizerDataProvider(
            DataFolderScanner.scan(dir).datFiles, cacheDir
        )

        val info = provider.openDataFile(
            "arw_laila_embedded-high_2-0-1"
        )
        assertNotNull(info)
        assertEquals(plain.size.toLong(), info!!.length)

        val decrypted = cacheDir.listFiles()
            ?.firstOrNull { it.name.endsWith(".sv") }
        assertNotNull("لم يُكتب ملف مفكوك في الكاش", decrypted)
        assertEquals(plain.size.toLong(), decrypted!!.length())
        assertArrayEquals(
            VocalizerCfDecryptor.SV_MAGIC,
            decrypted.readBytes().copyOf(4)
        )

        provider.closeAssetFile(info.descriptor)
    }

    @Test
    fun openDataFile_leavesAlreadyPlainVoiceUntouched() {
        val dir = tempFolder.newFolder("vdata_plain")
        val cacheDir = tempFolder.newFolder("cache_plain_unused")

        val plain = buildPlain()
        dir.resolve("arw_maged_embedded-high_2-0-1.dat")
            .writeBytes(plain)

        val provider = VocalizerDataProvider(
            DataFolderScanner.scan(dir).datFiles, cacheDir
        )

        val info = provider.openDataFile(
            "arw_maged_embedded-high_2-0-1"
        )
        assertNotNull(info)
        assertEquals(plain.size.toLong(), info!!.length)
        assertTrue(cacheDir.listFiles().isNullOrEmpty())

        provider.closeAssetFile(info.descriptor)
    }

    private fun buildPlain(): ByteArray {
        val size = 5000
        val b = ByteArray(size)
        System.arraycopy(VocalizerCfDecryptor.SV_MAGIC, 0, b, 0, 4)
        for (i in 4 until size) {
            b[i] = (i * 31 + 7).toByte()
        }
        return b
    }
}
