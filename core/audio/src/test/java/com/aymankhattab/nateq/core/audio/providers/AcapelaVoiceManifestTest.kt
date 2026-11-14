package com.aymankhattab.nateq.core.audio.providers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class AcapelaVoiceManifestTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parseVoice_readsInfPropertiesCorrectly() {
        val voiceDir = tempFolder.newFolder("mehdi")
        val infFile = File(voiceDir, "mehdi.inf")
        infFile.writeText(
            """
            [Voice]
            Speaker=Mehdi
            IsoCode=ara
            SampleRate=22050
            """.trimIndent()
        )

        val voiceInfo = AcapelaVoiceManifest.parseVoice(voiceDir)
        assertEquals("Mehdi", voiceInfo.voiceName)
        assertEquals("ar", voiceInfo.locale.language)
        assertEquals(22050, voiceInfo.sampleRate)
        assertTrue(voiceInfo.displayName.contains("Mehdi"))
    }

    @Test
    fun isCompleteVoiceDir_validatesPresenceOfConfigOrData() {
        val emptyDir = tempFolder.newFolder("empty")
        assertFalse(AcapelaVoiceManifest.isCompleteVoiceDir(emptyDir))

        val configDir = tempFolder.newFolder("configOnly")
        File(configDir, "voice.ini").writeText("Speaker=Salma")
        assertTrue(AcapelaVoiceManifest.isCompleteVoiceDir(configDir))

        val dataDir = tempFolder.newFolder("dataOnly")
        val bigData = File(dataDir, "voice.dat")
        bigData.writeBytes(ByteArray(20_000))
        assertTrue(AcapelaVoiceManifest.isCompleteVoiceDir(dataDir))
    }

    @Test
    fun listVoices_discoversNestedVoices() {
        val baseDir = tempFolder.newFolder("Acapela")
        val v1 = File(baseDir, "mehdi").apply { mkdirs() }
        File(v1, "mehdi.inf").writeText("Speaker=Mehdi\nIsoCode=ara")
        File(v1, "mehdi.dat").writeBytes(ByteArray(15_000))

        val v2 = File(baseDir, "salma").apply { mkdirs() }
        File(v2, "salma.inf").writeText("Speaker=Salma\nIsoCode=ara")
        File(v2, "salma.dat").writeBytes(ByteArray(15_000))

        val voices = AcapelaVoiceManifest.listVoices(baseDir)
        assertEquals(2, voices.size)
        assertTrue(voices.any { it.voiceName == "Mehdi" })
        assertTrue(voices.any { it.voiceName == "Salma" })
    }

    @Test
    fun cleanAcapelaSpeaker_normalizesRealWorldNames() {
        assertEquals(
            "Salma",
            AcapelaVoiceManifest.cleanAcapelaSpeaker(
                "ar_sa_salma_22k_ns.bvcu"
            )
        )
        assertEquals(
            "Salma",
            AcapelaVoiceManifest.cleanAcapelaSpeaker("Salma_HM")
        )
        assertEquals(
            "Mehdi",
            AcapelaVoiceManifest.cleanAcapelaSpeaker("ar_sa_mehdi_22k_ns")
        )
        assertEquals(
            "Leila",
            AcapelaVoiceManifest.cleanAcapelaSpeaker("acapela_leila")
        )
        assertEquals(
            "Salma",
            AcapelaVoiceManifest.cleanAcapelaSpeaker("Salma (العربية)")
        )
        assertEquals(
            "Salma",
            AcapelaVoiceManifest.cleanAcapelaSpeaker(
                "ar_sa_hd_salma_22k_lf.bvcu"
            )
        )
    }

    @Test
    fun pruneSyntheticConfigs_neverCreatesConfigFiles() {
        val baseDir = tempFolder.newFolder("voices_without_inf")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        File(voiceFolder, "ar_sa_salma_22k_ns.bvcu")
            .writeBytes(ByteArray(10_000))

        AcapelaVoiceManifest.pruneSyntheticConfigs(baseDir)

        // لا كتابة إطلاقاً: الملف المصطنع كان يُفسد تحميل الصوت.
        assertFalse(File(voiceFolder, "salma.inf").exists())
        assertFalse(File(voiceFolder, "salma.ini").exists())
        // الصوت ما زال يُعدّ من البيانات الخام.
        assertEquals(1, AcapelaVoiceManifest.listVoices(baseDir).size)
    }

    @Test
    fun pruneSyntheticConfigs_neverRewritesRichInf() {
        val baseDir = tempFolder.newFolder("voices_unspaced_inf")
        val voiceFolder = File(baseDir, "mehdi").apply { mkdirs() }
        File(voiceFolder, "mehdi.bvcu").writeBytes(ByteArray(10_000))
        val inf = File(voiceFolder, "mehdi.inf")
        val original = "[Voice]\n" +
            "speaker=\"Mehdi\"\n" +
            "frequency=22050\n" +
            "FILE \"mehdi.bvcu\" [ RAM ]\n"
        inf.writeText(original)

        AcapelaVoiceManifest.pruneSyntheticConfigs(baseDir)

        assertEquals(original, inf.readText())
        assertFalse(File(voiceFolder, "mehdi.ini").exists())
    }

    @Test
    fun pruneSyntheticConfigs_keepsOnlyConfigWhenNoRichAlternative() {
        val baseDir = tempFolder.newFolder("voices_only_plain")
        val voiceFolder = File(baseDir, "nizar").apply { mkdirs() }
        val inf = File(voiceFolder, "nizar.inf")
        inf.writeText("[Voice]\nspeaker = \" Nizar\"\n")
        val ini = File(voiceFolder, "nizar.ini")
        ini.writeText("[Voice]\nspeaker = \" Nizar\"\n")

        AcapelaVoiceManifest.pruneSyntheticConfigs(baseDir)

        // بلا بديل غنيّ في المجلد نفسه لا يُحذف شيء.
        assertTrue(inf.exists())
        assertTrue(ini.exists())
    }

    @Test
    fun collectVoiceSearchPaths_findsNestedVoiceFoldersDirectly() {
        val baseDir = tempFolder.newFolder("search_bases")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        File(voiceFolder, "salma.inf").writeText(
            "[Voice]\nspeaker = \" Salma\"\n"
        )
        File(voiceFolder, "salma.ini").writeText(
            "[Voice]\nspeaker = \" Salma\"\n"
        )

        val paths = com.aymankhattab.nateq.core.audio.enginehost
            .AcapelaHostSession.collectVoiceSearchPaths(baseDir)

        assertEquals(1, paths.size)
        assertEquals(voiceFolder.absolutePath, paths[0])
    }

    @Test
    fun pruneSyntheticConfigs_removesContentlessDuplicateInfs() {
        val baseDir = tempFolder.newFolder("voices_with_stubs")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        File(voiceFolder, "salma.bvcu").writeBytes(ByteArray(10_000))
        val richBody = "[Voice]\n" +
            "speaker = \" Salma\"\n" +
            "frequency = 22050\n" +
            "FILE \"salma.bvcu\" [ RAM ]\n"
        val mainInf = File(voiceFolder, "salma.inf")
        mainInf.writeText(richBody)
        val mainIni = File(voiceFolder, "salma.ini")
        mainIni.writeText(richBody)

        val stubInf = File(voiceFolder, "salma_22k.inf")
        stubInf.writeText("[Voice]\nspeaker = \" Salma\"\n")
        val stubIni = File(voiceFolder, "salma_22k.ini")
        stubIni.writeText("[Voice]\nspeaker = \" Salma\"\n")

        AcapelaVoiceManifest.pruneSyntheticConfigs(baseDir)

        assertTrue(mainInf.exists())
        assertTrue(mainIni.exists())
        assertFalse(stubInf.exists())
        // `.ini` لم يعد يُحذف: صار ملفاً نافعاً يُولَّد عند غيابه.
        assertTrue(stubIni.exists())
    }

    @Test
    fun ensureVoiceConfigs_neverGeneratesIniWithoutInf() {
        val baseDir = tempFolder.newFolder("bvcu_only")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        File(voiceFolder, "ar_sa_hd_salma_22k_lf.bvcu")
            .writeBytes(ByteArray(10_000))
        File(voiceFolder, "ar_sa_hd_salma_22k_lf.dat")
            .writeBytes(ByteArray(10_000))

        AcapelaVoiceManifest.ensureVoiceConfigs(baseDir)

        // بلا `.inf` لا يرى `find_voices` المجلد أصلاً، فلا قيمة لـ`.ini`.
        assertFalse(File(voiceFolder, "Salma.inf").exists())
        assertFalse(File(voiceFolder, "Salma.ini").exists())
        assertFalse(File(voiceFolder, "Salma.cfg").exists())
        assertTrue(File(voiceFolder, "userdicos").isDirectory)
    }

    @Test
    fun ensureVoiceConfigs_completesOnlyMissingInfKeys() {
        val baseDir = tempFolder.newFolder("rich_existing")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        File(voiceFolder, "salma.bvcu").writeBytes(ByteArray(10_000))
        val inf = File(voiceFolder, "salma.inf")
        inf.writeText(
            "[Voice]\n" +
                "speaker = \" Salma\"\n" +
                "frequency = 22050\n" +
                "PATH my_special_path\n" +
                "BNF \"salma.bnf\" [ RAM ]\n"
        )

        AcapelaVoiceManifest.ensureVoiceConfigs(baseDir)

        val text = inf.readText()
        // المحتوى الأصلي لا يُمحى ولا يُعاد كتابته.
        assertTrue(text.contains("PATH my_special_path"))
        assertTrue(text.contains("BNF \"salma.bnf\""))
        assertTrue(text.contains("speaker = \" Salma\""))
        // المفاتيح الناقصة وحدها تُضاف.
        assertTrue(text.contains("ISOCode = \"ar-SA\""))
        assertTrue(text.contains("NAME = \"Salma\""))
        assertTrue(text.contains("freq = \"22050\""))
        assertEquals(1, Regex("speaker\\s*=").findAll(text).count())
    }

    @Test
    fun ensureVoiceConfigs_copiesRegistrationKeyIntoVoiceFolder() {
        val baseDir = tempFolder.newFolder("pack_root")
        File(baseDir, "0x1A2B3C4D").writeText("key")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        File(voiceFolder, "salma.inf").writeText(
            "[Voice]\nspeaker = \"Salma\"\nISOCode = \"ara-SAU\"\n"
        )

        AcapelaVoiceManifest.ensureVoiceConfigs(baseDir)

        assertTrue(File(voiceFolder, "0x1A2B3C4D").isFile)
    }

    @Test
    fun ensureVoiceConfigs_generatesIniFromInfDirectives() {
        val baseDir = tempFolder.newFolder("ini_from_inf")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        File(voiceFolder, "salma.bvcu").writeBytes(ByteArray(10_000))
        File(voiceFolder, "salma.dat").writeBytes(ByteArray(10_000))
        File(voiceFolder, "salma_lf.bnf").writeBytes(ByteArray(10_000))
        File(voiceFolder, "salma.inf").writeText(
            "[Voice]\n" +
                "speaker = \"Salma\"\n" +
                "ISOCode = \"ara-SAU\"\n" +
                "freq = \"22050\"\n" +
                "FILEMAP = \"salma.bvcu\"\n" +
                "BNF \"salma_lf.bnf\" [ RAM ]\n"
        )

        AcapelaVoiceManifest.ensureVoiceConfigs(baseDir)

        val text = File(voiceFolder, "salma.ini").readText()
        assertTrue(text.contains("FILEMAP \"salma.bvcu\" [ RAM ]"))
        assertTrue(text.contains("BNF \"salma_lf.bnf\" [ RAM ]"))
        // المفاتيح الوصفية لا تُنقل إلى ملف اللغة: المحمّل لا يعرفها.
        assertFalse(text.contains("speaker", ignoreCase = true))
        assertFalse(text.contains("ISOCode", ignoreCase = true))
        assertFalse(text.contains("freq", ignoreCase = true))
    }

    @Test
    fun ensureVoiceConfigs_generatesRamSuffixedQuotedLines() {
        val baseDir = tempFolder.newFolder("ini_line_shape")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        File(voiceFolder, "salma.inf").writeText(
            "[Voice]\nspeaker = \"Salma\"\n"
        )
        File(voiceFolder, "salma_lf.slv").writeBytes(ByteArray(2_000))
        File(voiceFolder, "salma.bvcu").writeBytes(ByteArray(10_000))

        AcapelaVoiceManifest.ensureVoiceConfigs(baseDir)

        val lines = File(voiceFolder, "salma.ini").readLines()
        assertTrue(lines.isNotEmpty())
        // الصيغة تمرّ من بوّابة `%s "%[^"]"` ومن مُدخلات `[ %[^] ] ]`.
        val shape = Regex("^[A-Z]+ \"[^\"]+\" \\[ [A-Z_]+ ]$")
        for (line in lines) {
            assertTrue("سطر تهيئة غير مطابق: $line", shape.matches(line))
        }
    }

    @Test
    fun ensureVoiceConfigs_derivesIniFromDataFiles() {
        val baseDir = tempFolder.newFolder("ini_from_files")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        File(voiceFolder, "salma.inf").writeText(
            "[Voice]\nspeaker = \"Salma\"\nISOCode = \"ara-SAU\"\n"
        )
        File(voiceFolder, "salma_lf.bvcu").writeBytes(ByteArray(10_000))
        val nested = File(voiceFolder, "salma_lf").apply { mkdirs() }
        File(nested, "salma_lf.sll").writeBytes(ByteArray(1_000))

        AcapelaVoiceManifest.ensureVoiceConfigs(baseDir)

        val text = File(voiceFolder, "salma.ini").readText()
        assertTrue(text.contains("FILEMAP \"salma_lf.bvcu\" [ RAM ]"))
        // الملف المتداخل يُذكر بمسار نسبي يفهمه المحمّل.
        assertTrue(text.contains("SLL \"salma_lf/salma_lf.sll\" [ RAM ]"))
    }

    @Test
    fun ensureVoiceConfigs_leavesExistingIniUntouched() {
        val baseDir = tempFolder.newFolder("ini_existing")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        File(voiceFolder, "salma.bvcu").writeBytes(ByteArray(10_000))
        File(voiceFolder, "salma.inf").writeText(
            "[Voice]\nspeaker = \"Salma\"\n"
        )
        val original = "PATH RAM \".\"\n" +
            "FILEMAP \"salma.bvcu\" [ RAM ]\n"
        val ini = File(voiceFolder, "salma.ini")
        ini.writeText(original)

        AcapelaVoiceManifest.ensureVoiceConfigs(baseDir)

        assertEquals(original, ini.readText())
    }

    @Test
    fun ensureVoiceConfigs_skipsDirectivesPointingToMissingFiles() {
        val baseDir = tempFolder.newFolder("ini_missing_target")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        File(voiceFolder, "salma.inf").writeText(
            "[Voice]\n" +
                "speaker = \"Salma\"\n" +
                "FILEMAP \"ghost.bvcu\" [ RAM ]\n"
        )

        AcapelaVoiceManifest.ensureVoiceConfigs(baseDir)

        // لا ملف بيانات ولا توجيه صالح: لا تهيئة تُشير إلى العدم.
        assertFalse(File(voiceFolder, "salma.ini").exists())
    }

    @Test
    fun collectVoiceSearchPaths_discoversVoiceFoldersEvenWithoutExistingInf() {
        val baseDir = tempFolder.newFolder("no_prior_inf")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        File(voiceFolder, "ar_sa_salma_22k_ns.bvcu")
            .writeBytes(ByteArray(10_000))

        val paths = com.aymankhattab.nateq.core.audio.enginehost
            .AcapelaHostSession.collectVoiceSearchPaths(baseDir)

        assertTrue(paths.contains(voiceFolder.absolutePath))
    }

    @Test
    fun acapelaParameters_rateAndPitchCalculations() {
        assertEquals(30, AcapelaParameters.sdkRate(10))
        assertEquals(100, AcapelaParameters.sdkRate(100))
        assertEquals(400, AcapelaParameters.sdkRate(500))

        assertEquals(70, AcapelaParameters.sdkPitch(50))
        assertEquals(100, AcapelaParameters.sdkPitch(100))
        assertEquals(130, AcapelaParameters.sdkPitch(150))

        assertEquals(100, AcapelaParameters.registeredPitch(100))
        assertEquals(40, AcapelaParameters.registeredPitch(70))
        assertEquals(160, AcapelaParameters.registeredPitch(130))

        val map = mutableMapOf<String, String>()
        val code = AcapelaParameters.applyRegistered(120, 100) { k, v ->
            map[k] = v
            0
        }
        assertEquals(0, code)
        assertEquals("120", map["rate"])
        assertEquals("100", map["pitch"])
        assertEquals("0", map["volume"])
    }

    @Test
    fun acapelaParameters_applyRegisteredContinuesOnError() {
        val map = mutableMapOf<String, String>()
        val code = AcapelaParameters.applyRegistered(120, 100) { k, v ->
            map[k] = v
            if (k == "pitch") -1 else 0
        }
        assertEquals(-1, code)
        assertEquals("120", map["rate"])
        assertEquals("100", map["pitch"])
        assertEquals("0", map["volume"])
    }

    @Test
    fun resolveRegistration_readsDirectRegistrationField() {
        val baseDir = tempFolder.newFolder("voice_json_test")
        val jsonFile = File(baseDir, "voice.json")
        jsonFile.writeText("{\"registration\": \"test-license-1234\"}")

        val reg = AcapelaVoiceManifest.resolveRegistration(baseDir)
        assertEquals("test-license-1234", reg)
    }

    @Test
    fun resolveRegistration_rejectsHexFileNameAsMetadata() {
        val baseDir = tempFolder.newFolder("hex_only")
        File(baseDir, "0x1A2B3C4D").writeText("??? !!! ***")

        // اسم الملف ليس بيانات تسجيل: تمريره للمحرك الممتد يُفسد بناؤه.
        assertEquals("", AcapelaVoiceManifest.resolveRegistration(baseDir))
    }

    @Test
    fun resolveRegistration_readsHexFileContentAsMetadata() {
        val baseDir = tempFolder.newFolder("hex_content_valid")
        File(baseDir, "0x00169729").writeText("valid-meta-string@123")

        val reg = AcapelaVoiceManifest.resolveRegistration(baseDir)
        assertEquals("valid-meta-string@123", reg)
    }

    @Test
    fun resolveRegistration_rejectsInvalidHexFileContent() {
        val dirInvalid = tempFolder.newFolder("hex_invalid")
        File(dirInvalid, "0x00112233").writeText("??? !!! ***")
        assertEquals("", AcapelaVoiceManifest.resolveRegistration(dirInvalid))

        val dirCommentsOnly = tempFolder.newFolder("hex_comments")
        File(dirCommentsOnly, "0x00223344").writeText(
            "# only comments\n# no tokens\n"
        )
        assertEquals(
            "",
            AcapelaVoiceManifest.resolveRegistration(dirCommentsOnly)
        )

        val dirHexContent = tempFolder.newFolder("hex_itself")
        File(dirHexContent, "0x00445566").writeText("0x00169729")
        val hexReg = AcapelaVoiceManifest
            .resolveRegistration(dirHexContent)
        assertEquals("", hexReg)

        val dirTooLong = tempFolder.newFolder("hex_too_long")
        File(dirTooLong, "0x00778899").writeText("a".repeat(300))
        assertEquals("", AcapelaVoiceManifest.resolveRegistration(dirTooLong))
    }

    @Test
    fun resolveRegistration_readsHexFileWithCommentsAndQuotes() {
        val baseDir = tempFolder.newFolder("hex_structured")
        File(baseDir, "0x00169729").writeText(
            "# Key file for Salma HD\n" +
                "registration: \"AR_SA_HD_KEY_9988\" # trailing comment\n"
        )
        val reg = AcapelaVoiceManifest.resolveRegistration(baseDir)
        assertEquals("AR_SA_HD_KEY_9988", reg)
    }

    @Test
    fun resolveRegistration_readsBareQuotedKeyWithComment() {
        val baseDir = tempFolder.newFolder("hex_bare_quoted")
        File(baseDir, "0x00169729").writeText(
            "\"bare_salma_meta_123\" # comment for key\n"
        )
        val reg = AcapelaVoiceManifest.resolveRegistration(baseDir)
        assertEquals("bare_salma_meta_123", reg)
    }

    @Test
    fun resolveRegistration_prefersRegistrationOrLicenseKey() {
        val baseDir = tempFolder.newFolder("hex_prefer_reg")
        File(baseDir, "0x00169729").writeText(
            "name = \"Salma\"\n" +
                "license = \"PREF_LIC_123\"\n" +
                "other = \"LONGER_NON_PREFERRED_TOKEN_456\"\n"
        )
        val reg = AcapelaVoiceManifest.resolveRegistration(baseDir)
        assertEquals("PREF_LIC_123", reg)
    }

    @Test
    fun resolveRegistration_picksLongestWhenNoKeyPreferred() {
        val baseDir = tempFolder.newFolder("hex_longest")
        File(baseDir, "0x00169729").writeText(
            "foo = \"short\"\n" +
                "bar = \"longest_token_value_789\"\n"
        )
        val reg = AcapelaVoiceManifest.resolveRegistration(baseDir)
        assertEquals("longest_token_value_789", reg)
    }

    @Test
    fun maskFileShape_replacesLettersAndDigits() {
        val shape = AcapelaVoiceManifest.maskFileShape(
            "# Comment\nreg: \"KEY_123\" # info"
        )
        assertEquals("# aaaaaaa⏎aaa: \"aaa_999\" # aaaa", shape)
    }

    @Test
    fun resolveRegistration_readsYamlRegistration() {
        val baseDir = tempFolder.newFolder("yaml_valid")
        File(baseDir, "config.yaml").writeText(
            "name: Salma\nregistration: \"yaml-meta-token_456\"\n"
        )
        val reg = AcapelaVoiceManifest.resolveRegistration(baseDir)
        assertEquals("yaml-meta-token_456", reg)

        val enginesDir = tempFolder.newFolder("yaml_engines")
        File(enginesDir, "engines.yaml").writeText(
            "registration: engines_meta_789\n"
        )
        val regEngines = AcapelaVoiceManifest.resolveRegistration(enginesDir)
        assertEquals("engines_meta_789", regEngines)
    }

    @Test
    fun resolveRegistration_rejectsInvalidYamlRegistration() {
        val baseDir = tempFolder.newFolder("yaml_invalid")
        File(baseDir, "config.yaml").writeText(
            "registration: \"invalid spaces token\"\n"
        )
        assertEquals("", AcapelaVoiceManifest.resolveRegistration(baseDir))
    }

    @Test
    fun parseVoice_keepsCountryFromIsoCode() {
        val voiceDir = tempFolder.newFolder("iso3_country")
        File(voiceDir, "mehdi.inf").writeText(
            "[Voice]\n" +
                "speaker = \"Mehdi\"\n" +
                "ISOCode = \"ara-SAU\"\n"
        )

        val info = AcapelaVoiceManifest.parseVoice(voiceDir)
        assertEquals(
            Locale.Builder().setLanguage("ar").setRegion("SA").build(),
            info.locale
        )
    }

    @Test
    fun parseVoice_mapsKhzFreqToRealSampleRate() {
        val voiceDir = tempFolder.newFolder("khz_22")
        File(voiceDir, "mehdi.inf").writeText(
            "[Voice]\n" +
                "speaker = \"Mehdi\"\n" +
                "ISOCode = \"ara-SAU\"\n" +
                "freq = \"22.0\"\n"
        )
        assertEquals(
            22050,
            AcapelaVoiceManifest.parseVoice(voiceDir).sampleRate
        )

        val second = tempFolder.newFolder("khz_11")
        File(second, "salma.inf").writeText(
            "[Voice]\n" +
                "speaker = \"Salma\"\n" +
                "ISOCode = \"ara-SAU\"\n" +
                "freq = \"11.0\"\n"
        )
        assertEquals(
            11025,
            AcapelaVoiceManifest.parseVoice(second).sampleRate
        )
    }

    /**
     * حارس التخزين المؤقت: النداء الثاني لا يعيد قراءة القرص.
     *
     * حذف ملف الصوت بين النداءين دليلٌ قاطع: لو أُعيد التعداد لعادت
     * القائمة فارغة، وبقاؤها صوتاً واحداً يثبت أنها لقطة مخزَّنة.
     * وبعد انتهاء المدة يعود التعداد للقرص فتصير فارغة فعلاً.
     */
    @Test
    fun readVoicesCached_reusesSnapshotWithoutReadingDiskAgain() = runTest {
        EmbeddedStorage.resetBumpTimeForTest(1_000L)
        AcapelaVoiceManifest.invalidateVoicesCache()
        val baseDir = tempFolder.newFolder("cache_hit")
        val inf = File(baseDir, "salma.inf")
        inf.writeText("[Voice]\nspeaker = \"Salma\"\nISOCode = \"ara-SAU\"\n")

        var clock = 0L
        val first = AcapelaVoiceManifest.readVoicesCached(
            baseDir, 30_000L
        ) { clock }
        assertEquals(1, first.size)

        assertTrue(inf.delete())

        val second = AcapelaVoiceManifest.readVoicesCached(
            baseDir, 30_000L
        ) { clock }
        assertEquals(1, second.size)

        clock = 30_001L
        val expired = AcapelaVoiceManifest.readVoicesCached(
            baseDir, 30_000L
        ) { clock }
        assertTrue(expired.isEmpty())
    }

    /** رفع بصمة التخزين (استيراد/تنزيل/حذف) يُبطل اللقطة فوراً. */
    @Test
    fun readVoicesCached_invalidatesWhenStorageBumps() = runTest {
        EmbeddedStorage.resetBumpTimeForTest(5_000L)
        AcapelaVoiceManifest.invalidateVoicesCache()
        val baseDir = tempFolder.newFolder("cache_bump")
        val inf = File(baseDir, "salma.inf")
        inf.writeText("[Voice]\nspeaker = \"Salma\"\n")

        val clock = { 0L }
        val cached = AcapelaVoiceManifest.readVoicesCached(
            baseDir, 30_000L, clock
        )
        assertEquals(1, cached.size)

        assertTrue(inf.delete())
        EmbeddedStorage.resetBumpTimeForTest(6_000L)

        val afterBump = AcapelaVoiceManifest.readVoicesCached(
            baseDir, 30_000L, clock
        )
        assertTrue(afterBump.isEmpty())
    }

    /** الإبطال الصريح يكسر اللقطة حتى لو بقيت البصمة والمدة صالحتين. */
    @Test
    fun invalidateVoicesCache_forcesFreshReadAfterFileChanges() = runTest {
        EmbeddedStorage.resetBumpTimeForTest(7_000L)
        AcapelaVoiceManifest.invalidateVoicesCache()
        val baseDir = tempFolder.newFolder("cache_invalidate")
        val inf = File(baseDir, "salma.inf")
        inf.writeText("[Voice]\nspeaker = \"Salma\"\n")

        val clock = { 0L }
        assertEquals(
            1,
            AcapelaVoiceManifest.readVoicesCached(
                baseDir, 30_000L, clock
            ).size
        )

        assertTrue(inf.delete())
        AcapelaVoiceManifest.invalidateVoicesCache()

        assertTrue(
            AcapelaVoiceManifest.readVoicesCached(
                baseDir, 30_000L, clock
            ).isEmpty()
        )
    }

    /**
     * لقطة الخيط الرئيسي: طزاجة صحيحة، وآخر تعداد معروف يبقى معروضاً
     * بعد رفع البصمة حتى يكتمل التحديث الخلفي (لا يعود الملخص صفراً).
     */
    @Test
    fun voicesSnapshot_freshOnlyWithinTtlAndBump() = runTest {
        EmbeddedStorage.resetBumpTimeForTest(9_000L)
        AcapelaVoiceManifest.invalidateVoicesCache()
        val baseDir = tempFolder.newFolder("snapshot")
        File(baseDir, "salma.inf")
            .writeText("[Voice]\nspeaker = \"Salma\"\n")

        var clock = 1_000L
        assertFalse(
            AcapelaVoiceManifest.readVoicesSnapshot(baseDir) { clock }.fresh
        )

        AcapelaVoiceManifest.readVoicesCached(baseDir, 30_000L) { clock }
        val fresh = AcapelaVoiceManifest.readVoicesSnapshot(baseDir) { clock }
        assertTrue(fresh.fresh)
        assertEquals(1, fresh.voices.size)

        clock = 31_001L
        assertFalse(
            AcapelaVoiceManifest.readVoicesSnapshot(baseDir) { clock }.fresh
        )

        EmbeddedStorage.resetBumpTimeForTest(10_000L)
        clock = 1_500L
        val bumped = AcapelaVoiceManifest.readVoicesSnapshot(baseDir) { clock }
        assertFalse(bumped.fresh)
        assertEquals(1, bumped.voices.size)
    }
}
