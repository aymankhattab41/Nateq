package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AcapelaVoiceImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EngineStatusRegistry.clear()
        val acBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        if (acBase.exists()) {
            acBase.deleteRecursively()
        }
    }

    @After
    fun tearDown() {
        EngineStatusRegistry.clear()
        val acBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        if (acBase.exists()) {
            acBase.deleteRecursively()
        }
    }

    @Test
    fun importFromDirectory_copiesValidVoiceAndUpdatesRegistry() = runBlocking {
        val srcDir = tempFolder.newFolder("mehdi_source")
        // تهيئة أصلية متوافقة مطلوبة للاكتشاف/الاكتمال (عقد ini الأصيل).
        File(srcDir, "mehdi.ini").writeText(
            "PRO \"NLP/ar.oso\"\n" +
                "SLV \"mehdi.bvcu\"\n" +
                "VOICE \"Mehdi\"\n"
        )
        File(srcDir, "mehdi.inf").writeText("Speaker=Mehdi\nIsoCode=ara")
        File(srcDir, "mehdi.dat").writeBytes(ByteArray(15_000))

        val result = AcapelaVoiceImporter.importFromDirectory(context, srcDir)
        assertTrue(result.success)
        assertEquals(1, result.importedVoices.size)
        assertTrue(result.importedVoices.first().contains("Mehdi"))

        val status = EngineStatusRegistry.statusOf(
            AcapelaEmbeddedVoiceProvider.PROVIDER_ID
        )
        assertEquals(
            EngineStatusRegistry.State.VOICES_PRESENT,
            status?.state
        )
    }

    @Test
    fun importFromDirectory_failsOnEmptySource() = runBlocking {
        val emptyDir = tempFolder.newFolder("empty_source")
        val result = AcapelaVoiceImporter.importFromDirectory(context, emptyDir)
        assertFalse(result.success)
        assertTrue(result.errorMessage?.isNotBlank() == true)
    }

    @Test
    fun importFromZip_twice_yieldsOneVoiceDirAndOneVoice() = runBlocking {
        val zipFile = File(tempFolder.root, "salma.zip")
        java.util.zip.ZipOutputStream(
            java.io.FileOutputStream(zipFile)
        ).use { zos ->
            // تهيئة أصلية متوافقة مطلوبة للاكتشاف/الاكتمال (عقد ini الأصيل).
            zos.putNextEntry(java.util.zip.ZipEntry("salma/salma.ini"))
            zos.write(
                ("PRO \"NLP/ar.oso\"\n" +
                    "SLV \"salma.bvcu\"\n" +
                    "VOICE \"Salma\"\n").toByteArray()
            )
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("salma/salma.inf"))
            zos.write(
                "Speaker=Salma\nIsoCode=ara\nfreq=22050".toByteArray()
            )
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("salma/salma.dat"))
            zos.write(ByteArray(15_000))
            zos.closeEntry()
        }

        val uri = android.net.Uri.fromFile(zipFile)
        val res1 = AcapelaVoiceImporter.importFromZip(
            context,
            uri,
            "Salma.zip"
        )
        assertTrue(res1.success)

        val res2 = AcapelaVoiceImporter.importFromZip(
            context,
            uri,
            "Salma.zip"
        )
        assertTrue(res2.success)

        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        val voiceDirs = baseDir.listFiles { f ->
            f.isDirectory
        } ?: emptyArray()
        assertEquals(1, voiceDirs.size)

        val voices = AcapelaVoiceManifest.listVoices(baseDir)
        assertEquals(1, voices.size)
        assertEquals("Salma", voices.first().voiceName)
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var r: Int
            while (input.read(buf).also { r = it } != -1) {
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun rollbackAcapela_restoresPreviousVoiceAndDeletesNewTargets() {
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        baseDir.mkdirs()

        val oldVoiceDir = File(baseDir, "mehdi")
        oldVoiceDir.mkdirs()
        val oldIni = File(oldVoiceDir, "mehdi.ini")
        val oldInf = File(oldVoiceDir, "mehdi.inf")
        val oldDat = File(oldVoiceDir, "mehdi.dat")
        oldIni.writeText(
            "PRO \"NLP/ar.oso\"\nSLV \"mehdi.bvcu\"\nVOICE \"Mehdi\"\n"
        )
        oldInf.writeText("Speaker=Mehdi\nIsoCode=ara")
        oldDat.writeBytes(ByteArray(15_000) { 1 })

        val iniSha = sha256(oldIni)
        val infSha = sha256(oldInf)
        val datSha = sha256(oldDat)

        val backupDir = File(baseDir, ".backup_acapela_test_sess")
        backupDir.mkdirs()
        val backupVoiceDir = File(backupDir, "mehdi")
        backupVoiceDir.mkdirs()
        oldIni.copyTo(File(backupVoiceDir, "mehdi.ini"))
        oldInf.copyTo(File(backupVoiceDir, "mehdi.inf"))
        oldDat.copyTo(File(backupVoiceDir, "mehdi.dat"))

        // محاكاة كتابة بيانات تالفة فوق القديم وإنشاء صوت جديد غير مكتمل
        oldDat.writeBytes(ByteArray(500) { 9 })
        val newVoiceDir = File(baseDir, "broken_new_voice")
        newVoiceDir.mkdirs()
        File(newVoiceDir, "corrupted.bin").writeText("BadData")

        val journalFile = File(baseDir, ".journal_acapela_test_sess.json")
        journalFile.writeText("{}")

        // تنفيذ التراجع
        AcapelaVoiceImporter.rollbackAcapela(
            baseDir = baseDir,
            createdTargets = listOf(newVoiceDir),
            backups = listOf(oldVoiceDir to backupVoiceDir),
            backupDir = backupDir,
            journalFile = journalFile
        )

        // التحقق من حذف الصوت الجديد غير المكتمل
        assertFalse(newVoiceDir.exists())

        // التحقق من استعادة الصوت القديم كاملاً ببصمة SHA-256 متطابقة
        assertTrue(oldVoiceDir.exists())
        assertEquals(iniSha, sha256(oldIni))
        assertEquals(infSha, sha256(oldInf))
        assertEquals(datSha, sha256(oldDat))

        // التحقق من تنظيف السجل والنسخ الاحتياطية
        assertFalse(backupDir.exists())
        assertFalse(journalFile.exists())
    }

    @Test
    fun recoverInterruptedTransactions_cleansCrashedJournalInAcapela() {
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        baseDir.mkdirs()

        val liveVoice = File(baseDir, "leila")
        liveVoice.mkdirs()
        val liveDat = File(liveVoice, "leila.dat")
        liveDat.writeBytes(ByteArray(10_000) { 7 })
        val expectedSha = sha256(liveDat)

        val backupDir = File(baseDir, ".backup_acapela_crashed")
        val backupVoice = File(backupDir, "leila")
        backupVoice.mkdirs()
        liveDat.copyTo(File(backupVoice, "leila.dat"))

        // تلويث الحي وإنشاء مسار غير مكتمل
        liveDat.writeBytes(ByteArray(100) { 0 })
        val halfVoice = File(baseDir, "half_voice")
        halfVoice.mkdirs()
        File(halfVoice, "half.dat").writeText("half")

        val journalFile = File(baseDir, ".journal_acapela_crashed.json")
        val journalJson = org.json.JSONObject().apply {
            put("sessionId", "crashed")
            put("backupDir", backupDir.name)
            val cr = org.json.JSONArray().apply {
                put("half_voice")
            }
            put("created", cr)
            val bu = org.json.JSONArray().apply {
                val item = org.json.JSONObject().apply {
                    put("target", "leila")
                    put("backup", "leila")
                }
                put(item)
            }
            put("backedUp", bu)
        }
        journalFile.writeText(journalJson.toString(2), Charsets.UTF_8)

        AcapelaVoiceImporter.recoverInterruptedTransactions(baseDir)

        assertFalse(halfVoice.exists())
        assertTrue(liveVoice.exists())
        assertEquals(expectedSha, sha256(liveDat))
        assertFalse(backupDir.exists())
        assertFalse(journalFile.exists())
    }

    @Test
    fun importFromZip_rejectsInvalidPackageWithoutTouchingLiveDirectory() =
        runBlocking {
            val baseDir = EmbeddedStorage.baseDir(
                context,
                EmbeddedStorage.Engine.ACAPELA
            )
            baseDir.mkdirs()

            val liveVoice = File(baseDir, "salma")
            liveVoice.mkdirs()
            val liveIni = File(liveVoice, "salma.ini")
            liveIni.writeText(
                "PRO \"NLP/ar.oso\"\nSLV \"salma.bvcu\"\nVOICE \"Salma\"\n"
            )
            val originalSha = sha256(liveIni)
            val originalLen = liveIni.length()

            val invalidZip = File(tempFolder.root, "invalid_acapela.zip")
            java.util.zip.ZipOutputStream(
                java.io.FileOutputStream(invalidZip)
            ).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("docs/readme.txt"))
                zos.write("Not an acapela package".toByteArray())
                zos.closeEntry()
            }

            val uri = android.net.Uri.fromFile(invalidZip)
            val res = AcapelaVoiceImporter.importFromZip(
                context,
                uri,
                "invalid_acapela.zip"
            )

            assertFalse(res.success)
            assertTrue(res.errorMessage?.isNotBlank() == true)

            // التأكد من عدم لمس الدليل الحي
            assertTrue(liveVoice.exists())
            assertTrue(liveIni.exists())
            assertEquals(originalLen, liveIni.length())
            assertEquals(originalSha, sha256(liveIni))
        }
}
