package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارس عقد التخزين الموحّد (ع٢٩-3): المجلد الأساسي تحت filesDir
 * دائماً، الهجرة مرة واحدة، ورفض zip-slip وسقف الاستخراج.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class EmbeddedStorageTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun baseDir_isAlwaysUnderFilesDir() {
        for (engine in EmbeddedStorage.Engine.entries) {
            val base = EmbeddedStorage.baseDir(context, engine)
            assertTrue(
                "$base must live under ${context.filesDir}",
                base.absolutePath.startsWith(
                    context.filesDir.absolutePath + File.separator
                )
            )
            assertTrue(base.isDirectory || base.mkdirs())
        }
    }

    @Test
    fun allSearchDirs_startsWithBaseDirAndHasNoRawSdcardPath() {
        val dirs = EmbeddedStorage.allSearchDirs(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        assertEquals(
            EmbeddedStorage.baseDir(
                context,
                EmbeddedStorage.Engine.VOCALIZER
            ),
            dirs.first()
        )
        for (dir in dirs) {
            assertFalse(
                "raw /sdcard path leaked: $dir",
                dir.absolutePath.startsWith("/sdcard")
            )
            assertFalse(
                "raw /storage path leaked: $dir",
                dir.absolutePath.startsWith("/storage/emulated")
            )
        }
    }

    @Test
    fun migration_movesLegacyExternalFilesIntoInternalOnce() {
        val engine = EmbeddedStorage.Engine.VOCALIZER
        val extRoot = context.getExternalFilesDir(null)
            ?: error("external files dir unavailable")
        val legacy = File(extRoot, "VocalizerEx2")
        legacy.mkdirs()
        val legacyFile = File(legacy, "voice_dat_sample.dat")
        legacyFile.writeText("LEGACY")

        assertTrue(
            "migration must be pending before it runs",
            EmbeddedStorage.needsMigration(context, engine)
        )

        val moved = EmbeddedStorage.migrateExternalToInternal(
            context,
            engine
        )
        assertTrue("expected at least one moved entry", moved >= 1)

        val target = File(
            EmbeddedStorage.baseDir(context, engine),
            "voice_dat_sample.dat"
        )
        assertTrue(
            "file must land under filesDir: $target",
            target.exists()
        )
        assertEquals("LEGACY", target.readText())
        assertFalse(
            "legacy external file must be gone",
            legacyFile.exists()
        )
        assertFalse(
            "no second migration should be needed",
            EmbeddedStorage.needsMigration(context, engine)
        )
        assertEquals(
            "second run must move nothing",
            0,
            EmbeddedStorage.migrateExternalToInternal(context, engine)
        )
    }

    @Test
    fun bumpVersion_raisesStorageVersion() {
        val before = EmbeddedStorage.version
        EmbeddedStorage.bumpVersion()
        assertTrue(EmbeddedStorage.version > before)
    }

    @Test
    fun unzip_rejectsZipSlipEntries() {
        val zip = File.createTempFile("zipslip", ".zip")
        val evil = File.createTempFile("outside", ".txt").apply {
            delete()
        }
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("../${evil.name}"))
            zos.write("EVIL".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("safe.dat"))
            zos.write("SAFE".toByteArray())
            zos.closeEntry()
        }

        val target = File.createTempFile("target", "").apply {
            delete()
            mkdirs()
        }
        VocalizerDownloader.unzipToDirectory(zip, target)

        assertFalse(
            "zip-slip entry must not escape the target dir",
            evil.exists()
        )
        assertTrue(File(target, "safe.dat").exists())
        assertTrue(
            "safe entry must be written",
            File(target, "safe.dat").readText() == "SAFE"
        )
    }

    @Test
    fun unzip_enforcesExtractionSizeCap() {
        val zip = File.createTempFile("big", ".zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("big.dat"))
            zos.write(ByteArray(64 * 1024))
            zos.closeEntry()
        }

        val target = File.createTempFile("target", "").apply {
            delete()
            mkdirs()
        }

        val thrown = try {
            VocalizerDownloader.unzipToDirectory(
                zip,
                target,
                maxBytes = 1024
            )
            null
        } catch (t: IllegalStateException) {
            t
        }
        assertTrue(
            "size cap must abort extraction",
            thrown?.message?.contains("Extraction exceeds") == true
        )
    }
}
