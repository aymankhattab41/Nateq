package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
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

    @Test
    fun getJniFunctionsCatalog_containsExpectedNativeMethods() {
        val catalog = AcapelaVoiceDiagnostics.getJniFunctionsCatalog()
        assertTrue(catalog.contains("nInit"))
        assertTrue(catalog.contains("nSetCurrentVoice"))
        assertTrue(catalog.contains("nSetPreferedVoice"))
        assertTrue(catalog.contains("nRefrehEnumeration"))
        assertTrue(catalog.contains("nloadLanguage"))
        assertTrue(catalog.contains("nLoadVoice"))
        assertTrue(catalog.contains("nGetLastError"))
    }

    @Test
    fun getSpeakErrorCodeExplanation_explainsMinusTwo() {
        val expl = AcapelaVoiceDiagnostics.getSpeakErrorCodeExplanation()
        assertTrue(expl.contains("-2"))
        assertTrue(expl.contains("No voice loaded"))
        assertTrue(expl.contains("0x1bd5dc"))
        assertTrue(expl.contains("0x200"))
    }

    @Test
    fun getLicenseStringsAndError27Catalog_containsAnalysis() {
        val cat = AcapelaVoiceDiagnostics
            .getLicenseStringsAndError27Catalog()
        assertTrue(cat.contains("E_BABILE_LICENSE"))
        assertTrue(cat.contains("Default voice - license"))
        assertTrue(cat.contains("E_BABILE_NLPINITERROR"))
        assertTrue(cat.contains("-27"))
        assertTrue(cat.contains("BBNLP_init"))
        assertTrue(cat.contains("tts_function_load_voice"))
    }

    @Test
    fun stageVoiceV2_copiesSalmaBvcuToArSaHdBvcu() {
        val root = tempDir()
        try {
            val salma = File(root, "Acapela/Salma/salma_lf")
            salma.mkdirs()
            val bvcu = File(salma, "salma_22k_lf.bvcu")
            bvcu.writeText("BVCU_DATA_TEST_1234567890")
            val inf = File(salma, "ar_sa_hd_salma_22k_lf.bvcu.inf")
            inf.writeText("[voice]\nname = ar_sa_hd_salma_22k_lf.bvcu\n")

            val staged = AcapelaVoiceDiagnostics.stageVoiceV2(
                context,
                salma
            )
            val destNamed = File(staged, "salma_22k_lf.bvcu")
            val destArSa = File(staged, "ar_sa_hd_salma_22k_lf.bvcu")

            assertTrue(destNamed.exists())
            assertTrue(destArSa.exists())
            assertEquals(bvcu.length(), destNamed.length())
            assertEquals(bvcu.length(), destArSa.length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun babileErrorName_mapsErrorCodesCorrectly() {
        assertEquals(
            "NO ERROR",
            AcapelaVoiceDiagnostics.babileErrorName(0)
        )
        assertEquals(
            "E_BABILE_NULLARG",
            AcapelaVoiceDiagnostics.babileErrorName(-25)
        )
        assertEquals(
            "E_BABILE_NLPINITERROR",
            AcapelaVoiceDiagnostics.babileErrorName(-27)
        )
        assertEquals(
            "E_BABILE_LICENSE",
            AcapelaVoiceDiagnostics.babileErrorName(-34)
        )
    }

    @Test
    fun resolveAcapelaParam_plainParamResolvesCorrectly() {
        val raw = "salma,user@example.com,0123456789abcdef"
        val res = AcapelaMetadataCodec.resolveAcapelaParam(raw)
        assertEquals(false, res.isEncrypted)
        assertEquals(3, res.partsCount)
        assertEquals("user@example.com".length, res.emailLength)
        assertEquals("0123456789abcdef".length, res.hexCodeLength)
        assertEquals("user@example.com0123456789abcdef", res.registration)
        assertEquals(4, res.sha1First4Hex.length)
    }

    @Test
    fun isParamEncrypted_detectsCommaPresence() {
        assertEquals(
            true,
            AcapelaMetadataCodec.isParamEncrypted("encodedPayloadWithoutComma")
        )
        assertEquals(
            false,
            AcapelaMetadataCodec.isParamEncrypted("salma,a@b.c,1234")
        )
    }

    @Test
    fun explanations_containExpectedIdentifiers() {
        val exp25 = AcapelaVoiceDiagnostics.getBabileError25Explanation()
        assertTrue(exp25.contains("E_BABILE_NULLARG"))
        assertTrue(exp25.contains("-25"))

        val shaAnalysis = AcapelaVoiceDiagnostics.getSha1Mismatch7e88Analysis()
        assertTrue(shaAnalysis.contains("7e88"))
        assertTrue(shaAnalysis.contains("-34"))
    }

    @Test
    fun getLibraryComparisonDetails_containsChecksumsAndVersion() {
        val details = AcapelaVoiceDiagnostics.getLibraryComparisonDetails()
        assertTrue(details.contains("3,634,472"))
        assertTrue(details.contains("39ee54d22b1a69d2fb326d8492ec174e"))
        assertTrue(details.contains("4.14.00"))
        assertTrue(details.contains("100% تطابق"))
    }

    @Test
    fun getStagingVsMultiTtsFilesComparison_containsExpectedFiles() {
        val comp = AcapelaVoiceDiagnostics
            .getStagingVsMultiTtsFilesComparison(context)
        assertTrue(comp.contains("salma_22k_lf.bvcu"))
        assertTrue(comp.contains("ar_sa.dca"))
        assertTrue(comp.contains("ar_sa_hd_salma_22k_lf.ini"))
        assertTrue(comp.contains("لا يوجد أي ملف ناقص"))
    }

    @Test
    fun getBbnlpInitDisassemblyAnalysis_containsAnalysis() {
        val dis = AcapelaVoiceDiagnostics.getBbnlpInitDisassemblyAnalysis()
        assertTrue(dis.contains("BBNLP_init"))
        assertTrue(dis.contains("NLPE_initEx"))
        assertTrue(dis.contains("BB_dbOpen"))
        assertTrue(dis.contains("PRO"))
        assertTrue(dis.contains("كلا قطعياً"))
    }

    @Test
    fun getLogcatMultiTtsComparisonDetails_containsLogcatLines() {
        val logDetails = AcapelaVoiceDiagnostics
            .getLogcatMultiTtsComparisonDetails()
        assertTrue(logDetails.contains("babParam.license"))
        assertTrue(logDetails.contains("babParam.uid.userId 7e88"))
        assertTrue(logDetails.contains("babParam.uid.passwd 1480489"))
        assertTrue(logDetails.contains("tts_function_load_voice"))
    }

    @Test
    fun calcFileMd5_returnsValidHash() {
        val f = File(context.cacheDir, "test_md5.txt")
        try {
            f.writeText("hello world")
            val md5 = AcapelaVoiceDiagnostics.calcFileMd5(f)
            assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", md5)
        } finally {
            f.delete()
        }
    }

    @Test
    fun getLiveDiskFilesReport_scansDirectory() {
        val report = AcapelaVoiceDiagnostics.getLiveDiskFilesReport(context)
        assertTrue(report.contains("10721") || report.contains("10719"))
        assertTrue(report.contains("acapela_stage_v2"))
        assertTrue(report.contains("3,455,285"))
    }

    @Test
    fun getNlpComponentsComparison_containsComparison() {
        val comp = AcapelaVoiceDiagnostics.getNlpComponentsComparison(context)
        assertTrue(comp.contains("sNLPConfig"))
        assertTrue(comp.contains("pNLPConfig"))
        assertTrue(comp.contains("PRO"))
        assertTrue(comp.contains("salma_22k_lf.bvcu"))
    }

    @Test
    fun getSnlpConfigDisassemblyExplanation_containsTemplateAndModules() {
        val exp =
            AcapelaVoiceDiagnostics.getSnlpConfigDisassemblyExplanation()
        assertTrue(exp.contains("0x1ba2b4"))
        assertTrue(exp.contains("0x65180"))
        assertTrue(exp.contains("BABILE_NLP_BUFFERING0"))
        assertTrue(exp.contains("MBR"))
        assertTrue(exp.contains("IVX"))
    }

    @Test
    fun getGitDiffComparisonBetween10709AndCurrent_containsComparisons() {
        val diff =
            AcapelaVoiceDiagnostics.getGitDiffComparisonBetween10709AndCurrent()
        assertTrue(diff.contains("10720"))
        assertTrue(diff.contains("10709"))
        assertTrue(diff.contains("nSetCurrentVoice"))
        assertTrue(diff.contains("isVoiceLoaded"))
        assertTrue(diff.contains("callback"))
    }

    @Test
    fun getNuulFile10709Status_provesAbsenceIn10709() {
        val status = AcapelaVoiceDiagnostics.getNuulFile10709Status()
        assertTrue(status.contains("salma_22k_lf.bvcu.nuul216vqfp"))
        assertTrue(status.contains("1,215,248"))
        assertTrue(status.contains("كلا قطعياً"))
        assertTrue(status.contains("8426057b"))
    }

    @Test
    fun stageVoiceV2_withCopyArSaBvcuFalse_omitsDuplicateBvcu() {
        val root = tempDir()
        try {
            val salma = File(root, "Acapela/Salma/salma_lf").apply {
                mkdirs()
            }
            val bvcu = File(salma, "salma_22k_lf.bvcu")
            bvcu.writeText("TEST_BVCU")
            val staged = AcapelaVoiceDiagnostics.stageVoiceV2(
                context = context,
                sourceDir = salma,
                copyArSaBvcu = false
            )
            assertTrue(File(staged, "salma_22k_lf.bvcu").exists())
            val duplicate = File(staged, "ar_sa_hd_salma_22k_lf.bvcu")
            assertEquals(false, duplicate.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stage10709Exact_createsCleanStagingMatching10709() {
        val root = tempDir()
        try {
            val salma = File(root, "Acapela/Salma/salma_lf").apply {
                mkdirs()
            }
            val bvcu = File(salma, "salma_22k_lf.bvcu")
            bvcu.writeText("TEST_BVCU")
            val inf = File(salma, "ar_sa_hd_salma_22k_lf.bvcu.inf")
            inf.writeText("[General]\nname=ar_sa_hd_salma_22k_lf\n")
            val hex = File(salma, "0x00169729")
            hex.writeText("COMMERCIAL")
            val nuul = File(salma, "salma_22k_lf.bvcu.nuul216vqfp")
            nuul.writeText("NUUL")

            val staged = AcapelaVoiceDiagnostics.stage10709Exact(
                context = context,
                sourceDir = salma
            )
            assertTrue(File(staged, "salma_22k_lf.bvcu").exists())
            assertTrue(
                File(staged, "ar_sa_hd_salma_22k_lf.bvcu.inf").exists()
            )
            assertTrue(File(staged, "ar_sa_hd_salma_22k_lf.ini").exists())
            val ini = File(staged, "ar_sa_hd_salma_22k_lf.ini")
            val iniText = ini.readText()
            assertTrue(iniText.contains("MODE=prep_hd"))
            assertTrue(iniText.contains("[ RAM ]"))
            // استبعاد الملفات الزائدة وغير المطلوبة
            assertEquals(
                false,
                File(staged, "0x00169729").exists()
            )
            assertEquals(
                false,
                File(staged, "ar_sa_hd_salma_22k_lf.bvcu").exists()
            )
            assertEquals(
                false,
                File(staged, "salma_22k_lf.bvcu.nuul216vqfp").exists()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractRegistration10709_extractsPureEmailHexCode() {
        val reg = AcapelaVoiceDiagnostics.extractRegistration10709(
            "\"Salma\", \"user@domain.com\", \"0x12345678\""
        )
        assertEquals("user@domain.com12345678", reg)

        val regSimple = AcapelaVoiceDiagnostics.extractRegistration10709(
            "Salma,info@test.com,0Xabcdef"
        )
        assertEquals("info@test.comabcdef", regSimple)
    }

    @Test
    fun getHexKeyFileDiagnostic_containsLiteralContentAndExplanations() {
        val diag = AcapelaVoiceDiagnostics.getHexKeyFileDiagnostic(context)
        assertTrue(diag.contains("10721"))
        assertTrue(diag.contains("0x00169729"))
        assertTrue(diag.contains("139 بايت"))
        assertTrue(diag.contains("73 بايت"))
        assertTrue(diag.contains("لم يقم أي كود في ناطق"))
    }

    @Test
    fun getEvidenceReportFor10708And10709_containsRequiredSections() {
        val rep = AcapelaVoiceDiagnostics.getEvidenceReportFor10708And10709()
        assertTrue(rep.contains("10722"))
        assertTrue(rep.contains("10708"))
        assertTrue(rep.contains("10709"))
        assertTrue(rep.contains("acapela_stage_v2"))
        assertTrue(rep.contains("لا يوجد أي دليل مكتوب"))
    }

    @Test
    fun extractFilesFromLogLines_extractsKnownExtensions() {
        val lines = listOf(
            "opening ar_sa.dca at path /data/files/NLP/ar_sa.dca",
            "failed opening 0x00169729 error",
            "reading salma_22k_lf.bvcu into RAM"
        )
        val extracted = AcapelaVoiceDiagnostics.extractFilesFromLogLines(lines)
        assertTrue(extracted.contains("/data/files/NLP/ar_sa.dca"))
        assertTrue(extracted.contains("0x00169729"))
        assertTrue(extracted.contains("salma_22k_lf.bvcu"))
    }

    @Test
    fun evaluateTrialSuccess10723_checksExpectedConditions() {
        val cleanLogs = listOf(
            "loading done",
            "voice ready",
            "speak started"
        )
        val withLangErr = listOf(
            "after initLanguageDbaNewEx",
            "Error init language",
            "check_userdico : load error"
        )
        val withVoiceNotLoaded = listOf(
            "voice Salma (0) not loaded",
            "tts_function_speak error"
        )

        // نجاح حقيقي
        assertTrue(
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10723(
                speakCode = 0,
                samples = 16000L,
                nativeLines = cleanLogs
            )
        )

        // فشل بوجود سطر Error init language
        assertFalse(
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10723(
                speakCode = 0,
                samples = 16000L,
                nativeLines = withLangErr
            )
        )

        // فشل بوجود سطر not loaded
        assertFalse(
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10723(
                speakCode = 0,
                samples = 16000L,
                nativeLines = withVoiceNotLoaded
            )
        )

        // فشل بعودة speakCode = -2
        assertFalse(
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10723(
                speakCode = -2,
                samples = 16000L,
                nativeLines = cleanLogs
            )
        )

        // فشل بعدم إنتاج عينات PCM
        assertFalse(
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10723(
                speakCode = 0,
                samples = 0L,
                nativeLines = cleanLogs
            )
        )
    }

    @Test
    fun stageFull_withFlatNlp_copiesAllFilesFlatAndExcludesForbidden() {
        val root = tempDir()
        File(root, "salma_22k_lf.bvcu").writeBytes(ByteArray(100))
        File(root, "salma_22k_lf.bvcu.nuul216vqfp").writeBytes(ByteArray(50))
        File(root, "ar_sa_hd_salma_22k_lf.bvcu").writeBytes(ByteArray(80))
        File(root, "ar_sa_hd_salma_22k_lf.bvcu.inf").writeText("speaker=Salma")
        File(root, "0x00169729").writeBytes(ByteArray(61))
        File(root, "config.yaml").writeText("name: Salma")
        val nlpDir = File(root, "NLP").apply { mkdirs() }
        File(nlpDir, "ar_sa.bnx").writeBytes(ByteArray(100))
        File(nlpDir, "ar_sa.dca").writeBytes(ByteArray(200))
        val dicosDir = File(root, "userdicos").apply { mkdirs() }
        File(dicosDir, "ara-sau.userdico").writeBytes(ByteArray(30))

        val staged = AcapelaVoiceDiagnostics.stageFull(
            context = context,
            sourceDir = root,
            flatNlp = true
        )

        assertTrue(File(staged, "salma_22k_lf.bvcu").exists())
        assertTrue(File(staged, "ar_sa_hd_salma_22k_lf.bvcu.inf").exists())
        assertTrue(File(staged, "ar_sa_hd_salma_22k_lf.ini").exists())
        assertTrue(File(staged, "0x00169729").exists())
        assertTrue(File(staged, "config.yaml").exists())
        assertTrue(File(staged, "userdicos/ara-sau.userdico").exists())
        assertTrue(File(staged, "ara-sau.userdico").exists())
        assertTrue(File(staged, "ar_sa.bnx").exists())
        assertTrue(File(staged, "ar_sa.dca").exists())

        // التأكد من استبعاد المجلد الفرعي والملفات الممنوعة
        assertFalse(File(staged, "NLP").exists())
        assertFalse(File(staged, "salma_22k_lf.bvcu.nuul216vqfp").exists())
        assertFalse(File(staged, "ar_sa_hd_salma_22k_lf.bvcu").exists())

        val iniText = File(staged, "ar_sa_hd_salma_22k_lf.ini").readText()
        assertTrue(iniText.contains("MODE=prep_hd"))
        assertTrue(iniText.contains("#1 FILEMAP \"salma_22k_lf.bvcu\" [ RAM ]"))
    }

    @Test
    fun stageFull_withSubdirNlp_placesNlpFilesInSubdirectory() {
        val root = tempDir()
        File(root, "salma_22k_lf.bvcu").writeBytes(ByteArray(100))
        File(root, "ar_sa_hd_salma_22k_lf.bvcu.inf").writeText("speaker=Salma")
        File(root, "0x00169729").writeBytes(ByteArray(61))
        File(root, "config.yaml").writeText("name: Salma")
        val nlpDir = File(root, "NLP").apply { mkdirs() }
        File(nlpDir, "ar_sa.bnx").writeBytes(ByteArray(100))
        File(nlpDir, "ar_sa.dca").writeBytes(ByteArray(200))
        val dicosDir = File(root, "userdicos").apply { mkdirs() }
        File(dicosDir, "ara-sau.userdico").writeBytes(ByteArray(30))

        val staged = AcapelaVoiceDiagnostics.stageFull(
            context = context,
            sourceDir = root,
            flatNlp = false
        )

        assertTrue(File(staged, "salma_22k_lf.bvcu").exists())
        assertTrue(File(staged, "NLP/ar_sa.bnx").exists())
        assertTrue(File(staged, "NLP/ar_sa.dca").exists())
        assertFalse(File(staged, "ar_sa.bnx").exists())
        assertFalse(File(staged, "ar_sa.dca").exists())
    }

    @Test
    fun getDetailedDirectoryInventory_returnsExpectedEntries() {
        val folder = tempDir()
        File(folder, "test1.txt").writeBytes(ByteArray(15))
        val sub = File(folder, "sub").apply { mkdirs() }
        File(sub, "test2.txt").writeBytes(ByteArray(25))

        val inventory = AcapelaVoiceDiagnostics
            .getDetailedDirectoryInventory(folder)
        assertTrue(inventory.any { it.contains("test1.txt (15 بايت)") })
        assertTrue(inventory.any { it.contains("sub/test2.txt (25 بايت)") })
    }

    @Test
    fun evaluateTrialSuccess10724_verifiesSuccessCriteria() {
        assertFalse(
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
                speakCode = -2,
                samples = 1000L,
                nativeLines = listOf("clean log")
            )
        )
        assertFalse(
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
                speakCode = 0,
                samples = 0L,
                nativeLines = listOf("clean log")
            )
        )
        assertFalse(
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
                speakCode = 0,
                samples = 1000L,
                nativeLines = listOf(
                    "after initLanguageDbaNewEx",
                    "Error init language"
                )
            )
        )
        assertFalse(
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
                speakCode = 0,
                samples = 1000L,
                nativeLines = listOf("voice Salma (0) not loaded")
            )
        )
        assertTrue(
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
                speakCode = 0,
                samples = 44100L,
                nativeLines = listOf("Synthesis completed successfully")
            )
        )
    }

    @Test
    fun findFirstErrorAndPreceding_findsFirstKeywordAndPredecessor() {
        val lines = listOf(
            "Starting speech session",
            "after initLanguageDbaNewEx",
            "check_userdico : load error",
            "voice Salma (0) not loaded"
        )
        val result = AcapelaVoiceDiagnostics.findFirstErrorAndPreceding(lines)
        assertNotNull(result)
        assertEquals("check_userdico : load error", result?.first)
        assertEquals("after initLanguageDbaNewEx", result?.second)

        val linesAtZero = listOf("Error init language", "next line")
        val resultZero =
            AcapelaVoiceDiagnostics.findFirstErrorAndPreceding(linesAtZero)
        assertNotNull(resultZero)
        assertEquals("Error init language", resultZero?.first)
        assertEquals("(لا يوجد سطر سابق)", resultZero?.second)

        val clean = listOf("line 1", "line 2")
        val cleanResult =
            AcapelaVoiceDiagnostics.findFirstErrorAndPreceding(clean)
        assertEquals(null, cleanResult)
    }

    @Test
    fun stageOriginalRoot109_copiesTreeVerbatimAndOnlyDropsLog() {
        val root = tempDir()
        try {
            val salma = File(root, "Salma")
            val salmaLf = File(salma, "salma_lf").apply { mkdirs() }
            val iniText = buildString {
                appendLine("MODE=prep_hd")
                appendLine("#1 FILEMAP \"salma_22k_lf.bvcu\" [ RAM ]")
                appendLine(
                    "#2 FILEMAP \"salma_22k_lf.bvcu.nuul216vqfp\" [ RAM ]"
                )
            }.padEnd(109, ' ')
            File(salmaLf, "ar_sa_hd_salma_22k_lf.ini").writeText(iniText)
            File(salmaLf, "salma_22k_lf.bvcu.nuul216vqfp")
                .writeBytes(ByteArray(1_215_248))
            File(salmaLf, "salma_22k_lf.bvcu").writeBytes(ByteArray(2048))
            File(salmaLf, "ar_sa_hd_salma_22k_lf.bvcu")
                .writeBytes(ByteArray(16))
            File(salmaLf, "0x00169729").writeText("COMMERCIAL")
            File(salmaLf, "logfile.txt").writeText("old log")

            val staged = AcapelaVoiceDiagnostics.stageOriginalRoot109(
                context = context,
                sourceDir = salma
            )

            val stagedLf = File(staged, "salma_lf")
            val stagedIni = File(
                stagedLf,
                "ar_sa_hd_salma_22k_lf.ini"
            )
            assertEquals(109L, stagedIni.length())
            assertEquals(
                iniText,
                stagedIni.readText(StandardCharsets.ISO_8859_1)
            )
            assertEquals(
                1_215_248L,
                File(stagedLf, "salma_22k_lf.bvcu.nuul216vqfp").length()
            )
            assertTrue(File(stagedLf, "salma_22k_lf.bvcu").exists())
            assertTrue(File(stagedLf, "0x00169729").exists())
            assertFalse(File(stagedLf, "logfile.txt").exists())
            // لا يُولَّد أي ini بديل: ملف واحد فقط في الشجرة كلها
            val iniCount = staged.walkTopDown()
                .count { it.isFile && it.extension.equals("ini", true) }
            assertEquals(1, iniCount)
        } finally {
            root.deleteRecursively()
            File(context.cacheDir, "acapela_stage_orig_root_109")
                .deleteRecursively()
        }
    }

    @Test
    fun getOriginalRoot109Verification_reportsQuotedNamesAndDump() {
        val root = tempDir()
        try {
            val salma = File(root, "Salma")
            val salmaLf = File(salma, "salma_lf").apply { mkdirs() }
            val iniText = buildString {
                appendLine("MODE=prep_hd")
                appendLine("#1 FILEMAP \"salma_22k_lf.bvcu\" [ RAM ]")
                appendLine(
                    "#2 FILEMAP \"salma_22k_lf.bvcu.nuul216vqfp\" [ RAM ]"
                )
                appendLine("#3 FILEMAP \"missing_nlp.bnx\" [ RAM ]")
            }
            File(salmaLf, "ar_sa_hd_salma_22k_lf.ini").writeText(iniText)
            File(salmaLf, "salma_22k_lf.bvcu").writeBytes(ByteArray(4))
            File(salmaLf, "salma_22k_lf.bvcu.nuul216vqfp")
                .writeBytes(ByteArray(8))
            File(salmaLf, "ar_sa.dca").writeBytes(ByteArray(2))

            AcapelaVoiceDiagnostics.stageOriginalRoot109(
                context = context,
                sourceDir = salma
            )
            val report = AcapelaVoiceDiagnostics
                .getOriginalRoot109Verification(context)

            assertTrue(report.contains("Path_Original_Root_109"))
            assertTrue(
                report.contains("\"salma_22k_lf.bvcu\" → موجود")
            )
            assertTrue(
                report.contains(
                    "\"salma_22k_lf.bvcu.nuul216vqfp\" → موجود"
                )
            )
            assertTrue(
                report.contains("\"missing_nlp.bnx\" → غير موجود")
            )
            assertTrue(report.contains("المحتوى كـ Hex"))
            assertTrue(report.contains("MODE=prep_hd"))
            assertTrue(report.contains("المتوقع 109 بايت"))
        } finally {
            root.deleteRecursively()
            File(context.cacheDir, "acapela_stage_orig_root_109")
                .deleteRecursively()
        }
    }

    @Test
    fun stageOriginalRoot109_keepsRootCopyWhenNameRepeatsInSubdir() {
        val root = tempDir()
        try {
            val salma = File(root, "Salma").apply { mkdirs() }
            val rootIni = File(salma, "ar_sa_hd_salma_22k_lf.ini")
            val rootText = "ROOT-109".padEnd(109, ' ')
            rootIni.writeText(rootText, StandardCharsets.ISO_8859_1)

            val salmaLf = File(salma, "salma_lf").apply { mkdirs() }
            val subIni = File(salmaLf, "ar_sa_hd_salma_22k_lf.ini")
            subIni.writeText("SUB-VERSION", StandardCharsets.ISO_8859_1)

            val staged = AcapelaVoiceDiagnostics.stageOriginalRoot109(
                context = context,
                sourceDir = salma
            )

            val stagedRootIni = File(
                staged,
                "ar_sa_hd_salma_22k_lf.ini"
            )
            val stagedSubIni = File(
                File(staged, "salma_lf"),
                "ar_sa_hd_salma_22k_lf.ini"
            )
            assertEquals(109L, stagedRootIni.length())
            assertEquals(
                rootText,
                stagedRootIni.readText(StandardCharsets.ISO_8859_1)
            )
            assertEquals(
                "SUB-VERSION",
                stagedSubIni.readText(StandardCharsets.ISO_8859_1)
            )
        } finally {
            root.deleteRecursively()
            File(context.cacheDir, "acapela_stage_orig_root_109")
                .deleteRecursively()
        }
    }

    @Test
    fun stageOriginalRoot109_doesNotPromoteSubdirIniToRoot() {
        val root = tempDir()
        try {
            val salmaLf = File(root, "Salma/salma_lf").apply { mkdirs() }
            val subIni = File(salmaLf, "ar_sa_hd_salma_22k_lf.ini")
            subIni.writeText("SUB-ONLY".padEnd(109, ' '))

            val staged = AcapelaVoiceDiagnostics.stageOriginalRoot109(
                context = context,
                sourceDir = File(root, "Salma")
            )

            // لا يُرفع محتوى salma_lf إلى الجذر: يبقى في مكانه فقط
            assertFalse(File(staged, "ar_sa_hd_salma_22k_lf.ini").exists())
            assertTrue(
                File(staged, "salma_lf/ar_sa_hd_salma_22k_lf.ini").exists()
            )
        } finally {
            root.deleteRecursively()
            File(context.cacheDir, "acapela_stage_orig_root_109")
                .deleteRecursively()
        }
    }

    @Test
    fun verifyRoot109IniBeforeInit_passesWith109BytesInRoot() {
        val dir = tempDir()
        try {
            File(dir, "ar_sa_hd_salma_22k_lf.ini")
                .writeText("OK".padEnd(109, ' '))
            File(dir, "bvcu.ini").writeText("MODE=\"prep_hd\"")

            val check = AcapelaVoiceDiagnostics
                .verifyRoot109IniBeforeInit(dir)

            assertTrue(check.passed)
            assertTrue(check.report.contains("النتيجة: نجاح"))
            assertTrue(check.report.contains("bvcu.ini"))
            assertTrue(check.report.contains("MD5="))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifyRoot109IniBeforeInit_blocksWhenSizeDiffers() {
        val dir = tempDir()
        try {
            File(dir, "ar_sa_hd_salma_22k_lf.ini")
                .writeText("SHORT".padEnd(108, ' '))

            val check = AcapelaVoiceDiagnostics
                .verifyRoot109IniBeforeInit(dir)

            assertFalse(check.passed)
            assertTrue(check.report.contains("النتيجة: فشل"))
            assertTrue(
                check.report.contains("لن يُستدعى nInit بهذا المجلد")
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifyRoot109IniBeforeInit_blocksWhenIniOnlyInSubdir() {
        val dir = tempDir()
        try {
            val sub = File(dir, "salma_lf").apply { mkdirs() }
            File(sub, "ar_sa_hd_salma_22k_lf.ini")
                .writeText("SUB".padEnd(109, ' '))

            val check = AcapelaVoiceDiagnostics
                .verifyRoot109IniBeforeInit(dir)

            assertFalse(check.passed)
            assertTrue(
                check.report.contains("غير موجود في جذر المجلد")
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun capSharedReport_keepsShortReportUnchanged() {
        val report = "تقرير قصير"
        assertEquals(
            report,
            AcapelaVoiceDiagnostics.capSharedReport(report)
        )
    }

    @Test
    fun capSharedReport_capsLengthAndKeepsHeadTrimsLogcatTail() {
        val head = "===acapela-stable-diagnostic==="
        val tail = "LOGCAT_TAIL_MARKER"
        val report = head +
            "x".repeat(AcapelaVoiceDiagnostics.MAX_SHARED_REPORT_CHARS) +
            tail

        val capped = AcapelaVoiceDiagnostics.capSharedReport(report)

        assertTrue(
            capped.length <=
                AcapelaVoiceDiagnostics.MAX_SHARED_REPORT_CHARS
        )
        assertTrue(capped.startsWith(head))
        assertFalse(capped.contains(tail))
        assertTrue(capped.contains("حُذفت أسطر logcat"))
    }

    @Test
    fun buildAcapelaFullReport_readsStableFileFirstAndUses10729Header() {
        val cache = context.cacheDir
        val stable = File(
            cache,
            AcapelaVoiceDiagnostics.ACAPELA_FULL_DIAGNOSTIC_FILE
        )
        val legacy = File(cache, "acapela_full_diagnostic_10728.txt")
        val stableMarker = "STABLE_DIAGNOSTIC_MARKER_10729"
        val legacyMarker = "LEGACY_10728_MARKER"
        try {
            stable.writeText(stableMarker, StandardCharsets.UTF_8)
            legacy.writeText(legacyMarker, StandardCharsets.UTF_8)

            val report = AcapelaVoiceDiagnostics.buildAcapelaFullReport(
                context
            )

            assertTrue(report.contains("(إصدار 10729)"))
            assertTrue(report.contains(stableMarker))
            assertTrue(report.contains(legacyMarker))
            val stablePos = report.indexOf(stableMarker)
            val legacyPos = report.indexOf(legacyMarker)
            val sectionsPos = report.indexOf("أدلة نجاح 10708")
            assertTrue(stablePos in 0 until legacyPos)
            assertTrue(legacyPos < sectionsPos)
            assertTrue(report.contains("أسطر logcat :engine_host"))
        } finally {
            stable.delete()
            legacy.delete()
        }
    }
}
