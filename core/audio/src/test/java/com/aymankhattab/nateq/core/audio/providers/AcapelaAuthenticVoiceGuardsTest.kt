package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * حوارس العقد الحاكم لمسار صوت Acapela الأصيل:
 * لا ini مصطنع، لا خلط أصوات، لا كسر للمسارات النسبية، لا خلط
 * لنتائج قديم/جديد، ولا تسريب أسرار في النص متعدد الأسطر.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AcapelaAuthenticVoiceGuardsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun tempDir(): File =
        Files.createTempDirectory("acapela_guard").toFile()

    /** تهيئة أصلية تحمل تبعيات نسبية حقيقية. */
    private fun authenticIni(): String = buildString {
        appendLine("PRO \"NLP/ar_sa.oso\" [ PROSODY ]")
        appendLine("DIC \"NLP/ar_sa.dca\" [ DICO ]")
        appendLine("SLV \"salma_lf/salma_22k_lf.bvcu\" [ FILE ]")
        appendLine("END \"\"")
    }

    private fun writeSalmaPackage(root: File, iniName: String): File {
        val voice = File(root, "Salma/salma_lf").apply { mkdirs() }
        File(voice, iniName).writeText(authenticIni())
        File(voice, "0x00169729").writeText("COMMERCIAL")
        File(voice, "NLP").mkdirs()
        File(voice, "NLP/ar_sa.oso").writeBytes(ByteArray(64))
        File(voice, "NLP/ar_sa.dca").writeBytes(ByteArray(64))
        File(voice, "salma_lf").mkdirs()
        File(voice, "salma_lf/salma_22k_lf.bvcu")
            .writeBytes(ByteArray(128))
        return voice
    }

    @Test
    fun fabricatedIni_isRejectedByContentNotBySize() {
        val root = tempDir()
        try {
            val fake = File(root, "ar_sa_hd_salma_22k_lf.ini")
            fake.writeText(
                "MODE=prep_hd\n" +
                    "#1 FILEMAP \"salma_22k_lf.bvcu\" [ RAM ]\n" +
                    "# padding".repeat(40) + "\n"
            )
            assertTrue(fake.length() > 300L)

            assertTrue(AcapelaVoiceManifest.isFabricatedIni(fake))
            assertFalse(AcapelaVoiceManifest.isAuthenticIni(fake))
            assertEquals(null, AcapelaVoiceManifest.findAuthenticIni(root))

            // تهيئة صغيرة لكنها أصلية بمحتواها تُقبل بلا شرط حجم.
            val real = File(root, "real.ini")
            real.writeText("PRO \"NLP/ar_sa.oso\" [ PROSODY ]\n")
            assertFalse(AcapelaVoiceManifest.isFabricatedIni(real))
            assertTrue(AcapelaVoiceManifest.isAuthenticIni(real))
            assertNotNull(AcapelaVoiceManifest.findAuthenticIni(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun iniDependencies_keepRelativePathsAndCaseInsensitiveResolution() {
        val root = tempDir()
        try {
            val voice = writeSalmaPackage(
                root, "ar_sa_hd_salma_22k_lf.ini"
            )
            val ini = AcapelaVoiceManifest.findAuthenticIni(voice)
            assertNotNull(ini)
            val deps = AcapelaVoiceManifest.parseIniDependencies(ini!!)

            assertTrue(deps.any {
                it.key == "PRO" && it.relativePath == "NLP/ar_sa.oso"
            })
            assertTrue(deps.any {
                it.key == "SLV" &&
                    it.relativePath == "salma_lf/salma_22k_lf.bvcu"
            })
            // لا تُسطَّح المسارات النسبية إلى أسماء ملفات بلا مجلد.
            assertFalse(deps.any {
                it.relativePath == "salma_22k_lf.bvcu"
            })

            val found = AcapelaVoiceManifest.resolveRelativeFile(
                voice, "nlp/AR_SA.OSO"
            )
            assertNotNull(found)
            assertTrue(found!!.isFile)
            assertTrue(
                found.name.equals("ar_sa.oso", ignoreCase = true)
            )
            assertTrue(
                found.parentFile?.name.equals("NLP", ignoreCase = true)
                    == true
            )
            assertEquals(64L, found.length())
            assertEquals(
                null,
                AcapelaVoiceManifest.resolveRelativeFile(
                    voice, "NLP/missing.oso"
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verifyAuthenticVoicePackage_rejectsForeignVoiceIni() {
        val root = tempDir()
        try {
            // ini باسم ليلى داخل حزمة سلمى = خلط أصوات ممنوع.
            val mixed = writeSalmaPackage(
                root, "ar_sa_hd_leila_22k_lf.ini"
            )
            val bad = AcapelaVoiceDiagnostics.verifyAuthenticVoicePackage(
                voiceDir = mixed,
                expectedSpeakerHint = "Salma"
            )
            assertFalse(bad.ok)
            assertTrue(
                bad.report,
                bad.issues.any { it.dependency == "voice-mixing" }
            )

            val clean = writeSalmaPackage(
                File(tempDir(), "clean"),
                "ar_sa_hd_salma_22k_lf.ini"
            )
            val good = AcapelaVoiceDiagnostics.verifyAuthenticVoicePackage(
                voiceDir = clean,
                expectedSpeakerHint = "Salma"
            )
            assertTrue(good.report, good.ok)
            assertFalse(
                good.issues.any { it.dependency == "voice-mixing" }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stageAuthenticVoiceForRun_isUniqueAndPreservesRelativeTree() {
        val root = tempDir()
        try {
            val voice = writeSalmaPackage(
                root, "ar_sa_hd_salma_22k_lf.ini"
            )
            val first = AcapelaVoiceDiagnostics
                .stageAuthenticVoiceForRun(context, voice, "run-A")
            val second = AcapelaVoiceDiagnostics
                .stageAuthenticVoiceForRun(context, voice, "run-B")

            assertNotEquals(first.absolutePath, second.absolutePath)
            assertTrue(first.isDirectory)
            assertTrue(second.isDirectory)

            // البنية النسبية نفسها في المرحلي، بلا ini مولّد جديد.
            assertTrue(
                File(first, "NLP/ar_sa.oso").isFile
            )
            assertTrue(
                File(first, "salma_lf/salma_22k_lf.bvcu").isFile
            )
            assertEquals(1, first.walkTopDown().count {
                it.isFile && it.extension.equals("ini", true)
            })

            val check = AcapelaVoiceDiagnostics
                .verifyAuthenticVoicePackage(
                    voiceDir = voice,
                    stagedDir = first,
                    expectedSpeakerHint = "Salma"
                )
            assertTrue(check.report, check.ok)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun evaluateTrialSuccess10724_requiresCallbackAndRealPcm() {
        assertFalse(
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
                speakCode = 0,
                samples = 44100L,
                nativeLines = emptyList(),
                callbackConnected = false
            )
        )
        assertFalse(
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
                speakCode = 0,
                samples = 0L,
                nativeLines = emptyList()
            )
        )
        assertTrue(
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
                speakCode = 0,
                samples = 44100L,
                nativeLines = listOf("done")
            )
        )
    }

    @Test
    fun redactSensitiveText_masksEveryLineBeforeDisplayOrExport() {
        val raw = """
            babParam.uid.passwd=SUPERSECRET123
            license: "LICENSEVALUE9999"
            nInit(ctx, "0xabcdef0123456789abcdef", param)
            api_key: AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHH
            harmless: keep me
        """.trimIndent()

        val cleaned = AcapelaVoiceDiagnostics.redactSensitiveText(raw)

        assertFalse(cleaned.contains("SUPERSECRET123"))
        assertFalse(cleaned.contains("LICENSEVALUE9999"))
        assertFalse(cleaned.contains("0xabcdef0123456789abcdef"))
        assertFalse(cleaned.contains("AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHH"))
        assertTrue(cleaned.contains("harmless: keep me"))
        assertEquals(raw.lines().size, cleaned.lines().size)
    }

    @Test
    fun liveReport_neverMixesHistoricalCodesWithFreshMeasurement() {
        val fresh = AcapelaVoiceDiagnostics.MeasuredNInitCodes(
            nlpInitError = 0,
            eb = 0,
            nSpeak = 0,
            pcmSamples = 44100L,
            note = "قياس حي"
        )
        val report = AcapelaVoiceDiagnostics
            .getNlpInitAndLicenseArgumentComparison(fresh)

        assertTrue(report.contains("nlpInitError=0"))
        assertTrue(report.contains("PCM=44100"))
        assertFalse(report.contains("-34"))
        assertFalse(report.contains("-27"))
        assertFalse(report.contains("PCM=0"))
    }
}
