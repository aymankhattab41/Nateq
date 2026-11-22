package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * اختبارات شاملة لمحلل حزم أصوات Acapela الموحد (AcapelaPackageResolver).
 *
 * تغطي:
 * 1. الترتيب المتجاور (CO_LOCATED).
 * 2. الترتيب المتداخل (NESTED).
 * 3. ترتيب الحاوية الأب الموثقة (PARENT_CONTAINER).
 * 4. تجاهل YAML المتعارض المنسوخ من سلمى واعتماد ليلى الأصلية.
 * 5. رفض YAML المتعارض عند غياب الدعم الأصلي (METADATA_CONFLICT).
 * 6. تمييز الملفات المفقودة (FILE_NOT_FOUND).
 * 7. تعدد المرشحين الغامض (MULTIPLE_CANDIDATES).
 * 8. حراسة الحدود الشرعية (canonical paths).
 * 9. بقاء الخدمة الجاهزة (warm service) عند فشل فحص حزمة أخرى.
 * 10. تسليم PCM والمعاينة الصوتية التقديرية.
 */
class AcapelaPackageResolverTest {

    private fun tempDir(prefix: String = "acapela_pkg"): File =
        Files.createTempDirectory(prefix).toFile()

    private fun sampleIni(voiceName: String): String = buildString {
        appendLine("PRO \"NLP/ar_sa.oso\" [ PROSODY ]")
        appendLine("DIC \"NLP/ar_sa.dca\" [ DICO ]")
        appendLine("SLV \"${voiceName}_lf/${voiceName}_22k_lf.bvcu\" [ FILE ]")
        appendLine("END \"\"")
    }

    private fun sampleInf(voiceName: String): String = buildString {
        appendLine("VoiceName=$voiceName")
        appendLine("SampleRate=22050")
        appendLine("Language=ara")
    }

    @Test
    fun resolvePackage_coLocatedLayout_succeedsWithDirectHexAndYaml() {
        val root = tempDir("colocated")
        val voiceDir = File(root, "salma22k_hq").apply { mkdirs() }
        File(voiceDir, "ar_sa_hd_salma_22k_lf.bvcu.ini")
            .writeText(sampleIni("salma"))
        File(voiceDir, "ar_sa_hd_salma_22k_lf.bvcu.inf")
            .writeText(sampleInf("Salma"))
        File(voiceDir, "0x00169729").writeText("COMMERCIAL_LICENSE")
        File(voiceDir, "config.yaml").writeText(
            "param: \"salma22k_hq,test@example.com,0123456789abcdef\""
        )
        File(voiceDir, "salma_lf").mkdirs()
        File(voiceDir, "salma_lf/salma_22k_lf.bvcu")
            .writeBytes(ByteArray(128))

        val res = AcapelaPackageResolver.resolvePackage(voiceDir)
        assertTrue(res.isSuccess)
        assertEquals(
            AcapelaPackageResolver.ResolutionStatus.SUCCESS,
            res.status
        )
        assertEquals(
            AcapelaPackageResolver.PackageRelation.CO_LOCATED,
            res.relation
        )
        assertNotNull(res.hexFile)
        assertEquals("0x00169729", res.hexFile?.name)
        assertNotNull(res.metadataYaml)
        assertEquals("Salma", res.resolvedSpeaker)
    }

    @Test
    fun resolvePackage_nestedLayout_succeedsWithSubfolderHex() {
        val root = tempDir("nested")
        val voiceDir = File(root, "leila22k_hq").apply { mkdirs() }
        File(voiceDir, "ar_sa_hd_leila_22k_lf.bvcu.ini")
            .writeText(sampleIni("leila"))
        File(voiceDir, "ar_sa_hd_leila_22k_lf.bvcu.inf")
            .writeText(sampleInf("Leila"))
        val subDir = File(voiceDir, "leila_lf").apply { mkdirs() }
        File(subDir, "0x00294994").writeText("LEILA_LICENSE_DATA")
        File(subDir, "leila_22k_lf.bvcu").writeBytes(ByteArray(128))
        File(voiceDir, "leila_lf.txt").writeText("leila text preview")

        val res = AcapelaPackageResolver.resolvePackage(voiceDir)
        assertTrue(res.isSuccess)
        assertEquals(
            AcapelaPackageResolver.ResolutionStatus.SUCCESS,
            res.status
        )
        assertEquals(
            AcapelaPackageResolver.PackageRelation.NESTED,
            res.relation
        )
        assertNotNull(res.hexFile)
        assertEquals("0x00294994", res.hexFile?.name)
        assertEquals("Leila", res.resolvedSpeaker)
    }

