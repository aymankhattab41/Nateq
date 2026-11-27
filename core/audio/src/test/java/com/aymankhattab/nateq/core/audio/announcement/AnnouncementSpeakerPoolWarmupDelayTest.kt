package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    shadows = [AnnouncementSpeakerPoolWarmupDelayTest.AutoInitTtsShadow::class]
)
class AnnouncementSpeakerPoolWarmupDelayTest {

    @Implements(TextToSpeech::class)
    class AutoInitTtsShadow : ShadowTextToSpeech() {
        @Implementation
        fun __constructor__(
            context: Context,
            listener: TextToSpeech.OnInitListener,
            engine: String
        ) {
            // Robolectric 4.17 الافتراضي لا يستدعي onInit في منشئ 3-وسائط؛
            // هذا الظل يحاكي تهيئة محرك ناجحة للاختبارات.
            listener.onInit(TextToSpeech.SUCCESS)
        }
    }

    @Before
    fun setUp() {
        EnginePicker.invalidateCache()
        VisibleEngines.resetForTest()
    }

    private fun registerFakeTtsEngine(context: Context, pkg: String) {
        val pm = shadowOf(context.packageManager)
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val resolveInfo = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = pkg
                name = "$pkg.Service"
            }
        }
        pm.addResolveInfoForIntent(intent, resolveInfo)
    }

    private fun speakWithEngine(
        speaker: AnnouncementSpeaker,
        engine: String
    ) {
        speaker.speak(
            text = "اختبار",
            locale = Locale("ar"),
            speechRate = 1f,
            pitch = 1f,
            volume = 1f,
            category = SettingsRepository.VOICE_CATEGORY_LORD_SYSTEM,
            engineOverride = engine
        )
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun poolReusesWarmInstanceAndEvictsLeastRecentlyUsed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("com.a.tts", "com.b.tts", "com.c.tts", "com.d.tts")
            .forEach { registerFakeTtsEngine(context, it) }
        val settings = SettingsRepository(context)
        settings.setAllAnnouncementsEnabled(true)
        val speaker = AnnouncementSpeaker(context)
        try {
            speakWithEngine(speaker, "com.a.tts")
            val aTts = speaker.getTtsForTesting()
            speakWithEngine(speaker, "com.b.tts")
            val bTts = speaker.getTtsForTesting()
            speakWithEngine(speaker, "com.c.tts")
            speakWithEngine(speaker, "com.d.tts")

            val pooled = speaker.warmTtsPoolEnginesForTesting()
            assertEquals(3, pooled.size)
            assertTrue(pooled.contains("com.a.tts"))
            assertTrue(pooled.contains("com.b.tts"))
            assertTrue(pooled.contains("com.c.tts"))

            speakWithEngine(speaker, "com.b.tts")
            assertEquals("com.b.tts", speaker.boundEngineForTesting())
            assertSame(bTts, speaker.getTtsForTesting())
            assertFalse(
                speaker.warmTtsPoolEnginesForTesting().contains("com.a.tts")
            )
            assertTrue(aTts != null)
        } finally {
            runCatching { speaker.shutdown() }
        }
    }

    @Test
    fun warmReadyEngineSpeaksWithoutFixedDelay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        registerFakeTtsEngine(context, "com.warm.tts")
        val settings = SettingsRepository(context)
        settings.setAllAnnouncementsEnabled(true)
        val speaker = AnnouncementSpeaker(context)
        try {
            speakWithEngine(speaker, "com.warm.tts")
            speaker.speak(
                text = "اختبار دافئ ثانٍ",
                locale = Locale("ar"),
                speechRate = 1f,
                pitch = 1f,
                volume = 1f,
                category = SettingsRepository.VOICE_CATEGORY_LORD_SYSTEM,
                engineOverride = "com.warm.tts"
            )
            // بند 26: لا تأخير ثابت 60-80ms للمحرك الدافئ الجاهز.
            ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)
            val tts = speaker.getTtsForTesting()
            assertTrue(tts != null)
            assertEquals("com.warm.tts", speaker.boundEngineForTesting())
        } finally {
            runCatching { speaker.shutdown() }
        }
    }

    @Test
    fun warmupReadySkipsRepeatedWaitAndShortensTimeout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        registerFakeTtsEngine(context, "com.warmup.tts")
        val speaker = AnnouncementSpeaker(context)
        try {
            speaker.prewarmTtsForTesting(Locale("ar"))
            speaker.setBoundEngineForTesting("com.warmup.tts")
            val locale = Locale("ar")

            speaker.markWarmupReadyForLanguageForTesting(
                "com.warmup.tts",
                locale
            )
            val startReady = System.currentTimeMillis()
            val ready = speaker.awaitWarmupReadyForTesting(locale, 1L, 1500L)
            val elapsedReady = System.currentTimeMillis() - startReady
            assertTrue(ready)
            assertTrue(
                "لا يجب تكرار الانتظار لنفس المحرك/اللغة",
                elapsedReady < 150
            )

            val freshContext = ApplicationProvider
                .getApplicationContext<Context>()
            val speaker2 = AnnouncementSpeaker(freshContext)
            try {
                speaker2.prewarmTtsForTesting(locale)
                speaker2.setBoundEngineForTesting("com.warmup.tts")
                speaker2.markWarmupDoneForEngineForTesting(
                    "com.warmup.tts",
                    true
                )
                speaker2.setSimulatedVoicesEmpty(true)
                val startTimeout = System.currentTimeMillis()
                speaker2.awaitWarmupReadyForTesting(locale, 1L, 1500L)
                val elapsedTimeout = System.currentTimeMillis() - startTimeout
                assertTrue(
                    "المهلة يجب أن تُقصّر لمحرك مهيأ مسبقاً (<700ms)",
                    elapsedTimeout < 700
                )
                assertTrue(
                    "لا يجب الانتظار للمهلة الأصلية الطويلة 1500ms",
                    elapsedTimeout < 1200
                )
            } finally {
                runCatching { speaker2.shutdown() }
            }
        } finally {
            runCatching { speaker.shutdown() }
        }
    }
}
