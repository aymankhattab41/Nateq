package com.aymankhattab.nateq.core.audio.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import com.aymankhattab.nateq.core.audio.enginehost.HostWarmStatusRegistry
import com.aymankhattab.nateq.core.audio.enginehost.MicrosoftHostSession
import com.aymankhattab.nateq.core.audio.enginehost.PcmSink
import com.aymankhattab.nateq.core.audio.enginehost.SpeakRequest
import com.aymankhattab.nateq.core.audio.enginehost.VocalizerHostSession
import com.aymankhattab.nateq.core.audio.providers.AcapelaEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceRepository
import com.aymankhattab.nateq.core.audio.providers.EmbeddedStorage
import com.aymankhattab.nateq.core.audio.providers.MicrosoftEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.MicrosoftVoiceRepository
import com.aymankhattab.nateq.core.audio.providers.VocalizerEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VocalizerVoiceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * اختبارات شاملة لتحسين زمن أول نطق وجاهزية الأصوات عبر كافة المحركات
 * المدمجة (Vocalizer، Acapela، Microsoft):
 * 1. ضمان صفر مسح قرص متزامن على الخيط الرئيسي في isConfigured لكافة المحركات.
 * 2. التحقق من كاش الجلسات الدافئة والعدادات الموضوعية (Hits vs Misses).
 * 3. دقة التتبع الموحد لزمن الاستجابة (UnifiedSpeechTracker) مع p50 و p95.
 * 4. إدارة منسق الجاهزية (VoiceReadinessCoordinator) والدمج وإلغاء الاستباق.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class MultiEngineReadinessAndLatencyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EnginePerformanceCounters.resetAllForTest()
        UnifiedSpeechTracker.clearForTest()
        SalmaSpeechTracker.clearForTest()
        AcapelaVoiceRepository.resetForTests()
        MicrosoftVoiceRepository.resetForTests()
        VocalizerVoiceRepository.resetForTests()
        VoiceReadinessCoordinator.resetForTest()
        HostWarmStatusRegistry.resetForTests()
        HostWarmStatusRegistry.clearAll(context)
    }

    @After
    fun tearDown() {
        EnginePerformanceCounters.resetAllForTest()
        UnifiedSpeechTracker.clearForTest()
        SalmaSpeechTracker.clearForTest()
        AcapelaVoiceRepository.resetForTests()
        MicrosoftVoiceRepository.resetForTests()
        VocalizerVoiceRepository.resetForTests()
        VoiceReadinessCoordinator.resetForTest()
        HostWarmStatusRegistry.resetForTests()
        HostWarmStatusRegistry.clearAll(context)
    }

    @Test
    fun testAllEngines_isConfigured_onMainThread_zeroDiskIo() {
        assertEquals(
            "يجب تشغيل الفحص على الخيط الرئيسي",
            Looper.getMainLooper(),
            Looper.myLooper()
        )

        val acapela = AcapelaEmbeddedVoiceProvider(context)
        val vocalizer = VocalizerEmbeddedVoiceProvider(context)
        val microsoft = MicrosoftEmbeddedVoiceProvider(context)

        // استدعاء فحص التهيئة على الخيط الرئيسي
        val acOk = acapela.isConfigured()
        val vocOk = vocalizer.isConfigured()
        val msOk = microsoft.isConfigured()

        // التحقق من عدم حظر الخيط الرئيسي وعدم لمس القرص
        assertEquals(
            "Acapela لم تقم بأي مسح قرص على الرئيسي",
            0L,
            EnginePerformanceCounters.acapela.scans.get()
        )
        assertEquals(
            "Vocalizer لم يقم بأي مسح قرص على الرئيسي",
            0L,
            EnginePerformanceCounters.vocalizer.scans.get()
        )
        assertEquals(
            "Microsoft لم تقم بأي مسح قرص على الرئيسي",
            0L,
            EnginePerformanceCounters.microsoft.scans.get()
        )

        assertFalse(acOk)
        assertFalse(vocOk)
        assertFalse(msOk)
    }

    @Test
    fun testUnifiedSpeechTracker_aggregatesAllEnginesAndPercentiles() {
        val engines = listOf(
            UnifiedSpeechTracker.Engine.ACAPELA,
            UnifiedSpeechTracker.Engine.VOCALIZER,
            UnifiedSpeechTracker.Engine.MICROSOFT
        )

        for (eng in engines) {
            for (i in 1..10) {
                val req = UnifiedSpeechTracker.startRequest(
                    engine = eng,
                    category = UnifiedSpeechTracker.Category.WARM,
                    voiceName = "voice_$eng",
                    textLength = 20
                )
                req.markQueueWaitStart()
                req.markQueueWaitEnd()
                req.markSpeakCallStart()
                req.markSpeakCallEnd()
                req.markFirstPcm()
                req.markFirstTrackWrite()
                req.complete(UnifiedSpeechTracker.Status.SUCCESS)
            }

            // إضافة طلب ملغى وطلب فاشل
            val cancelled = UnifiedSpeechTracker.startRequest(
                engine = eng,
                category = UnifiedSpeechTracker.Category.WARM,
                voiceName = "voice_$eng",
                textLength = 10
            )
            cancelled.complete(
                UnifiedSpeechTracker.Status.CANCELLED,
                "Cancelled by user"
            )

            val failed = UnifiedSpeechTracker.startRequest(
                engine = eng,
                category = UnifiedSpeechTracker.Category.WARM,
                voiceName = "voice_$eng",
                textLength = 10
            )
            failed.complete(
                UnifiedSpeechTracker.Status.FAILED,
                "Engine error"
            )

            val stats = UnifiedSpeechTracker.getStats(engine = eng)
            assertEquals(12, stats.totalCount)
            assertEquals(10, stats.successCount)
            assertEquals(1, stats.cancelledCount)
            assertEquals(1, stats.failedCount)
            assertEquals(0, stats.unmeasuredCount)

            assertTrue(
                "متوسط أول بايت يجب أن يكون موجباً",
                stats.avgFirstPcmMs >= 0.0
            )
            assertTrue(
                "p50 يجب أن يكون أقل أو يساوي p95",
                stats.p50FirstPcmMs <= stats.p95FirstPcmMs
            )
            assertTrue(
                "p95 يجب أن يكون أكبر أو يساوي 0",
                stats.p95FirstPcmMs >= 0.0
            )
        }

        val overallStats = UnifiedSpeechTracker.getStats()
        assertEquals(36, overallStats.totalCount)
        assertEquals(30, overallStats.successCount)
        assertEquals(3, overallStats.cancelledCount)
        assertEquals(3, overallStats.failedCount)
    }

    @Test
    fun testVoiceReadinessCoordinator_onSpeechRequested_cancelsProactiveJobs() =
        runBlocking {
            VoiceReadinessCoordinator.onSpeechRequested(
                engineId = "vocalizer_embedded",
                voiceId = "tarek"
            )

            // محاولة إحماء صوت آخر أثناء النطق الحقيقي تُلغى أو تُرفض
            val prepared = VoiceReadinessCoordinator.prepareVoice(
                context = context,
                engineId = "microsoft_embedded",
                voiceId = "salma",
                priority = false
            )
            assertFalse(
                "يجب عدم تنفيذ الإحماء غير ذي الأولوية أثناء نطق حقيقي",
                prepared
            )

            VoiceReadinessCoordinator.onSpeechCompleted()
        }

    @Test
    fun testVoiceReadinessCoordinator_onTrimMemory_clearsState() {
        VoiceReadinessCoordinator.onTrimMemory(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        )
        assertFalse(
            VoiceReadinessCoordinator.isVoiceReady(
                "vocalizer_embedded",
                "tarek"
            )
        )
    }

    @Test
    fun testPerformanceCounters_recordingAndReset() {
        EnginePerformanceCounters.microsoft.scans.incrementAndGet()
        EnginePerformanceCounters.microsoft.synthWarmHits.addAndGet(5L)
        EnginePerformanceCounters.vocalizer.loadVoiceHits.addAndGet(3L)

        assertEquals(
            1L,
            EnginePerformanceCounters.microsoft.scans.get()
        )
        assertEquals(
            5L,
            EnginePerformanceCounters.microsoft.synthWarmHits.get()
        )
        assertEquals(
            3L,
            EnginePerformanceCounters.vocalizer.loadVoiceHits.get()
        )

        EnginePerformanceCounters.resetAllForTest()

        assertEquals(
            0L,
            EnginePerformanceCounters.microsoft.scans.get()
        )
        assertEquals(
            0L,
            EnginePerformanceCounters.microsoft.synthWarmHits.get()
        )
        assertEquals(
            0L,
            EnginePerformanceCounters.vocalizer.loadVoiceHits.get()
        )
    }

    @Test
    fun testVoiceReadinessCoordinator_probeLanguageSelection_matchesVoice() =
        runBlocking {
            // التحقق من فحص الجاهزية المباشر عبر المحركات
            MicrosoftHostSession.noteWarmVoiceLoaded("JennyNeural")
            assertTrue(
                VoiceReadinessCoordinator.isVoiceReady(
                    MicrosoftEmbeddedVoiceProvider.PROVIDER_ID,
                    "en-US-JennyNeural"
                )
            )

            VocalizerHostSession.noteWarmVoiceLoaded("tarik_embedded")
            assertTrue(
                VoiceReadinessCoordinator.isVoiceReady(
                    VocalizerEmbeddedVoiceProvider.PROVIDER_ID,
                    "tarik_embedded"
                )
            )

            MicrosoftHostSession.noteWarmVoiceClosed()
            assertFalse(
                VoiceReadinessCoordinator.isVoiceReady(
                    MicrosoftEmbeddedVoiceProvider.PROVIDER_ID,
                    "en-US-JennyNeural"
                )
            )
        }

    @Test
    fun testVoiceReadinessCoordinator_concurrentRequests_coalesce() =
        runBlocking {
            // محاكاة طلبين متزامنين لنفس الصوت
            val j1 = async {
                VoiceReadinessCoordinator.prepareVoice(
                    context,
                    "vocalizer_embedded",
                    "tarek",
                    priority = true
                )
            }
            val j2 = async {
                VoiceReadinessCoordinator.prepareVoice(
                    context,
                    "vocalizer_embedded",
                    "tarek",
                    priority = true
                )
            }
            val res1 = j1.await()
            val res2 = j2.await()
            assertEquals(res1, res2)
        }

    @Test
    fun testSpeechToken_overlappingRequests_olderDoesNotLowerNewerFlag() =
        runBlocking {
            val token1 = VoiceReadinessCoordinator.onSpeechRequested(
                engineId = "vocalizer_embedded",
                voiceId = "tarek"
            )
            val token2 = VoiceReadinessCoordinator.onSpeechRequested(
                engineId = "vocalizer_embedded",
                voiceId = "mariam"
            )

            // انتهاء الطلب القديم لا يخفض علم الطلب الأحدث
            VoiceReadinessCoordinator.onSpeechCompleted(token1)

            val attemptDuringToken2 = VoiceReadinessCoordinator.prepareVoice(
                context = context,
                engineId = "microsoft_embedded",
                voiceId = "salma",
                priority = false
            )
            assertFalse(
                "طلب أحدث ما زال نشطاً؛ يجب رفض الإحماء غير ذي الأولوية",
                attemptDuringToken2
            )

            // انتهاء الطلب الأحدث يخفض العلم
            VoiceReadinessCoordinator.onSpeechCompleted(token2)
        }

    @Test
    fun testHostWarmStatusRegistry_validationLifecycle_and_invalidation() {
        val engine = "vocalizer_embedded"
        val voice = "tariq"
        val rev1 = 500L
        val gen1 = 1L

        // تسجيل صوت دافئ مع نجاح PCM
        HostWarmStatusRegistry.recordWarmVoice(
            context = context,
            engineId = engine,
            actualVoiceId = voice,
            dataRevision = rev1,
            hostGeneration = gen1,
            pcmSuccess = true
        )

        // التحقق من صحة الحالة
        assertTrue(
            HostWarmStatusRegistry.isVoiceWarm(
                context = context,
                engineId = engine,
                voiceId = voice,
                expectedRevision = rev1,
                expectedHostGen = gen1
            )
        )

        // عدم تطابق جيل المضيف (موت/إعادة تشغيل المضيف)
        assertFalse(
            HostWarmStatusRegistry.isVoiceWarm(
                context = context,
                engineId = engine,
                voiceId = voice,
                expectedRevision = rev1,
                expectedHostGen = 2L
            )
        )

        // عدم تطابق مراجعة التخزين (تغيير/استبدال ملفات)
        assertFalse(
            HostWarmStatusRegistry.isVoiceWarm(
                context = context,
                engineId = engine,
                voiceId = voice,
                expectedRevision = 501L,
                expectedHostGen = gen1
            )
        )

        // عدم تطابق الصوت
        assertFalse(
            HostWarmStatusRegistry.isVoiceWarm(
                context = context,
                engineId = engine,
                voiceId = "other_voice",
                expectedRevision = rev1,
                expectedHostGen = gen1
            )
        )

        // مسح الحالة
        HostWarmStatusRegistry.clearWarmStatus(context, engine)
        assertFalse(
            HostWarmStatusRegistry.isVoiceWarm(
                context = context,
                engineId = engine,
                voiceId = voice,
                expectedRevision = rev1,
                expectedHostGen = gen1
            )
        )
    }

    @Test
    fun testProbeDetails_resolvesLanguageCorrectlyWithoutSubstringGuessing() {
        // فحص لغة عربية
        val (arText, arLocale) =
            VoiceReadinessCoordinator.resolveProbeDetails(
                context,
                "acapela_embedded",
                "salma22k"
            )
        assertEquals("مرحبا", arText)
        assertEquals("ar", arLocale.language)

        // فحص لغة إنجليزية
        val (enText, enLocale) =
            VoiceReadinessCoordinator.resolveProbeDetails(
                context,
                "microsoft_embedded",
                "en-US-JennyNeural"
            )
        assertEquals("Hello", enText)
        assertEquals("en", enLocale.language)
    }

    @Test
    fun testVocalizerAndMicrosoftRepositories_crossProcessRevisionMarker() {
        val vocBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        val msBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.MICROSOFT
        )
        vocBase.mkdirs()
        msBase.mkdirs()

        val vocRev1 = VocalizerVoiceRepository.getStorageRevision(context)
        VocalizerVoiceRepository.invalidate(context)
        val vocRev2 = VocalizerVoiceRepository.getStorageRevision(context)
        assertTrue(
            "تحديث علامة مراجعة Vocalizer يجب أن يرفع القيمة",
            vocRev2 > vocRev1
        )

        val msRev1 = MicrosoftVoiceRepository.getStorageRevision(context)
        MicrosoftVoiceRepository.invalidate(context)
        val msRev2 = MicrosoftVoiceRepository.getStorageRevision(context)
        assertTrue(
            "تحديث علامة مراجعة Microsoft يجب أن يرفع القيمة",
            msRev2 > msRev1
        )
    }

    @Test
    fun testHostSessions_switchingVoices_updatesWarmState() {
        MicrosoftHostSession.noteWarmVoiceLoaded("ar-EG-SalmaNeural")
        assertTrue(
            MicrosoftHostSession.isWarmReady("SalmaNeural")
        )
        // تبديل إلى شاكر
        MicrosoftHostSession.noteWarmVoiceLoaded("ar-SA-ShakirNeural")
        assertTrue(
            MicrosoftHostSession.isWarmReady("ShakirNeural")
        )
        assertFalse(
            MicrosoftHostSession.isWarmReady("SalmaNeural")
        )

        VocalizerHostSession.noteWarmVoiceLoaded("tarik")
        assertTrue(
            VocalizerHostSession.isWarmVoiceLoaded("tarik")
        )
        // تبديل إلى مريم
        VocalizerHostSession.noteWarmVoiceLoaded("mariam")
        assertTrue(
            VocalizerHostSession.isWarmVoiceLoaded("mariam")
        )
        assertFalse(
            VocalizerHostSession.isWarmVoiceLoaded("tarik")
        )
    }

    @Test
    fun testUnifiedSpeechTracker_tenWarmUtterancesPerEngine() {
        val engines = listOf(
            UnifiedSpeechTracker.Engine.ACAPELA,
            UnifiedSpeechTracker.Engine.VOCALIZER,
            UnifiedSpeechTracker.Engine.MICROSOFT
        )

        for (eng in engines) {
            for (i in 1..10) {
                val req = UnifiedSpeechTracker.startRequest(
                    engine = eng,
                    category = UnifiedSpeechTracker.Category.WARM,
                    voiceName = "warm_voice",
                    textLength = 15
                )
                req.markSpeakCallStart()
                req.markFirstPcm()
                req.markSpeakCallEnd()
                req.complete(UnifiedSpeechTracker.Status.SUCCESS)
            }
            val stats = UnifiedSpeechTracker.getStats(
                engine = eng,
                category = UnifiedSpeechTracker.Category.WARM
            )
            assertEquals(10, stats.totalCount)
            assertEquals(10, stats.successCount)
            assertEquals(0, stats.failedCount)
            assertEquals(0, stats.unmeasuredCount)
            assertTrue(stats.avgFirstPcmMs >= 0.0)
            assertTrue(stats.p50FirstPcmMs <= stats.p95FirstPcmMs)
        }
    }

    @Test
    fun testUnifiedSpeechTracker_unmeasuredMinusOne_doesNotSkewAverages() {
        UnifiedSpeechTracker.clearForTest()

        // عبارتان فقط بقياس حقيقي (500ms و 700ms)
        val r1 = UnifiedSpeechTracker.startRequest(
            engine = UnifiedSpeechTracker.Engine.ACAPELA,
            category = UnifiedSpeechTracker.Category.WARM,
            voiceName = "salma",
            textLength = 20
        )
        r1.firstPcmMs = 500L
        r1.complete(UnifiedSpeechTracker.Status.SUCCESS)

        val r2 = UnifiedSpeechTracker.startRequest(
            engine = UnifiedSpeechTracker.Engine.ACAPELA,
            category = UnifiedSpeechTracker.Category.WARM,
            voiceName = "salma",
            textLength = 20
        )
        r2.firstPcmMs = 700L
        r2.complete(UnifiedSpeechTracker.Status.SUCCESS)

        // 27 عبارة بلا قياس أول بايت (-1L)
        for (i in 1..27) {
            val unmeasuredReq = UnifiedSpeechTracker.startRequest(
                engine = UnifiedSpeechTracker.Engine.ACAPELA,
                category = UnifiedSpeechTracker.Category.WARM,
                voiceName = "salma",
                textLength = 15
            )
            unmeasuredReq.complete(
                UnifiedSpeechTracker.Status.FAILED,
                "No PCM"
            )
        }

        val stats = UnifiedSpeechTracker.getStats(
            engine = UnifiedSpeechTracker.Engine.ACAPELA,
            category = UnifiedSpeechTracker.Category.WARM
        )
        assertEquals(29, stats.totalCount)
        assertEquals(2, stats.pcmMeasuredCount)
        assertEquals(27, stats.pcmUnmeasuredCount)

        // يجب أن يكون المتوسط 600ms (500+700)/2 وليس 41ms (1200/29)
        assertEquals(600.0, stats.avgFirstPcmMs, 0.01)
        assertEquals(600.0, stats.p50FirstPcmMs, 0.01)

        // المرحلة غير المقيسة إطلاقاً تعيد -1.0 وتظهر "غير مقاس"
        assertEquals(-1.0, stats.avgFirstTrackWriteMs, 0.01)
        assertEquals("غير مقاس", stats.formatMetric(stats.avgFirstTrackWriteMs))
    }

    @Test
    fun testUnifiedSpeechTracker_completeIsIdempotentInFinally() {
        UnifiedSpeechTracker.clearForTest()
        val req = UnifiedSpeechTracker.startRequest(
            engine = UnifiedSpeechTracker.Engine.VOCALIZER,
            category = UnifiedSpeechTracker.Category.WARM,
            voiceName = "tarek",
            textLength = 10
        )
        req.firstPcmMs = 250L

        // استدعاء أول في try
        val rec1 = req.complete(UnifiedSpeechTracker.Status.SUCCESS)
        assertNotNull(rec1)

        // استدعاء ثانٍ في finally
        val rec2 = req.complete(
            UnifiedSpeechTracker.Status.CANCELLED,
            "redundant call"
        )
        assertNull("الاستدعاء الثاني يجب ألا يكرر التسجيل", rec2)

        val all = UnifiedSpeechTracker.getAllRecords()
        assertEquals("طلب واحد فقط يجب أن يسجل في السجل", 1, all.size)
        assertEquals(UnifiedSpeechTracker.Status.SUCCESS, all[0].status)
    }

    @Test
    fun testHostWarmStatusRegistry_identifiesWarmAndColdProperly() {
        val filesDir = context.filesDir
        val engine = "acapela"
        val voice = "salma"
        val rev = 100L

        // مبدئياً غير دافئ
        assertFalse(
            HostWarmStatusRegistry.isVoiceWarm(
                context = context,
                engineId = engine,
                voiceId = voice,
                expectedRevision = rev
            )
        )

        // تسجيل دافئ
        HostWarmStatusRegistry.recordWarmVoice(
            context = context,
            engineId = engine,
            actualVoiceId = voice,
            dataRevision = rev,
            hostGeneration = 1L,
            pcmSuccess = true
        )

        assertTrue(
            HostWarmStatusRegistry.isVoiceWarm(
                context = context,
                engineId = engine,
                voiceId = voice,
                expectedRevision = rev
            )
        )

        // مراجعة بيانات مختلفة -> يعود بارداً
        assertFalse(
            HostWarmStatusRegistry.isVoiceWarm(
                context = context,
                engineId = engine,
                voiceId = voice,
                expectedRevision = rev + 1L
            )
        )

        // صوت مختلف -> يعود بارداً
        assertFalse(
            HostWarmStatusRegistry.isVoiceWarm(
                context = context,
                engineId = engine,
                voiceId = "leila",
                expectedRevision = rev
            )
        )
    }
}
