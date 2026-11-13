package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AcapelaHostSessionVoiceResolutionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun candidateNames_includesOriginalAndLowercaseVariations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = AcapelaHostSession(context)
        val voiceDir = tempFolder.newFolder("mehdi")
        val infFile = File(voiceDir, "mehdi22k.inf").apply {
            writeText("speaker = \"mehdi22k\"\nfreq = \"22050\"")
        }

        val voice = AcapelaVoiceManifest.VoiceInfo(
            id = "Mehdi",
            voiceName = "Mehdi",
            displayName = "Mehdi (العربية)",
            locale = Locale.forLanguageTag("ar-SA"),
            sampleRate = 22050,
            dir = voiceDir,
            rawSpeaker = "mehdi22k",
            configFile = infFile
        )

        val candidates = session.candidateNames(voice)
        assertTrue(candidates.contains("mehdi22k"))
        assertTrue(candidates.contains("Mehdi"))
        assertTrue(candidates.contains("mehdi"))
    }

    @Test
    fun resolveVoice_matchesByNamePrefixAndLanguageFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = AcapelaHostSession(context)
        val baseDir = tempFolder.newFolder("Acapela")

        val mehdiDir = File(baseDir, "mehdi").apply { mkdirs() }
        File(mehdiDir, "mehdi.inf").writeText(
            "speaker = \"mehdi22k\"\nISOCode = \"ar-SA\"\nfreq = \"22050\""
        )
        File(mehdiDir, "mehdi.dat").writeBytes(ByteArray(10_000))

        val salmaDir = File(baseDir, "salma").apply { mkdirs() }
        File(salmaDir, "salma.inf").writeText(
            "speaker = \"salma22k\"\nISOCode = \"ar-EG\"\nfreq = \"22050\""
        )
        File(salmaDir, "salma.dat").writeBytes(ByteArray(10_000))

        // 1. مطابقة بالاسم المباشر
        val byName = session.resolveVoice("Mehdi", baseDir)
        assertNotNull(byName)
        assertEquals("Mehdi", byName?.voiceName)

        // 2. مطابقة بالبادئة المدمجة
        val byPrefix = session.resolveVoice(
            "acapela_embedded:Salma",
            baseDir
        )
        assertNotNull(byPrefix)
        assertEquals("Salma", byPrefix?.voiceName)

        // 3. مطابقة برمز اللغة
        val byLang = session.resolveVoice("ar-EG", baseDir)
        assertNotNull(byLang)
        assertEquals("ar", byLang?.locale?.language)

        // 4. السقوط الآمن عند طلب فارغ أو عام بلا خطأ VOICE_NOT_FOUND
        val byEmpty = session.resolveVoice("", baseDir)
        assertNotNull(byEmpty)
        assertEquals("ar", byEmpty?.locale?.language)

        val byDefault = session.resolveVoice("default", baseDir)
        assertNotNull(byDefault)
        assertEquals("ar", byDefault?.locale?.language)

        val byUnknown = session.resolveVoice("non_existent_voice", baseDir)
        assertNotNull(byUnknown)
        assertEquals("ar", byUnknown?.locale?.language)
    }

    @Test
    fun resolveVoice_cachesVoicesUntilBump() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = AcapelaHostSession(context)
        val baseDir = tempFolder.newFolder("CacheTest")
        val mehdiDir = File(baseDir, "mehdi").apply { mkdirs() }
        File(mehdiDir, "mehdi.inf").writeText(
            "speaker = \"mehdi22k\"\nISOCode = \"ar-SA\"\nfreq = \"22050\""
        )
        File(mehdiDir, "mehdi.dat").writeBytes(ByteArray(10_000))

        val first = session.resolveVoice("Mehdi", baseDir)
        assertNotNull(first)

        val laylaDir = File(baseDir, "layla").apply { mkdirs() }
        File(laylaDir, "layla.inf").writeText(
            "speaker = \"layla22k\"\nISOCode = \"ar-EG\"\nfreq = \"22050\""
        )
        File(laylaDir, "layla.dat").writeBytes(ByteArray(10_000))

        val beforeBump = session.resolveVoice("Layla", baseDir)
        assertEquals("Mehdi", beforeBump?.voiceName)

        com.aymankhattab.nateq.core.audio.providers.EmbeddedStorage
            .bumpVersion()
        val afterBump = session.resolveVoice("Layla", baseDir)
        assertNotNull(afterBump)
        assertEquals("Layla", afterBump?.voiceName)
    }
}
