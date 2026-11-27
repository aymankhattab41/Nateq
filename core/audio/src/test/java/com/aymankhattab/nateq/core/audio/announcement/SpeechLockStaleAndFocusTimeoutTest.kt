package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsChangeProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.SpeechLock
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * اختبارات المجموعة 14:
 * بند 28: صلاحية علم النطق العالق (Heartbeat/TTL) وتقليص مهلة الأحداث العاجلة.
 * بند 29: تقليص مهلة التركيز المؤجل (DELAYED) ورد نداء
 * OnAudioFocusChangeListener.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    shadows = [SpeechLockStaleAndFocusTimeoutTest.InstantInitTtsShadow::class]
)
class SpeechLockStaleAndFocusTimeoutTest {

    @Implements(TextToSpeech::class)
    class InstantInitTtsShadow : ShadowTextToSpeech() {
        @Implementation
        fun __constructor__(
            context: Context,
            listener: TextToSpeech.OnInitListener,
            engine: String
        ) {
            listener.onInit(TextToSpeech.SUCCESS)
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appContext: Context get() = context.applicationContext

    private fun registerFakeTtsEngine(pkg: String) {
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

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(
            SettingsChangeProvider::class.java,
            SettingsChangeProvider.AUTHORITY
        )
        registerFakeTtsEngine("com.test.tts")
        val settings = SettingsRepository(appContext)
        settings.setAllAnnouncementsEnabled(true)
        SpeechLock.setSpeaking(appContext, false)
    }

    @After
    fun tearDown() {
        SpeechLock.setSpeaking(appContext, false)
        AnnouncementSpeaker.resetSharedForTesting()
    }

    @Test
    fun staleSpeakingLock_dropsImmediately_andDoesNotDelaySpeech() {
        // نقدّم ساعة النظام حتى لا تكون القيمة سالبة في بيئة الاختبار
        org.robolectric.shadows.ShadowSystemClock.advanceBy(
            java.time.Duration.ofSeconds(10)
        )
        // يحاكي قفل نطق عالقاً تركته عملية ماتت قبل 5 ثوانٍ
        val staleTimestamp = SystemClock.elapsedRealtime() - 5000L
        SpeechLock.setSpeaking(appContext, true, staleTimestamp)

        // التحقق من أن isSpeaking يكتشف سقوط نبضة القلب ويعيد false
        assertFalse(
            "القفل العالق يجب أن يسقط فوراً بعد فوات مهلة النبضة",
            SpeechLock.isSpeaking(appContext)
        )

        // عند إرسال إعلان لا يتأجل 5 ثوانٍ بل ينطلق مباشرة
        val speaker = AnnouncementSpeaker(appContext)
        try {
            speaker.speak(
                text = "إعلان فوري بعد قفل ساقط",
                locale = Locale("ar"),
                speechRate = 1f,
                pitch = 1f,
                volume = 1f,
                category = null
            )
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            assertTrue(
                "الإعلان ينطلق فوراً دون تأخير 5 ثوانٍ",
                speaker.speechDispatchedCount > 0L
            )
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun activeLock_withHeartbeat_keepsSpeakingLockAlive() {
        // قفل نطق طازج
        SpeechLock.setSpeaking(appContext, true)
        assertTrue(
            "القفل نشط فور التعيين",
            SpeechLock.isSpeaking(appContext)
        )

        // تجديد نبضة القلب
        SpeechLock.heartbeat(appContext)
        assertTrue(
            "القفل يظل نشطاً مع تجديد نبضة القلب",
            SpeechLock.isSpeaking(appContext)
        )
    }

    @Test
    fun urgentEventAnnouncement_hasShorterSpeakingWaitTimeout() {
        // نرفع القفل حديثاً
        SpeechLock.setSpeaking(appContext, true)
        val speaker = AnnouncementSpeaker(appContext)
        try {
            val normalFired = AtomicBoolean(false)
            val urgentFired = AtomicBoolean(false)

            // إدراج إعلان عادي في طابور القفل
            speaker.enqueueWhileSpeakingForTesting({
                normalFired.set(true)
            }, isUrgent = false)

            // إدراج إعلان عاجل (مكالمة أو بطارية حرجة)
            speaker.enqueueWhileSpeakingForTesting({
                urgentFired.set(true)
            }, isUrgent = true)

            // بعد 1600ms (تجاوز مهلة 1500ms العاجلة وقبل مهلة 5000ms العادية)
            ShadowLooper.idleMainLooper(1600, TimeUnit.MILLISECONDS)

            assertTrue(
                "المهلة العاجلة (1500ms) تُطلق الطابور دون انتظار 5 ثوانٍ",
                urgentFired.get() || normalFired.get()
            )
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun delayedAudioFocus_firesImmediatelyOnAudioFocusGainCallback() {
        val speaker = AnnouncementSpeaker(appContext)
        try {
            // محاكاة استجابة النظام بالتركيز المؤجل DELAYED
            speaker.setSimulatedAudioFocusResult(
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED
            )

            speaker.speak(
                text = "إعلان بانتظار التركيز المؤجل",
                locale = Locale("ar"),
                speechRate = 1f,
                pitch = 1f,
                volume = 1f,
                category = SettingsRepository.VOICE_CATEGORY_LORD_SYSTEM
            )
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)

            assertTrue(
                "يجب تعليق النطق بانتظار رد نداء التركيز",
                speaker.hasPendingFocusActionForTesting()
            )

            // وصول رد نداء AUDIOFOCUS_GAIN عبر OnAudioFocusChangeListener
            speaker.onAudioFocusChangeForTesting(
                AudioManager.AUDIOFOCUS_GAIN
            )
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)

            assertFalse(
                "يجب استهلاك إجراء النطق فور وصول التركيز",
                speaker.hasPendingFocusActionForTesting()
            )
            assertEquals(
                "النطق يجب أن ينطلق فوراً دون انتظار المؤقت",
                1L,
                speaker.speechDispatchedCount
            )
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun delayedAudioFocus_eventCategoryUsesShorterTimeout() {
        val speaker = AnnouncementSpeaker(appContext)
        try {
            speaker.setSimulatedAudioFocusResult(
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED
            )

            // فئة حدث (وقت أو بطارية أو متصل)
            speaker.speak(
                text = "الساعة الخامسة",
                locale = Locale("ar"),
                speechRate = 1f,
                pitch = 1f,
                volume = 1f,
                category = SettingsRepository.VOICE_CATEGORY_TIME
            )
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            assertTrue(speaker.hasPendingFocusActionForTesting())

            // بعد 1600ms (تجاوزت مهلة الأحداث 1500ms وقبل مهلة العادي 3000ms)
            ShadowLooper.idleMainLooper(1600, TimeUnit.MILLISECONDS)

            assertFalse(
                "مهلة التركيز المؤجل للحدث تنقضي عند 1500ms بنطق أفضل جهد",
                speaker.hasPendingFocusActionForTesting()
            )
        } finally {
            speaker.shutdown()
        }
    }
}
