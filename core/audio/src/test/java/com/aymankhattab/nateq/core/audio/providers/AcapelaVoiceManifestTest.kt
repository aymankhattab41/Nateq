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
    }

    @Test
    fun ensureVoiceInfFiles_createsProperNativeInfFormat() {
        val baseDir = tempFolder.newFolder("voices_without_inf")
        val voiceFolder = File(baseDir, "salma").apply { mkdirs() }
        val bvcu = File(voiceFolder, "ar_sa_salma_22k_ns.bvcu")
        bvcu.writeBytes(ByteArray(10_000))

        AcapelaVoiceManifest.ensureVoiceInfFiles(baseDir)

        val inf = File(voiceFolder, "salma.inf")
        assertTrue(inf.exists())
        val ini = File(voiceFolder, "salma.ini")
        assertTrue(ini.exists())
        val content = inf.readText()
        assertTrue(content.contains("speaker = \" Salma\""))
        assertTrue(content.contains("codepage = \" utf-8\""))
    }

    @Test
    fun ensureVoiceInfFiles_updatesExistingInfWithoutSpaceAndCreatesIni() {
        val baseDir = tempFolder.newFolder("voices_unspaced_inf")
        val voiceFolder = File(baseDir, "mehdi").apply { mkdirs() }
        val inf = File(voiceFolder, "mehdi.inf")
        inf.writeText("[Voice]\nspeaker=\"Mehdi\"\nfrequency=22050\n")

        AcapelaVoiceManifest.ensureVoiceInfFiles(baseDir)

        val updatedInf = inf.readText()
        assertTrue(updatedInf.contains("speaker = \" Mehdi\""))
        val ini = File(voiceFolder, "mehdi.ini")
        assertTrue(ini.exists())
        assertTrue(ini.readText().contains("speaker = \" Mehdi\""))
    }
}
