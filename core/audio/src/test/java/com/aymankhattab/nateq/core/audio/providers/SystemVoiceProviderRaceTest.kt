package com.aymankhattab.nateq.core.audio.providers

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.speech.tts.TextToSpeech
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * اختبار تزامن مسار النطق بـ [SystemVoiceProvider] (بند السباقات): مقطعان
 * متوازيان على محركين مختلفين — كل مقطع يجب أن يُخلَّق على مثيل محركه الخاص
 * (يُثبَّت ذلك بلغة كل مثيل في shadow TTS) وأن يكتملا بنجاح دون إسقاط زائف
 * لـ [dropBrokenEngine] (يبقى المثيلان مسبَّحَين صالحَين).
 *
 * السبر أثبت أن shadow Robolectric لا يستدعي onInit ولا onDone تلقائياً، لذا
 * يقود الاختبار إكمالَ الوحدة اللفظية يدوياً عبر
 * [org.robolectric.shadows.ShadowTextToSpeech.getUtteranceProgressListener]
 * بعد التأكد من اكتمال كتابة المحرك لملف الجلسة (الطول > رأس WAV كي يعمل
 * المسار الكامل في [synthesizeInternal]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemVoiceProviderRaceTest {

    companion object {
        private const val ENGINE_A = "com.fake.engineA"
        private const val ENGINE_B = "com.fake.engineB"
        private const val TTS_ACTION = "android.intent.action.TTS_SERVICE"
    }

    private val textEn = "A".repeat(200)
    private val textAr = "ب".repeat(200)

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

    private fun registerEngineAndInvalidateCache() {
        EnginePicker.invalidateCache()
        registerEngine(ENGINE_A)
        registerEngine(ENGINE_B)
    }

    private fun awaitSynthesizedFile(
        tts: TextToSpeech,
        deadlineMs: Long
    ) {
        while (System.currentTimeMillis() < deadlineMs) {
            val file = runCatching {
                shadowOf(tts).getLastSynthesizeToFile()
            }.getOrNull()
            if (file != null && file.exists() && file.length() > 44L) {
                return
            }
            Thread.sleep(25)
        }
        throw AssertionError(
            "لم يكتمل تشغيل المحرك خلال المهلة: $tts"
        )
    }

    @Test
    fun `parallelSegmentsOnTwoEngines_eachOnItsOwnEngine_noFalseDrop`() {
        registerEngineAndInvalidateCache()

        val context = RuntimeEnvironment.getApplication()
        val provider = SystemVoiceProvider(context)

        val ttsA = TextToSpeech(
            context, { }, ENGINE_A
        )
        val ttsB = TextToSpeech(
            context, { }, ENGINE_B
        )
        enginePoolOf(provider)[ENGINE_A] = ttsA
        enginePoolOf(provider)[ENGINE_B] = ttsB

        val voiceAr = VoiceDescriptor(
            id = "nateq-ar-EG",
            providerId = SystemVoiceProvider.SYSTEM_PROVIDER_ID,
            displayName = "Arabic",
            locale = Locale.forLanguageTag("ar-EG")
        )
        val voiceEn = VoiceDescriptor(
            id = "nateq-en-US",
            providerId = SystemVoiceProvider.SYSTEM_PROVIDER_ID,
            displayName = "English",
            locale = Locale.forLanguageTag("en-US")
        )

        val lock = Any()
        val chunksAr = ArrayList<ByteArray>()
        val chunksEn = ArrayList<ByteArray>()

        runBlocking {
            val ar = async(Dispatchers.Default) {
                provider.synthesize(
                    text = textAr,
                    voice = voiceAr,
                    speechRate = 1.0f,
                    pitch = 1.0f,
                    volume = 1.0f,
                    onFormatInfo = { _, _ -> },
                    onAudioChunk = { data, len ->
                        synchronized(lock) {
                            chunksAr.add(data.copyOf(len))
                        }
                    },
                    enginePackage = ENGINE_A
                )
            }
            val en = async(Dispatchers.Default) {
                provider.synthesize(
                    text = textEn,
                    voice = voiceEn,
                    speechRate = 1.0f,
                    pitch = 1.0f,
                    volume = 1.0f,
                    onFormatInfo = { _, _ -> },
                    onAudioChunk = { data, len ->
                        synchronized(lock) {
                            chunksEn.add(data.copyOf(len))
                        }
                    },
                    enginePackage = ENGINE_B
                )
            }

            val deadline = System.currentTimeMillis() + 10_000L
            awaitSynthesizedFile(ttsA, deadline)
            awaitSynthesizedFile(ttsB, deadline)

            // إنهاء الوحدتين اللفظيتين يدوياً (لا onDone تلقائياً في shadow).
            shadowOf(ttsA).getUtteranceProgressListener().onDone("a")
            shadowOf(ttsB).getUtteranceProgressListener().onDone("b")
            ar.await()
            en.await()
        }

        // كل مقطع أنتج صوتاً فعلياً.
        assertTrue("المقطع العربي لم يُنتج صوتاً", chunksAr.isNotEmpty())
        assertTrue("المقطع الإنجليزي لم يُنتج صوتاً", chunksEn.isNotEmpty())

        // كل مقطع خُلِّق على مثيل محركه هو: العربية على A (ar)،
        // وإنكليزية على B (en).
        val langA = shadowOf(ttsA).getCurrentLanguage()?.language
        val langB = shadowOf(ttsB).getCurrentLanguage()?.language
        assertEquals("ar", langA)
        assertEquals("en", langB)

        // لا إسقاط زائف: المثيلان ما زالا حيَّين في المسبح بعد التخليق الناجح
        // (لو فشل تحديد المحرك أو تسابق لحصل dropBrokenEngine لطرفٍ مُسقَط).
        val pool = enginePoolOf(provider)
        assertTrue(
            "مثيل المحرك A سُقط زائفاً",
            pool.get(ENGINE_A) === ttsA
        )
        assertTrue(
            "مثيل المحرك B سُقط زائفاً",
            pool.get(ENGINE_B) === ttsB
        )
        assertTrue("ttsA أُغلق", !shadowOf(ttsA).isShutdown)
        assertTrue("ttsB أُغلق", !shadowOf(ttsB).isShutdown)
    }
}