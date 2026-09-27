package com.aymankhattab.nateq.core.audio.providers

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.aymankhattab.nateq.core.data.VoicePrefsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
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
 * اختبارات منع التراجع التلقائي عند فشل المحرك:
 * إن فشل المحرك المختار أو لم يدعم اللغة أو كان معطلاً،
 * يُسقط المثيل المعطوب ولا يتم التبديل التلقائي لأي محرك
 * آخر إطلاقاً. لا تُطلق أي رسالة نطق مزعجة للمستخدم عند
 * فشل المحرك أو غياب محرك للغة (تُسجَّل في اللوج فقط).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SystemVoiceProviderLanguageFallbackTest {

    companion object {
        private const val ENGINE_A = "com.fake.engineA"
        private const val ENGINE_B = "com.fake.engineB"
        private const val TTS_ACTION = "android.intent.action.TTS_SERVICE"
    }

    /** محرك وهمي لا يدعم اللغة الإنجليزية إطلاقاً. */
    private class UnsupportedEnEngine(
        context: Context,
        listener: TextToSpeech.OnInitListener,
        engine: String
    ) : TextToSpeech(context, listener, engine) {
        override fun setLanguage(locale: Locale?): Int {
            return if (locale != null &&
                locale.language.equals("en", ignoreCase = true)
            ) {
                TextToSpeech.LANG_NOT_SUPPORTED
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
        }
    }

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

    @Test
    fun unsupportedLanguage_failsFast_noBytesAndNoFallbackToOtherEngine() {
        registerEngine(ENGINE_A)
        registerEngine(ENGINE_B)
        EnginePicker.invalidateCache()

        val context = RuntimeEnvironment.getApplication()
        val provider = SystemVoiceProvider(context)

        val ttsA = UnsupportedEnEngine(context, { }, ENGINE_A)
        val ttsB = TextToSpeech(context, { }, ENGINE_B)
        enginePoolOf(provider)[ENGINE_A] = ttsA
        enginePoolOf(provider)[ENGINE_B] = ttsB

        val voiceEn = VoiceDescriptor(
            id = "nateq-en-US",
            providerId = SystemVoiceProvider.SYSTEM_PROVIDER_ID,
            displayName = "English",
            locale = Locale.forLanguageTag("en-US")
        )

        val chunks = ArrayList<ByteArray>()
        runBlocking {
            val job = async(Dispatchers.Default) {
                provider.synthesize(
                    text = textEn,
                    voice = voiceEn,
                    speechRate = 1.0f,
                    pitch = 1.0f,
                    volume = 1.0f,
                    onFormatInfo = { _, _ -> },
                    onAudioChunk = { data, len ->
                        chunks.add(data.copyOf(len))
                    },
                    enginePackage = ENGINE_A
                )
            }
            job.await()
        }
        shadowOf(Looper.getMainLooper()).idle()

        // 1. لا بايتات من المحرك الفاشل
        assertTrue(chunks.isEmpty())

        // 2. المحرك الفاشل أُسقط من المسبح وأُغلق
        val pool = enginePoolOf(provider)
        assertNull(pool[ENGINE_A])
        assertTrue(shadowOf(ttsA).isShutdown)

        // 3. لم يتم التبديل التلقائي إلى المحرك B
        assertNull(shadowOf(ttsB).getLastSynthesizeToFile())
    }

    @Test
    fun uninstalledOrDisabledEngine_doesNotSynthesize() {
        // ENGINE_B مثبت فقط؛ ENGINE_A معطل/غير مثبت
        registerEngine(ENGINE_B)
        EnginePicker.invalidateCache()

        val context = RuntimeEnvironment.getApplication()
        val provider = SystemVoiceProvider(context)

        val voiceEn = VoiceDescriptor(
            id = "nateq-en-US",
            providerId = SystemVoiceProvider.SYSTEM_PROVIDER_ID,
            displayName = "English",
            locale = Locale.forLanguageTag("en-US")
        )

        val chunks = ArrayList<ByteArray>()
        runBlocking {
            provider.synthesize(
                text = textEn,
                voice = voiceEn,
                speechRate = 1.0f,
                pitch = 1.0f,
                volume = 1.0f,
                onFormatInfo = { _, _ -> },
                onAudioChunk = { data, len ->
                    chunks.add(data.copyOf(len))
                },
                enginePackage = ENGINE_A
            )
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(chunks.isEmpty())
    }

    @Test
    fun unconfiguredLanguage_doesNotSynthesize() {
        val context = RuntimeEnvironment.getApplication()
        val fakePrefs = object : VoicePrefsProvider {
            override fun getEngineForLanguage(languageTag: String): String? =
                null

            override fun getVoiceForLanguage(languageTag: String): String? =
                null

            override fun getPreferredVoiceIdForCategory(
                category: String
            ): String? = null

            override fun getSpeechRateForCategory(category: String): Float =
                1.0f

            override fun getPitchForCategory(category: String): Float = 1.0f

            override fun getVolumeForCategory(category: String): Float = 1.0f
        }
        val provider = SystemVoiceProvider(context, fakePrefs)

        val voiceFr = VoiceDescriptor(
            id = "nateq-fr-FR",
            providerId = SystemVoiceProvider.SYSTEM_PROVIDER_ID,
            displayName = "French",
            locale = Locale.FRENCH
        )

        val chunks = ArrayList<ByteArray>()
        runBlocking {
            provider.synthesize(
                text = "Bonjour",
                voice = voiceFr,
                speechRate = 1.0f,
                pitch = 1.0f,
                volume = 1.0f,
                onFormatInfo = { _, _ -> },
                onAudioChunk = { data, len -> chunks.add(data.copyOf(len)) }
            )
            provider.synthesize(
                text = "Merci",
                voice = voiceFr,
                speechRate = 1.0f,
                pitch = 1.0f,
                volume = 1.0f,
                onFormatInfo = { _, _ -> },
                onAudioChunk = { data, len -> chunks.add(data.copyOf(len)) }
            )
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(chunks.isEmpty())
    }
}