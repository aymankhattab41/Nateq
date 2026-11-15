package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AcapelaVoiceDiagnosticsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun tempDir(): File =
        Files.createTempDirectory("acapela_diag").toFile()

    @Test
    fun expectedLicensePath_replacesLastThreeCharsWithLic() {
        val ini = File("voice/ar_sa_hd_salma_22k_lf.bvcu.ini")
        assertEquals(
            File("voice/ar_sa_hd_salma_22k_lf.bvcu.lic"),
            AcapelaVoiceDiagnostics.expectedLicensePath(ini)
        )
    }

    @Test
    fun expectedLicensePath_isNullWithoutConfig() {
        assertEquals(
            null,
            AcapelaVoiceDiagnostics.expectedLicensePath(null)
        )
    }

    @Test
    fun inventory_listsSortedFilesAndCountsTheRest() {
        val dir = tempDir()
        try {
            File(dir, "b.inf").writeText("yy")
            File(dir, "a.ini").writeText("x")
            File(dir, "c.bvcu").writeText("zzz")

            val lines = AcapelaVoiceDiagnostics.inventory(dir, limit = 2)

            assertEquals(3, lines.size)
            assertTrue(lines[0].startsWith("a.ini (1"))
            assertTrue(lines[1].startsWith("b.inf (2"))
            assertTrue(lines[2].contains("ملفاً آخر"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun inventory_reportsMissingDirectory() {
        val lines = AcapelaVoiceDiagnostics.inventory(
            File("no_such_dir_12345")
        )
        assertEquals(listOf("(المجلد غير موجود)"), lines)
    }

    @Test
    fun tailLines_keepsLastLinesAndTruncatesThem() {
        val dir = tempDir()
        try {
            val log = File(dir, AcapelaVoiceDiagnostics.LOG_FILE_NAME)
            log.writeText(
                (1..20).joinToString("\n") { "line-$it" } +
                    "\n" + "x".repeat(300)
            )

            val tail = AcapelaVoiceDiagnostics.tailLines(
                log,
                maxLines = 3,
                maxChars = 10
            )

            assertEquals(3, tail.size)
            assertEquals("line-19", tail[0])
            assertEquals("line-20", tail[1])
            assertEquals("x".repeat(10) + "…", tail[2])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun tailLines_reportsMissingFile() {
        val lines = AcapelaVoiceDiagnostics.tailLines(
            File("missing_logfile_12345.txt")
        )
        assertEquals(listOf("(الملف غير موجود)"), lines)
    }

    @Test
    fun scanRoot_findsConfigsAndReportsLicense() {
        val root = tempDir()
        val voice = File(root, "Salma").apply { mkdirs() }
        try {
            File(voice, "a.bvcu.ini").writeText("[voice]")

            val text = AcapelaVoiceDiagnostics.scanRoot(root)

            assertTrue(text.contains("a.bvcu.ini"))
            assertTrue(text.contains("الرخصة المتوقعة"))
            assertTrue(text.contains("طول التسجيل: غير محسوب"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun report_showsLicenseStateKeyFileAndLogTail() {
        val root = tempDir()
        val voice = File(root, "Salma").apply { mkdirs() }
        try {
            val config = File(voice, "ar_sa_hd_salma_22k_lf.bvcu.ini")
            config.writeText("[voice]")
            File(voice, "0x00169729").writeText("secret")
            File(voice, AcapelaVoiceDiagnostics.LOG_FILE_NAME)
                .writeText("tts_function_load_voice : x - error - eb 1")

            val report = AcapelaVoiceDiagnostics.report(
                rootDir = root,
                voiceDir = voice,
                configFile = config,
                registrationLength = 40,
                packageName = "com.aymankhattab.nateq"
            )

            assertTrue(report.contains("- الرخصة المتوقعة: "))
            assertTrue(report.contains("غير موجود"))
            assertTrue(report.contains("0x00169729"))
            assertTrue(report.contains("- config.yaml: "))
            assertTrue(
                report.contains("- اسم حزمة التطبيق: com.aymankhattab.nateq")
            )
            assertTrue(
                report.contains("- بصمة توقيع التطبيق (SHA-256): ")
            )
            assertTrue(report.contains("- معرف الجهاز (ANDROID_ID): "))
            assertTrue(report.contains("- المعمارية الحالية: "))
            assertTrue(report.contains("- دعم المعماريتين: "))
            assertTrue(report.contains("طول التسجيل: 40"))
            assertTrue(report.contains("error - eb 1"))
            assertTrue(!report.contains("secret"))

            assertEquals(
                "1234...7890",
                AcapelaVoiceDiagnostics.maskMiddle("1234567890", 4, 4)
            )
            assertTrue(AcapelaVoiceDiagnostics.getArchInfo().isNotEmpty())
            assertTrue(AcapelaVoiceDiagnostics.hasDualArchLibs().isNotEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stageVoiceFlat_createsFlatStructureAndUserdicos() {
        val root = tempDir()
        val voice = File(root, "Salma").apply { mkdirs() }
        val nlp = File(voice, "NLP").apply { mkdirs() }
        try {
            File(voice, "salma_22k_lf.bvcu").writeText("bvcu_data")
            File(voice, "bvcu.inf").writeText("Speaker=Salma\n[voice]\n")
            File(voice, "config.yaml")
                .writeText("param: 1,test@test.com,0x12345678\n")
            File(voice, "salma_lf.txt").writeText("txt_data")
            File(voice, "0x00169729").writeText("hex_data")
            File(voice, "user.userdico").writeText("user_dico_data")
            File(nlp, "ar_sa.dca").writeText("dca_data")
            File(nlp, "ar_sa_unplugged.dca").writeText("unp_data")
            File(nlp, "ar_sa_hde_arq.dca").writeText("hde_data")

            val stageFlat = AcapelaVoiceDiagnostics.stageVoiceFlat(
                context,
                voice
            )

            assertTrue(File(stageFlat, "salma_22k_lf.bvcu").exists())
            assertTrue(File(stageFlat, "ar_sa.dca").exists())
            assertTrue(File(stageFlat, "ar_sa_unplugged.dca").exists())
            assertTrue(File(stageFlat, "ar_sa_hde_arq.dca").exists())
            assertTrue(File(stageFlat, "bvcu.inf").exists())
            assertTrue(File(stageFlat, "bvcu.ini").exists())
            assertTrue(File(stageFlat, "config.yaml").exists())
            assertTrue(File(stageFlat, "salma_lf.txt").exists())
            assertTrue(File(stageFlat, "0x00169729").exists())
            assertTrue(File(stageFlat, "user.userdico").exists())
            assertTrue(File(stageFlat, "userdicos/user.userdico").exists())
            assertTrue(File(stageFlat, "userdicos/ara-sau.userdico").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun dumpFileContent_masksConfigYamlParamAndPrintsText() {
        val root = tempDir()
        try {
            val yaml = File(root, "config.yaml")
            yaml.writeText("param: 1,user@test.com,0xABCDEF12\nname: Salma\n")
            val dumped = AcapelaVoiceDiagnostics.dumpFileContent(yaml)
            assertTrue(dumped.contains("Salma"))
            assertTrue(!dumped.contains("ABCDEF12"))
            assertTrue(dumped.contains("param: "))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun getFileHexHeaderAndMd5_returnsValidHexAndHash() {
        val root = tempDir()
        try {
            val f = File(root, "test.dca")
            f.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
            val header = AcapelaVoiceDiagnostics.getFileHexHeaderAndMd5(f)
            assertTrue(header.contains("MD5:"))
            assertTrue(header.contains("01 02 03 04"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun prepareStageMin_filtersNonAllowedFiles() {
        val root = tempDir()
        try {
            File(root, "salma_22k_lf.bvcu").writeText("bvcu")
            File(root, "bvcu.inf").writeText("inf")
            File(root, "bvcu.ini").writeText("ini")
            File(root, "config.yaml").writeText("yaml")
            File(root, "ar_sa.dca").writeText("dca")
            File(root, "unwanted_extra.txt").writeText("extra")
            val userdicos = File(root, "userdicos").apply { mkdirs() }
            File(userdicos, "user.userdico").writeText("dico")

            val minDir = AcapelaVoiceDiagnostics.prepareStageMin(context, root)

            assertTrue(File(minDir, "salma_22k_lf.bvcu").exists())
            assertTrue(File(minDir, "bvcu.inf").exists())
            assertTrue(File(minDir, "ar_sa.dca").exists())
            assertTrue(!File(minDir, "unwanted_extra.txt").exists())
            assertTrue(File(minDir, "userdicos/user.userdico").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun prepareStageNoUnplugged_removesUnpluggedAndHdeArq() {
        val root = tempDir()
        try {
            File(root, "ar_sa.dca").writeText("keep")
            File(root, "ar_sa_unplugged.dca").writeText("remove")
            File(root, "ar_sa_hde_arq.dca").writeText("remove")
            File(root, "salma_22k_lf.bvcu").writeText("keep")

            val nounpDir = AcapelaVoiceDiagnostics.prepareStageNoUnplugged(
                context,
                root
            )

            assertTrue(File(nounpDir, "ar_sa.dca").exists())
            assertTrue(File(nounpDir, "salma_22k_lf.bvcu").exists())
            assertTrue(!File(nounpDir, "ar_sa_unplugged.dca").exists())
            assertTrue(!File(nounpDir, "ar_sa_hde_arq.dca").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stageVoiceV2_copiesRequiredAndExcludesForbiddenFiles() {
        val root = tempDir()
        try {
            File(root, "ar_sa.dca").writeText("dca")
            File(root, "salma_22k_lf.bvcu").writeText("bvcu")
            File(root, "ar_sa_hd_salma_22k_lf.bvcu.inf").writeText("inf")
            File(root, "config.yaml").writeText("yaml")
            File(root, "salma_lf.txt").writeText("txt")
            File(root, "0x00169729").writeText("hex")
            File(root, "3073.ini").writeText("forbidden")
            File(root, "MSTTSLocArEG.ini").writeText("forbidden")
            File(root, "logfile.txt").writeText("forbidden")
            File(root, "test.nuul").writeText("forbidden")
            val userdicos = File(root, "userdicos").apply { mkdirs() }
            File(userdicos, "user.userdico").writeText("dico")

            val v2 = AcapelaVoiceDiagnostics.stageVoiceV2(context, root)

            assertTrue(File(v2, "ar_sa.dca").exists())
            assertTrue(File(v2, "salma_22k_lf.bvcu").exists())
            assertTrue(File(v2, "ar_sa_hd_salma_22k_lf.bvcu.inf").exists())
            assertTrue(File(v2, "bvcu.inf").exists())
            assertTrue(File(v2, "config.yaml").exists())
            assertTrue(File(v2, "salma_lf.txt").exists())
            assertTrue(File(v2, "0x00169729").exists())
            assertTrue(File(v2, "userdicos/user.userdico").exists())
            assertTrue(File(v2, "userdicos/ara-sau.userdico").exists())

            // تأكيد استبعاد الملفات المحظورة
            assertTrue(!File(v2, "3073.ini").exists())
            assertTrue(!File(v2, "MSTTSLocArEG.ini").exists())
            assertTrue(!File(v2, "logfile.txt").exists())
            assertTrue(!File(v2, "test.nuul").exists())

            // تأكيد كتابة ملفات .ini بسطرين
            val ini = File(v2, "ar_sa_hd_salma_22k_lf.ini")
            assertTrue(ini.exists())
            val iniLines = ini.readLines()
            assertEquals(2, iniLines.size)
            assertEquals("MODE = \"MODE=prep_hd\"", iniLines[0])
            assertTrue(
                iniLines[1].contains("FILEMAP \"salma_22k_lf.bvcu\"")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeStageV2Ini_writesExactlyTwoLinesToAllThreeFiles() {
        val root = tempDir()
        try {
            AcapelaVoiceDiagnostics.writeStageV2Ini(
                stageDir = root,
                mode = "MODE=prep",
                filemapDirective = "#1 FILEMAP \"salma_22k_lf.bvcu\""
            )
            val targets = listOf(
                "ar_sa_hd_salma_22k_lf.ini",
                "ar_sa_hd_salma_22k_lf.bvcu.ini",
                "bvcu.ini"
            )
            for (t in targets) {
                val f = File(root, t)
                assertTrue(f.exists())
                val iniLines = f.readLines()
                assertEquals(2, iniLines.size)
                assertEquals("MODE = \"MODE=prep\"", iniLines[0])
                assertEquals(
                    "#1 FILEMAP \"salma_22k_lf.bvcu\"",
                    iniLines[1]
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun savePcmAsWav_createsValidWavHeaderWithAudioLength() {
        val root = tempDir()
        try {
            val wavFile = File(root, "test_out.wav")
            val pcm = ByteArray(100) { 1 }
            val ok = AcapelaVoiceDiagnostics.savePcmAsWav(
                file = wavFile,
                pcmData = pcm,
                sampleRate = 22050
            )
            assertTrue(ok)
            assertTrue(wavFile.exists())
            assertEquals(144L, wavFile.length())
            val header = wavFile.readBytes().take(4).toByteArray()
            assertEquals("RIFF", String(header))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun compareNlpFiles_detectsPresentAndMissingFiles() {
        val root = tempDir()
        try {
            val salma = File(root, "Acapela/Salma")
            val nlp = File(salma, "NLP")
            nlp.mkdirs()
            File(nlp, "ar_sa.dca").writeText("DICA content")
            File(salma, "ar_sa_hd_salma_22k_lf.bvcu.inf").writeText(
                "FILEMAP \"ar_sa.dca\" [ RAM ]\n" +
                    "FILEMAP \"missing_file.dca\" [ RAM ]\n"
            )

            val context = ApplicationProvider
                .getApplicationContext<Context>()
            val report = AcapelaVoiceDiagnostics.compareNlpFiles(context)
            assertNotNull(report)
            assertTrue(report.contains("NLP") || report.contains("ar_sa"))
        } finally {
            root.deleteRecursively()
        }
    }
}
