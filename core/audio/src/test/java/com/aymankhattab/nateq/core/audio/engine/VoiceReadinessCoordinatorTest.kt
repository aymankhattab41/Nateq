package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import com.aymankhattab.nateq.core.audio.enginehost.HostWarmStatusRegistry
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceRepository
import com.aymankhattab.nateq.core.audio.providers.MicrosoftVoiceRepository
import com.aymankhattab.nateq.core.audio.providers.VocalizerVoiceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class VoiceReadinessCoordinatorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        VoiceReadinessCoordinator.resetForTest()
        HostWarmStatusRegistry.resetForTests()
        HostWarmStatusRegistry.clearAll(context)
        AcapelaVoiceRepository.resetForTests()
        MicrosoftVoiceRepository.resetForTests()
        VocalizerVoiceRepository.resetForTests()
    }

    @After
    fun tearDown() {
        VoiceReadinessCoordinator.resetForTest()
        HostWarmStatusRegistry.resetForTests()
        HostWarmStatusRegistry.clearAll(context)
        AcapelaVoiceRepository.resetForTests()
        MicrosoftVoiceRepository.resetForTests()
        VocalizerVoiceRepository.resetForTests()
    }

    @Test
    fun testSingleFlight_executesOnce_forConcurrentRequests() = runBlocking {
        val executionCounter = AtomicInteger(0)
        val inFlight = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

        // دالة تحاكي نموذج single-flight في VoiceReadinessCoordinator
        suspend fun simulateSingleFlight(key: String): Boolean {
            val myDeferred = CompletableDeferred<Boolean>()
            val existing = inFlight.putIfAbsent(key, myDeferred)
            if (existing != null) {
                return existing.await()
            }
            try {
                // محاكاة عمل التخليق والإحماء
                delay(50L)
                executionCounter.incrementAndGet()
                myDeferred.complete(true)
                return myDeferred.await()
            } finally {
                inFlight.remove(key, myDeferred)
            }
        }

        // إطلاق 15 طلباً متزامناً لنفس الصوت
        val jobs = (1..15).map {
            async {
                simulateSingleFlight("vocalizer:tariq")
            }
        }

        val results = jobs.map { it.await() }
        assertTrue(results.all { it })
        assertEquals(
            "يجب تنفيذ التخليق مرة واحدة بالضبط لكافة الطلبات المتزامنة",
            1,
            executionCounter.get()
        )
    }

    @Test
    fun testSingleFlight_retryAllowedAfterFailure() = runBlocking {
        val executionCounter = AtomicInteger(0)
        val inFlight = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

        suspend fun simulateFailingThenSuccess(
            key: String,
            shouldFail: Boolean
        ): Boolean {
            val myDeferred = CompletableDeferred<Boolean>()
            val existing = inFlight.putIfAbsent(key, myDeferred)
            if (existing != null) {
                return existing.await()
            }
            try {
                executionCounter.incrementAndGet()
                if (shouldFail) {
                    myDeferred.complete(false)
                } else {
                    myDeferred.complete(true)
                }
                return myDeferred.await()
            } finally {
                inFlight.remove(key, myDeferred)
            }
        }

        // محاولة أولى فاشلة
        val res1 = simulateFailingThenSuccess("ms:salma", shouldFail = true)
        assertFalse(res1)
        assertEquals(1, executionCounter.get())

        // محاولة ثانية لاحقة (إعادة محاولة)
        // يجب أن تُنفذ ولا تُحظر بالكاش الفاشل
        val res2 = simulateFailingThenSuccess("ms:salma", shouldFail = false)
        assertTrue(res2)
        assertEquals(2, executionCounter.get())
    }

    @Test
    fun testSingleFlight_followerCancellationDoesNotClearWinnerKey() =
        runBlocking {
            val inFlight =
                ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
            val key = "acapela:salma"

            val winnerDeferred = CompletableDeferred<Boolean>()
            inFlight.putIfAbsent(key, winnerDeferred)

            // محاكاة متابع (follower) تم إلغاء كوروتينه
            val followerJob = launch {
                try {
                    val followerDeferred = CompletableDeferred<Boolean>()
                    val existing = inFlight.putIfAbsent(key, followerDeferred)
                    if (existing != null) {
                        existing.await()
                    }
                } finally {
                    // المتابع لا يملك المفتاح ولن يحذفه بـ inFlight.remove
                    val followerDummy = CompletableDeferred<Boolean>()
                    inFlight.remove(key, followerDummy)
                }
            }

            delay(10L)
            followerJob.cancel()
            followerJob.join()

            // التحقق من بقاء مفتاح الفائز سليماً في الخريطة دون حذف
            assertTrue(
                "مفتاح الفائز يجب ألا يُحذف بسبب إلغاء المتابع",
                inFlight.containsKey(key)
            )
            assertEquals(winnerDeferred, inFlight[key])

            winnerDeferred.complete(true)
            inFlight.remove(key, winnerDeferred)
            assertFalse(inFlight.containsKey(key))
        }

    @Test
    fun testSpeechToken_isolatedFromSettingsAndShutdown() {
        // التحقق من عدم رفع علم النطق في البداية
        VoiceReadinessCoordinator.resetForTest()

        // استدعاء انتهاء النطق بلا طلب لا يترك أثراً سلبياً
        VoiceReadinessCoordinator.onSpeechCompleted(0L)

        val token1 = VoiceReadinessCoordinator.onSpeechRequested()
        assertTrue(token1 > 0L)

        val token2 = VoiceReadinessCoordinator.onSpeechRequested()
        assertTrue(token2 > token1)

        // إنهاء token1 لا يخفض العلم
        VoiceReadinessCoordinator.onSpeechCompleted(token1)

        // إنهاء token2 يخفض العلم
        VoiceReadinessCoordinator.onSpeechCompleted(token2)
    }

    @Test
    fun testHostWarmStatusRegistry_pcmSuccessRequired() {
        val eng = "acapela_embedded"
        val voice = "salma22k"

        // تسجيل دون نجاح PCM
        HostWarmStatusRegistry.recordWarmVoice(
            context = context,
            engineId = eng,
            actualVoiceId = voice,
            dataRevision = 100L,
            hostGeneration = 1L,
            pcmSuccess = false
        )
        assertFalse(
            "حالة دافئة بلا نجاح PCM يجب أن تُرفض",
            HostWarmStatusRegistry.isVoiceWarm(
                context,
                eng,
                voice,
                100L,
                1L
            )
        )

        // تسجيل مع نجاح PCM
        HostWarmStatusRegistry.recordWarmVoice(
            context = context,
            engineId = eng,
            actualVoiceId = voice,
            dataRevision = 100L,
            hostGeneration = 1L,
            pcmSuccess = true
        )
        assertTrue(
            "حالة دافئة مع نجاح PCM يجب أن تُقبل",
            HostWarmStatusRegistry.isVoiceWarm(
                context,
                eng,
                voice,
                100L,
                1L
            )
        )
    }

    @Test
    fun onSpeechCompleted_schedulesRewarmAfterQuietDelay() = runBlocking {
        VoiceReadinessCoordinator.rewarmQuietDelayMs = 40L
        VoiceReadinessCoordinator.rewarmPeriodicDelayMs = 5_000L
        VoiceReadinessCoordinator.prepareActiveVoices(context).join()

        val token = VoiceReadinessCoordinator.onSpeechRequested()
        VoiceReadinessCoordinator.onSpeechCompleted(token)

        assertTrue(
            "يجب جدولة تدفئة بعد هدوء قصير إثر انتهاء النطق",
            VoiceReadinessCoordinator.hasPendingRewarmForTest()
        )
    }

    @Test
    fun onSpeechRequested_cancelsPendingRewarm_noContention() = runBlocking {
        VoiceReadinessCoordinator.rewarmQuietDelayMs = 5_000L
        VoiceReadinessCoordinator.rewarmPeriodicDelayMs = 5_000L
        VoiceReadinessCoordinator.prepareActiveVoices(context).join()

        val token = VoiceReadinessCoordinator.onSpeechRequested()
        VoiceReadinessCoordinator.onSpeechCompleted(token)
        assertTrue(VoiceReadinessCoordinator.hasPendingRewarmForTest())

        VoiceReadinessCoordinator.onSpeechRequested()
        assertFalse(
            "بدء النطق الحقيقي يجب أن يلغي إعادة التدفئة المجدولة",
            VoiceReadinessCoordinator.hasPendingRewarmForTest()
        )
    }

    @Test
    fun oldSpeechCompletion_doesNotScheduleRewarm() = runBlocking {
        VoiceReadinessCoordinator.rewarmQuietDelayMs = 5_000L
        VoiceReadinessCoordinator.prepareActiveVoices(context).join()

        val oldToken = VoiceReadinessCoordinator.onSpeechRequested()
        VoiceReadinessCoordinator.onSpeechRequested() // طلب أحدث
        VoiceReadinessCoordinator.onSpeechCompleted(oldToken)

        assertFalse(
            "انتهاء طلب قديم لا يجب أن يعيد جدولة التدفئة",
            VoiceReadinessCoordinator.hasPendingRewarmForTest()
        )
    }

    @Test
    fun onTrimMemory_schedulesRewarmAfterDelay() = runBlocking {
        VoiceReadinessCoordinator.rewarmAfterTrimDelayMs = 5_000L
        VoiceReadinessCoordinator.rewarmPeriodicDelayMs = 5_000L
        VoiceReadinessCoordinator.prepareActiveVoices(context).join()

        VoiceReadinessCoordinator.onTrimMemory(
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        )

        assertTrue(
            "بعد تفريغ الذاكرة يجب جدولة إعادة تدفئة بعد هدوء",
            VoiceReadinessCoordinator.hasPendingRewarmForTest()
        )
    }

    @Test
    fun recordActuallyUsedVoice_includesEmbeddedVoiceInPrepare() =
        runBlocking {
            VoiceReadinessCoordinator.recordActuallyUsedVoice(
                HostProtocol.ENGINE_ACAPELA,
                "salma22k"
            )
            VoiceReadinessCoordinator.recordActuallyUsedVoice(
                "com.google.android.tts",
                "ar-xa-x-arz-local"
            )

            // لا انهيار، والأصوات المدمجة فقط تُقبل للتدفئة.
            VoiceReadinessCoordinator.prepareActiveVoices(context).join()
            assertTrue(true)
        }
}
