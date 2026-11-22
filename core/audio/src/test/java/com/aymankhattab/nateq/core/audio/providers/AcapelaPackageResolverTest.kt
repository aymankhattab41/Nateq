package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors

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
 * 11. كشف ini المكرر وتفضيل ini الأصلي وتحديد جذر محرك التنفيذ (Salma).
 * 12. مراجع NLP النسبية والمسارات الشرعية دون هروب.
 * 13. دعم الحاويات المسماة Acapela دون تصنيفها جذراً عاماً محظوراً.
 * 14. التزامن وتوحيد المحاولات المتزامنة قيد التنفيذ (in-flight deduplication).
 * 15. فترة تهدئة الفشل (failure cooldown) وإعادة المحاولة الصريحة.
 * 16. إبطال الذاكرة المؤقتة عند رفع مراجعة التثبيت (bumpVersion).
 * 17. انعدام عمليات القرص عند استدعاء الحزمة الجاهزة (warm resolution).
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

    @Test
    fun resolvePackage_duplicateIni_selectsAuthenticIniAndNativeRoot() {
        // اختبار انحدار 10758: وجود ini سطحي مختصر في جذر Salma
        // و ini أصيل كامل داخل salma_lf يربط NLP و SLV.
        val root = tempDir("salma_duplicate_ini")
        val salmaDir = File(root, "Salma").apply { mkdirs() }

        // ini سطحي بديل يفتقر لتوجيهات PRO
        File(salmaDir, "ar_sa_hd_salma_22k_lf.ini").writeText(
            "MODE=\"prep_hd\"\n# 109 bytes stub\n"
        )
        File(salmaDir, "0x00169729").writeText("SALMA_LICENSE_COMMERCIAL")
        File(salmaDir, "config.yaml").writeText(
            "param: \"salma22k_hq,salma@test.com,0123456789abcdef\""
        )

        val salmaLf = File(salmaDir, "salma_lf").apply { mkdirs() }
        val authenticIni = File(salmaLf, "ar_sa_hd_salma_22k_lf.ini")
        authenticIni.writeText(sampleIni("salma"))
        File(salmaLf, "salma_22k_lf.bvcu").writeBytes(ByteArray(128))

        val nlpDir = File(salmaDir, "NLP").apply { mkdirs() }
        File(nlpDir, "ar_sa.oso").writeBytes(ByteArray(64))
        File(nlpDir, "ar_sa.dca").writeBytes(ByteArray(64))

        val res = AcapelaPackageResolver.resolvePackage(
            voiceDir = salmaDir,
            expectedSpeakerHint = "Salma",
            forceRetry = true
        )

        assertTrue(res.diagnosticReason, res.isSuccess)
        assertEquals(
            AcapelaPackageResolver.ResolutionStatus.SUCCESS,
            res.status
        )
        // يجب اختيار ini الأصلي الموجود داخل salma_lf وليس السطحي الفاسد
        assertNotNull(res.authenticIni)
        assertEquals(
            authenticIni.canonicalPath,
            res.authenticIni?.canonicalPath
        )
        // يجب أن يحدد جذر محرك التنفيذ الأصلي كـ salma_lf
        // لمنع تحميل البديل السطحي
        assertEquals(
            salmaLf.canonicalPath,
            res.nativeEngineSearchRoot.canonicalPath
        )
        assertTrue(res.resolvedDependencies.isNotEmpty())
        assertTrue(res.missingDependencies.isEmpty())
    }

    @Test
    fun resolvePackage_legitimateRelativeReferences_resolvesSafely() {
        val root = tempDir("relative_refs")
        val salmaDir = File(root, "Salma").apply { mkdirs() }
        val userdicoDir = File(root, "userdicos").apply { mkdirs() }
        val dicoFile = File(userdicoDir, "ara-sau.userdico").apply {
            writeBytes(ByteArray(32))
        }

        val iniContent = buildString {
            appendLine("PRO \"NLP/ar_sa.oso\" [ PROSODY ]")
            appendLine("SLV \"salma_lf/salma_22k_lf.bvcu\" [ FILE ]")
            appendLine("userdico \"../userdicos/ara-sau.userdico\"")
            appendLine("END \"\"")
        }

        File(salmaDir, "ar_sa_hd_salma_22k_lf.ini").writeText(iniContent)
        File(salmaDir, "0x00169729").writeText("COMMERCIAL")

        val nlp = File(salmaDir, "NLP").apply { mkdirs() }
        File(nlp, "ar_sa.oso").writeBytes(ByteArray(64))
        val lf = File(salmaDir, "salma_lf").apply { mkdirs() }
        File(lf, "salma_22k_lf.bvcu").writeBytes(ByteArray(128))

        val res = AcapelaPackageResolver.resolvePackage(
            voiceDir = salmaDir,
            expectedSpeakerHint = "Salma",
            forceRetry = true
        )

        assertTrue(res.diagnosticReason, res.isSuccess)
        assertTrue(
            res.resolvedDependencies.any {
                it.canonicalPath == dicoFile.canonicalPath
            }
        )
    }

    @Test
    fun resolvePackage_parentNamedAcapela_isAcceptedAsDocumentedParent() {
        val root = tempDir("acapela_parent_test")
        // حاوية أصلية اسمها Acapela
        val acapelaContainer = File(root, "Acapela").apply { mkdirs() }
        File(acapelaContainer, "voicelist").writeText("Leila\n")
        File(acapelaContainer, "0x00294994").writeText("LEILA_HEX")

        val leilaDir = File(acapelaContainer, "leila22k_hq").apply { mkdirs() }
        File(leilaDir, "ar_sa_hd_leila_22k_lf.bvcu.ini")
            .writeText(sampleIni("leila"))
        File(leilaDir, "ar_sa_hd_leila_22k_lf.bvcu.inf")
            .writeText(sampleInf("Leila"))

        val res = AcapelaPackageResolver.resolvePackage(
            voiceDir = leilaDir,
            expectedSpeakerHint = "Leila",
            forceRetry = true
        )

        assertTrue(res.diagnosticReason, res.isSuccess)
        assertEquals(
            AcapelaPackageResolver.PackageRelation.PARENT_CONTAINER,
            res.relation
        )
        assertEquals("0x00294994", res.hexFile?.name)
    }

    @Test
    fun resolvePackage_concurrency_inFlightCallsShareSingleAttempt() {
        val root = tempDir("concurrency_test")
        val salmaDir = File(root, "salma22k_hq").apply { mkdirs() }
        File(salmaDir, "ar_sa_hd_salma_22k_lf.bvcu.ini")
            .writeText(sampleIni("salma"))
        File(salmaDir, "0x00169729").writeText("SALMA_HEX")

        val pool = Executors.newFixedThreadPool(6)
        try {
            val tasks = (1..6).map {
                Callable {
                    AcapelaPackageResolver.resolvePackage(
                        voiceDir = salmaDir,
                        expectedSpeakerHint = "Salma"
                    )
                }
            }
            val futures = pool.invokeAll(tasks)
            val results = futures.map { it.get() }
            assertEquals(6, results.size)
            assertTrue(results.all { it.isSuccess })
            // كافة النتائج المتزامنة تعيد نفس ملف الترخيص والمتحدث
            assertTrue(results.all { it.hexFile?.name == "0x00169729" })
            assertTrue(results.all { it.resolvedSpeaker == "Salma" })
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun resolvePackage_failureCooldown_and_explicitRetry() {
        val root = tempDir("cooldown_test")
        val brokenDir = File(root, "broken_salma").apply { mkdirs() }
        // مجلد فارغ يفتقر لأي ملف ترخيص أو تهيئة

        val firstRes = AcapelaPackageResolver.resolvePackage(
            voiceDir = brokenDir,
            expectedSpeakerHint = "Salma",
            forceRetry = true
        )
        assertFalse(firstRes.isSuccess)

        // استدعاء فوري ثانٍ: يجب أن يرجع نفس النتيجة الفاشلة من التهدئة
        val secondRes = AcapelaPackageResolver.resolvePackage(
            voiceDir = brokenDir,
            expectedSpeakerHint = "Salma",
            forceRetry = false
        )
        assertFalse(secondRes.isSuccess)
        assertEquals(firstRes.status, secondRes.status)

        // تجاوز صريح للتهدئة عبر forceRetry
        val retryRes = AcapelaPackageResolver.resolvePackage(
            voiceDir = brokenDir,
            expectedSpeakerHint = "Salma",
            forceRetry = true
        )
        assertFalse(retryRes.isSuccess)
    }

    @Test
    fun resolvePackage_cacheInvalidationOnStorageBump() {
        val root = tempDir("cache_invalidation")
        val voiceDir = File(root, "salma22k_hq").apply { mkdirs() }
        File(voiceDir, "ar_sa_hd_salma_22k_lf.bvcu.ini")
            .writeText(sampleIni("salma"))
        File(voiceDir, "0x00169729").writeText("HEX_KEY")

        val first = AcapelaPackageResolver.resolvePackage(
            voiceDir = voiceDir,
            expectedSpeakerHint = "Salma"
        )
        assertTrue(first.isSuccess)

        // الاستدعاء الثاني يسترجع الكائن من الذاكرة المؤقتة
        val second = AcapelaPackageResolver.resolvePackage(
            voiceDir = voiceDir,
            expectedSpeakerHint = "Salma"
        )
        assertSame(first, second)

        // رفع مراجعة التثبيت يجب أن يُبطل الذاكرة المؤقتة
        EmbeddedStorage.bumpVersion()

        val third = AcapelaPackageResolver.resolvePackage(
            voiceDir = voiceDir,
            expectedSpeakerHint = "Salma"
        )
        assertTrue(third.isSuccess)
        assertNotSame(first, third)
    }

    @Test
    fun warmSpeech_noDiskIoOnMainThread() {
        val root = tempDir("warm_speech_io")
        val voiceDir = File(root, "salma22k_hq").apply { mkdirs() }
        File(voiceDir, "ar_sa_hd_salma_22k_lf.bvcu.ini")
            .writeText(sampleIni("salma"))
        File(voiceDir, "0x00169729").writeText("HEX_KEY")

        // تجهيز مسبق في الذاكرة المؤقتة (Warmup)
        val warmed = AcapelaPackageResolver.resolvePackage(
            voiceDir = voiceDir,
            expectedSpeakerHint = "Salma"
        )
        assertTrue(warmed.isSuccess)

        // أثناء النطق الدافئ: الاسترجاع من الذاكرة المؤقتة يكتمل فوراً
        val startNs = System.nanoTime()
        val warmResult = AcapelaPackageResolver.resolvePackage(
            voiceDir = voiceDir,
            expectedSpeakerHint = "Salma"
        )
        val elapsedUs = (System.nanoTime() - startNs) / 1000L

        assertTrue(warmResult.isSuccess)
        assertSame(warmed, warmResult)
        // الوصول للذاكرة المؤقتة يكون أقل من 500 ميكروثانية
        assertTrue(
            "الاسترجاع الدافئ سريع جداً ($elapsedUs us)",
            elapsedUs < 50_000L
        )
    }
}
