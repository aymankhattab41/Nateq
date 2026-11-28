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
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AcapelaVoiceDiagnosticsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /**
     * مجلد مؤقت بآباء معزولين: [stageVoiceV2] و[stageFull] يمشيان أيضاً
     * في parentFile وparentFile.parentFile بحثاً عن ملفات MultiTTS، فلو
     * كان المجلد مباشرةً في مؤقت النظام لالتقط بقايا اختبارات سابقة
     * (مثل شجرة ليلى المستخرجة) فأنتج ini بـ26 سطراً بدل سطرين.
     */
    private fun tempDir(): File =
        File(
            Files.createTempDirectory("acapela_diag").toFile(),
            "a/b/source"
        ).apply { mkdirs() }

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
    fun stageVoiceFlat_createsFlatStructureWithoutFabricatedIni() {
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

            val staged = AcapelaVoiceDiagnostics.stageVoiceFlat(
                context,
                voice
            )

            // نسخ حرفي يحفظ البنية النسبية دون توليد/تعديل تهيئة.
            assertTrue(File(staged, "salma_22k_lf.bvcu").exists())
            assertTrue(File(staged, "NLP/ar_sa.dca").exists())
            assertTrue(File(staged, "NLP/ar_sa_unplugged.dca").exists())
            assertTrue(File(staged, "NLP/ar_sa_hde_arq.dca").exists())
            assertTrue(File(staged, "bvcu.inf").exists())
            assertEquals(
                "bvcu_data",
                File(staged, "salma_22k_lf.bvcu").readText()
            )
            assertEquals(
                "dca_data",
                File(staged, "NLP/ar_sa.dca").readText()
            )
            assertTrue(
                staged.walkTopDown().none {
                    it.isFile && AcapelaVoiceManifest.isFabricatedIni(it)
                }
            )
            assertTrue(File(staged, "config.yaml").exists())
            assertTrue(File(staged, "salma_lf.txt").exists())
            assertTrue(File(staged, "0x00169729").exists())
            assertTrue(File(staged, "user.userdico").exists())
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

            // نسخ حرفي يحفظ البنية/المسارات/حالة الأحرف دون توليد تهيئة.
            assertTrue(File(minDir, "salma_22k_lf.bvcu").exists())
            assertTrue(File(minDir, "bvcu.inf").exists())
            assertEquals("ini", File(minDir, "bvcu.ini").readText())
            assertTrue(File(minDir, "ar_sa.dca").exists())
            assertEquals(
                "extra",
                File(minDir, "unwanted_extra.txt").readText()
            )
            assertEquals(
                "dico",
                File(minDir, "userdicos/user.userdico").readText()
            )
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

            // نسخ حرفي يحفظ البنية/التبعيات دون توليد تهيئة.
            assertEquals("keep", File(nounpDir, "ar_sa.dca").readText())
            assertEquals("keep", File(nounpDir, "salma_22k_lf.bvcu").readText())
            assertEquals(
                "remove",
                File(nounpDir, "ar_sa_unplugged.dca").readText()
            )
            assertEquals(
                "remove",
                File(nounpDir, "ar_sa_hde_arq.dca").readText()
            )
            assertTrue(
                nounpDir.walkTopDown().none {
                    it.isFile && AcapelaVoiceManifest.isFabricatedIni(it)
                }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stageVoiceV2_copiesRequiredWithoutFabricatingIni() {
        val root = tempDir()
        try {
            File(root, "ar_sa.dca").writeText("dca")
            File(root, "salma_22k_lf.bvcu").writeText("bvcu")
            File(root, "ar_sa_hd_salma_22k_lf.bvcu.inf").writeText("inf")
            File(root, "config.yaml").writeText("yaml")
            File(root, "salma_lf.txt").writeText("txt")
            File(root, "0x00169729").writeText("hex")
            File(root, "3073.ini").writeText("ini3073")
            File(root, "MSTTSLocArEG.ini").writeText("mstts")
            File(root, "logfile.txt").writeText("oldlog")
            File(root, "test.nuul").writeText("nuul")
            val userdicos = File(root, "userdicos").apply { mkdirs() }
            File(userdicos, "user.userdico").writeText("dico")

            val v2 = AcapelaVoiceDiagnostics.stageVoiceV2(context, root)

            // نسخ حرفي يحفظ البنية/المسارات/حالة الأحرف والتبعيات.
            assertEquals("dca", File(v2, "ar_sa.dca").readText())
            assertEquals("bvcu", File(v2, "salma_22k_lf.bvcu").readText())
            assertEquals(
                "inf",
                File(v2, "ar_sa_hd_salma_22k_lf.bvcu.inf").readText()
            )
            assertEquals("yaml", File(v2, "config.yaml").readText())
            assertEquals("txt", File(v2, "salma_lf.txt").readText())
            assertEquals("hex", File(v2, "0x00169729").readText())
            assertEquals(
                "dico",
                File(v2, "userdicos/user.userdico").readText()
            )
            // ملفات المصدر تُنسخ حرفياً (لا توليد تهيئة بديلة).
            assertEquals("ini3073", File(v2, "3073.ini").readText())
            assertEquals("mstts", File(v2, "MSTTSLocArEG.ini").readText())
            assertEquals("nuul", File(v2, "test.nuul").readText())
            // سجلات قديمة لا تُنسخ.
            assertFalse(File(v2, "logfile.txt").exists())
            // لا يُنشأ أي ملف ini مُولّد/مُضاف.
            assertFalse(File(v2, "ar_sa_hd_salma_22k_lf.ini").exists())
            assertFalse(File(v2, "bvcu.inf").exists())
            assertFalse(File(v2, "bvcu.ini").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeStageV2Ini_neverFabricatesIni() {
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
                assertFalse(File(root, t).exists())
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

            // نسخ حرفي للملفات الموجودة فقط دون إنشاء نسخ/أسماء بديلة.
            assertTrue(destNamed.exists())
            assertFalse(destArSa.exists())
            assertEquals(bvcu.length(), destNamed.length())
            assertEquals(
                "[voice]\nname = ar_sa_hd_salma_22k_lf.bvcu\n",
                File(staged, "ar_sa_hd_salma_22k_lf.bvcu.inf").readText()
            )
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
        assertTrue(shaAnalysis.contains("مخفية"))
        assertTrue(shaAnalysis.contains("-34"))
        assertTrue(shaAnalysis.contains("لا تُعرض"))
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
    fun getStagingVsMultiTtsFilesComparison_usesMeasuredSizesAndMd5() {
        val stage = File(context.cacheDir, "acapela_stage").apply {
            mkdirs()
        }
        val original = File(
            context.filesDir, "Acapela/Salma/salma_lf"
        ).apply { mkdirs() }
        val stagedDca = File(stage, "ar_sa.dca")
        val originalDca = File(original, "ar_sa.dca")
        val stagedIni = File(stage, "ar_sa_hd_salma_22k_lf.ini")
        try {
            stagedDca.writeBytes("STAGED".toByteArray(StandardCharsets.UTF_8))
            originalDca.writeBytes(
                "ORIGINAL".toByteArray(StandardCharsets.UTF_8)
            )
            stagedIni.writeText(
                "MODE=prep_hd\n", StandardCharsets.ISO_8859_1
            )

            val comp = AcapelaVoiceDiagnostics
                .getStagingVsMultiTtsFilesComparison(context)

            assertTrue(comp.contains("بالقياس الفعلي"))
            assertTrue(comp.contains("ar_sa.dca"))
            assertTrue(comp.contains("salma_22k_lf.bvcu"))
            assertTrue(comp.contains("ar_sa_hd_salma_22k_lf.ini"))
            assertTrue(comp.contains("التجهيز: 6 بايت"))
            assertTrue(comp.contains("الأصلي : 8 بايت"))
            assertTrue(comp.contains("MD5="))
            assertTrue(comp.contains("مختلف الحجم أو البصمة"))
            assertTrue(comp.contains(AcapelaVoiceDiagnostics.calcFileMd5(
                stagedDca
            )))
            assertTrue(comp.contains("المسار=${stagedDca.absolutePath}"))
            assertTrue(comp.contains("المسار=${originalDca.absolutePath}"))
            assertTrue(comp.contains("النسبي=ar_sa.dca"))
            assertTrue(comp.contains("acapela_stage_orig_root_109"))
        } finally {
            stagedDca.delete()
            originalDca.delete()
            stagedIni.delete()
        }
    }

    @Test
    fun capSharedReport_movesAcapelaSectionToFrontBeforeTrimming() {
        val start = AcapelaVoiceDiagnostics.ACAPELA_REPORT_START
        val end = AcapelaVoiceDiagnostics.ACAPELA_REPORT_END
        val longTail = "أسطر logcat ".repeat(40)
        val report = "مقدمة عامة\n$start\nأقسام Acapela هنا\n$end\n$longTail"

        val capped = AcapelaVoiceDiagnostics.capSharedReport(
            report, maxChars = 120
        )

        assertTrue(capped.startsWith("أقسام Acapela هنا"))
        assertFalse(capped.contains(start))
        assertFalse(capped.contains(end))
        assertTrue(capped.length <= 120)
        assertTrue(capped.contains("تم الاقتطاع"))
    }

    @Test
    fun writeIniTargets_neverWritesFabricatedIni() {
        val original = File(
            context.filesDir,
            "acapela/Leila/leila22k_hq/" +
                "ar_sa_hd_leila_22k_lf.bvcu.ini"
        ).apply { parentFile?.mkdirs() }
        val target = File(tempDir(), "bvcu.ini")
        try {
            original.writeText("PRO\t\"NLP/ar_sa.oso\"\t\tPROSODY\n")

            val written = AcapelaVoiceDiagnostics.writeIniTargets(
                context, listOf(target)
            )

            // لا نسخ ضمني من مصدر مُخمَّن ولا كتابة أي تهيئة بديلة.
            assertEquals(0, written)
            assertFalse(target.exists())
        } finally {
            original.delete()
            target.parentFile?.deleteRecursively()
        }
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
        assertTrue(logDetails.contains("babParam.uid.userId <مقنّع>"))
        assertTrue(logDetails.contains("babParam.uid.passwd <مقنّع>"))
        assertTrue(logDetails.contains("مقنّعة في كلا الجانبين"))
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
    fun stage10709Exact_createsCleanStagingWithoutFabricatedIni() {
        val root = tempDir()
        try {
            val salma = File(root, "Acapela/Salma/salma_lf").apply {
                mkdirs()
            }
            File(salma, "salma_22k_lf.bvcu").writeText("TEST_BVCU")
            File(salma, "ar_sa_hd_salma_22k_lf.bvcu.inf")
                .writeText("[General]\nname=ar_sa_hd_salma_22k_lf\n")
            File(salma, "0x00169729").writeText("COMMERCIAL")
            File(salma, "salma_22k_lf.bvcu.nuul216vqfp").writeText("NUUL")

            val staged = AcapelaVoiceDiagnostics.stage10709Exact(
                context = context,
                sourceDir = salma
            )
            // نسخ حرفي يحفظ البنية/التبعيات/المسارات دون توليد تهيئة.
            assertEquals(
                "TEST_BVCU",
                File(staged, "salma_22k_lf.bvcu").readText()
            )
            assertEquals(
                "[General]\nname=ar_sa_hd_salma_22k_lf\n",
                File(staged, "ar_sa_hd_salma_22k_lf.bvcu.inf").readText()
            )
            assertEquals(
                "COMMERCIAL",
                File(staged, "0x00169729").readText()
            )
            assertEquals(
                "NUUL",
                File(staged, "salma_22k_lf.bvcu.nuul216vqfp").readText()
            )
            assertFalse(File(staged, "ar_sa_hd_salma_22k_lf.ini").exists())
            assertTrue(
                staged.walkTopDown().none {
                    it.isFile && AcapelaVoiceManifest.isFabricatedIni(it)
                }
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
    fun stageFull_withFlatNlp_copiesFilesWithoutFabricatingIni() {
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

        // نسخ حرفي يحفظ البنية النسبية/المسارات/حالة الأحرف.
        assertEquals(100, File(staged, "salma_22k_lf.bvcu").length())
        assertEquals(50, File(staged, "salma_22k_lf.bvcu.nuul216vqfp").length())
        assertEquals(80, File(staged, "ar_sa_hd_salma_22k_lf.bvcu").length())
        assertEquals(
            "speaker=Salma",
            File(staged, "ar_sa_hd_salma_22k_lf.bvcu.inf").readText()
        )
        assertEquals(61, File(staged, "0x00169729").length())
        assertEquals("name: Salma", File(staged, "config.yaml").readText())
        assertEquals(30, File(staged, "userdicos/ara-sau.userdico").length())
        assertEquals(100, File(staged, "NLP/ar_sa.bnx").length())
        assertEquals(200, File(staged, "NLP/ar_sa.dca").length())
        assertTrue(
            staged.walkTopDown().none {
                it.isFile && AcapelaVoiceManifest.isFabricatedIni(it)
            }
        )
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
        assertEquals("voice Salma (0) not loaded", result?.first)
        assertEquals("check_userdico : load error", result?.second)

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
    fun getOriginalRoot109Verification_reportsWithoutFixedSizeClaim() {
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

            assertTrue(report.contains("acapela_stage_orig_root_109"))
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
            // الحجم الثابت ليس معيار صحة؛ لا يُقدَّم ادعاء بصحة 109 بايت.
            assertFalse(report.contains("المتوقع 109 بايت"))
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
    fun verifyRoot109IniBeforeInit_neverTreatsFixedSizeAsValid() {
        val dir = tempDir()
        try {
            File(dir, "ar_sa_hd_salma_22k_lf.ini")
                .writeText("OK".padEnd(109, ' '))
            File(dir, "bvcu.ini").writeText("MODE=\"prep_hd\"")

            val check = AcapelaVoiceDiagnostics
                .verifyRoot109IniBeforeInit(dir)

            // 109 بايت ليس معيار صحة؛ لا يُفتح nInit بناءً عليه.
            assertFalse(check.passed)
            assertTrue(check.report.contains("النتيجة: فشل"))
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
    fun capSharedReport_capsLengthAndKeepsHeadAndLogcatTailTrimsMiddle() {
        val head = "===acapela-stable-diagnostic==="
        val middle = "MIDDLE_CHUNK_TO_DROP_".repeat(200)
        val tail = "LOGCAT_TAIL_MARKER"
        val report = head +
            "x".repeat(AcapelaVoiceDiagnostics.MAX_SHARED_REPORT_CHARS) +
            middle +
            tail

        val capped = AcapelaVoiceDiagnostics.capSharedReport(report)

        assertTrue(
            capped.length <=
                AcapelaVoiceDiagnostics.MAX_SHARED_REPORT_CHARS
        )
        assertTrue(capped.startsWith(head))
        assertTrue(capped.contains(tail))
        assertTrue(capped.contains("تم الاقتطاع"))
    }

    @Test
    fun buildAcapelaFullReport_readsStableFileFirstAndUses10730Header() {
        val cache = context.cacheDir
        val stable = File(
            cache,
            AcapelaVoiceDiagnostics.ACAPELA_FULL_DIAGNOSTIC_FILE
        )
        val legacy = File(cache, "acapela_full_diagnostic_10728.txt")
        val stableMarker = "STABLE_DIAGNOSTIC_MARKER_CURRENT"
        val legacyMarker = "LEGACY_10728_MARKER"
        try {
            stable.writeText(stableMarker, StandardCharsets.UTF_8)
            legacy.writeText(legacyMarker, StandardCharsets.UTF_8)

            val report = AcapelaVoiceDiagnostics.buildAcapelaFullReport(
                context
            )

            // تقرير المحاولة الحالية فقط (ملف التشخيص المقيد) بلا إلحاق إرث.
            assertEquals(stableMarker, report)
            assertFalse(report.contains(legacyMarker))
        } finally {
            stable.delete()
            legacy.delete()
        }
    }

    @Test
    fun buildAcapelaFullReport_appendsLastRealSpeechFailureWhenPresent() {
        val cache = context.cacheDir
        val stable = File(
            cache,
            AcapelaVoiceDiagnostics.ACAPELA_FULL_DIAGNOSTIC_FILE
        )
        val failureFile = File(
            cache,
            AcapelaVoiceDiagnostics.ACAPELA_LAST_REAL_SPEECH_FAILURE_FILE
        )
        val stableMarker = "=== تشخيص Acapela ===\nالمحاولة الحالية ناجحة"
        val failureMarker =
            "=== آخر محاولة نطق حقيقية فاشلة ===\nكود nSpeak: -2"
        try {
            stable.writeText(stableMarker, StandardCharsets.UTF_8)
            failureFile.writeText(failureMarker, StandardCharsets.UTF_8)

            val report = AcapelaVoiceDiagnostics.buildAcapelaFullReport(
                context
            )

            assertTrue(report.contains(stableMarker))
            assertTrue(report.contains("=== آخر محاولة نطق حقيقية فاشلة ==="))
            assertTrue(report.contains("كود nSpeak: -2"))
        } finally {
            stable.delete()
            failureFile.delete()
        }
    }

    @Test
    fun getNlpInitComparison_neverPresentsFixedCodesAsMeasured() {
        val report = AcapelaVoiceDiagnostics
            .getNlpInitAndLicenseArgumentComparison()

        assertTrue(report.contains("المرصود"))
        assertTrue(report.contains("nlpInitError=لم تُرصد"))
        assertFalse(report.contains(
            "nlpInitError=-34 | eb=-27 | nSpeak=-2 | PCM=0"
        ))
        assertTrue(report.contains("الفرضيات"))
        assertFalse(report.contains("الترخيص نفسه سليم"))
        assertFalse(report.contains("package_check"))
        assertFalse(report.contains("الأصل 109 بايت"))

        val injected = AcapelaVoiceDiagnostics
            .getNlpInitAndLicenseArgumentComparison(
                AcapelaVoiceDiagnostics.MeasuredNInitCodes(
                    nlpInitError = -9,
                    eb = -8,
                    nSpeak = -7,
                    pcmSamples = 12L
                )
            )
        assertTrue(injected.contains("nlpInitError=-9"))
        assertTrue(injected.contains("PCM=12"))
    }

    @Test
    fun stagingComparison_prefersMultiTtsAndStatesUnreadableSources() {
        val stage = tempDir()
        val multittsRoot = tempDir()
        val multitts = File(multittsRoot, "multitts_files").apply {
            mkdirs()
        }
        val nateq = tempDir()
        val missingParent = tempDir()
        val missing = File(missingParent, "missing_private_folder")
        try {
            File(stage, "ar_sa.dca").writeText("STAGE")
            File(multitts, "ar_sa.dca").writeText("REF")
            File(nateq, "ar_sa.dca").writeText("NATEQ")
            val comp = AcapelaVoiceDiagnostics
                .getStagingVsMultiTtsFilesComparison(
                    context,
                    stageRootsOverride = listOf(stage),
                    originalRootsOverride = listOf(
                        nateq, multitts, missing
                    )
                )

            assertTrue(comp.contains(
                "المصدر المرجعي المفضّل: " + multitts.absolutePath
            ))
            assertTrue(comp.contains(
                "المرجع المختار: " + multitts.absolutePath
            ))
            assertTrue(comp.contains(stage.absolutePath))
            val refPath = File(multitts, "ar_sa.dca").absolutePath
            assertTrue(comp.contains("المسار=$refPath"))
            assertTrue(comp.contains(
                "⚠ تعذّرت قراءة مجلد التطبيق الخاص"
            ))
        } finally {
            stage.deleteRecursively()
            multittsRoot.deleteRecursively()
            nateq.deleteRecursively()
            missingParent.deleteRecursively()
        }
    }

    @Test
    fun getMeasuredIniFilesReport_logsPathsWithoutFixedLiveCodes() {
        val root = tempDir()
        try {
            val original = File(root, "working.ini").apply {
                writeBytes(ByteArray(61))
            }
            val staged109 = File(root, "stage109.ini").apply {
                writeBytes(ByteArray(109))
            }
            val staged121 = File(root, "stage121.ini").apply {
                writeBytes(ByteArray(121))
            }
            val report = AcapelaVoiceDiagnostics
                .getMeasuredIniFilesReport(
                    listOf(
                        AcapelaVoiceDiagnostics.MeasuredIniFile(
                            "الأصل", original
                        ),
                        AcapelaVoiceDiagnostics.MeasuredIniFile(
                            "تجهيز 109", staged109
                        ),
                        AcapelaVoiceDiagnostics.MeasuredIniFile(
                            "تجهيز 121", staged121
                        )
                    )
                )

            assertTrue(report.contains("الأصل: 61 بايت"))
            assertTrue(report.contains(original.absolutePath))
            assertTrue(report.contains("تجهيز 109: 109 بايت"))
            assertTrue(report.contains(staged109.absolutePath))
            assertTrue(report.contains("تجهيز 121: 121 بايت"))
            assertTrue(report.contains(staged121.absolutePath))
            assertTrue(report.contains("nlpInitError=لم تُرصد"))
            assertFalse(report.contains(
                "nlpInitError=-34 | eb=-27 | nSpeak=-2 | PCM=0"
            ))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stageMultiTtsVerbatim_copiesAllFilesWithoutGeneratingIni() {
        val root = tempDir()
        try {
            val source = File(root, "MultiTTS").apply { mkdirs() }
            val sub = File(source, "NLP").apply { mkdirs() }
            val iniBytes = "MODE=prep_hd\n".toByteArray(
                StandardCharsets.ISO_8859_1
            )
            File(source, "ar_sa_hd_salma_22k_lf.ini")
                .writeBytes(iniBytes)
            File(source, "salma_22k_lf.bvcu")
                .writeBytes(ByteArray(2048) { it.toByte() })
            File(sub, "ar_sa.dca").writeBytes(ByteArray(64))

            val experiment = AcapelaVoiceDiagnostics
                .stageMultiTtsVerbatim(context, source)
            val staged = experiment.staged!!

            assertTrue(experiment.readable)
            assertEquals(3, experiment.copiedFiles)
            org.junit.Assert.assertArrayEquals(
                iniBytes,
                File(staged, "ar_sa_hd_salma_22k_lf.ini").readBytes()
            )
            assertTrue(File(staged, "NLP/ar_sa.dca").exists())
            assertEquals(
                File(source, "salma_22k_lf.bvcu").length(),
                File(staged, "salma_22k_lf.bvcu").length()
            )
            val iniCount = staged.walkTopDown()
                .count { it.isFile && it.extension.equals("ini", true) }
            assertEquals(1, iniCount)

            val injected = experiment.copy(
                codes = AcapelaVoiceDiagnostics.MeasuredNInitCodes(
                    nlpInitError = -34,
                    eb = -27,
                    nSpeak = -2,
                    pcmSamples = 0L
                )
            )
            val report = AcapelaVoiceDiagnostics
                .getMultiTtsVerbatimExperimentReport(injected)
            assertTrue(report.contains(source.absolutePath))
            assertTrue(report.contains(staged.absolutePath))
            assertTrue(report.contains("ini مولَّد: لا"))
            assertTrue(report.contains(
                "nlpInitError=-34 | eb=-27 | nSpeak=-2 | PCM=0"
            ))
        } finally {
            root.deleteRecursively()
            File(context.cacheDir, "acapela_stage_multitts_verbatim")
                .deleteRecursively()
        }
    }

    @Test
    fun getMultiTtsVerbatimReport_statesUnreadableFolderExplicitly() {
        val experiment = AcapelaVoiceDiagnostics
            .stageMultiTtsVerbatim(
                context,
                sourceDir = File("no_such_multitts_folder_12345")
            )
        val report = AcapelaVoiceDiagnostics
            .getMultiTtsVerbatimExperimentReport(experiment)

        assertFalse(experiment.readable)
        assertEquals(null, experiment.staged)
        assertTrue(report.contains("تعذّرت قراءة مجلد MultiTTS"))
        assertTrue(report.contains("لا نسخة ولا nInit"))
    }

    private fun findMultiTtsZipForTest(): File? {
        var current: File? = File(".").absoluteFile
        repeat(6) {
            val candidate = File(current, "multitts_files.zip")
            if (candidate.isFile) return candidate
            current = current?.parentFile
        }
        return null
    }

    private fun relativePath(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    private fun md5Of(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeFakeMultiTtsVoiceTree(voiceRoot: File) {
        val tokenDir = File(
            voiceRoot,
            "acapela/88GqqbfobVqqleus52QYWilZ0Nc0DtPaQztCeFMhsGg=" +
                "/leila22k_hq"
        ).apply { mkdirs() }
        val nlp = File(tokenDir, "NLP").apply { mkdirs() }
        val leila = File(tokenDir, "leila_lf").apply { mkdirs() }
        File(nlp, "ar_sa.dca").writeBytes(ByteArray(4096) { it.toByte() })
        File(tokenDir, "ar_sa_hd_leila_22k_lf.bvcu.ini").writeText(
            "PRO\t\"NLP/ar_sa.oso\"\n" +
                "DIC\t\"NLP/ar_sa.dca\"\n" +
                "SLV\t\"leila_lf/leila_22k_lf.bvcu\"\t[FILE]\n"
        )
        File(tokenDir, "ar_sa_hd_leila_22k_lf.bvcu.inf").writeText(
            "speaker = \"Leila\"\nISOCode = \"ara-SAU\"\n"
        )
        File(leila, "leila_22k_lf.bvcu")
            .writeBytes(ByteArray(2048) { it.toByte() })
        File(tokenDir.parentFile, "logfile.txt").writeText("forbidden")
        File(voiceRoot, "config.yaml").writeText("acapela: []\n")
    }

    @Test
    fun `verbatim copy drops no original bytes nor adds generated ini`() {
        val root = tempDir()
        val source = File(root, "source/voice").apply { mkdirs() }
        val target = File(root, "target/voice")
        val zip = findMultiTtsZipForTest()
        if (zip != null) {
            ZipFile(zip).use { zf ->
                val prefix = "multitts_files/files/"
                val entries = zf.entries().toList().filter { e ->
                    val name = e.name.replace('\\', '/')
                    name.startsWith(prefix + "voice/") && !e.isDirectory &&
                        !name.substringAfterLast('/').equals(
                            AcapelaVoiceDiagnostics.LOG_FILE_NAME,
                            ignoreCase = true
                        )
                }
                assertTrue(entries.size >= 40)
                for (e in entries) {
                    val rel = e.name.substring(prefix.length)
                    val out = File(source, rel)
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { input ->
                        FileOutputStream(out).use { output ->
                            input.copyTo(output, 64 * 1024)
                        }
                    }
                }
            }
        } else {
            writeFakeMultiTtsVoiceTree(source)
        }

        val copied = AcapelaVoiceImporter.copyVoiceTreeVerbatim(
            source, target
        )
        assertTrue(copied > 0)

        val sourceFiles = source.walkTopDown().filter { it.isFile }
            .associate { relativePath(source, it) to it }
        val targetFiles = target.walkTopDown().filter { it.isFile }
            .associate { relativePath(target, it) to it }
        val expectedTargetKeys = sourceFiles.keys.filterNot {
            it.substringAfterLast('/').equals(
                AcapelaVoiceDiagnostics.LOG_FILE_NAME,
                ignoreCase = true
            )
        }.toSet()
        assertEquals(expectedTargetKeys, targetFiles.keys)
        assertFalse(targetFiles.keys.any {
            it.substringAfterLast('/').equals(
                AcapelaVoiceDiagnostics.LOG_FILE_NAME,
                ignoreCase = true
            )
        })

        val sourceInis = sourceFiles.keys.filter {
            it.endsWith(".ini", ignoreCase = true)
        }.sorted()
        val targetInis = targetFiles.keys.filter {
            it.endsWith(".ini", ignoreCase = true)
        }.sorted()
        assertEquals(sourceInis, targetInis)

        for ((rel, src) in sourceFiles) {
            val dst = targetFiles[rel] ?: continue
            when {
                rel.endsWith(".ini", ignoreCase = true) ->
                    org.junit.Assert.assertArrayEquals(
                        src.readBytes(), dst.readBytes()
                    )
                rel.endsWith(".dca", ignoreCase = true) ||
                    src.length() > 1_000_000L -> {
                    assertEquals(src.length(), dst.length())
                    assertEquals(md5Of(src), md5Of(dst))
                }
                else -> org.junit.Assert.assertArrayEquals(
                    src.readBytes(), dst.readBytes()
                )
            }
        }

        if (zip != null) {
            val leilaRel = "voice/acapela/" +
                "88GqqbfobVqqleus52QYWilZ0Nc0DtPaQztCeFMhsGg=" +
                "/leila22k_hq/ar_sa_hd_leila_22k_lf.bvcu.ini"
            val leila = targetFiles[leilaRel]
            assertNotNull(leila)
            assertEquals(1047L, leila?.length())
            assertEquals(
                "7f28c7e3c123b6dd952e583509d77293",
                leila?.let(::md5Of)
            )
        }
    }

    @Test
    fun lastRealSpeechFailure_readAndClear_worksCorrectly() {
        val failFile = File(
            context.cacheDir,
            AcapelaVoiceDiagnostics.ACAPELA_LAST_REAL_SPEECH_FAILURE_FILE
        )
        failFile.writeText(
            "=== آخر محاولة نطق حقيقية فاشلة ===\nrunId: test1234\n"
        )
        val content = AcapelaVoiceDiagnostics
            .readLastRealSpeechFailure(context)
        assertNotNull(content)
        assertTrue(content!!.contains("runId: test1234"))

        AcapelaVoiceDiagnostics.clearLastRealSpeechFailure(context)
        assertFalse(failFile.exists())
        assertEquals(
            null,
            AcapelaVoiceDiagnostics.readLastRealSpeechFailure(context)
        )
    }

    @Test
    fun redactSensitiveLines_multilineLicensePayloadMaskedFully() {
        val lines = listOf(
            "05-23 12:34:56.789 1234 1234 I acattsandroid: " +
                "\tbabParam.license MIIEvgIBADANBgkqhkiG9w0BAQEFAASC",
            "05-23 12:34:56.789 1234 1234 I acattsandroid: /VwPChunkOne==",
            "05-23 12:34:56.789 1234 1234 I acattsandroid: x+JdChunkTwo==",
            "05-23 12:34:56.790 1234 1234 I acattsandroid: " +
                "\tbabParam.uid.userId 12345",
            "05-23 12:34:56.791 1234 1234 I acattsandroid: " +
                "tts_function_load_voice : Salma - done"
        )

        val redacted = AcapelaVoiceDiagnostics.redactSensitiveLines(lines)

        assertEquals(lines.size, redacted.size)
        // لا تظهر الحمولة الخام في أي سطر
        assertFalse(
            redacted.any { it.contains("MIIEvgIBADANBgkqhkiG9w0BAQEFAASC") }
        )
        assertFalse(redacted.any { it.contains("/VwPChunkOne==") })
        assertFalse(redacted.any { it.contains("x+JdChunkTwo==") })
        assertFalse(redacted.any { it.contains("12345") })

        // السطر الأول يحوي الطول المنقى
        assertTrue(redacted[0].contains("babParam.license: [محجوب]"))

        // الأسطر التالية مقنعة بصيغة واضحة
        assertTrue(redacted[1].contains("[حمولة ترخيص محجوبة]"))
        assertTrue(redacted[2].contains("[حمولة ترخيص محجوبة]"))

        // السطر التالي لـ tts_function_load_voice يبقى كما هو بلا إفساد
        assertTrue(
            redacted[4].contains("tts_function_load_voice : Salma - done")
        )

        // فحص redactSensitiveText لنفس الكتلة كنص كامل
        val textBlock = lines.joinToString("\n")
        val cleanedText = AcapelaVoiceDiagnostics.redactSensitiveText(
            textBlock
        )
        assertFalse(cleanedText.contains("/VwPChunkOne=="))
        assertFalse(cleanedText.contains("x+JdChunkTwo=="))
        assertTrue(
            cleanedText.contains("tts_function_load_voice : Salma - done")
        )
    }

    @Test
    fun sanitizeNativeReportLines_allowlistBlocksRawLicenseValues() {
        val fakeReg = "0x00FAKE42"
        val fakeUser = "user_fake_12345"
        val fakePass = "pass_fake_67890"
        val rawLines = listOf(
            "05-23 12:34:56.789 1 1 I acattsandroid: registration=$fakeReg",
            "05-23 12:34:56.789 1 1 I acattsandroid: " +
                "babParam.uid.userId $fakeUser",
            "05-23 12:34:56.789 1 1 I acattsandroid: " +
                "babParam.uid.passwd $fakePass",
            "05-23 12:34:56.790 1 1 I acattsandroid: nSpeak: -25",
            "05-23 12:34:56.791 1 1 I acattsandroid: " +
                "babParam.license MIIEFAKEBLOB1234567890" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        )
        val sanitized =
            AcapelaVoiceDiagnostics.sanitizeNativeReportLines(rawLines)
        val joined = sanitized.joinToString("\n")
        assertFalse(joined.contains(fakeReg))
        assertFalse(joined.contains(fakeUser))
        assertFalse(joined.contains(fakePass))
        assertFalse(joined.contains("MIIEFAKEBLOB1234567890"))
        assertTrue(
            "السطر الآمن يجب أن يمر بعد السماح",
            sanitized.any { it.contains("nSpeak") }
        )
        assertTrue(
            "السطور الحساسة يجب أن تُحجب بقائمة سماح",
            sanitized.count { it.contains("[محجوب") } >= 4
        )
    }

    @Test
    fun previewWithEmptyDisk_doesNotConstructPhantomMehdiFolder() =
        kotlinx.coroutines.test.runTest {
            val baseDir = EmbeddedStorage.baseDir(
                context,
                EmbeddedStorage.Engine.ACAPELA
            )
            if (baseDir.exists()) baseDir.deleteRecursively()
            baseDir.mkdirs()
            AcapelaVoiceManifest.invalidateVoicesCache()

            AcapelaVoiceDiagnostics.runDiagnosticProbe(context)

            val diagFile = File(
                context.cacheDir,
                AcapelaVoiceDiagnostics.ACAPELA_FULL_DIAGNOSTIC_FILE
            )
            assertTrue(diagFile.exists())
            val text = diagFile.readText(StandardCharsets.UTF_8)
            assertTrue(text.contains("لا يوجد صوت مستورد"))
            assertFalse(text.contains("Mehdi [Mehdi]"))
            assertFalse(text.contains("--- صوت (1/1): Mehdi"))
        }
}
