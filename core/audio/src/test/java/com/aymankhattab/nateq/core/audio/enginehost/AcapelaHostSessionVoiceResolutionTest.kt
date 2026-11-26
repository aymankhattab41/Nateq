package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceDiagnostics
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
        // تهيئة أصلية متوافقة مطلوبة للاكتشاف (عقد ini الأصيل).
        File(mehdiDir, "mehdi.ini").writeText(
            "PRO \"NLP/ar.oso\"\n" +
                "SLV \"mehdi.bvcu\"\n" +
                "VOICE \"Mehdi\"\n"
        )
        File(mehdiDir, "mehdi.inf").writeText(
            "speaker = \"mehdi22k\"\nISOCode = \"ar-SA\"\nfreq = \"22050\""
        )
        File(mehdiDir, "mehdi.dat").writeBytes(ByteArray(10_000))

        val salmaDir = File(baseDir, "salma").apply { mkdirs() }
        File(salmaDir, "salma.ini").writeText(
            "PRO \"NLP/ar.oso\"\n" +
                "SLV \"salma.bvcu\"\n" +
                "VOICE \"Salma\"\n"
        )
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
        File(mehdiDir, "mehdi.ini").writeText(
            "PRO \"NLP/ar.oso\"\n" +
                "SLV \"mehdi.bvcu\"\n" +
                "VOICE \"Mehdi\"\n"
        )
        File(mehdiDir, "mehdi.inf").writeText(
            "speaker = \"mehdi22k\"\nISOCode = \"ar-SA\"\nfreq = \"22050\""
        )
        File(mehdiDir, "mehdi.dat").writeBytes(ByteArray(10_000))

        val first = session.resolveVoice("Mehdi", baseDir)
        assertNotNull(first)

        val laylaDir = File(baseDir, "layla").apply { mkdirs() }
        File(laylaDir, "layla.ini").writeText(
            "PRO \"NLP/ar.oso\"\n" +
                "SLV \"layla.bvcu\"\n" +
                "VOICE \"Layla\"\n"
        )
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

    @Test
    fun handleRealSpeechFailure_writesToPersistentFileAndReportsDetails() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = AcapelaHostSession(context)
        val voiceDir = tempFolder.newFolder("salma_fail_test")
        val voice = AcapelaVoiceManifest.VoiceInfo(
            id = "Salma",
            voiceName = "Salma",
            displayName = "Salma (العربية)",
            locale = Locale.forLanguageTag("ar-SA"),
            sampleRate = 22050,
            dir = voiceDir,
            rawSpeaker = "salma22k",
            registration = "test@domain.com|0123456789abcdef"
        )

        val failFile = File(
            context.cacheDir,
            com.aymankhattab.nateq.core.audio.providers
                .AcapelaVoiceDiagnostics.ACAPELA_LAST_REAL_SPEECH_FAILURE_FILE
        )
        failFile.delete()

        val startMarker = "=== START_REAL_SPEAK_Salma_test ==="
        val endMarker = "=== END_REAL_SPEAK_Salma_test ==="

        val result = session.handleRealSpeechFailure(
            voice = voice,
            path = "extended",
            chunk = "مرحبا بكم في الاختبار",
            speakRes = -2,
            startMarker = startMarker,
            endMarker = endMarker
        )

        org.junit.Assert.assertFalse(result.ok)
        assertEquals("SPEAK_ERROR", result.code)
        assertTrue(result.message?.contains("-2") == true)

        assertTrue(failFile.exists())
        val content = failFile.readText()
        assertTrue(content.contains("=== آخر محاولة نطق حقيقية فاشلة ==="))
        assertTrue(content.contains("كود nSpeak: -2"))
        assertTrue(content.contains("Salma"))
        assertTrue(content.contains("أول خطأ أصلي:"))
    }

    @Test
    fun trialLock_softerPolicy_allowsUpToThreeFailuresBeforeLocking() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = AcapelaHostSession(context)
        val voiceDir = tempFolder.newFolder("salma_lock_test")
        val voice = AcapelaVoiceManifest.VoiceInfo(
            id = "Salma",
            voiceName = "Salma",
            displayName = "Salma",
            locale = Locale.forLanguageTag("ar-SA"),
            sampleRate = 22050,
            dir = voiceDir,
            rawSpeaker = "salma22k",
            registration = "reg_test_123"
        )
        val canonicalDir = AcapelaVoiceDiagnostics
            .normalizeToUser0(voiceDir)
            .canonicalPath
        val trialKey =
            "$canonicalDir" +
                "|${voice.rawSpeaker?.lowercase(Locale.ROOT)}" +
                "|${voice.registration}"

        // محاولة أولى فاشلة: لا يتم قفل الجلسة
        session.handleRealSpeechFailure(
            voice, "extended", "chunk1", -2, "s1", "e1"
        )
        assertEquals(1, session.consecutiveTrialFailures[trialKey])
        org.junit.Assert.assertNull(session.failedTrialKey)

        // محاولة ثانية فاشلة: ما زالت لا تُقفل
        session.handleRealSpeechFailure(
            voice, "extended", "chunk2", -2, "s2", "e2"
        )
        assertEquals(2, session.consecutiveTrialFailures[trialKey])
        org.junit.Assert.assertNull(session.failedTrialKey)

        // محاولة ثالثة فاشلة: الآن فقط يُثبت القفل
        session.handleRealSpeechFailure(
            voice, "extended", "chunk3", -2, "s3", "e3"
        )
        assertEquals(3, session.consecutiveTrialFailures[trialKey])
        assertEquals(trialKey, session.failedTrialKey)

        // نجاح يصفّر العداد ويزيل القفل
        session.onRealSpeechSuccess(voice)
        org.junit.Assert.assertNull(
            session.consecutiveTrialFailures[trialKey]
        )
        org.junit.Assert.assertNull(session.failedTrialKey)
    }

    @Test
    fun trialLock_resetTrialLock_clearsLockImmediately() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = AcapelaHostSession(context)
        val key = "/path/to/voice|speaker|reg"
        session.consecutiveTrialFailures[key] = 3
        session.failedTrialKey = key

        session.resetTrialLock()
        org.junit.Assert.assertNull(session.failedTrialKey)
        assertTrue(session.consecutiveTrialFailures.isEmpty())
    }

    @Test
    fun handleRealSpeechFailure_writesDiagnosticWithRunIdAndNativeLines() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = AcapelaHostSession(context)
        val voiceDir = tempFolder.newFolder("salma_fail_diag")
        val voice = AcapelaVoiceManifest.VoiceInfo(
            id = "Salma",
            voiceName = "Salma",
            displayName = "Salma",
            locale = Locale.forLanguageTag("ar-SA"),
            sampleRate = 22050,
            dir = voiceDir,
            rawSpeaker = "salma22k",
            registration = "reg_test_123"
        )
        val testRunId = "run_fail_99"
        session.handleRealSpeechFailure(
            voice = voice,
            path = "extended",
            chunk = "اختبار النطق الفاشل",
            speakRes = -2,
            startMarker = "start_marker",
            endMarker = "end_marker",
            runId = testRunId
        )

        val failFile = File(
            context.cacheDir,
            com.aymankhattab.nateq.core.audio.providers
                .AcapelaVoiceDiagnostics
                .ACAPELA_LAST_REAL_SPEECH_FAILURE_FILE
        )
        assertTrue(failFile.isFile)
        val content = failFile.readText()
        assertTrue(content.contains("runId: $testRunId"))
        assertTrue(content.contains("كود رجوع nSpeak الرقمي: -2"))
        assertTrue(content.contains("كود nSpeak: -2"))
        assertTrue(content.contains("عدد العينات:"))
        assertTrue(content.contains("عينات PCM:"))
        assertTrue(content.contains("أول خطأ أصلي في الأسطر:"))
        assertTrue(content.contains("=== آخر محاولة نطق حقيقية فاشلة ==="))
    }

    @Test
    fun probeSelector_resolvesLocaleSpecificProbes() {
        assertEquals(
            "مرحبا",
            AcapelaHostSession.Companion.AcapelaProbeSelector.resolveProbeText(
                Locale.forLanguageTag("ar-SA")
            )
        )
        assertEquals(
            "Hello",
            AcapelaHostSession.Companion.AcapelaProbeSelector.resolveProbeText(
                Locale.forLanguageTag("en-US")
            )
        )
        assertEquals(
            "Hello",
            AcapelaHostSession.Companion.AcapelaProbeSelector.resolveProbeText(
                Locale.forLanguageTag("en-GB")
            )
        )
        assertEquals(
            "Bonjour",
            AcapelaHostSession.Companion.AcapelaProbeSelector.resolveProbeText(
                Locale.forLanguageTag("fr-FR")
            )
        )
        assertEquals(
            "Hello",
            AcapelaHostSession.Companion.AcapelaProbeSelector.resolveProbeText(
                null,
                "eng"
            )
        )
        assertEquals(
            "مرحبا",
            AcapelaHostSession.Companion.AcapelaProbeSelector.resolveProbeText(
                null,
                "ara"
            )
        )
    }
}
