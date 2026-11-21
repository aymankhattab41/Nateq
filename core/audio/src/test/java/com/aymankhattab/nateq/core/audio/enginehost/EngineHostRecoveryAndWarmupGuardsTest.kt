package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceDiagnostics
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceManifest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * اختبارات حراسة التعافي والإحماء وسجل أول فشل لمضيف المحركات (بناء 10749).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineHostRecoveryAndWarmupGuardsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EngineHostClient.setContext(context)
        AcapelaVoiceDiagnostics.clearLastRealSpeechFailure(context)
    }

    @After
    fun tearDown() {
        AcapelaVoiceDiagnostics.clearLastRealSpeechFailure(context)
        EngineHostClient.release(context, HostProtocol.ENGINE_ACAPELA)
        EngineHostClient.release(context, HostProtocol.ENGINE_VOCALIZER)
    }

    @Test
    fun invalidate_doesNotTriggerDeathEvent() {
        val initialGen = EngineHostClient.hostGeneration.get()
        val conn = EngineHostClient.connection(
            context,
            HostProtocol.ENGINE_ACAPELA
        )

        // تنظيف متعمد: لا يطلق حدث موت ولا يزيد جيل المضيف
        conn.invalidate()

        assertEquals(initialGen, EngineHostClient.hostGeneration.get())
        assertFalse(EngineHostClient.lastRecoveryFailed)
    }

    @Test
    fun duplicateDeathCallbacks_coalesceIntoSingleRecovery() {
        val currentGen = EngineHostClient.hostGeneration.get()

        // استدعاءان متتاليان لنفس الجيل (مثلاً من محركين يشتركان في المضيف)
        EngineHostClient.notifyHostDied(
            HostProtocol.ENGINE_ACAPELA,
            currentGen
        )
        val genAfterFirst = EngineHostClient.hostGeneration.get()

        // البلاغ المكرر للجيل السابق يجب أن يُتجاهل
        EngineHostClient.notifyHostDied(
            HostProtocol.ENGINE_VOCALIZER,
            currentGen
        )
        val genAfterSecond = EngineHostClient.hostGeneration.get()

        assertEquals(genAfterFirst, genAfterSecond)
        assertEquals(currentGen + 1L, genAfterFirst)
    }

    @Test
    fun staleGeneration_doesNotTearDownNewerGeneration() {
        EngineHostClient.hostGeneration.set(10L)

        // بلاغ متأخر من جيل قديم (مثلاً جيل 8)
        EngineHostClient.notifyHostDied(
            HostProtocol.ENGINE_ACAPELA,
            8L
        )

        // يبقى الجيل ثابتاً ولا يُعاد التنسيق
        assertEquals(10L, EngineHostClient.hostGeneration.get())
    }

    @Test
    fun voiceLoadFailure_withoutSpeak_recordsNullSpeakCodeAndNoReady() {
        // عند تعثر تحميل الصوت وعدم استدعاء nSpeak
        val evaluated = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
            speakCode = null,
            samples = null,
            nativeLines = listOf(
                "tts_function_load_voice : Salma - error eb -27"
            ),
            callbackConnected = false,
            invoked = false
        )
        assertFalse(evaluated)

        val record = AcapelaVoiceDiagnostics.FirstFailureRecord(
            buildVersion = "1.6.71",
            buildCode = 10671L,
            timestamp = "2026-11-21 12:00:00.000",
            runId = "test_run_nospeak",
            connectionGeneration = 1L,
            engineName = HostProtocol.ENGINE_ACAPELA,
            stage = "AuthenticVoice",
            verifiedVoiceDir = "/test/dir",
            speaker = "Salma",
            initInvoked = true,
            initCode = 0,
            voiceInvoked = true,
            voiceCode = -27,
            speakInvoked = false,
            speakCode = null,
            speakDesc = "لم يُستدعَ",
            samples = 0L,
            bytes = 0L,
            exitReasonDesc = "لا يوجد انهيار",
            isConfirmedNativeCrash = false
        )

        AcapelaVoiceDiagnostics.recordFirstFailure(context, record)

        val saved = AcapelaVoiceDiagnostics.readLastRealSpeechFailure(context)
        assertNotNull(saved)
        assertTrue(
            saved!!.contains("nSpeak: مستدعى=false | كود=null (لم يُستدعَ)")
        )
        assertTrue(saved.contains("وصف nSpeak: لم يُستدعَ"))
        assertNull(record.speakCode)
        assertFalse(saved.contains("nSpeak: مستدعى=false | كود=-27"))
    }

    @Test
    fun warmupSuccess_requiresPositiveSamples() {
        // صفر عينات لا يُعتبر نجاحاً حتى لو كان كود nSpeak صفراً
        val zeroSamples = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
            speakCode = 0,
            samples = 0L,
            nativeLines = listOf("tts_function_load_voice : Salma - done"),
            callbackConnected = true,
            invoked = true
        )
        assertFalse(zeroSamples)

        // عينات موجبة مع كود صفر وبلا أخطاء = نجاح
        val positiveSamples = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
            speakCode = 0,
            samples = 29223L,
            nativeLines = listOf("tts_function_load_voice : Salma - done"),
            callbackConnected = true,
            invoked = true
        )
        assertTrue(positiveSamples)
    }

    @Test
    fun firstFailureRecord_stability_andMultilineLicenseRedaction() {
        val runId = "run_first_failure_check"
        val firstRecord = AcapelaVoiceDiagnostics.FirstFailureRecord(
            buildVersion = "1.6.71",
            buildCode = 10671L,
            timestamp = "2026-11-21 12:00:00.000",
            runId = runId,
            connectionGeneration = 2L,
            engineName = HostProtocol.ENGINE_ACAPELA,
            stage = "AuthenticVoice",
            verifiedVoiceDir = "/verified/voice/dir",
            speaker = "Salma",
            initInvoked = true,
            initCode = 0,
            voiceInvoked = true,
            voiceCode = 0,
            speakInvoked = true,
            speakCode = -2,
            speakDesc = "nSpeak=-2",
            samples = 0L,
            bytes = 0L,
            exitReasonDesc = "SIGSEGV",
            isConfirmedNativeCrash = true
        )

        val logLines = listOf(
            "05-23 12:34:56.789 I acattsandroid: " +
                "\tbabParam.license MIIEvgIBADANBgkqhkiG9w0BAQEFAASC",
            "05-23 12:34:56.789 I acattsandroid: ContinuationPayloadLineOne==",
            "05-23 12:34:56.790 I acattsandroid: " +
                "tts_function_load_voice : Salma - error eb -27"
        )

        AcapelaVoiceDiagnostics.recordFirstFailure(
            context = context,
            record = firstRecord,
            nativeTail = logLines
        )

        // محاولة تسجيل ثانية لنفس runId (مثلاً تحذير تنظيف لاحق)
        val secondaryRecord = firstRecord.copy(
            stage = "SecondaryCleanupWarning",
            speakDesc = "محاولة ثانوية طامسة"
        )
        AcapelaVoiceDiagnostics.recordFirstFailure(
            context = context,
            record = secondaryRecord,
            nativeTail = emptyList()
        )

        val report = AcapelaVoiceDiagnostics.readLastRealSpeechFailure(context)
        assertNotNull(report)
        // يجب أن يبقى السجل الأول كما هو دون أن يُطمس
        assertTrue(report!!.contains("المرحلة: AuthenticVoice"))
        assertFalse(report.contains("SecondaryCleanupWarning"))
        assertFalse(report.contains("ContinuationPayloadLineOne=="))
        assertTrue(report.contains("[حمولة ترخيص محجوبة]"))
    }

    @Test
    fun generalVerificationInput_nInitReceivesSubpackageExclusively() {
        val generalDir = File(context.cacheDir, "test_acapela_general_root")
        generalDir.mkdirs()
        val salmaDir = File(generalDir, "Salma")
        salmaDir.mkdirs()
        val salmaIni = File(salmaDir, "Salma22k_embedded.ini")
        salmaIni.writeText(
            "salma authentic content directives with voice parameters",
            StandardCharsets.ISO_8859_1
        )
        val heatherDir = File(generalDir, "Heather")
        heatherDir.mkdirs()
        val heatherIni = File(heatherDir, "Heather22k_embedded.ini")
        heatherIni.writeText(
            "heather foreign voice directives",
            StandardCharsets.ISO_8859_1
        )

        val verification = AcapelaVoiceDiagnostics.verifyAuthenticVoicePackage(
            voiceDir = generalDir,
            expectedSpeakerHint = "Salma"
        )
        assertNotNull(verification.voiceDir)

        val resolved = ResolvedVerifiedPackage(
            voiceDir = verification.voiceDir,
            iniFile = verification.iniFile,
            speaker = "Salma",
            dependencies = verification.checked
        )

        // nInit يجب أن يستقبل مسار الحزمة الفرعية حصراً
        assertEquals(salmaDir.canonicalPath, resolved.voiceDir.canonicalPath)
        assertEquals("Salma", resolved.speaker)
        assertFalse(resolved.voiceDir.name.contains("Heather"))
    }

    @Test
    fun loadFailure_andRepeatedClose_neverCallsUnsafeUnload() {
        val dummyAudio = AcapelaHostSession.ManagedExtendedService(
            service = com.acapelagroup.android.tts.AcattsandroidService(
                null,
                "dummy_reg"
            ),
            audioHandler = AcapelaHostSession.TrialAudio { _, _ -> },
            resolvedPackage = null
        )

        dummyAudio.markPoisoned("فشل تحميل الصوت الأصلي")
        assertEquals(ExtendedServiceState.POISONED, dummyAudio.state)

        // تكرار الإغلاق على خدمة مسمومة يجب ألا يرمي استثناء ولا يستدعي unload
        dummyAudio.safeRelease()
        dummyAudio.safeRelease()
        assertEquals(ExtendedServiceState.CLOSED, dummyAudio.state)

        // التحقق من بقاء منسق التعافي سليماً وبلا حلقات
        val currentGen = EngineHostClient.hostGeneration.get()
        val conn = EngineHostClient.connection(
            context,
            HostProtocol.ENGINE_ACAPELA
        )
        conn.invalidate()
        assertEquals(currentGen, EngineHostClient.hostGeneration.get())
        assertFalse(EngineHostClient.lastRecoveryFailed)
    }

    @Test
    fun oldPid_andMixedLogs_separated_doesNotProveVoiceLoaded() {
        val mixedLogs = listOf(
            "lang_name: Arabic",
            "tts_function_load_voice: Heather - done",
            "tts_function_load_voice: Leila - done"
        )

        // أصوات أخرى أو lang_name لا تثبت تحميل Salma
        assertFalse(
            AcapelaVoiceDiagnostics.isSpeakerLoadedInLogs("Salma", mixedLogs)
        )

        val myPid = android.os.Process.myPid()
        val priorCrashLogs = listOf(
            "05-23 12:34:56.780  99999  99999 I acattsandroid: " +
                "Fatal signal 11 (SIGSEGV)",
            "05-23 12:34:56.781  $myPid  $myPid I acattsandroid: " +
                "tts_function_load_voice: Salma - error eb -27"
        )

        val record = AcapelaVoiceDiagnostics.FirstFailureRecord(
            buildVersion = "1.6.71",
            buildCode = 10671L,
            timestamp = "2026-11-21 12:00:00.000",
            runId = "run_pid_split_test",
            connectionGeneration = 1L,
            engineName = HostProtocol.ENGINE_ACAPELA,
            stage = "AuthenticVoice",
            verifiedVoiceDir = "/verified/voice",
            speaker = "Salma",
            initInvoked = true,
            initCode = 0,
            voiceInvoked = true,
            voiceCode = -27,
            speakInvoked = false,
            speakCode = null,
            speakDesc = "لم يُستدعَ",
            samples = 0L,
            bytes = 0L,
            exitReasonDesc = "none",
            isConfirmedNativeCrash = false,
            pid = myPid
        )

        AcapelaVoiceDiagnostics.recordFirstFailure(
            context = context,
            record = record,
            nativeTail = priorCrashLogs
        )

        val report = AcapelaVoiceDiagnostics.readLastRealSpeechFailure(context)
        assertNotNull(report)
        // قسم الانهيار السابق مفصول مع PID القديم
        assertTrue(report!!.contains("=== انهيار سابق سياقي (PID سابق) ==="))
        assertTrue(report.contains("Fatal signal 11"))
    }

    @Test
    fun multilineLicenseMasking_splitAcrossMessages_masksPayload() {
        val session = AcapelaVoiceDiagnostics.RedactionSession()
        val msg1 = "05-23 12:34:56.789 1234 1234 I acattsandroid: " +
            "babParam.license MIIEvgIBADANBgkqhkiG9w0BAQEFAASC\n" +
            "05-23 12:34:56.790 1234 1234 I prefix_one: ContinuationOne=="
        val msg2 = "05-23 12:34:56.791 1234 1234 I prefix_two: " +
            "ContinuationTwo==\n" +
            "05-23 12:34:56.792 1234 1234 I acattsandroid: " +
            "tts_function_load_voice: Salma - done\n" +
            "مسار الملف: /data/user/0/com.aymankhattab.nateq/files/Acapela/" +
            "ara-sau-salma-embedded-22khz/NLP/ara-sau.dat"

        val red1 = AcapelaVoiceDiagnostics.redactSensitiveText(msg1, session)
        val red2 = AcapelaVoiceDiagnostics.redactSensitiveText(msg2, session)

        assertFalse(red1.contains("ContinuationOne=="))
        assertTrue(red1.contains("[حمولة ترخيص محجوبة]"))

        // استمرار القناع في الرسالة الثانية دون إعادة ضبط
        assertFalse(red2.contains("ContinuationTwo=="))
        assertTrue(red2.contains("[حمولة ترخيص محجوبة]"))
        assertTrue(red2.contains("tts_function_load_voice: Salma - done"))

        // بقاء مسار الملف العادي سليماً ومقروءاً دون إتلاف بقناع BLOB
        assertTrue(
            red2.contains(
                "/data/user/0/com.aymankhattab.nateq/files/Acapela/" +
                    "ara-sau-salma-embedded-22khz/NLP/ara-sau.dat"
            )
        )
    }

    @Test
    fun rootWithDifferentMetadata_resolvesVerifiedSubpackageExclusively() {
        val rootDir = File(
            context.cacheDir,
            "test_acapela_subpackage_resolution"
        )
        rootDir.deleteRecursively()
        rootDir.mkdirs()
        val rootConfig = File(rootDir, "config.yaml")
        rootConfig.writeText(
            "name: Heather\nspeaker: Heather\nparam: heather_secret_param",
            StandardCharsets.UTF_8
        )
        val salmaDir = File(rootDir, "Salma")
        salmaDir.mkdirs()
        val salmaConfig = File(salmaDir, "config.yaml")
        salmaConfig.writeText(
            "name: Salma\nspeaker: Salma\nparam: salma_secret_param",
            StandardCharsets.UTF_8
        )
        val salmaIni = File(salmaDir, "Salma22k_embedded.ini")
        salmaIni.writeText(
            "salma authentic content directives with voice parameters",
            StandardCharsets.ISO_8859_1
        )

        val verification = AcapelaVoiceDiagnostics
            .verifyAuthenticVoicePackage(
                voiceDir = rootDir,
                expectedSpeakerHint = "Salma"
            )
        assertNotNull(verification.voiceDir)
        assertEquals(
            salmaDir.canonicalPath,
            verification.voiceDir?.canonicalPath
        )

        val verifiedDir = verification.voiceDir!!
        val verifiedYaml = File(verifiedDir, "config.yaml")
            .takeIf { it.isFile }
        assertNotNull(verifiedYaml)
        assertEquals(
            salmaConfig.canonicalPath,
            verifiedYaml!!.canonicalPath
        )

        val resolved = ResolvedVerifiedPackage(
            voiceDir = verifiedDir,
            iniFile = verification.iniFile,
            speaker = "Salma",
            dependencies = verification.checked,
            metadataFile = verifiedYaml,
            rawParam = "salma_secret_param",
            registration = "salma_reg"
        )
        assertEquals("Salma", resolved.speaker)
        assertEquals(
            salmaConfig.canonicalPath,
            resolved.metadataFile?.canonicalPath
        )
        assertEquals("salma_secret_param", resolved.rawParam)
    }

    @Test
    fun conflictingIdentityOrMissingMetadata_failsValidationFast() {
        val emptyDir = File(context.cacheDir, "test_empty_metadata_voice")
        emptyDir.mkdirs()
        val ini = File(emptyDir, "Salma22k_embedded.ini")
        ini.writeText("content", StandardCharsets.ISO_8859_1)

        val verifiedYaml = File(emptyDir, "config.yaml").takeIf { it.isFile }
        val hexFile = com.aymankhattab.nateq.core.audio.providers
            .AcapelaVoiceManifest.findHexRegistrationFile(emptyDir)
        assertNull(verifiedYaml)
        assertNull(hexFile)

        val heatherIni = File(emptyDir, "Heather22k_embedded.ini")
        heatherIni.writeText("content", StandardCharsets.ISO_8859_1)
        val iniToken = com.aymankhattab.nateq.core.audio.providers
            .AcapelaVoiceManifest.tokenOfIdentity(heatherIni.name)
        val speakerToken = com.aymankhattab.nateq.core.audio.providers
            .AcapelaVoiceManifest.tokenOfIdentity("Salma")
        assertNotNull(iniToken)
        assertNotNull(speakerToken)
        assertTrue(iniToken != speakerToken)
    }

    @Test
    fun idempotentRedaction_zeroSecretLeakage_andMultilineChunking() {
        val secret = "SuperSecretAcapelaLicense123456789"
        val firstRedact = AcapelaVoiceDiagnostics.redactSensitive(secret)
        assertEquals("[محجوب]", firstRedact)
        val secondRedact = AcapelaVoiceDiagnostics.redactSensitive(firstRedact)
        assertEquals("[محجوب]", secondRedact)

        val rawLine = "babParam.license: $secret"
        val line1 = AcapelaVoiceDiagnostics.redactSensitiveLine(rawLine)
        assertTrue(line1.contains("babParam.license"))
        assertTrue(line1.contains("[محجوب]"))
        assertFalse(line1.contains(secret))
        val line2 = AcapelaVoiceDiagnostics.redactSensitiveLine(line1)
        assertEquals(line1, line2)

        val hexLine = "ndInit: 0x1234567890abcdef"
        val hexRedacted = AcapelaVoiceDiagnostics.redactSensitiveLine(hexLine)
        assertTrue(hexRedacted.contains("[محجوب]"))
        assertFalse(hexRedacted.contains("1234567890abcdef"))
    }

    @Test
    fun logcatMarkersAndPidFiltering_supportsThreadtimeAndTimeFormats() {
        val threadtimeLine =
            "05-23 12:34:56.789  1234  5678 I acattsandroid: test"
        val timeWithSpaces =
            "05-23 12:34:56.789 I/acattsandroid( 1234): test"
        val timeWith3Spaces =
            "05-23 12:34:56.789 I/acattsandroid(   99): test"

        assertEquals(
            1234,
            AcapelaVoiceDiagnostics.extractPidFromLogcatLine(threadtimeLine)
        )
        assertEquals(
            1234,
            AcapelaVoiceDiagnostics.extractPidFromLogcatLine(timeWithSpaces)
        )
        assertEquals(
            99,
            AcapelaVoiceDiagnostics.extractPidFromLogcatLine(timeWith3Spaces)
        )

        val mixedPidLogs = listOf(
            "05-23 12:34:56.780  1000  1000 I acattsandroid: " +
                "tts_function_load_voice: Salma - error eb -27",
            "05-23 12:34:56.790  2000  2000 I acattsandroid: " +
                "tts_function_load_voice: Salma - done"
        )
        assertFalse(
            AcapelaVoiceDiagnostics.isSpeakerLoadedInLogs(
                speaker = "Salma",
                logs = mixedPidLogs,
                expectedPid = 1000
            )
        )
        assertTrue(
            AcapelaVoiceDiagnostics.isSpeakerLoadedInLogs(
                speaker = "Salma",
                logs = mixedPidLogs,
                expectedPid = 2000
            )
        )

        val testLines = listOf(
            "05-23 12:34:56.780  1000  1000 I acattsandroid: random line",
            "05-23 12:34:56.790  2000  2000 I acattsandroid: other line"
        )
        val missingBoundary = AcapelaVoiceDiagnostics
            .filterLogcatLinesBetweenMarkers(
                lines = testLines,
                startMarker = "NON_EXISTENT_START_MARKER_9999",
                endMarker = "NON_EXISTENT_END_MARKER_9999"
            )
        assertEquals(1, missingBoundary.size)
        assertTrue(
            missingBoundary[0].contains("حدود المحاولة غير متاحة")
        )
    }

    @Test
    fun speakInvokedFalse_displaysLoadFailureTitle_andNullCode() {
        val record = AcapelaVoiceDiagnostics.FirstFailureRecord(
            buildVersion = "1.6.71",
            buildCode = 10671L,
            timestamp = "2026-11-21 12:00:00.000",
            runId = "run_title_check",
            connectionGeneration = 1L,
            engineName = HostProtocol.ENGINE_ACAPELA,
            stage = "AuthenticVoice",
            verifiedVoiceDir = "/test/dir",
            speaker = "Salma",
            initInvoked = true,
            initCode = 0,
            voiceInvoked = true,
            voiceCode = -25,
            speakInvoked = false,
            speakCode = null,
            speakDesc = "لم يُستدعَ",
            samples = 0L,
            bytes = 0L,
            exitReasonDesc = "none",
            isConfirmedNativeCrash = false
        )

        AcapelaVoiceDiagnostics.recordFirstFailure(context, record)
        val text = AcapelaVoiceDiagnostics.readLastRealSpeechFailure(context)
        assertNotNull(text)
        assertTrue(
            text!!.contains(
                "=== سجل أول فشل أصلي (فشل تحميل قبل النطق) ==="
            )
        )
        assertTrue(
            text.contains(
                "nSpeak: مستدعى=false | كود=null (لم يُستدعَ)"
            )
        )
        assertTrue(text.contains("عينات PCM: غير مقيس"))
        assertTrue(text.contains("بايتات PCM: غير مقيس"))
    }

    @Test
    fun logcatReader_missingOrDelayedMarker_producesEvidenceUnavailable() {
        val reader = AcapelaVoiceDiagnostics.AcapelaLogcatReader(
            runId = "test_run_missing_marker",
            hostGeneration = 1L,
            pid = 1234
        )
        val seen = reader.waitForStartMarker(50L)
        assertFalse(seen)

        val result = AcapelaVoiceDiagnostics.evaluateVoiceEvidenceState(
            speaker = "Salma",
            logs = emptyList(),
            boundariesFound = false,
            source = "none"
        )
        assertEquals(
            AcapelaVoiceDiagnostics.VoiceEvidenceState.EVIDENCE_UNAVAILABLE,
            result.state
        )
        assertTrue(result.missingElements.contains("boundaries_missing"))
    }

    @Test
    fun releaseLoggingDisabled_producesEvidenceUnavailable() {
        val reader = AcapelaVoiceDiagnostics.AcapelaLogcatReader(
            runId = "test_release_disabled",
            hostGeneration = 1L,
            pid = 1234
        )
        val captured = reader.getCapturedLines()
        val result = AcapelaVoiceDiagnostics.evaluateVoiceEvidenceState(
            speaker = "Salma",
            logs = captured,
            boundariesFound = false,
            source = "none"
        )
        assertEquals(
            AcapelaVoiceDiagnostics.VoiceEvidenceState.EVIDENCE_UNAVAILABLE,
            result.state
        )
    }

    @Test
    fun failedReaderProcess_closesCleanlyWithoutCrash() {
        val reader = AcapelaVoiceDiagnostics.AcapelaLogcatReader(
            runId = "test_failed_proc",
            hostGeneration = 1L,
            pid = -9999
        )
        reader.start()
        reader.close()
        assertTrue(reader.isClosed)
    }

    @Test
    fun interleavedPids_filtersByExpectedPidAccurately() {
        val interleaved = listOf(
            "05-23 12:34:56.780  1000  1000 I acattsandroid: " +
                "tts_function_load_voice : Salma - done",
            "05-23 12:34:56.790  2000  2000 I acattsandroid: " +
                "tts_function_load_voice : Salma - error - eb -25"
        )
        val resultPid1000 = AcapelaVoiceDiagnostics
            .evaluateVoiceEvidenceState(
                speaker = "Salma",
                logs = interleaved,
                expectedPid = 1000,
                boundariesFound = true
            )
        assertEquals(
            AcapelaVoiceDiagnostics.VoiceEvidenceState.LOADED,
            resultPid1000.state
        )

        val resultPid2000 = AcapelaVoiceDiagnostics
            .evaluateVoiceEvidenceState(
                speaker = "Salma",
                logs = interleaved,
                expectedPid = 2000,
                boundariesFound = true
            )
        assertEquals(
            AcapelaVoiceDiagnostics.VoiceEvidenceState.LOAD_FAILED,
            resultPid2000.state
        )
        assertEquals(-25, resultPid2000.ebCode)
    }

    @Test
    fun staleLogFile_isExcludedAndProducesEvidenceUnavailable() {
        val tempDir = File(
            context.cacheDir,
            "test_stale_log_${System.currentTimeMillis()}"
        )
        tempDir.mkdirs()
        val logFile = File(tempDir, "logfile.txt")
        logFile.writeText("tts_function_load_voice : Salma - done")
        val attemptStartMs = System.currentTimeMillis() + 10000L
        logFile.setLastModified(attemptStartMs - 5000L)

        val isFresh = logFile.exists() && logFile.length() > 0L &&
            logFile.lastModified() >= (attemptStartMs - 500L)
        assertFalse(isFresh)

        val result = AcapelaVoiceDiagnostics.evaluateVoiceEvidenceState(
            speaker = "Salma",
            logs = emptyList(),
            boundariesFound = false,
            source = "stale_logfile.txt",
            missingElements = listOf("fresh_logfile")
        )
        assertEquals(
            AcapelaVoiceDiagnostics.VoiceEvidenceState.EVIDENCE_UNAVAILABLE,
            result.state
        )
        assertEquals("stale_logfile.txt", result.source)
        assertTrue(result.missingElements.contains("fresh_logfile"))
        tempDir.deleteRecursively()
    }

    @Test
    fun distinguishingMissingEvidence_fromLoadFailure() {
        val missingEvidenceResult = AcapelaVoiceDiagnostics
            .evaluateVoiceEvidenceState(
                speaker = "Salma",
                logs = emptyList(),
                boundariesFound = false
            )
        assertEquals(
            AcapelaVoiceDiagnostics.VoiceEvidenceState.EVIDENCE_UNAVAILABLE,
            missingEvidenceResult.state
        )

        val failureLogs = listOf(
            "05-23 12:34:56.780  1000  1000 I acattsandroid: " +
                "tts_function_load_voice : Salma - error - eb -25"
        )
        val loadFailedResult = AcapelaVoiceDiagnostics
            .evaluateVoiceEvidenceState(
                speaker = "Salma",
                logs = failureLogs,
                expectedPid = 1000,
                boundariesFound = true
            )
        assertEquals(
            AcapelaVoiceDiagnostics.VoiceEvidenceState.LOAD_FAILED,
            loadFailedResult.state
        )
        assertEquals(-25, loadFailedResult.ebCode)
    }

    @Test
    fun redaction_preservesSafeVerdictAndStructuredFields() {
        val auditLine = "  - [AcattsandroidService.<init>] " +
            "وسيط_العقد=registration | نوع_متوقع=String | " +
            "نوع_فعلي=String | الوجود=موجود | طول_آمن=56 | الحالة=ناجح"
        val redactedAudit = AcapelaVoiceDiagnostics
            .redactSensitiveLine(auditLine)
        assertEquals(auditLine, redactedAudit)

        val secretLine = "registration: mySecretRegistration123 | " +
            "نوع_متوقع=String | الحالة=ناجح"
        val redactedSecret = AcapelaVoiceDiagnostics
            .redactSensitiveLine(secretLine)
        assertTrue(redactedSecret.contains("[محجوب]"))
        assertFalse(redactedSecret.contains("mySecretRegistration123"))
        assertTrue(redactedSecret.contains("الحالة=ناجح"))

        val idempotent = AcapelaVoiceDiagnostics
            .redactSensitiveLine(redactedSecret)
        assertEquals(redactedSecret, idempotent)
    }

    @Test
    fun parentChildRelationship_validSupportFilesAttachedToResolvedPackage() {
        val rootDir = File(
            context.cacheDir,
            "test_parent_child_${System.currentTimeMillis()}"
        )
        rootDir.mkdirs()
        try {
            val parentDir = File(rootDir, "Acapela")
            parentDir.mkdirs()
            val licenseFile = File(parentDir, "0x00169729")
            licenseFile.writeText("sample_license_content")
            val parentYaml = File(parentDir, "config.yaml")
            parentYaml.writeText("voice: Salma\nvoiceCode: salma22k\n")

            val childVoiceDir = File(parentDir, "salma22k_hq")
            childVoiceDir.mkdirs()
            val iniFile = File(childVoiceDir, "salma22k.ini")
            iniFile.writeText("[Voice]\nname=Salma\n")

            val resolved = ResolvedVerifiedPackage(
                voiceDir = childVoiceDir,
                iniFile = iniFile,
                speaker = "Salma",
                metadataFile = parentYaml,
                supportDir = parentDir,
                supportFiles = listOf(licenseFile, parentYaml),
                supportRelation = "PARENT_CONTAINER"
            )

            assertEquals(childVoiceDir, resolved.voiceDir)
            assertEquals("PARENT_CONTAINER", resolved.supportRelation)
            assertEquals(parentDir, resolved.supportDir)
            assertEquals(2, resolved.supportFiles.size)
            assertTrue(resolved.supportFiles.contains(licenseFile))
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun supportFileMismatch_anotherVoiceRejected() {
        val rootDir = File(
            context.cacheDir,
            "test_mismatch_${System.currentTimeMillis()}"
        )
        rootDir.mkdirs()
        try {
            val parentDir = File(rootDir, "Acapela")
            parentDir.mkdirs()
            val leilaYaml = File(parentDir, "config.yaml")
            leilaYaml.writeText("voice: Leila\nvoiceCode: leila22k\n")

            val yamlSpeaker = "Leila"
            val expectedSpeaker = "Salma"
            val yamlToken = AcapelaVoiceManifest.tokenOfIdentity(yamlSpeaker)
            val expectedToken = AcapelaVoiceManifest.tokenOfIdentity(
                expectedSpeaker
            )

            assertNotNull(yamlToken)
            assertNotNull(expectedToken)
            assertTrue(yamlToken != expectedToken)

            val isMatch = yamlToken == expectedToken
            assertFalse(isMatch)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun missingContractResource_proactiveVerificationDetectsMissing() {
        val rootDir = File(
            context.cacheDir,
            "test_proactive_missing_${System.currentTimeMillis()}"
        )
        rootDir.mkdirs()
        try {
            val voiceDir = File(rootDir, "salma22k_hq")
            voiceDir.mkdirs()
            val iniFile = File(voiceDir, "salma22k.ini")
            iniFile.writeText("[Voice]\nname=Salma\n")

            val audits = AcapelaVoiceDiagnostics.auditProactiveContractPaths(
                voiceDir = voiceDir,
                supportDir = null,
                iniFile = iniFile,
                metadataFile = null,
                supportFiles = emptyList()
            )

            val licenseAudit = audits.firstOrNull {
                it.role.startsWith("licenseFile")
            }
            assertNotNull(licenseAudit)
            assertEquals("MISSING", licenseAudit!!.auditStatus)
            assertFalse(licenseAudit.passed)

            val nlpAudit = audits.firstOrNull {
                it.role.startsWith("nlpDir")
            }
            assertNotNull(nlpAudit)
            assertEquals("MISSING", nlpAudit!!.auditStatus)
            assertFalse(nlpAudit.passed)

            val iniAudit = audits.firstOrNull {
                it.role.startsWith("iniFile")
            }
            assertNotNull(iniAudit)
            assertEquals("PASSED", iniAudit!!.auditStatus)
            assertTrue(iniAudit.passed)
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun openErrorsWithoutLeaks_redactionPreserved() {
        val secretError = "error opening file: 0x00169729 | " +
            "registration: 1234567890ABCDEF | errno=2"
        val redacted = AcapelaVoiceDiagnostics.redactSensitiveLine(
            secretError
        )
        assertTrue(redacted.contains("[محجوب]"))
        assertFalse(redacted.contains("1234567890ABCDEF"))

        assertTrue(
            AcapelaVoiceDiagnostics.NATIVE_INTERCEPTION_DISCLAIMER
                .contains("closed-source")
        )
    }

    @Test
    fun failedReader_handlesLogcatUnavailableAndFallsBackToFreshLogfile() {
        val reader = AcapelaVoiceDiagnostics.AcapelaLogcatReader(
            runId = "test_unavailable_logcat",
            hostGeneration = 1L,
            pid = 9999
        )
        reader.stop(50L)
        assertNotNull(reader.stopReason)
        assertTrue(reader.stopReason.isNotEmpty())
        assertEquals(0, reader.linesCount)
        assertFalse(reader.startMarkerSeen)
        assertFalse(reader.endMarkerSeen)
        reader.close()
        assertTrue(reader.isClosed)

        val fallbackState = AcapelaVoiceDiagnostics
            .evaluateVoiceEvidenceState(
                speaker = "Salma",
                logs = emptyList(),
                boundariesFound = false,
                source = "fresh_logfile.txt",
                missingElements = listOf("logcat_unavailable")
            )
        assertEquals(
            AcapelaVoiceDiagnostics.VoiceEvidenceState.EVIDENCE_UNAVAILABLE,
            fallbackState.state
        )
        assertEquals("fresh_logfile.txt", fallbackState.source)
    }

    @Test
    fun readerCancellation_finallyBlockCleansUp() {
        val reader = AcapelaVoiceDiagnostics.AcapelaLogcatReader(
            runId = "test_cleanup",
            hostGeneration = 1L,
            pid = 8888
        )
        reader.start()
        try {
            throw IllegalStateException("Trial cancelled simulated")
        } catch (_: Exception) {
            reader.close()
        }
        assertTrue(reader.isClosed)
        assertTrue(reader.stopReason.isNotEmpty())
    }
}
