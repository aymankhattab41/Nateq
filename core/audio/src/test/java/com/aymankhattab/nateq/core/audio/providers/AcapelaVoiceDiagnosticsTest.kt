package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AcapelaVoiceDiagnosticsTest {

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
                registrationLength = 40
            )

            assertTrue(report.contains("- الرخصة المتوقعة: "))
            assertTrue(report.contains("غير موجود"))
            assertTrue(report.contains("0x00169729"))
            assertTrue(report.contains("طول التسجيل: 40"))
            assertTrue(report.contains("error - eb 1"))
            assertTrue(!report.contains("secret"))
        } finally {
            root.deleteRecursively()
        }
    }
}
