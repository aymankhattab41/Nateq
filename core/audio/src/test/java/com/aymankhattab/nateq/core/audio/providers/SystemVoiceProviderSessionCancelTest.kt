package com.aymankhattab.nateq.core.audio.providers

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * بند ٩ — إلغاء الجلسة الواحدة: علم الإلغاء ومثيل المحرك خاصّان بكل
 * استدعاء synthesize على حدة. كان إلغاء مقطعٍ يوقف كل محركات المسبح
 * فيُسمع الصوت المتبقي مبتوراً. نُثبت هنا أن إلغاء جلسةٍ يوقف محركها
 * وحده، وأن الجلسة الأخرى تُكمل نطقها وتُصدر صوتها كاملاً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SystemVoiceProviderSessionCancelTest {

    companion object {
        private const val ENGINE_A = "com.fake.engineA"
        private const val ENGINE_B = "com.fake.engineB"
        private const val TTS_ACTION = "android.intent.action.TTS_SERVICE"
    }

    /** محرك وهمي يحصي stop — الدليل القاطع على أي جلسة أُلغيت. */
    private class CountingStopEngine(
        context: Context,
        listener: TextToSpeech.OnInitListener,
        engine: String
    ) : TextToSpeech(context, listener, engine) {
        val stopCalls = AtomicInteger(0)

        override fun stop(): Int {
            stopCalls.incrementAndGet()
            return super.stop()
        }
    }

    private class Rig(
        val provider: SystemVoiceProvider,
        val ttsA: CountingStopEngine,
        val ttsB: CountingStopEngine
    )

    private val textAr = "ب".repeat(200)
    private val textEn = "A".repeat(200)

    private fun registerEngine(pkg: String) {
        val app = RuntimeEnvironment.getApplication()
        val component = ComponentName(pkg, "com.fake.TtsService")
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

    /** مزوّد بمحركين وهميين جاهزين في المسبح، ولغتاهما مُعلنتان. */
    private fun newRig(): Rig {
        registerEngine(ENGINE_A)
        registerEngine(ENGINE_B)
        ShadowTextToSpeech.addLanguageAvailability(
            Locale.forLanguageTag("ar")
        )
        ShadowTextToSpeech.addLanguageAvailability(
            Locale.forLanguageTag("en-US")
        )
        EnginePicker.invalidateCache()
        val context = RuntimeEnvironment.getApplication()
        val provider = SystemVoiceProvider(context)
        val ttsA = CountingStopEngine(context, { }, ENGINE_A)
        val ttsB = CountingStopEngine(context, { }, ENGINE_B)
        val pool = enginePoolOf(provider)
        pool[ENGINE_A] = ttsA
        pool[ENGINE_B] = ttsB
        return Rig(provider, ttsA, ttsB)
    }

    private fun voiceA() = VoiceDescriptor(
        id = "nateq-ar-EG",
        providerId = SystemVoiceProvider.SYSTEM_PROVIDER_ID,
        displayName = "Arabic",
        locale = Locale.forLanguageTag("ar-EG")
    )

    private fun voiceB() = VoiceDescriptor(
        id = "nateq-en-US",
        providerId = SystemVoiceProvider.SYSTEM_PROVIDER_ID,
        displayName = "English",
        locale = Locale.forLanguageTag("en-US")
    )

    /** يبدأ جلسة تخليق على خيط خلفي ويجمع شراياها الصوتية. */
    private fun CoroutineScope.startSession(
        provider: SystemVoiceProvider,
        text: String,
        voice: VoiceDescriptor,
        engine: String,
        chunks: MutableList<ByteArray>
    ): Deferred<Unit> = async(Dispatchers.Default) {
        provider.synthesize(
            text = text,
            voice = voice,
            speechRate = 1.0f,
            pitch = 1.0f,
            volume = 1.0f,
            onFormatInfo = { _, _ -> },
            onAudioChunk = { data, len ->
                synchronized(chunks) { chunks.add(data.copyOf(len)) }
            },
            enginePackage = engine
        )
    }

    /** ينتظر حتى يكتب المحرك ملف الجلسة (الظل لا يُرسل onDone تلقائياً). */
    private fun awaitSynthesizedFile(tts: TextToSpeech, deadline: Long) {
        while (System.currentTimeMillis() < deadline) {
            val file = runCatching {
                shadowOf(tts).getLastSynthesizeToFile()
            }.getOrNull()
            if (file != null && file.exists() && file.length() > 44L) {
                return
            }
            Thread.sleep(25)
        }
        throw AssertionError("لم يكتمل تشغيل المحرك خلال المهلة: $tts")
    }

    /** ينتظر رصد استدعاء stop على المحرك المُلغى. */
    private fun awaitStopCall(tts: CountingStopEngine, deadline: Long) {
        while (System.currentTimeMillis() < deadline
            && tts.stopCalls.get() < 1
        ) {
            Thread.sleep(10)
        }
    }

    @Test
    fun cancelOneSession_keepsOtherSessionAlive_andProducesAudio() {
        val rig = newRig()
        val chunksA = ArrayList<ByteArray>()
        val chunksB = ArrayList<ByteArray>()
        val deadline = System.currentTimeMillis() + 10_000L
        runBlocking {
            val a = startSession(
                rig.provider, textAr, voiceA(), ENGINE_A, chunksA
            )
            val b = startSession(
                rig.provider, textEn, voiceB(), ENGINE_B, chunksB
            )

            awaitSynthesizedFile(rig.ttsA, deadline)
            awaitSynthesizedFile(rig.ttsB, deadline)

            // بند ٩: نلغي الجلسة A وحدها — لا تُمسّ الجلسة B.
            a.cancel()
            runCatching { a.await() }
            awaitStopCall(rig.ttsA, deadline)

            // B ما زالت تكتب: نُعلِم محركها بالاكتمال وننتظر صوتها.
            shadowOf(rig.ttsB).getUtteranceProgressListener().onDone("b")
            b.await()

            assertTrue(
                "محرك الجلسة المُلغاة لم يُوقف",
                rig.ttsA.stopCalls.get() >= 1
            )
            assertEquals(
                "إلغاء A أوقف محرك B — عيب الإلغاء الجماعي قبل بند ٩",
                0,
                rig.ttsB.stopCalls.get()
            )
            assertTrue(
                "الجلسة B لم تُكمل بعد إلغاء A",
                b.isCompleted && !b.isCancelled
            )
            assertTrue(
                "الجلسة B لم تُصدر صوتاً بعد إلغاء A",
                chunksB.isNotEmpty()
            )
            assertTrue(
                "مثيل B سُقط من المسبح زائفاً",
                enginePoolOf(rig.provider)[ENGINE_B] === rig.ttsB
            )
        }
    }

    @Test
    fun cancelledSession_stopsItsEngine_andEmitsNoAudio() {
        val rig = newRig()
        val chunks = ArrayList<ByteArray>()
        val deadline = System.currentTimeMillis() + 10_000L
        runBlocking {
            val job = startSession(
                rig.provider, textAr, voiceA(), ENGINE_A, chunks
            )
            awaitSynthesizedFile(rig.ttsA, deadline)

            // الإلغاء بعد بدء الكتابة يضبط علم الجلسة ويوقف مثيلها.
            job.cancel()
            runCatching { job.await() }
            awaitStopCall(rig.ttsA, deadline)

            assertTrue(
                "الجلسة المُلغاة يجب أن تُعد ملغاة",
                job.isCancelled
            )
            assertTrue(
                "محرك الجلسة المُلغاة لم يُوقف عند الإلغاء",
                rig.ttsA.stopCalls.get() >= 1
            )
            assertTrue(
                "الجلسة المُلغاة أخطأت إصدار صوت",
                chunks.isEmpty()
            )
        }
    }
}