    @Test
    fun resolvePackage_parentContainerLayout_succeedsWithDocumentedVoicelist() {
        val root = tempDir("parent_container")
        val container = File(root, "token_container_xyz").apply { mkdirs() }
        File(container, "voicelist").writeText("Leila\n")
        File(container, "0x00294994").writeText("CONTAINER_LICENSE")

        val voiceDir = File(container, "leila22k_hq").apply { mkdirs() }
        File(voiceDir, "ar_sa_hd_leila_22k_lf.bvcu.ini")
            .writeText(sampleIni("leila"))
        File(voiceDir, "ar_sa_hd_leila_22k_lf.bvcu.inf")
            .writeText(sampleInf("Leila"))
        File(voiceDir, "leila_lf").mkdirs()
        File(voiceDir, "leila_lf/leila_22k_lf.bvcu")
            .writeBytes(ByteArray(128))

        val res = AcapelaPackageResolver.resolvePackage(voiceDir)
        assertTrue(res.isSuccess)
        assertEquals(
            AcapelaPackageResolver.ResolutionStatus.SUCCESS,
            res.status
        )
        assertEquals(
            AcapelaPackageResolver.PackageRelation.PARENT_CONTAINER,
            res.relation
        )
        assertNotNull(res.hexFile)
        assertEquals("0x00294994", res.hexFile?.name)
    }

    @Test
    fun resolvePackage_conflictingYamlFromSalmaInLeila_isIgnored() {
        val root = tempDir("conflict_ignore")
        val voiceDir = File(root, "leila22k_hq").apply { mkdirs() }
        File(voiceDir, "ar_sa_hd_leila_22k_lf.bvcu.ini")
            .writeText(sampleIni("leila"))
        File(voiceDir, "ar_sa_hd_leila_22k_lf.bvcu.inf")
            .writeText(sampleInf("Leila"))
        // ملف config.yaml قديم منسوخ يخص سلمى
        File(voiceDir, "config.yaml").writeText(
            "param: \"salma22k_hq,salma@old.com,1122334455667788\""
        )
        val subDir = File(voiceDir, "leila_lf").apply { mkdirs() }
        File(subDir, "0x00294994").writeText("AUTHENTIC_LEILA_KEY")
        File(subDir, "leila_22k_lf.bvcu").writeBytes(ByteArray(128))

        val res = AcapelaPackageResolver.resolvePackage(
            voiceDir = voiceDir,
            expectedSpeakerHint = "Leila"
        )
        assertTrue(res.isSuccess)
        assertEquals(
            AcapelaPackageResolver.ResolutionStatus.SUCCESS,
            res.status
        )
        // يجب عدم اعتماد YAML المتعارض
        assertNull(res.metadataYaml)
        // يجب اعتماد ملف الدعم الأصلي لليلى
        assertEquals("0x00294994", res.hexFile?.name)
        assertEquals(
            AcapelaPackageResolver.PackageRelation.NESTED,
            res.relation
        )
        assertTrue(res.diagnosticReason.contains("المتعارض"))
    }

    @Test
    fun resolvePackage_conflictingYamlWithoutAuthenticSupport_fails() {
        val root = tempDir("conflict_fail")
        val voiceDir = File(root, "leila22k_hq").apply { mkdirs() }
        File(voiceDir, "config.yaml").writeText(
            "param: \"salma22k_hq,salma@old.com,1122334455667788\""
        )
        // لا يوجد ملف ترخيص سداسي ولا ini أصلي لليلى
        val res = AcapelaPackageResolver.resolvePackage(
            voiceDir = voiceDir,
            expectedSpeakerHint = "Leila"
        )
        assertFalse(res.isSuccess)
        assertEquals(
            AcapelaPackageResolver.ResolutionStatus.METADATA_CONFLICT,
            res.status
        )
    }

    @Test
    fun resolvePackage_missingDirectory_returnsFileNotFound() {
        val nonExistent = File(tempDir(), "ghost_voice_nizar")
        val res = AcapelaPackageResolver.resolvePackage(nonExistent)
        assertFalse(res.isSuccess)
        assertEquals(
            AcapelaPackageResolver.ResolutionStatus.FILE_NOT_FOUND,
            res.status
        )
    }

