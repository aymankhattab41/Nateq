package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارسُ مسارِ النطق فوق مكالمةٍ جارية (الأمر 5).
 *
 * **الجذرُ الذي يحرسه:** `isCallerCategory` كانت تعني حالتين
 * مختلفتين: «يرنّ متصل» و«ثمة مكالمةٌ جارية وفيها متصلٌ ينتظر».
 * فوجَّهت الاثنتان إلى `STREAM_NOTIFICATION` — وهو **ليس على مسار
 * المكالمة**، فيلتقطه الميكروفون فيسمعه الطرف الآخر، ويرفعه
 * `boostStreamVolume` إلى القمّة بلا منحنى صوت مكالمة يخفّضه.
 *
 * **الاسمُ في كل اختبار = الكسرُ الذي يحرسه:**
 * - `never reaches the call stream` يمنع **خطفَ مسار مكالمة
 *   المستخدم** لفئاتٍ لا صلة لها بالمكالمة (بطارية/وقت/رسائل) — وهو
 *   أسوأُ ما يمكن أن يقع هنا.
 * - `the grace window keeps` يمنع **نقلةَ القناة في ذيل النطق** حين
 *   يرجع `mode` إلى `NORMAL` قبل آخر جملة.
 * - `the grace window expires` يمنع **الالتصاقِ بالمسار** بعد المكالمة.
 * - `at most 600 ms` يمنع تمدّدَ النعمة فتبقى النعمةُ سبباً لاختلال
 *   المسار في مشاهدَ لا مكالمة فيها.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SpeechRouteDecisionTest {

    private val caller = SettingsRepository.ANNOUNCE_CATEGORY_CALLER
    private val callerAr = SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR
    private val callerEn = SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN
    private val time = SettingsRepository.VOICE_CATEGORY_TIME

    private fun route(
        category: String?,
        inCall: Boolean
    ) = AnnouncementSpeaker.speechRouteFor(category, inCall)

    @Test
    fun `caller during a call is routed to the call stream`() {
        assertEquals(
            "مكالمةُ انتظار: قناةُ المكالمة لا قناةُ الإشعار",
            SpeechRoute.CALL,
            route(caller, inCall = true)
        )
    }

    @Test
    fun `caller outside a call stays on the notification stream`() {
        assertEquals(
            "رنينُ متصلٍ بلا مكالمة جارية: قناةُ الإشعار كما كان",
            SpeechRoute.NOTIFICATION,
            route(caller, inCall = false)
        )
    }

    @Test
    fun `the three caller variants all reach the call route`() {
        for (category in listOf(caller, callerAr, callerEn)) {
            assertEquals(
                "كلُّ أنواع المتصل الثلاثة تتشارك المسار",
                SpeechRoute.CALL,
                route(category, inCall = true)
            )
        }
    }

    @Test
    fun `a non caller category never reaches the call stream`() {
        for (category in listOf(time, null, "any-other-category")) {
            assertEquals(
                "فئةٌ لا صلة لها بالمكالمة لا تُوضع على قناة المكالمة",
                SpeechRoute.MEDIA,
                route(category, inCall = true)
            )
        }
    }

    @Test
    fun `the grace window keeps the call route after the call ends`() {
        val latch = CallRouteLatch()
        assertTrue(
            "أثناء المكالمة: مقفل",
            latch.observe(inCall = true, nowMs = 1_000L)
        )
        assertTrue(
            "بعد انتهاء المكالمة بقليل: يبقى مقفلاً بميعاد النعمة",
            latch.observe(inCall = false, nowMs = 1_000L + 599L)
        )
    }

    @Test
    fun `the grace window expires so the route does not stick`() {
        val latch = CallRouteLatch()
        latch.observe(inCall = true, nowMs = 1_000L)
        assertFalse(
            "انقضاءُ النعمة يعيد المسارَ قفلاً مفتوحاً",
            latch.observe(inCall = false, nowMs = 1_000L + 601L)
        )
    }

    @Test
    fun `the grace window is at most 600 ms`() {
        assertTrue(
            "نعمةٌ أطول من 600 مللي ثانية تُبقي المسارَ معلَّقاً بلا سبب",
            CALL_ROUTE_GRACE_MS <= 600L
        )
    }

    @Test
    fun `a never seen latch starts open`() {
        assertFalse(
            "مِعْلَقٌ لم يرَ مكالمةً لا يدّعي أنه مقفل",
            CallRouteLatch()
                .observe(inCall = false, nowMs = 0L)
        )
    }

    @Test
    fun `each route maps to its own stream`() {
        assertEquals(
            "المسارُ الأول يمرّ على قناة المكالمة",
            AudioManager.STREAM_VOICE_CALL,
            AnnouncementSpeaker.streamForRoute(
                SpeechRoute.CALL
            )
        )
        assertEquals(
            "فئةُ المتصل خارجَ المكالمة: قناةُ الإشعار",
            AudioManager.STREAM_NOTIFICATION,
            AnnouncementSpeaker.streamForRoute(
                SpeechRoute.NOTIFICATION
            )
        )
        assertEquals(
            "باقي الفئات: قناةُ الوسائط",
            AudioManager.STREAM_MUSIC,
            AnnouncementSpeaker.streamForRoute(
                SpeechRoute.MEDIA
            )
        )
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private fun manager() = context.getSystemService(Context.AUDIO_SERVICE)
        as AudioManager

    /**
     * محاكاةُ مكالمةٍ جارية — **في الاختبار فقط**.
     *
     * **والتطبيقُ نفسُه لا يسمّي `setMode` أبداً** (قرار المدير):
     * تغييرُ الوضع العامِّ للصوت يختطف توجيهَ مكالمة المستخدم. فهنا
     * نحاكي ما تفعله الشبكةُ فحسب، ثم نعيد الوضعَ كما كان.
     */
    private fun <T> duringCall(body: () -> T): T {
        val am = manager()
        val saved = am.mode
        am.mode = AudioManager.MODE_IN_CALL
        try {
            return body()
        } finally {
            am.mode = saved
        }
    }

    @Test
    fun `the speaker reads the call mode into the call route`() {
        val speaker = AnnouncementSpeaker(context)
        try {
            duringCall {
                assertEquals(
                    "قراءةُ وضعِ المكالمة تُخرج المسارَ CALL",
                    SpeechRoute.CALL,
                    speaker.speechRouteForNow(caller, nowMs = 1_000L)
                )
            }
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `the call route never boosts the voice call stream`() {
        val speaker = AnnouncementSpeaker(context)
        try {
            val am = manager()
            val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            if (max <= 0) return
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 1, 0)
            val before = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            duringCall {
                speaker.boostStreamVolume(caller)
                assertEquals(
                    "قناةُ مكالمةِ المستخدم لا تُرفع إلى القمة أبداً",
                    before,
                    am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                )
            }
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `the call route stops boosting the notification stream`() {
        val speaker = AnnouncementSpeaker(context)
        try {
            val am = manager()
            val max = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
            if (max <= 0) return
            am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 1, 0)
            val before = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            duringCall {
                speaker.boostStreamVolume(caller)
                assertEquals(
                    "فوقَ المكالمة لا يُرفع شيء: القناةُ قناةُ المكالمة",
                    before,
                    am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
                )
            }
        } finally {
            speaker.restoreBoostedStreamVolume()
            speaker.shutdown()
        }
    }
}
