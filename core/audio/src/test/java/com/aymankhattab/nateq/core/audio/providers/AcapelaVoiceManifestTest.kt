package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
        assertFalse(stubIni.exists())
    }

    @Test
    fun ensureVoiceConfigs_createsInfAndIniWithMbrForBvcuOnly() {
        val baseDir = tempFolder.newFolder("bvcu_only")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        File(voiceFolder, "ar_sa_hd_salma_22k_lf.bvcu")
            .writeBytes(ByteArray(10_000))
        File(voiceFolder, "ar_sa_hd_salma_22k_lf.dat")
            .writeBytes(ByteArray(10_000))

        AcapelaVoiceManifest.ensureVoiceConfigs(baseDir)

        val inf = File(voiceFolder, "Salma.inf")
        val ini = File(voiceFolder, "Salma.ini")
        val cfg = File(voiceFolder, "Salma.cfg")
        assertTrue(inf.exists())
        assertTrue(ini.exists())
        assertTrue(cfg.exists())

        val infText = inf.readText()
        assertTrue(infText.contains("speaker = \" Salma\""))

        val iniText = ini.readText()
        assertTrue(iniText.contains("MBR = ar_sa_hd_salma_22k_lf.bvcu"))
        assertTrue(iniText.contains("FILE \"ar_sa_hd_salma_22k_lf.bvcu\""))
        assertTrue(iniText.contains("FILEMAP \"ar_sa_hd_salma_22k_lf.dat\""))
    }

    @Test
    fun ensureVoiceConfigs_preservesRichDirectivesWithoutOverwriting() {
        val baseDir = tempFolder.newFolder("rich_existing")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        File(voiceFolder, "salma.bvcu").writeBytes(ByteArray(10_000))
        val richBody = "[Voice]\n" +
            "speaker = \" Salma\"\n" +
            "frequency = 22050\n" +
            "PATH my_special_path\n" +
            "BNF \"salma.bnf\" [ RAM ]\n"
        val ini = File(voiceFolder, "Salma.ini")
        ini.writeText(richBody)

        AcapelaVoiceManifest.ensureVoiceConfigs(baseDir)

        val newIniText = ini.readText()
        assertTrue(newIniText.contains("PATH my_special_path"))
        assertTrue(newIniText.contains("BNF \"salma.bnf\""))
        assertTrue(newIniText.contains("MBR = salma.bvcu"))
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
    fun resolveRegistration_readsDirectRegistrationField() {
        val baseDir = tempFolder.newFolder("voice_json_test")
        val jsonFile = File(baseDir, "voice.json")
        jsonFile.writeText("{\"registration\": \"test-license-1234\"}")

        val reg = AcapelaVoiceManifest.resolveRegistration(baseDir)
        assertEquals("test-license-1234", reg)
    }
}