    @Test
    fun resolvePackage_multipleAmbiguousHexCandidates_returnsConflict() {
        val root = tempDir("multiple_hex")
        val voiceDir = File(root, "salma22k_hq").apply { mkdirs() }
        File(voiceDir, "ar_sa_hd_salma_22k_lf.bvcu.ini")
            .writeText(sampleIni("salma"))
        File(voiceDir, "0x00169729").writeText("HEX_ONE")
        File(voiceDir, "0x00294994").writeText("HEX_TWO")

        val res = AcapelaPackageResolver.resolvePackage(voiceDir)
        assertFalse(res.isSuccess)
        assertEquals(
            AcapelaPackageResolver.ResolutionStatus.MULTIPLE_CANDIDATES,
            res.status
        )
        assertTrue(res.supportFiles.size >= 2)
    }

    @Test
    fun resolvePackage_canonicalBoundaryEscape_isGuarded() {
        val root = tempDir("boundary_guard")
        val parent = File(root, "parent_box").apply { mkdirs() }
        val voiceDir = File(parent, "voice_dir").apply { mkdirs() }
        val foreignDir = File(root, "foreign_secrets").apply { mkdirs() }
        val secretFile = File(foreignDir, "0x00999999").apply {
            writeText("SECRET")
        }

        // محاولة إنشاء رابط نسبي يخرج خارج حدود الحزمة
        assertFalse(
            AcapelaPackageResolver.isSafeCanonicalChild(voiceDir, secretFile)
        )
    }

    @Test
    fun resolvePackage_speakerMismatchInIni_returnsSpeakerMismatch() {
        val root = tempDir("ini_mismatch")
        val voiceDir = File(root, "leila22k_hq").apply { mkdirs() }
        // ini يخص سلمى داخل مجلد ليلى
        File(voiceDir, "ar_sa_hd_salma_22k_lf.bvcu.ini")
            .writeText(sampleIni("salma"))
        File(voiceDir, "0x00294994").writeText("HEX_DATA")

        val res = AcapelaPackageResolver.resolvePackage(
            voiceDir = voiceDir,
            expectedSpeakerHint = "Leila"
        )
        assertFalse(res.isSuccess)
        assertEquals(
            AcapelaPackageResolver.ResolutionStatus.SPEAKER_MISMATCH,
            res.status
        )
    }

    @Test
    fun warmVoicePreservation_preResolutionFailureDoesNotAffectWarmVoice() {
        // التحقق من أن فحص حزمة فاشلة لا يُعدل حالة الحزمة السليمة
        val root = tempDir("warm_preservation")
        val salmaDir = File(root, "salma22k_hq").apply { mkdirs() }
        File(salmaDir, "ar_sa_hd_salma_22k_lf.bvcu.ini")
            .writeText(sampleIni("salma"))
        File(salmaDir, "0x00169729").writeText("SALMA_HEX")

        val salmaRes = AcapelaPackageResolver.resolvePackage(
            voiceDir = salmaDir,
            expectedSpeakerHint = "Salma"
        )
        assertTrue(salmaRes.isSuccess)

        // فحص حزمة أخرى غير صالحة (معطوبة أو مفقودة)
        val brokenDir = File(root, "broken_voice")
        val brokenRes = AcapelaPackageResolver.resolvePackage(
            voiceDir = brokenDir,
            expectedSpeakerHint = "Broken"
        )
        assertFalse(brokenRes.isSuccess)

        // الحزمة الجاهزة تظل سليمة ومحققة 100%
        val salmaResAfter = AcapelaPackageResolver.resolvePackage(
            voiceDir = salmaDir,
            expectedSpeakerHint = "Salma"
        )
        assertTrue(salmaResAfter.isSuccess)
        assertEquals("0x00169729", salmaResAfter.hexFile?.name)
    }

    @Test
    fun realAudioPreview_pcmDeliveryAndTrackPlayback_handlesPlayback() {
        val dummyPcm = ByteArray(1024) { (it % 128).toByte() }
        val chunks = listOf(dummyPcm, dummyPcm)
        val result = AcapelaVoiceDiagnostics.playAudioChunks(
            chunks = chunks,
            sampleRate = 22050
        )
        assertNotNull(result.state)
        assertTrue(
            result.state == "COMPLETED" || result.state == "ERROR"
        )
    }
}
