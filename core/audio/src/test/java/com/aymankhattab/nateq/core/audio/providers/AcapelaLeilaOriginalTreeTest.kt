package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * حوارس البند ١ و٤ و٦: النسخ الحرفي لشجرة ليلى، ومطابقة الترخيص،
 * وتقرير المقارنة، وإخفاء القيم الحساسة.
 */
class AcapelaLeilaOriginalTreeTest {

    private fun tempDir(): File =
        Files.createTempDirectory("leila_verbatim").toFile()

    private fun findArchive(): File? {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val candidate = File(dir, "multitts_files.zip")
            if (candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        return null
    }

    @Test
    fun manifest_coversEveryOriginalLeilaFileByNameAndHash() {
        val files = AcapelaLeilaOriginalTree.originalFiles
        assertEquals(27, files.size)
        val byPath = files.associateBy { it.relativePath }
        val required = listOf(
            "leila22k_hq/ar_sa_hd_leila_22k_lf.bvcu.ini",
            "leila22k_hq/ar_sa_hd_leila_22k_lf.bvcu.inf",
            "leila22k_hq/NLP/ar_sa.oso",
            "leila22k_hq/NLP/ar_sa.dca",
            "leila22k_hq/leila_lf/leila_22k_lf.bvcu",
            "leila22k_hq/leila_lf/" +
                "leila_22k_lf.bvcu.nuul216vqfp",
            "userdicos/ara-sau.userdico",
            "voicelist",
            "0x00294994"
        )
        required.forEach { assertTrue("مفقود: $it", byPath.containsKey(it)) }
        val ini = byPath.getValue(required[0])
        assertEquals(1047L, ini.size)
        assertEquals("7f28c7e3c123b6dd952e583509d77293", ini.md5)
        assertEquals(
            "5f2b80e9fab385aa9096aaf91a6135af" +
                "cdc3a91e0f249101ffc164c44ceaed66",
            ini.sha256
        )
        assertTrue(files.all { it.sha256.length == 64 })
        assertTrue(files.all { it.md5.length == 32 })
        assertFalse(files.any {
            it.relativePath.substringAfterLast('/').equals(
                AcapelaLeilaOriginalTree.LOG_FILE_NAME, true
            )
        })
    }

    @Test
    fun verbatimExtraction_keepsPathsAndEveryFileHash() {
        val zip = findArchive()
        assumeTrue("multitts_files.zip غير موجود", zip != null)
        val target = File(tempDir(), "voice/acapela/" +
            AcapelaLeilaOriginalTree.TOKEN_DIR)
        val copied = AcapelaLeilaOriginalTree
            .extractVerbatim(zip!!, target)
        assertEquals(27, copied)
        val verification = AcapelaLeilaOriginalTree.verify(target)
        assertTrue(verification.asReport(), verification.isVerbatim)
        assertEquals(27, verification.matched.size)
        assertTrue(verification.missing.isEmpty())
        assertTrue(verification.mismatched.isEmpty())
        assertTrue(verification.extra.isEmpty())
        assertTrue(verification.generatedIni.isEmpty())
        val ini = File(
            target,
            AcapelaLeilaOriginalTree.VOICE_DIR + "/" +
                AcapelaLeilaOriginalTree.MAIN_INI
        )
        assertEquals(1047L, ini.length())
        assertTrue(File(target, "leila22k_hq/NLP").isDirectory)
        assertTrue(File(target, "leila22k_hq/leila_lf").isDirectory)
        assertTrue(File(target, "userdicos").isDirectory)
        assertFalse(File(target, AcapelaLeilaOriginalTree.LOG_FILE_NAME)
            .exists())
    }

    @Test
    fun trialReport_neverShowsFixedCodeAsMeasured() {
        val verification = AcapelaLeilaOriginalTree.Verification(
            matched = listOf("a"),
            missing = emptyList(),
            mismatched = emptyList(),
            extra = emptyList(),
            generatedIni = emptyList()
        )
        val report = AcapelaLeilaOriginalTree.trialReport(verification)
        assertTrue(report.contains("Path_Original_Leila_Verbatim"))
        assertTrue(report.contains("لم يُشغَّل nInit/nSpeak"))
        assertTrue(report.contains("غير مقيسة"))
        // لا يُعرض أي كود قديم (-34/-27/-2) بوصفه قياساً حياً.
        assertFalse(report.contains("nlpInitError=-34"))
        assertTrue(report.contains("غير مقيس"))
        val measured = AcapelaLeilaOriginalTree.trialReport(
            verification,
            AcapelaVoiceDiagnostics.MeasuredNInitCodes(
                nlpInitError = 0,
                eb = 0,
                nSpeak = 0,
                pcmSamples = 128L
            )
        )
        assertTrue(measured.contains("PCM=128"))
        assertFalse(measured.contains("غير مقيسة"))
    }

    @Test
    fun licenseFlowReport_neverPrintsSensitiveValues() {
        val report = AcapelaLeilaOriginalTree.licenseFlowReport()
        assertTrue(report.contains("nSetLicense"))
        assertTrue(report.contains("idfunction"))
        assertTrue(report.contains("nInit"))
        assertTrue(report.contains("محظور"))
        assertTrue(report.contains("مقنّعة فقط"))
        val masked = AcapelaVoiceDiagnostics.redactSensitive("abcdefgh")
        assertEquals("[محجوب]", masked)
        assertFalse(masked.contains("abcdefgh"))
        val maskedLine = AcapelaVoiceDiagnostics.redactSensitiveLine(
            "babParam.license secret-value-here"
        )
        assertFalse(maskedLine.contains("secret-value-here"))
        assertTrue(maskedLine.contains("[محجوب]"))
    }

    @Test
    fun comparisonReport_groupsByVoiceAndEndsWithSummary() {
        val zip = findArchive()
        assumeTrue("multitts_files.zip غير موجود", zip != null)
        val copied = File(tempDir(), "voice")
        AcapelaLeilaOriginalTree.extractVerbatim(
            zip!!, File(copied, "acapela/" +
                AcapelaLeilaOriginalTree.TOKEN_DIR)
        )
        val comparison = AcapelaVoiceFileComparison.compare(zip, copied)
        assertTrue(comparison.voices.any {
            it.name == AcapelaLeilaOriginalTree.VOICE_DIR
        })
        val leila = comparison.voices.first {
            it.name == AcapelaLeilaOriginalTree.VOICE_DIR
        }
        assertTrue(leila.rows.size >= 24)
        assertTrue(leila.allMatch)
        val text = comparison.asText()
        assertTrue(text.contains("[${AcapelaLeilaOriginalTree.VOICE_DIR}]"))
        assertTrue(text.lines().last().startsWith("الخلاصة:"))
        assertTrue(text.contains("E_BABILE_LICENSE"))
        assertTrue(text.contains("لم يتغير مسار الإنتاج"))
        assertTrue(text.contains("المستبعد بالقياس"))
    }
}
