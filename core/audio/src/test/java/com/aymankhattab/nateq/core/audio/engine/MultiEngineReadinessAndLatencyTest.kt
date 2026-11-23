package com.aymankhattab.nateq.core.audio.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.enginehost.MicrosoftHostSession
import com.aymankhattab.nateq.core.audio.enginehost.PcmSink
import com.aymankhattab.nateq.core.audio.enginehost.SpeakRequest
import com.aymankhattab.nateq.core.audio.enginehost.VocalizerHostSession
import com.aymankhattab.nateq.core.audio.providers.AcapelaEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceRepository
import com.aymankhattab.nateq.core.audio.providers.MicrosoftEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.MicrosoftVoiceRepository
import com.aymankhattab.nateq.core.audio.providers.VocalizerEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.VocalizerVoiceRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.concurrent.atomic.AtomicBoolean

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
}
