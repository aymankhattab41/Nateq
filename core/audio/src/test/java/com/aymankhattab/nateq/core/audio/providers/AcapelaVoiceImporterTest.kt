package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
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

    @Test
    fun isValidConfigYaml_checksNonEmptyValidParamAndSpeakerMatch() {
        val validYaml = File(tempFolder.root, "config_salma.yaml")
        validYaml.writeText(
            "param: \"salma22k_hq, test@example.com, 1234567890ABCDEF\"\n"
        )

        assertTrue(
            AcapelaVoiceImporter.isValidConfigYaml(validYaml, "salma")
        )
        assertFalse(
            AcapelaVoiceImporter.isValidConfigYaml(validYaml, "leila")
        )

        val emptyYaml = File(tempFolder.root, "config_empty.yaml")
        emptyYaml.writeText("")
        assertFalse(
            AcapelaVoiceImporter.isValidConfigYaml(emptyYaml, "salma")
        )

        val noParamYaml = File(tempFolder.root, "config_no_param.yaml")
        noParamYaml.writeText("voice: salma\nother: 123\n")
        assertFalse(
            AcapelaVoiceImporter.isValidConfigYaml(noParamYaml, "salma")
        )
    }

    @Test
    fun copyConfigFileSafely_copiesAndOverwritesEmptyTarget() {
        val src = File(tempFolder.root, "src_config.yaml")
        src.writeText("param: test_param\n")
        val dst = File(tempFolder.root, "dst_config.yaml")
        dst.writeText("")

        val copied = AcapelaVoiceImporter.copyConfigFileSafely(src, dst)
        assertTrue(copied)
        assertTrue(dst.exists())
        assertEquals(src.length(), dst.length())
        assertEquals(src.readText(), dst.readText())
    }

    @Test
    fun copyMatchingConfigsToTree_copiesConfigAndRegistrationFromOutside() {
        val srcRoot = tempFolder.newFolder("unpack_root")
        val configYaml = File(srcRoot, "config.yaml")
        configYaml.writeText(
            "param: \"leila22k_hq, test@example.com, 1234567890ABCDEF\"\n"
        )
        val regHex = File(srcRoot, "0x1234567890ABCDEF.txt")
        regHex.writeText("registration_data")

        val targetTree = tempFolder.newFolder("target_voice_tree")
        val voiceDir = File(targetTree, "voice/acapela/leila22k_hq")
        voiceDir.mkdirs()
        File(voiceDir, "leila22k_hq.inf").writeText("Speaker=leila")

        val count = AcapelaVoiceImporter.copyMatchingConfigsToTree(
            sourceTree = srcRoot,
            targetTree = targetTree
        )
        assertEquals(1, count)
        val targetConfig = File(voiceDir, "config.yaml")
        assertTrue(targetConfig.exists())
        assertEquals(configYaml.length(), targetConfig.length())
        val targetHex = File(voiceDir, "0x1234567890ABCDEF.txt")
        assertTrue(targetHex.exists())
    }

    @Test
    fun ensureValidConfigYamlForVoices_findsStagingDirs() {
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        baseDir.mkdirs()
        val voiceDir = File(baseDir, "leila22k_hq")
        voiceDir.mkdirs()
        File(voiceDir, "leila22k_hq.inf").writeText("Speaker=leila")

        val stageDir = File(context.cacheDir, "acapela_staging_test_sess")
        stageDir.mkdirs()
        val stageYaml = File(stageDir, "config.yaml")
        stageYaml.writeText(
            "param: \"leila22k_hq, user@test.com, 1122334455667788\"\n"
        )

        try {
            val repaired = AcapelaVoiceImporter.ensureValidConfigYamlForVoices(
                context = context,
                baseDir = baseDir,
                stagingDir = stageDir
            )
            assertEquals(1, repaired)
            val destYaml = File(voiceDir, "config.yaml")
            assertTrue(destYaml.exists())
            assertTrue(
                AcapelaVoiceImporter.isValidConfigYaml(
                    destYaml,
                    "leila22k_hq"
                )
            )
        } finally {
            stageDir.deleteRecursively()
        }
    }

    @Test
    fun isIgnoredOrNonVoiceDirectory_filtersHiddenAndReservedDirs() {
        val base = tempFolder.newFolder("check_ignore")
        val backupDir = File(base, ".backup_acapela_123")
        val journalDir = File(base, ".journal_acapela_456")
        val voiceDir = File(base, "voice")
        val userdicosDir = File(base, "userdicos")
        val filesDir = File(base, "files")
        val validVoiceDir = File(base, "leila22k_hq")

        assertTrue(
            AcapelaVoiceManifest.isIgnoredOrNonVoiceDirectory(backupDir)
        )
        assertTrue(
            AcapelaVoiceManifest.isIgnoredOrNonVoiceDirectory(journalDir)
        )
        assertFalse(
            AcapelaVoiceManifest.isIgnoredOrNonVoiceDirectory(voiceDir)
        )
        assertTrue(
            AcapelaVoiceManifest.isIgnoredOrNonVoiceDirectory(userdicosDir)
        )
        assertFalse(
            AcapelaVoiceManifest.isIgnoredOrNonVoiceDirectory(filesDir)
        )
        assertFalse(
            AcapelaVoiceManifest.isIgnoredOrNonVoiceDirectory(validVoiceDir)
        )
    }

    @Test
    fun recoverInterruptedTransactions_cleansLegacyBackupAndRecoversExternal() {
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        baseDir.mkdirs()

        // مجلد .backup_acapela_ قديم يجب حذفه عند بدء التشغيل
        val legacyBackup = File(baseDir, ".backup_acapela_old")
        legacyBackup.mkdirs()
        File(legacyBackup, "dummy.txt").writeText("old")

        // مجلد النسخ الاحتياطي الخارجي الآمن
        val backupParent = File(context.filesDir, "acapela_backups")
        backupParent.mkdirs()
        val backupDir = File(backupParent, "acapela_backup_ext_sess")
        backupDir.mkdirs()
        val backupVoice = File(backupDir, "salma")
        backupVoice.mkdirs()
        val backupDat = File(backupVoice, "salma.dat")
        backupDat.writeBytes(ByteArray(2000) { 3 })
        val expectedSha = sha256(backupDat)

        val liveVoice = File(baseDir, "salma")
        liveVoice.mkdirs()
        val liveDat = File(liveVoice, "salma.dat")
        liveDat.writeBytes(ByteArray(10) { 0 })

        val journalFile = File(
            backupParent,
            ".journal_acapela_ext_sess.json"
        )
        val journalJson = org.json.JSONObject().apply {
            put("sessionId", "ext_sess")
            put("backupDir", backupDir.name)
            put("backupDirPath", backupDir.absolutePath)
            put("baseDirPath", baseDir.absolutePath)
            put("created", org.json.JSONArray())
            val bu = org.json.JSONArray().apply {
                val item = org.json.JSONObject().apply {
                    put("target", liveVoice.absolutePath)
                    put("backup", backupVoice.absolutePath)
                }
                put(item)
            }
            put("backedUp", bu)
        }
        journalFile.writeText(journalJson.toString(2), Charsets.UTF_8)

        AcapelaVoiceImporter.recoverInterruptedTransactions(baseDir, context)

        // التحقق من حذف المجلد القديم واستعادة النسخة الخارجية
        assertFalse(legacyBackup.exists())
        assertTrue(liveVoice.exists())
        assertEquals(expectedSha, sha256(liveDat))
        assertFalse(backupDir.exists())
        assertFalse(journalFile.exists())
    }

    @Test
    fun mockPackageInVoiceAcapelaMehdi_inventoryAndStatusNotNotInstalled() {
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        val voiceDir = File(baseDir, "voice").apply { mkdirs() }
        val acapelaSub = File(voiceDir, "acapela").apply { mkdirs() }
        val mehdiDir = File(acapelaSub, "Mehdi").apply { mkdirs() }

        File(mehdiDir, "Mehdi.inf").writeText(
            "[Voice]\nSpeaker=Mehdi\nIsoCode=ara\nSampleRate=22050\n"
        )
        File(mehdiDir, "mehdi.dat").writeBytes(ByteArray(20_000))
        File(voiceDir, "config.yaml").writeText(
            "param: \"Mehdi,user@example.com,0011223344556677\"\n"
        )

        AcapelaVoiceManifest.invalidateVoicesCache()
        val inv = EmbeddedStorage.inventory(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        assertTrue(inv.isCompleteVoicePresent)

        val engines = EnginePicker.installedEngines(context)
        val acapelaEngine = engines.firstOrNull {
            it.packageName == AcapelaEmbeddedVoiceProvider.PROVIDER_ID
        }
        assertNotNull(acapelaEngine)
        val status = EngineStatusRegistry.statusOf(
            context,
            AcapelaEmbeddedVoiceProvider.PROVIDER_ID
        )
        assertFalse(status.state == EngineStatusRegistry.State.NOT_INSTALLED)
    }

    @Test
    fun rollback_failedZipImport_clearsCacheBumpsVersionAndAlignsStatus() =
        runBlocking {
            val baseDir = EmbeddedStorage.baseDir(
                context,
                EmbeddedStorage.Engine.ACAPELA
            )
            baseDir.mkdirs()

            val voiceDir = File(baseDir, "voice").apply { mkdirs() }
            val mehdiDir = File(voiceDir, "Mehdi").apply { mkdirs() }
            val infFile = File(mehdiDir, "Mehdi.inf")
            infFile.writeText(
                "[Voice]\nSpeaker=Mehdi\nIsoCode=ara\nSampleRate=22050\n"
            )
            val datFile = File(mehdiDir, "mehdi.dat")
            datFile.writeBytes(ByteArray(20_000) { 1 })
            val yamlFile = File(mehdiDir, "config.yaml")
            yamlFile.writeText(
                "param: \"Mehdi,user@example.com,0011223344556677\"\n"
            )

            val initialVoices = AcapelaVoiceManifest.listVoices(baseDir)
            assertEquals(1, initialVoices.size)
            val initialVersion = EmbeddedStorage.version

            val backupParent = File(context.filesDir, "acapela_backups")
            backupParent.mkdirs()
            val backupDir = File(backupParent, "acapela_backup_test_1")
            backupDir.mkdirs()
            val backupVoice = File(backupDir, "voice")
            mehdiDir.parentFile?.copyRecursively(backupVoice, overwrite = true)

            val badNewDir = File(voiceDir, "CorruptedNew")
            badNewDir.mkdirs()
            File(badNewDir, "broken.dat").writeBytes(ByteArray(100) { 0 })

            val journalFile = File(backupParent, ".journal_acapela_test_1.json")
            journalFile.writeText("{}")

            AcapelaVoiceImporter.rollbackAcapela(
                baseDir = baseDir,
                createdTargets = listOf(badNewDir),
                backups = listOf(voiceDir to backupVoice),
                backupDir = backupDir,
                journalFile = journalFile,
                context = context
            )

            val currentVoices = AcapelaVoiceManifest.listVoices(baseDir)
            assertEquals(1, currentVoices.size)
            assertEquals("Mehdi", currentVoices[0].id)
            assertFalse(badNewDir.exists())

            assertTrue(EmbeddedStorage.version > initialVersion)

            val status = EngineStatusRegistry.statusOf(
                context,
                AcapelaEmbeddedVoiceProvider.PROVIDER_ID
            )
            assertFalse(
                status.state == EngineStatusRegistry.State.NOT_INSTALLED
            )
        }

    @Test
    fun rollback_withNoPreviousVoice_statusIsFilesAbsent() {
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        baseDir.mkdirs()

        EngineStatusRegistry.noteFilesPresent(
            AcapelaEmbeddedVoiceProvider.PROVIDER_ID
        )

        val backupParent = File(context.filesDir, "acapela_backups")
        backupParent.mkdirs()
        val backupDir = File(backupParent, "acapela_backup_test_empty")
        backupDir.mkdirs()
        val badDir = File(baseDir, "IncompleteVoice").apply { mkdirs() }
        val journalFile = File(backupParent, ".journal_test_empty.json")
        journalFile.writeText("{}")

        // اختبار التراجع بلا سياق Context للتأكد من عدم الانهيار
        AcapelaVoiceImporter.rollbackAcapela(
            baseDir = baseDir,
            createdTargets = listOf(badDir),
            backups = emptyList(),
            backupDir = backupDir,
            journalFile = journalFile,
            context = null
        )

        val status = EngineStatusRegistry.statusOf(
            context,
            AcapelaEmbeddedVoiceProvider.PROVIDER_ID
        )
        assertEquals(
            EngineStatusRegistry.State.NOT_INSTALLED,
            status.state
        )
        assertFalse(badDir.exists())
    }
}
