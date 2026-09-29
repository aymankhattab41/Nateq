package com.aymankhattab.nateq.announcement

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowLooper

/**
 * يغطي قرارات إدارة التركيز الصوتي في [AnnouncementSpeaker]:
 * - إلغاءٌ صامت فوري عند AUDIOFOCUS_REQUEST_FAILED (لا نطق فوق
 *   مكالمة/صوت ناشط).
 * - حارس المسار المؤجل: عند انقضاء مؤقّت الأمان بلا تسليم التركيز لا يُنطق شيء.
 * - مسار ما قبل Android 8 يمرّر المستمع المسجَّل فعلاً عند الإخلاء (لا null —
 *   كان التسريب يترك مراجع المستمعين معلقة في AudioService).
 * الوصول للحقول الخاصة عبر Reflection (محرك tts + الإجراء المنتظر) لأنها
 * منفّذة داخلياً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AnnouncementSpeakerFocusTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val appContext: Context get() = context.applicationContext

    private val audioManager: AudioManager
        get() = appContext.getSystemService(
            Context.AUDIO_SERVICE
        ) as AudioManager

    private val shadowAudio: ShadowAudioManager
        get() = shadowOf(audioManager)

    private val arLocale = Locale.forLanguageTag("ar")

    @org.junit.Before
    fun setUp() {
        com.aymankhattab.nateq.core.data.SettingsRepository(context)
            .setAnnouncementMediaStreamAlways(true)
    }

    private fun speaker(): AnnouncementSpeaker = AnnouncementSpeaker(context)

    private fun fieldOf(instance: Any, name: String): Any? {
        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(instance)
    }

    private fun ttsIsNull(s: AnnouncementSpeaker): Boolean =
        fieldOf(s, "tts") == null

    private fun pendingActionIsNull(s: AnnouncementSpeaker): Boolean =
        fieldOf(s, "pendingFocusAction") == null

    private fun boolField(s: AnnouncementSpeaker, name: String): Boolean =
        fieldOf(s, name) as Boolean

    @Test
    fun focusRequestFailed_duringCall_defersAndRetries_thenDropsSilently() {
        val s = speaker()
        audioManager.mode = AudioManager.MODE_IN_CALL
        s.speak("اختبار المكالمة", arLocale, 1f, 1f, 1f)
        // لا تهيئة فورية بعد الرفض: يُعيد جدولة طلب التركيز لاحقاً (لا نطق
        // فوق مشغّلٍ محجوز — المكالمة) بدل النطق فوقه.
        assertTrue("لا محرك يتهيأ قبل إعادة الجدولة", ttsIsNull(s))
        assertTrue("لا إجراء نطق معلّق فورياً", pendingActionIsNull(s))

        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

        // نفاد محاولات إعادة الجدولة: إسقاطٌ صامت حمايةً للمكالمة.
        assertTrue("لا محرك يتهيأ بعد نفاد المحاولات", ttsIsNull(s))
        assertTrue("الإجراء أُلغي بعد النفاد", pendingActionIsNull(s))
        audioManager.mode = AudioManager.MODE_NORMAL
        s.shutdown()
    }

    @Test
    fun normalMode_grantsFocusImmediatelyWithoutDucking() {
        val s = speaker()
        audioManager.mode = AudioManager.MODE_NORMAL
        s.speak("اختبار فوري", arLocale, 1f, 1f, 1f)
        // في الوضع الطبيعي: منح فوري وتزامن كامل بلا تأخير
        assertTrue(
            "يُمنح التركيز فوراً دون تأجيل",
            boolField(s, "hasAudioFocus")
        )
        assertNull(
            "لا يُرسل طلب تركيز للنظام لمنع خفض صوت الوسائط",
            shadowAudio.getLastAudioFocusRequest()
        )
        assertTrue(
            "لا يوجد إجراء مؤجل لانتظار التركيز",
            pendingActionIsNull(s)
        )
        s.shutdown()
    }

    @Test
    @Config(sdk = [24])
    fun preO_abandon_passesRegisteredListener_notNull() {
        // على أندرويد قبل 8.0: الإخلاء بلا مستمع (null) كان يترك تسجيل المستمع
        // معلقاً في AudioService — الآن يُمرَّر المستمع الفعلي
        // فيُسجَّل الإخلاء.
        val s = speaker()
        s.stop()
        assertNotNull(
            "تمرير المستمع عند الإخلاء",
            shadowAudio.getLastAbandonedAudioFocusListener()
        )
    }

    @Test
    fun focusLoss_duringAnnouncement_stopsAndReleasesFocus() {
        val s = speaker()
        s.speak("إعلانٌ أثناء مكالمة", arLocale, 1f, 1f, 1f)
        val listener = fieldOf(
            s, "onAudioFocusChange"
        ) as AudioManager.OnAudioFocusChangeListener
        // بدأت مكالمة (فقد التركيز النهائي) — يُوقف النطق فوراً ويحرر التركيز.
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(
            "فقد التركيز يُوقف النطق فوراً",
            boolField(s, "nowSpeaking")
        )
        assertFalse(
            "فقد التركيز يُحرر تملك التركيز",
            boolField(s, "hasAudioFocus")
        )
        s.shutdown()
    }

    @Test
    fun focusLossTransient_duringAnnouncement_stopsAndReleasesFocus() {
        val s = speaker()
        s.speak("إعلانٌ عابر", arLocale, 1f, 1f, 1f)
        val listener = fieldOf(
            s, "onAudioFocusChange"
        ) as AudioManager.OnAudioFocusChangeListener
        // فقدان مؤقت — إيقاف وتحرير التركيز.
        listener.onAudioFocusChange(
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        )
        assertFalse(
            "فقدان التركيز المؤقت يُوقف النطق",
            boolField(s, "nowSpeaking")
        )
        assertFalse(
            "فقدان التركيز المؤقت يُحرر التركيز",
            boolField(s, "hasAudioFocus")
        )
        s.shutdown()
    }
    // ── اختبارات إعادة الضبط غير المشروطة ──────────────────────────────

    /**
     * [resetAudioFocusUnconditionally] يستدعي abandon حتى عندما
     * hasAudioFocus=false — أي بدون أي شرط داخلي.
     */
    @Test
    @Config(sdk = [24])
    fun resetUnconditional_whenFocusIsFalse_preO_stillCallsAbandon() {
        val s = speaker()
        // تأكد أن hasAudioFocus = false (الحالة الافتراضية بلا طلب سابق)
        assertFalse(
            "hasAudioFocus افتراضياً false",
            boolField(s, "hasAudioFocus")
        )
        // نستدعي إعادة الضبط اليدوية — يجب أن يُستدعى abandonAudioFocus
        // بصرف النظر عن hasAudioFocus الداخلية.
        s.resetAudioFocusUnconditionally()
        assertNotNull(
            "abandon استُدعي رغم hasAudioFocus=false",
            shadowAudio.getLastAbandonedAudioFocusListener()
        )
        s.shutdown()
    }

    /**
     * [resetAudioFocusUnconditionally] يستدعي abandon أيضاً حين
     * hasAudioFocus=true — يؤكد اللاشرطية في الاتجاهين.
     */
    @Test
    fun resetUnconditional_whenFocusIsGranted_stillCallsAbandon() {
        val s = speaker()
        s.speak("طلب التركيز أولاً", arLocale, 1f, 1f, 1f)
        assertTrue(
            "hasAudioFocus=true بعد المنح",
            boolField(s, "hasAudioFocus")
        )
        assertNull(
            "لا يُرسل طلب تركيز للنظام لمنع خفض صوت الوسائط",
            shadowAudio.getLastAudioFocusRequest()
        )
        // إعادة الضبط اليدوية — abandon مباشرة.
        s.resetAudioFocusUnconditionally()
        assertFalse(
            "hasAudioFocus=false بعد إعادة الضبط",
            boolField(s, "hasAudioFocus")
        )
        assertTrue(
            "pendingFocusAction أُلغي",
            pendingActionIsNull(s)
        )
        s.shutdown()
    }
}
