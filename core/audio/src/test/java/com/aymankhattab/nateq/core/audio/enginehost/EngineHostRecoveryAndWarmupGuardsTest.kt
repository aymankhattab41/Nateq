package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceDiagnostics
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
        assertTrue(saved!!.contains("nSpeak: مستدعى=false | كود=لم يُستدعَ"))
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
        assertTrue(report.contains("[حمولة ترخيص مُقنَّعة]"))
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
        assertTrue(red1.contains("[حمولة ترخيص مُقنَّعة]"))

        // استمرار القناع في الرسالة الثانية دون إعادة ضبط
        assertFalse(red2.contains("ContinuationTwo=="))
        assertTrue(red2.contains("[حمولة ترخيص مُقنَّعة]"))
        assertTrue(red2.contains("tts_function_load_voice: Salma - done"))

        // بقاء مسار الملف العادي سليماً ومقروءاً دون إتلاف بقناع BLOB
        assertTrue(
            red2.contains(
                "/data/user/0/com.aymankhattab.nateq/files/Acapela/" +
                    "ara-sau-salma-embedded-22khz/NLP/ara-sau.dat"
            )
        )
    }
}
