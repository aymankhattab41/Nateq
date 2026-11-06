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
}
