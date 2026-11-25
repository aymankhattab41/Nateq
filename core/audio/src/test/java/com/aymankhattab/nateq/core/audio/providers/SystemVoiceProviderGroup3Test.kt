package com.aymankhattab.nateq.core.audio.providers

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.os.Looper
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * اختبارات مخصصة لإصلاحات المجموعة 3:
 * 7) أقفال المحرك القابلة للمقاطعة وسرعة تحرير القفل عند الإلغاء.
 * 8) نقل استدعاء built.voices و EngineBindVerifier خارج الخيط الرئيسي.
 * 9) التحقق من الحد الأدنى لحجم مسبح synthExecutor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SystemVoiceProviderGroup3Test {

    companion object {
        private const val TEST_ENGINE = "com.test.group3.engine"
        private const val TTS_ACTION = "android.intent.action.TTS_SERVICE"
    }

    private fun registerEngine(pkg: String) {
        val app = RuntimeEnvironment.getApplication()
        val component = ComponentName(pkg, "com.test.TtsService")
        val shadowPm = shadowOf(app.packageManager)
        shadowPm.addServiceIfNotPresent(component)
        shadowPm.addIntentFilterForService(
            component,
            IntentFilter(TTS_ACTION)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun enginePoolOf(
        provider: SystemVoiceProvider
    ): ConcurrentHashMap<String, TextToSpeech> {
        val field =
            SystemVoiceProvider::class.java.getDeclaredField("enginePool")
        field.isAccessible = true
        return field.get(provider) as ConcurrentHashMap<String, TextToSpeech>
    }

    private fun voiceDesc() = VoiceDescriptor(
        id = "nateq-test-voice",
        providerId = SystemVoiceProvider.SYSTEM_PROVIDER_ID,
        displayName = "Arabic",
        locale = Locale.forLanguageTag("ar")
    )

    @Test
    fun synthPoolSizing_minimumThreadsWithAmpleMemory() {
        // التحقق من أن حجم المسبح لا يقل عن 2 عند توفر ذاكرة كافية
        // حتى لو كان هناك محرك واحد مثبت
        val poolSizeSingle = resolvedPoolSize(
            1,
            4L * 1024 * 1024 * 1024
        )
        assertEquals(2, poolSizeSingle)

        val poolSizeZero = resolvedPoolSize(
            0,
            4L * 1024 * 1024 * 1024
        )
        assertEquals(2, poolSizeZero)

        // وعلى الأجهزة قليلة الذاكرة يظل 1
        val poolSizeLowMem = resolvedPoolSize(
            1,
            2L * 1024 * 1024 * 1024
        )
        assertEquals(1, poolSizeLowMem)
    }

    @Test
    fun onInitVoices_executedOffMainThread() {
        registerEngine(TEST_ENGINE)
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("ar"))
        EnginePicker.invalidateCache()

        val context = RuntimeEnvironment.getApplication()
        val provider = SystemVoiceProvider(context)

        // تتبع الخيط الذي استُدعي عليه getVoices
        val voicesInvokedOnMainThread = AtomicBoolean(false)
        val voicesInvoked = AtomicBoolean(false)

        val tts = object : TextToSpeech(context, {}, TEST_ENGINE) {
            override fun getVoices(): Set<android.speech.tts.Voice> {
                val isMain = Looper.myLooper() == Looper.getMainLooper()
                if (isMain) {
                    voicesInvokedOnMainThread.set(true)
                }
                voicesInvoked.set(true)
                return super.getVoices().orEmpty()
            }
        }

        val pool = enginePoolOf(provider)
        pool[TEST_ENGINE] = tts

        runBlocking {
            val job = async(Dispatchers.Default) {
                provider.synthesize(
                    text = "مرحبا بكم",
                    voice = voiceDesc(),
                    speechRate = 1.0f,
                    pitch = 1.0f,
                    volume = 1.0f,
                    onFormatInfo = { _, _ -> },
                    onAudioChunk = { _, _ -> },
                    enginePackage = TEST_ENGINE
                )
            }
            val deadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < deadline) {
                val file = runCatching {
                    shadowOf(tts).getLastSynthesizeToFile()
                }.getOrNull()
                if (file != null && file.exists() && file.length() > 44L) {
                    break
                }
                Thread.sleep(10)
            }
            shadowOf(tts).getUtteranceProgressListener()?.onDone("test")
            job.await()
        }

        assertFalse(
            "built.voices must not be called on the main thread",
            voicesInvokedOnMainThread.get()
        )
    }

    @Test
    fun cancelledSession_exitsFastWithoutBlockingNextRequest() {
        registerEngine(TEST_ENGINE)
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("ar"))
        EnginePicker.invalidateCache()

        val context = RuntimeEnvironment.getApplication()
        val provider = SystemVoiceProvider(context)

        val stopCalled = AtomicInteger(0)
        val tts = object : TextToSpeech(context, {}, TEST_ENGINE) {
            override fun stop(): Int {
                stopCalled.incrementAndGet()
                return super.stop()
            }
        }

        val pool = enginePoolOf(provider)
        pool[TEST_ENGINE] = tts

        runBlocking {
            val deadline = System.currentTimeMillis() + 5000L
            // نبدأ جلسة أولى
            val job1 = async(Dispatchers.Default) {
                provider.synthesize(
                    text = "نص طويل للجلسة الأولى الملغاة",
                    voice = voiceDesc(),
                    speechRate = 1.0f,
                    pitch = 1.0f,
                    volume = 1.0f,
                    onFormatInfo = { _, _ -> },
                    onAudioChunk = { _, _ -> },
                    enginePackage = TEST_ENGINE
                )
            }
            while (System.currentTimeMillis() < deadline) {
                val file = runCatching {
                    shadowOf(tts).getLastSynthesizeToFile()
                }.getOrNull()
                if (file != null && file.exists() && file.length() > 44L) {
                    break
                }
                Thread.sleep(10)
            }
            // نلغي الجلسة الأولى
            job1.cancel()
            runCatching { job1.await() }

            // التحقق من استدعاء stop فور الإلغاء
            val stopDeadline = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < stopDeadline
                && stopCalled.get() < 1
            ) {
                Thread.sleep(10)
            }
            assertTrue(
                "stop must be invoked upon cancellation",
                stopCalled.get() >= 1
            )

            // الجلسة التالية لنفس المحرك يجب أن تنفذ وتكتمل بنجاح دون حجز القفل
            val job2 = async(Dispatchers.Default) {
                provider.synthesize(
                    text = "نص الجلسة الثانية",
                    voice = voiceDesc(),
                    speechRate = 1.0f,
                    pitch = 1.0f,
                    volume = 1.0f,
                    onFormatInfo = { _, _ -> },
                    onAudioChunk = { _, _ -> },
                    enginePackage = TEST_ENGINE
                )
                true
            }
            val secondDeadline = System.currentTimeMillis() + 5000L
            while (System.currentTimeMillis() < secondDeadline) {
                val file2 = runCatching {
                    shadowOf(tts).getLastSynthesizeToFile()
                }.getOrNull()
                if (file2 != null && file2.exists() && file2.length() > 44L) {
                    break
                }
                Thread.sleep(10)
            }
            shadowOf(tts).getUtteranceProgressListener()?.onDone("second")
            val secondCompleted = job2.await()

            assertTrue(
                "Second request must complete cleanly after first is cancelled",
                secondCompleted
            )
        }
    }
}
