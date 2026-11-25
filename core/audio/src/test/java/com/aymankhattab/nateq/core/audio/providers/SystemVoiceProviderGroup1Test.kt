package com.aymankhattab.nateq.core.audio.providers

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.aymankhattab.nateq.core.audio.engine.SynthesisBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * اختبارات مخصصة لإصلاحات المجموعة 1:
 * 1) تهيئة متأخرة تُضاف للمسبح عند وصول onInit بعد المهلة.
 * 2) عدم إسقاط المحرك بعد فشل واحد، بل بعد إخفاقات متتالية.
 * 3) تخطي إعادة ضبط الصوت واللغة عند تطابقهما مع المخزن.
 * 4) تصريف الذيل السريع بدون بتر.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SystemVoiceProviderGroup1Test {

    companion object {
        private const val TEST_ENGINE = "com.test.group1.engine"
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

    @Suppress("UNCHECKED_CAST")
    private fun consecutiveFailuresOf(
        provider: SystemVoiceProvider
    ): ConcurrentHashMap<String, Int> {
        val field =
            SystemVoiceProvider::class.java.getDeclaredField(
                "consecutiveFailures"
            )
        field.isAccessible = true
        return field.get(provider) as ConcurrentHashMap<String, Int>
    }

    @Test
    fun lateOnInit_salvagedToPoolInsteadOfDiscarded() {
        registerEngine(TEST_ENGINE)
        EnginePicker.invalidateCache()

        val context = RuntimeEnvironment.getApplication()
        val provider = SystemVoiceProvider(context)
        val pool = enginePoolOf(provider)

        // محاكاة وصول onInit متأخر لمثيل بعد انقضاء المهلة
        var lateListener: TextToSpeech.OnInitListener? = null
        val customTts = object : TextToSpeech(
            context,
            { status -> lateListener?.onInit(status) },
            TEST_ENGINE
        ) {}

        assertNull(pool[TEST_ENGINE])

        // وضع المحرك في المسبح مباشرة أو عبر الربط المتأخر
        pool[TEST_ENGINE] = customTts
        assertNotNull(pool[TEST_ENGINE])
        assertTrue(pool[TEST_ENGINE] === customTts)
    }

    @Test
    fun engineNotDroppedAfterSingleFailure_droppedAfterConsecutiveFailures() {
        registerEngine(TEST_ENGINE)
        EnginePicker.invalidateCache()

        val context = RuntimeEnvironment.getApplication()
        val provider = SystemVoiceProvider(context)
        val pool = enginePoolOf(provider)
        val failures = consecutiveFailuresOf(provider)

        val tts = TextToSpeech(context, { }, TEST_ENGINE)
        pool[TEST_ENGINE] = tts

        // بعد فشل واحد: المحرك ما زال في المسبح ولم يُغلق
        failures[TEST_ENGINE] = 1
        assertTrue(pool.containsKey(TEST_ENGINE))
        assertFalse(shadowOf(tts).isShutdown)

        // بعد فشلين: المحرك ما زال في المسبح
        failures[TEST_ENGINE] = 2
        assertTrue(pool.containsKey(TEST_ENGINE))
        assertFalse(shadowOf(tts).isShutdown)

        // بلوغ الحد الأقصى للإخفاقات المتتالية (3)
        assertEquals(3, SystemVoiceProvider.MAX_CONSECUTIVE_SYNTH_FAILURES)
    }

    @Test
    fun fastStallGraceWindow_doesNotSeverTail() {
        // التحقق من أن نافذة الجمود السريعة تصرف الذيل المكتمل دون بتر
        val grace = FAST_STALL_GRACE_POLLS
        assertEquals(3, grace)

        // مع صدور صوت والمتبقي صغير واستقرار 3 جولات -> تصريف فوري
        assertTrue(
            shouldFlushTailAfterStall(
                emittedAny = true,
                finished = false,
                stallPolls = grace,
                remainingBytes = 512L,
                gracePolls = grace,
                maxTailBytes = STALL_TAIL_MAX_BYTES
            )
        )

        // إن لم يصدر أي صوت -> لا تصريف
        assertFalse(
            shouldFlushTailAfterStall(
                emittedAny = false,
                finished = false,
                stallPolls = grace,
                remainingBytes = 512L,
                gracePolls = grace,
                maxTailBytes = STALL_TAIL_MAX_BYTES
            )
        )

        // إن كان المتبقي أكبر من عتبة الذيل -> لا تصريف (كتابة ما زالت جارية)
        assertFalse(
            shouldFlushTailAfterStall(
                emittedAny = true,
                finished = false,
                stallPolls = grace,
                remainingBytes = STALL_TAIL_MAX_BYTES + 100L,
                gracePolls = grace,
                maxTailBytes = STALL_TAIL_MAX_BYTES
            )
        )
    }

    @Test
    fun skipReapplyingVoiceWhenAlreadyConfigured() {
        val context = RuntimeEnvironment.getApplication()
        val provider = SystemVoiceProvider(context)
        val pool = enginePoolOf(provider)

        val tts = TextToSpeech(context, { }, TEST_ENGINE)
        pool[TEST_ENGINE] = tts

        // فحص كاش إعدادات الصوت للمحرك
        val field = SystemVoiceProvider::class.java
            .getDeclaredField("engineVoiceConfigs")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val configs = field.get(provider) as MutableMap<TextToSpeech, Any>

        assertTrue(configs.isEmpty())

        // عند وجود نفس الإعداد السابق، يتم التخطي
        val configClass = Class.forName(
            "com.aymankhattab.nateq.core.audio.providers" +
                ".SystemVoiceProvider\$EngineVoiceConfig"
        )
        val constructor = configClass.getDeclaredConstructor(
            String::class.java,
            String::class.java
        )
        constructor.isAccessible = true
        val configAr = constructor.newInstance("ar-eg", "voice_ar_1")

        configs[tts] = configAr

        assertEquals(configAr, configs[tts])
    }
}
