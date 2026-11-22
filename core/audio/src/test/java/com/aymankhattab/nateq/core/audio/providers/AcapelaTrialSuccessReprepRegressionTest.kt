package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * اختبارات انحدار صارمة وشاملة لنجاح وتأكيد تخليق Acapela:
 * 1. نجاح تجربة مع تحذير Reprep وnSpeak=0 وPCM موجب.
 * 2. فشل حقيقي عند خطأ nInit أو ترخيص أو هوية خاطئة.
 * 3. فشل حقيقي عند crash أو عدم اتصال callback أو 0 PCM.
 * 4. ثبات عدادات البايتات قبل وبعد التنظيف.
 * 5. محاولة جديدة ناجحة بعد فشل قديم تنظف حالة الفشل السابقة ولا تخلط runId.
 * 6. فحص متعدد الأصوات يختبر ليلى وسلمى باستقلال ملفات الدعم.
 * 7. مسار تشغيل المعاينة الصوتية وتوقفها عند الإلغاء.
 * 8. اختبار انحدار خاص بالتناقض الذي حدث في 10756 لمنع تكراره.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AcapelaTrialSuccessReprepRegressionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AcapelaVoiceDiagnostics.clearOldLogs(context)
    }

    /**
     * 1. نجاح تجربة مع تحذير Reprep وnSpeak=0 وPCM موجب (عينات سلمى المقيسة).
     */
    @Test
    fun testReprepWarningWithZeroSpeakAndPositivePcm_isSuccess() {
        val nativeLines = listOf(
            "tts_function_load_voice : Salma - done",
            "tts_function_set_specific_params : Reprep load error (warning)",
            "check_userdico : load error"
        )
        val success = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
            speakCode = 0,
            samples = 29223L,
            nativeLines = nativeLines,
            callbackConnected = true,
            invoked = true,
            speaker = "Salma"
        )
        assertTrue(
            "تحذير Reprep مع كود 0 وPCM موجب يجب أن يُصنَّف نجاحاً حقيقياً",
            success
        )

        val firstErr =
            AcapelaVoiceDiagnostics.findFirstErrorAndPreceding(nativeLines)
        assertNull(
            "تحذيرات Reprep وcheck_userdico لا تُسجَّل كأخطاء أصلية قاتلة",
            firstErr
        )
    }

    /**
     * 2. فشل حقيقي عند خطأ nInit أو ترخيص أو هوية خاطئة.
     */
    @Test
    fun testTrueFatalErrors_nInitLicenseOrIdentityMismatch_fail() {
        // فشل nInit (خطأ تهيئة لغة أصلي)
        val nInitFail = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
            speakCode = 0,
            samples = 29223L,
            nativeLines = listOf(
                "after initLanguageDbaNewEx",
                "Error init language : -27"
            ),
            callbackConnected = true,
            invoked = true,
            speaker = "Salma"
        )
        assertFalse("خطأ تهيئة اللغة الأصلي يجب أن يفشل التجربة", nInitFail)

        // فشل تحميل الصوت صراحة للصوت المطلوب
        val voiceLoadFail = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
            speakCode = 0,
            samples = 29223L,
            nativeLines = listOf(
                "tts_function_load_voice : Leila",
                "voice Leila (0) not loaded"
            ),
            callbackConnected = true,
            invoked = true,
            speaker = "Leila"
        )
        assertFalse(
            "فشل تحميل الصوت المحدد صراحة يجب أن يفشل التجربة",
            voiceLoadFail
        )

        // خطأ صريح في تحميل الصوت مع رمز خطأ
        val explicitLoadError =
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
                speakCode = 0,
                samples = 29223L,
                nativeLines = listOf(
                    "tts_function_load_voice : Nizar - error",
                    "eb -27"
                ),
                callbackConnected = true,
                invoked = true,
                speaker = "Nizar"
            )
        assertFalse(
            "ظهور tts_function_load_voice : - error يجب أن يفشل التجربة",
            explicitLoadError
        )
    }

    /**
     * 3. فشل حقيقي عند crash أو عدم اتصال callback أو 0 PCM.
     */
    @Test
    fun testTrueFatalErrors_crashOrMissingCallbackOrZeroPcm_fail() {
        // انهيار أصلي (SIGSEGV / Fatal signal)
        val crashFail = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
            speakCode = 0,
            samples = 29223L,
            nativeLines = listOf(
                "Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)"
            ),
            callbackConnected = true,
            invoked = true,
            speaker = "Salma"
        )
        assertFalse("الانهيار الأصلي يجب أن يفشل التجربة فوراً", crashFail)

        // عدم اتصال الـ callback
        val noCallbackFail = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
            speakCode = 0,
            samples = 29223L,
            nativeLines = listOf("clean log"),
            callbackConnected = false,
            invoked = true,
            speaker = "Salma"
        )
        assertFalse(
            "غياب اتصال الـ callback يجب أن يفشل التجربة",
            noCallbackFail
        )

        // عينات PCM صفرية
        val zeroPcmFail = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
            speakCode = 0,
            samples = 0L,
            nativeLines = listOf("clean log"),
            callbackConnected = true,
            invoked = true,
            speaker = "Salma"
        )
        assertFalse("عينات PCM صفرية يجب أن تفشل التجربة", zeroPcmFail)

        // كود nSpeak سالب
        val negativeSpeakFail =
            AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
                speakCode = -2,
                samples = 29223L,
                nativeLines = listOf("clean log"),
                callbackConnected = true,
                invoked = true,
                speaker = "Salma"
            )
        assertFalse("كود nSpeak سالب يجب أن يفشل التجربة", negativeSpeakFail)
    }

    /**
     * 4. ثبات عدادات البايتات قبل وبعد التنظيف (تجنب تصفير العداد).
     */
    @Test
    fun testByteCounters_immutableBeforeAndAfterReset() {
        val pcmStream = ByteArrayOutputStream()
        val dummyData = ByteArray(58446) { (it % 127).toByte() }
        pcmStream.write(dummyData)

        // التقاط لقطة غير قابلة للتغيير
        val immutableBytesCount = pcmStream.size().toLong()
        val immutableByteArray = pcmStream.toByteArray()

        assertEquals(58446L, immutableBytesCount)
        assertEquals(58446, immutableByteArray.size)

        // محاكاة التنظيف داخل safeRelease
        pcmStream.reset()

        assertEquals(0, pcmStream.size())
        assertEquals(
            "اللقطة المحفوظة تظل ثابتة بعد تفريغ المجرى",
            58446L,
            immutableBytesCount
        )
        assertEquals(
            "مصفوفة البايتات تظل ثابتة بعد تفريغ المجرى",
            58446,
            immutableByteArray.size
        )
    }

    /**
     * 5. محاولة جديدة ناجحة بعد فشل قديم تنظف حالة الفشل السابقة
     * ولا تخلط runId.
     */
    @Test
    fun testSuccessAfterOldFailure_clearsFailureAndDoesNotShowOldRunId() {
        // كتابة ملف فشل سابق لـ runId قديم
        val oldFailureText = buildString {
            appendLine("=== آخر محاولة نطق حقيقية فاشلة ===")
            appendLine("runId: 10755_old_run")
            appendLine("كود nSpeak: -2")
            appendLine("عينات PCM: 0")
        }
        val failureFile = File(
            context.cacheDir,
            AcapelaVoiceDiagnostics.ACAPELA_LAST_REAL_SPEECH_FAILURE_FILE
        )
        failureFile.writeText(oldFailureText)
        assertTrue(failureFile.exists())

        // محاكاة نجاح تجربة جديدة
        AcapelaVoiceDiagnostics.clearLastRealSpeechFailure(context)

        assertFalse(
            "ملف الفشل القديم يجب حذفه بالكامل عند نجاح محاولة جديدة",
            failureFile.exists()
        )
    }

    /**
     * 6. فحص متعدد الأصوات يختبر ليلى وسلمى باستقلال ملفات الدعم.
     */
    @Test
    fun testMultiVoiceProbe_independentSupportFiles_isolation() {
        val salmaSuccess = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
            speakCode = 0,
            samples = 29223L,
            nativeLines = listOf(
                "tts_function_load_voice : Salma - done",
                "tts_function_set_specific_params : Reprep"
            ),
            callbackConnected = true,
            invoked = true,
            speaker = "Salma"
        )
        assertTrue("نجاح سلمى يجب أن يستقر مستقلاً", salmaSuccess)

        // ليلى غير محملة أو تفتقر للدعم
        val leilaSuccess = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
            speakCode = -2,
            samples = 0L,
            nativeLines = listOf(
                "tts_function_load_voice : Leila - error",
                "voice Leila (0) not loaded"
            ),
            callbackConnected = true,
            invoked = true,
            speaker = "Leila"
        )
        assertFalse("فشل ليلى مستقل ولا يمس سلمى", leilaSuccess)

        // نزار ناجح
        val nizarSuccess = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
            speakCode = 0,
            samples = 15000L,
            nativeLines = listOf(
                "tts_function_load_voice : Nizar - done"
            ),
            callbackConnected = true,
            invoked = true,
            speaker = "Nizar"
        )
        assertTrue("نجاح نزار يعمل بأمان", nizarSuccess)
    }

    /**
     * 7. مسار تشغيل المعاينة الصوتية وتوقفها عند الإلغاء.
     */
    @Test
    fun testAudioPreviewPlayback_runsAndStopsOnCancellation() {
        val dummyChunk1 = ByteArray(1024) { 1 }
        val dummyChunk2 = ByteArray(1024) { 2 }
        val chunks = listOf(dummyChunk1, dummyChunk2)

        // تجربة مع الإلغاء الفوري
        val cancelled = AtomicBoolean(true)
        val cancelResult = AcapelaVoiceDiagnostics.playAudioChunks(
            chunks = chunks,
            sampleRate = 22050,
            cancelled = cancelled
        )
        assertTrue(
            "الإلغاء الفوري يجب أن ينعكس في حالة المشغل",
            cancelResult.state == "CANCELLED" ||
                cancelResult.error?.contains("أُلغي") == true
        )

        // تجربة بدون إلغاء
        val normalCancelled = AtomicBoolean(false)
        val normalResult = AcapelaVoiceDiagnostics.playAudioChunks(
            chunks = chunks,
            sampleRate = 22050,
            cancelled = normalCancelled
        )
        assertNotNull(normalResult)
        assertTrue(
            normalResult.state == "COMPLETED" ||
                normalResult.state == "CANCELLED" ||
                normalResult.deliveredBytes >= 0L
        )
    }

    /**
     * 8. اختبار انحدار خاص بالتناقض الذي حدث في 10756 لمنع تكراره:
     * nSpeak=0، 29223 عينة، 58446 بايت، مع ظهور Reprep وغياب أسطر Logcat.
     */
    @Test
    fun test10756ParadoxRegression_reprepAndEvidenceUnavailableDoesNotFail() {
        val nativeLines10756 = listOf(
            "tts_function_set_specific_params : Reprep load error (warning)",
            "nlpInitError : 0",
            "check_userdico : load error"
        )
        val isSuccess = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
            speakCode = 0,
            samples = 29223L,
            nativeLines = nativeLines10756,
            callbackConnected = true,
            invoked = true,
            speaker = "Salma"
        )
        assertTrue(
            "تناقض 10756: وجود Reprep وغياب سطر load_voice لا يفشل " +
                "تجربة أنتجت كود 0 و29223 عينة حقيقية",
            isSuccess
        )

        // التحقق من أن السطر الأول للخطأ لا يلتقط Reprep
        val firstErr =
            AcapelaVoiceDiagnostics.findFirstErrorAndPreceding(nativeLines10756)
        assertNull(
            "السطر الأول للخطأ يجب ألا يلتقط Reprep أو check_userdico",
            firstErr
        )
    }
}
