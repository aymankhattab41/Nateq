package com.aymankhattab.nateq.core.audio.enginehost

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ع٣٧-2: حارس مسح مجلد بيانات Vocalizer — تجميع ترويسات .hdr
 * وفهرسة ملفات .dat مع استثناء ملفات الأساس المشتركة.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataFolderScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun scan_concatenatesHdrFilesInStablePathOrder() {
        val dir = tempFolder.newFolder("VocalizerEx2")
        dir.resolve("b.hdr").writeText("<B>")
        dir.resolve("a.hdr").writeText("<A>")

        val scan = DataFolderScanner.scan(dir)

        assertEquals("<A><B>", scan.headersText)
        assertEquals(2, scan.hdrFiles.size)
        assertEquals(
            listOf("a.hdr", "b.hdr"),
            scan.hdrFiles.map { it.name }
        )
    }

    @Test
    fun scan_indexesVoiceDatAndExcludesSharedOnes() {
        val dir = tempFolder.newFolder("VocalizerEx2")
        dir.resolve("arw_laila.dat").writeText("voice")
        dir.resolve("clm.dat").writeText("shared")
        dir.resolve("lid.dat").writeText("shared")
        dir.resolve("synth_med_fxd_bet3f22.dat").writeText("shared")
        dir.resolve("sysdct.dat").writeText("shared")

        val scan = DataFolderScanner.scan(dir)

        assertTrue("arw_laila.dat" in scan.datFiles)
        assertFalse("clm.dat" in scan.datFiles)
        assertFalse("lid.dat" in scan.datFiles)
        assertFalse("synth_med_fxd_bet3f22.dat" in scan.datFiles)
        assertFalse("sysdct.dat" in scan.datFiles)
    }

    @Test
    fun scan_isRecursiveAcrossSubDirectories() {
        val dir = tempFolder.newFolder("VocalizerEx2")
        val sub = dir.resolve("ar").also { it.mkdirs() }
        dir.resolve("root.hdr").writeText("<R>")
        sub.resolve("nested.hdr").writeText("<N>")
        sub.resolve("voice.dat").writeText("v")

        val scan = DataFolderScanner.scan(dir)

        assertEquals("<R><N>".length, scan.headersText.length)
        assertTrue(scan.headersText.contains("<R>"))
        assertTrue(scan.headersText.contains("<N>"))
        assertTrue("voice.dat" in scan.datFiles)
    }

    @Test
    fun scan_missingDirectory_returnsEmptyResult() {
        val scan = DataFolderScanner.scan(
            java.io.File("/no/such/vocalizer/dir")
        )

        assertEquals("", scan.headersText)
        assertTrue(scan.datFiles.isEmpty())
        assertTrue(scan.hdrFiles.isEmpty())
    }
}
