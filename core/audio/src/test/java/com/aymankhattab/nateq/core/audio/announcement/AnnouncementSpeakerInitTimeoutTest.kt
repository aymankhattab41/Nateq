package com.aymankhattab.nateq.core.audio.announcement

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
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
    shadows = [AnnouncementSpeakerInitTimeoutTest.NoOnInitTtsShadow::class]
)
class AnnouncementSpeakerInitTimeoutTest {

    companion object {
        val requestedEngines = CopyOnWriteArrayList<String?>()
    }

    @Before
    fun setUp() {
        requestedEngines.clear()
    }

    /**
     * ظلّ يحاكي محرك TTS لا يستدعي onInit أبداً (تعليق الربط).
     */
    @Implements(TextToSpeech::class)
    class NoOnInitTtsShadow : ShadowTextToSpeech() {
        @Implementation
        fun __constructor__(
            context: Context,
            listener: TextToSpeech.OnInitListener,
            engine: String
        ) {
            requestedEngines.add(engine)
            // لا نستدعي super: محاكاة محرك لا يستدعي onInit أبداً.
        }

        @Implementation
        override fun shutdown() {
        }
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

    @Test
    fun hungInitDoesNotBlockAnnouncements_andFallbackIsAttempted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        registerFakeTtsEngine(context, "com.hung.tts")
        registerFakeTtsEngine(context, "com.fallback.tts")
        val settings = SettingsRepository(context)
        settings.setAnnouncementFallbackEngine("com.fallback.tts")
        settings.setAllAnnouncementsEnabled(true)

        val speaker = AnnouncementSpeaker(context)
        try {
            speaker.speak(
                text = "اختبار",
                locale = Locale("ar"),
                speechRate = 1f,
                pitch = 1f,
                volume = 1f,
                category = SettingsRepository.VOICE_CATEGORY_LORD_SYSTEM,
                engineOverride = "com.hung.tts"
            )

            // دفع مهلة التهيئة (8s) + هامش.
            ShadowLooper.idleMainLooper(9_000, TimeUnit.MILLISECONDS)

            assertTrue(
                "يجب محاولة ربط المحرك الأول، المسجّل: $requestedEngines",
                requestedEngines.any { it == "com.hung.tts" }
            )
            assertTrue(
                "يجب تجربة المحرك الاحتياطي بعد تعليق onInit، المسجّل: $requestedEngines",
                requestedEngines.any { it == "com.fallback.tts" }
            )

            // البوابة يجب ألا تبقى معلقة: محاولة نطق لاحقة لا تُحجب للأبد.
            speaker.speak(
                text = "اختبار ثانٍ",
                locale = Locale("ar"),
                speechRate = 1f,
                pitch = 1f,
                volume = 1f,
                category = SettingsRepository.VOICE_CATEGORY_LORD_SYSTEM,
                engineOverride = "com.hung.tts"
            )
            ShadowLooper.idleMainLooper(9_000, TimeUnit.MILLISECONDS)
            assertTrue(
                "يجب استمرار معالجة المحاولات بعد المهلة",
                requestedEngines.count { it == "com.hung.tts" } >= 2
            )
        } finally {
            runCatching { speaker.shutdown() }
        }
    }
}
