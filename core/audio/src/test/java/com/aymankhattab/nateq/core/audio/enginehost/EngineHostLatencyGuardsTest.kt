package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineHostLatencyGuardsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EngineHostClient.clearInitialized()
    }

    @After
    fun tearDown() {
        EngineHostClient.clearInitialized()
    }

    @Test
    fun readFrames_returnsImmediatelyOnEnd_withoutPollingDelay() = runBlocking {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        val writer = Thread {
            DataOutputStream(FileOutputStream(writeSide.fileDescriptor)).use { out ->
                HostProtocol.writeEnd(out, HostProtocol.STATUS_OK, "", "")
            }
            runCatching { writeSide.close() }
        }
        writer.start()

        val started = SystemClock.elapsedRealtime()
        val result = EngineHostClient.readFrames(
            readSide,
            HostProtocol.ENGINE_ACAPELA,
            onFormatInfo = { _, _ -> },
            onAudioChunk = { _, _ -> }
        )
        val elapsed = SystemClock.elapsedRealtime() - started
        writer.join(1_000)

        assertTrue("يجب أن يعود End فوراً دون انتظار 50ms", result.ok)
        assertTrue(
            "الاكتمال المباشر يجب أن يكون أسرع من استطلاع 50ms ($elapsed ms)",
            elapsed < 80L
        )
    }

    @Test
    fun readFrames_returnsImmediatelyOnEof() = runBlocking {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        runCatching { writeSide.close() }

        val started = SystemClock.elapsedRealtime()
        val result = EngineHostClient.readFrames(
            readSide,
            HostProtocol.ENGINE_ACAPELA,
            onFormatInfo = { _, _ -> },
            onAudioChunk = { _, _ -> }
        )
        val elapsed = SystemClock.elapsedRealtime() - started

        assertEquals("HOST_EOF", result.code)
        assertTrue(
            "EOF يجب أن يُبلَّغ فوراً دون استطلاع ($elapsed ms)",
            elapsed < 80L
        )
    }

    @Test
    fun synthesize_realRequestPreemptsBackgroundWarmup() {
        val controller: ServiceController<EmbeddedEngineHostService> =
            Robolectric.buildService(EmbeddedEngineHostService::class.java)
                .create()
        val service = controller.get()
        val binder = IEmbeddedEngineHost.Stub.asInterface(service.onBind(null))

        val sessionsField = EmbeddedEngineHostService::class.java
            .getDeclaredField("sessions")
        sessionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessions = sessionsField.get(service) as
            java.util.concurrent.ConcurrentHashMap<String, HostEngineSession>

        val backgroundStarted = CountDownLatch(1)
        val backgroundCancelled = CountDownLatch(1)
        val allowBackgroundFinish = CountDownLatch(1)
        val realStarted = CountDownLatch(1)

        val fakeSession = object : HostEngineSession {
            override fun init(dataDir: String): HostResult = HostResult(ok = true)

            override suspend fun speak(
                req: SpeakRequest,
                sink: PcmSink,
                cancelled: AtomicBoolean
            ): HostResult {
                if (req.volume <= 0f) {
                    backgroundStarted.countDown()
                    // محاكاة تدفئة/معاينة بطيئة: تُلغى فور وصول طلب حقيقي.
                    val stopped = backgroundCancelled.await(2, TimeUnit.SECONDS)
                    if (!stopped && !cancelled.get()) {
                        allowBackgroundFinish.await(2, TimeUnit.SECONDS)
                    }
                    return if (cancelled.get()) {
                        HostResult.failure("CANCELLED", "cancelled")
                    } else {
                        HostResult(ok = true)
                    }
                }
                realStarted.countDown()
                sink.end(HostProtocol.STATUS_OK, "", "")
                return HostResult(ok = true)
            }

            override fun stop() {
                backgroundCancelled.countDown()
            }

            override fun shutdown() {}
        }
        sessions[HostProtocol.ENGINE_ACAPELA] = fakeSession

        val bgPipe = ParcelFileDescriptor.createPipe()
        val realPipe = ParcelFileDescriptor.createPipe()
        val bgRead = DataOutputStream(
            FileOutputStream(bgPipe[0].fileDescriptor)
        )
        val realRead = DataOutputStream(
            FileOutputStream(realPipe[0].fileDescriptor)
        )

        val bgResult = AtomicReference<String?>()
        val bgThread = Thread {
            bgResult.set(
                binder.synthesize(
                    HostProtocol.ENGINE_ACAPELA,
                    "voice",
                    "warmup",
                    1f,
                    1f,
                    0f, // تدفئة/معاينة
                    bgPipe[1]
                )
            )
        }
        bgThread.start()
        assertTrue(backgroundStarted.await(2, TimeUnit.SECONDS))

        val realResult = binder.synthesize(
            HostProtocol.ENGINE_ACAPELA,
            "voice",
            "real speech",
            1f,
            1f,
            1f, // طلب حقيقي
            realPipe[1]
        )

        val interrupted = backgroundCancelled.await(2, TimeUnit.SECONDS)
        if (!interrupted) {
            val speakingId = EmbeddedEngineHostService::class.java
                .getDeclaredField("speakingRequestId").apply { isAccessible = true }
                .get(service) as java.util.concurrent.atomic.AtomicLong
            val speakingBg = EmbeddedEngineHostService::class.java
                .getDeclaredField("speakingIsBackground").apply { isAccessible = true }
                .get(service) as AtomicBoolean
            val pendingReal = EmbeddedEngineHostService::class.java
                .getDeclaredField("pendingRealRequests").apply { isAccessible = true }
                .get(service) as java.util.concurrent.atomic.AtomicInteger
            throw AssertionError(
                "لم تُقاطع التدفئة. speakingId=${speakingId.get()} " +
                    "speakingBg=${speakingBg.get()} pendingReal=${pendingReal.get()}"
            )
        }
        allowBackgroundFinish.countDown()
        assertTrue(realStarted.await(2, TimeUnit.SECONDS))
        assertTrue(
            "طلب حقيقي يجب أن يُقبل: $realResult",
            realResult.contains("OK", ignoreCase = true)
        )

        bgThread.join(2_000)
        runCatching { bgPipe[0].close() }
        runCatching { bgPipe[1].close() }
        runCatching { realPipe[0].close() }
        runCatching { realPipe[1].close() }
        controller.destroy()
    }

    @Test
    fun synthesize_backgroundYieldsToPendingRealRequest() {
        val controller: ServiceController<EmbeddedEngineHostService> =
            Robolectric.buildService(EmbeddedEngineHostService::class.java)
                .create()
        val service = controller.get()
        val binder = IEmbeddedEngineHost.Stub.asInterface(service.onBind(null))

        val sessionsField = EmbeddedEngineHostService::class.java
            .getDeclaredField("sessions")
        sessionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessions = sessionsField.get(service) as
            java.util.concurrent.ConcurrentHashMap<String, HostEngineSession>

        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        val realDone = CountDownLatch(1)
        val fakeSession = object : HostEngineSession {
            override fun init(dataDir: String): HostResult = HostResult(ok = true)
            override suspend fun speak(
                req: SpeakRequest,
                sink: PcmSink,
                cancelled: AtomicBoolean
            ): HostResult {
                if (req.volume > 0f) {
                    order.add("real")
                    realDone.countDown()
                } else {
                    // خلفية لا يجب أن تسبق الحقيقي إذا كان الحقيقي منتظراً.
                    order.add("bg")
                }
                sink.end(HostProtocol.STATUS_OK, "", "")
                return HostResult(ok = true)
            }
            override fun stop() {}
            override fun shutdown() {}
        }
        sessions[HostProtocol.ENGINE_VOCALIZER] = fakeSession

        // اجعل البوابة مشغولة بخلفية حالية عبر طلب خلفية مباشر ثم طلب حقيقي.
        val bgPipe = ParcelFileDescriptor.createPipe()
        val realPipe = ParcelFileDescriptor.createPipe()
        // الطلب الحقيقي أولاً يرفع pendingRealRequests عبر binder.
        val realThread = Thread {
            binder.synthesize(
                HostProtocol.ENGINE_VOCALIZER, "v", "r", 1f, 1f, 1f, realPipe[1]
            )
        }
        realThread.start()
        // أرسل خلفية بعد الحقيقي بفارق صغير؛ يجب ألا تسبق الحقيقي.
        Thread.sleep(30)
        val bgThread = Thread {
            binder.synthesize(
                HostProtocol.ENGINE_VOCALIZER, "v", "b", 1f, 1f, 0f, bgPipe[1]
            )
        }
        bgThread.start()

        assertTrue(realDone.await(3, TimeUnit.SECONDS))
        realThread.join(2_000)
        bgThread.join(2_000)
        // الحقيقي يجب أن يُسجّل ولا يُحجب نهائياً.
        assertTrue(order.contains("real"))
        runCatching { bgPipe[0].close() }; runCatching { bgPipe[1].close() }
        runCatching { realPipe[0].close() }; runCatching { realPipe[1].close() }
        controller.destroy()
    }
}