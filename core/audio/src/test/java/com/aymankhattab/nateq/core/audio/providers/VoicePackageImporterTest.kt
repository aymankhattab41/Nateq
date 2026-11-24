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
                // تهيئة أصلية متوافقة مطلوبة للاكتشاف/الاكتمال.
                "mehdi.ini" to (
                    "PRO \"NLP/ar.oso\"\n" +
                        "SLV \"mehdi.bvcu\"\n" +
                        "VOICE \"Mehdi\"\n"
                    ).toByteArray(),
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
    fun importVocalizer_twoFilesSameNameDifferentSubdirs_noCollision() =
        runBlocking {
            val vocBase = EmbeddedStorage.baseDir(
                context,
                EmbeddedStorage.Engine.VOCALIZER
            )
            val fileAra = File(vocBase, "languages/ara/voice.dat")
            val fileFra = File(vocBase, "languages/fra/voice.dat")
            fileAra.parentFile?.mkdirs()
            fileFra.parentFile?.mkdirs()
            fileAra.writeText("ArabicOldVoiceData_12345")
            fileFra.writeText("FrenchOldVoiceData_67890")

            val originalShaAra = sha256(fileAra)
            val originalShaFra = sha256(fileFra)

            val zipFile = File(tempFolder.root, "update_voices.zip")
            createZip(
                zipFile,
                mapOf(
                    "languages/ara/mulan.hdr" to "HdrDataAra".toByteArray(),
                    "languages/ara/voice.dat" to
                        "ArabicNewData_999".toByteArray(),
                    "languages/fra/voice.dat" to
                        "FrenchNewData_888".toByteArray()
                )
            )

            val result = VoicePackageImporter.importZip(
                context,
                Uri.fromFile(zipFile),
                "update_voices.zip"
            )

            assertTrue(result.success)
            assertEquals("ArabicNewData_999", fileAra.readText())
            assertEquals("FrenchNewData_888", fileFra.readText())

            // التحقق من عدم وجود مجلدات احتياطية عالقة
            val leftoverBackups = vocBase.listFiles { f ->
                f.name.startsWith(".backup_") || f.name.startsWith(".journal_")
            }?.toList().orEmpty()
            assertTrue(leftoverBackups.isEmpty())
        }

    @Test
    fun rollbackVocalizer_deletesNewFilesAndRestoresExactSha256() {
        val vocBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        vocBase.mkdirs()

        val oldFile = File(vocBase, "languages/ara/existing.dat")
        oldFile.parentFile?.mkdirs()
        oldFile.writeText("OriginalDataBeforeTransaction_ABCDEF")
        val expectedSha = sha256(oldFile)

        val backupDir = File(vocBase, ".backup_vocalizer_test_session")
        backupDir.mkdirs()
        val backupFile = File(backupDir, "languages/ara/existing.dat")
        backupFile.parentFile?.mkdirs()
        oldFile.copyTo(backupFile, overwrite = true)

        // محاكاة تعديل الملف القديم وإنشاء ملف جديد
        oldFile.writeText("CorruptedHalfModifiedData")
        val newFile = File(vocBase, "languages/ara/brand_new.dat")
        newFile.writeText("NewlyCreatedFileInTransaction")

        val journalFile = File(vocBase, ".journal_vocalizer_test_session.json")
        journalFile.writeText("{}")

        VoicePackageImporter.rollbackVocalizer(
            baseDir = vocBase,
            createdFiles = listOf(newFile),
            backedUpFiles = listOf(oldFile to backupFile),
            backupDir = backupDir,
            journalFile = journalFile
        )

        // التحقق من حذف المنشأ الجديد
        assertFalse(newFile.exists())

        // التحقق من استرجاع القديم كاملاً ببصمة SHA-256 متطابقة
        assertTrue(oldFile.exists())
        assertEquals(expectedSha, sha256(oldFile))
        assertEquals(
            "OriginalDataBeforeTransaction_ABCDEF",
            oldFile.readText()
        )

        // التحقق من حذف مجلد النسخ الاحتياطي وسجل المعاملة
        assertFalse(backupDir.exists())
        assertFalse(journalFile.exists())
    }

    @Test
    fun importZip_rejectsInvalidPackageWithoutTouchingLiveDirectory() =
        runBlocking {
            val vocBase = EmbeddedStorage.baseDir(
                context,
                EmbeddedStorage.Engine.VOCALIZER
            )
            vocBase.mkdirs()
            val liveHdr = File(vocBase, "languages/ara/mulan.hdr")
            val liveDat = File(vocBase, "languages/ara/mulan.dat")
            liveHdr.parentFile?.mkdirs()
            liveHdr.writeText("LiveHdrContent_111")
            liveDat.writeText("LiveDatContent_222")

            val originalHdrSha = sha256(liveHdr)
            val originalDatSha = sha256(liveDat)
            val originalHdrLen = liveHdr.length()
            val originalDatLen = liveDat.length()

            val invalidZip = File(tempFolder.root, "invalid_voice.zip")
            createZip(
                invalidZip,
                mapOf(
                    "doc/readme.txt" to "Some text file".toByteArray(),
                    "images/logo.png" to ByteArray(500)
                )
            )

            val result = VoicePackageImporter.importZip(
                context,
                Uri.fromFile(invalidZip),
                "invalid_voice.zip"
            )

            assertFalse(result.success)
            assertTrue(result.errorMessage?.isNotBlank() == true)

            // التأكد من عدم لمس الملفات الحية نهائياً
            assertTrue(liveHdr.exists())
            assertTrue(liveDat.exists())
            assertEquals(originalHdrLen, liveHdr.length())
            assertEquals(originalDatLen, liveDat.length())
            assertEquals(originalHdrSha, sha256(liveHdr))
            assertEquals(originalDatSha, sha256(liveDat))
        }

    @Test
    fun recoverInterruptedTransactions_restoresStateAfterSimulatedCrash() {
        val vocBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        vocBase.mkdirs()

        val importantFile = File(vocBase, "languages/ara/important.hdr")
        importantFile.parentFile?.mkdirs()
        importantFile.writeText("OriginalImportantHeaderData")
        val expectedSha = sha256(importantFile)

        // محاكاة تعطل مفاجئ أثناء التبديل
        val backupDir = File(vocBase, ".backup_vocalizer_crashed_sess")
        val backupFile = File(backupDir, "languages/ara/important.hdr")
        backupFile.parentFile?.mkdirs()
        importantFile.copyTo(backupFile)

        // الملف الحي تلوّث والملف الجديد نصف منسوخ
        importantFile.writeText("CorruptedHalfWrittenHeader")
        val halfFile = File(vocBase, "languages/ara/half_copied.dat")
        halfFile.writeText("HalfCopiedGarbageData")

        val journalFile = File(
            vocBase,
            ".journal_vocalizer_crashed_sess.json"
        )
        val journalJson = org.json.JSONObject().apply {
            put("sessionId", "crashed_sess")
            put("backupDir", backupDir.name)
            val cr = org.json.JSONArray().apply {
                put("languages/ara/half_copied.dat")
            }
            put("created", cr)
            val bu = org.json.JSONArray().apply {
                val item = org.json.JSONObject().apply {
                    put("target", "languages/ara/important.hdr")
                    put("backup", "languages/ara/important.hdr")
                }
                put(item)
            }
            put("backedUp", bu)
        }
        journalFile.writeText(journalJson.toString(2), Charsets.UTF_8)

        // استدعاء التعافي
        VoicePackageImporter.recoverInterruptedTransactions(vocBase)

        // التحقق من حذف المنشأ غير المكتمل واسترجاع الملف الأصلي بهويته وهاشه
        assertFalse(halfFile.exists())
        assertTrue(importantFile.exists())
        assertEquals(expectedSha, sha256(importantFile))
        assertEquals("OriginalImportantHeaderData", importantFile.readText())

        // التحقق من حذف السجل والنسخ الاحتياطية
        assertFalse(journalFile.exists())
        assertFalse(backupDir.exists())
    }

    @Test
    fun copyFileVerified_checksSizeAndIntegrity() {
        val src = File(tempFolder.root, "source.dat")
        val dest = File(tempFolder.root, "dest.dat")
        src.writeText("HelloVerifiedCopy_123456")

        VoicePackageImporter.copyFileVerified(src, dest)
        assertTrue(dest.exists())
        assertEquals(src.length(), dest.length())
        assertEquals(src.readText(), dest.readText())
    }
}
