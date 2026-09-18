package com.aymankhattab.nateq.core.audio.providers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.speech.tts.TextToSpeech
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
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * محركٌ لا يدعم لغةَ النطق (إعادة [TextToSpeech.setLanguage]
 * = LANG_NOT_SUPPORTED لـ en) يجب أن يُفشل
 * [SystemVoiceProvider.synthesizeInternal]
 * فوراً وبلا بثّ أي بايتات، وأن يتولى المتصل تراجعاً على [speechLanguage]
 * الفعلية (en) لمحركٍ آخر يدعمها — بدل أن يحتفظ المحركُ بلغته السابقة
 * (العربية) فيقرأ الحروف اللاتينية بصوتٍ عربي.
 *
 * «المحرك غير الداعم» فئة فرعية واضحة من [TextToSpeech] تعيد
 * LANG_NOT_SUPPORTED لـ الإنجليزية (محرك وهمي)، لأن إعلان ShadowTextToSpeech
 * .addLanguageAvailability مجموعةٌ ثابتة مشتركة بين المحركين كليهما فلا
 * يعزل محركاً دون آخر. والمحرك الاحتياطي عادي (لغة el وحدة إعلانه).
 * إكمال الوحدة اللفظية يُقاد يدوياً عبر
 * [org.robolectric.shadows.ShadowTextToSpeech.getUtteranceProgressListener]
 * لأن شادو Robolectric لا يستدعي onDone تلقائياً وجدار المحاكاة يجمد
 * ساعة [android.os.SystemClock] فلا تنقضي مهلة الانتظار وحدها — نفس نمط
 * [SystemVoiceProviderRaceTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
    fun `unsupportedLanguage_failsFast_noBytesAndFallsBackToCapableEngine`() {
        registerEngine(ENGINE_A)
        registerEngine(ENGINE_B)
        EnginePicker.invalidateCache()

        // الإعلان عن توفر الإنكليزية للاحتياطي العادي B (المجموعة مشتركة،
        // لكن A يتجاوز الشادو بمحركه الوهمي فيفشل لغةً حتمياً).
        ShadowTextToSpeech.addLanguageAvailability(
            Locale.forLanguageTag("en-US")
        )

        val context = RuntimeEnvironment.getApplication()
        val provider = SystemVoiceProvider(context)
        provider.capableEnginesFor = { listOf(ENGINE_A, ENGINE_B) }

        val ttsA = UnsupportedEnEngine(context, { }, ENGINE_A)
        val ttsB = TextToSpeech(
            context, { }, ENGINE_B
        )
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
            val deadline = System.currentTimeMillis() + 10_000L
            // A لا يُكتب منه شيء (فشل اللغة يستبق synthesizeToFile)؛
            // ننتظر كتابة الاحتياطي B ثم نكمل وحدته اللفظية يدوياً.
            awaitSynthesizedFile(ttsB, deadline)
            shadowOf(ttsB).getUtteranceProgressListener().onDone("b")
            job.await()
        }
        // تصريف رقيقات Main المعلّقة (تنبيه التراجع المنشور عبر Toast) —
        // لا يؤثر على الحالة لكنه يطهر السجل.
        shadowOf(Looper.getMainLooper()).idle()

        // (1) محرك فاشل اللغة لم يُبثّ منه أي بايت: لا synthesizeToFile
        // ولا المستمع أصلاً (التراجع مبكرٌ جداً ومضمون).
        assertNull(
            "المحرك غير الداعم للغة كتب ملفاً",
            shadowOf(ttsA).getLastSynthesizeToFile()
        )
        assertNull(
            "المحرك غير الداعم للغة سُجّل له مستمع أصوات",
            shadowOf(ttsA).getUtteranceProgressListener()
        )
        // (2) التراجع أُنجز فعلاً على محركٍ يدعم اللغة: صوتٌ من B.
        assertTrue(
            "التراجع على المحرك الداعم للغة لم يُنتج صوتاً",
            chunks.isNotEmpty()
        )
        assertTrue(
            "مثيل المحرك الاحتياطي لم يبقَ في المسبح",
            enginePoolOf(provider).get(ENGINE_B) === ttsB
        )

        // (3) المحرك الفاشل أُسقط من المسبح وأُغلق (فشلٌ مؤكد).
        val pool = enginePoolOf(provider)
        assertTrue(
            "مثيل المحرك الفاشل لم يُسقط من المسبح",
            pool.get(ENGINE_A) == null
        )
        assertTrue(
            "مثيل المحرك الفاشل لم يُغلق",
            shadowOf(ttsA).isShutdown
        )
    }
}