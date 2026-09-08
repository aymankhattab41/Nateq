package com.aymankhattab.nateq.announcement

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * - إلغاءٌ صامت فوري عند AUDIOFOCUS_REQUEST_FAILED (لا نطق فوق مكالمة/صوت ناشط).
 * - حارس المسار المؤجل: عند انقضاء مؤقّت الأمان بلا تسليم التركيز لا يُنطق شيء.
 * - مسار ما قبل Android 8 يمرّر المستمع المسجَّل فعلاً عند الإخلاء (لا null —
 *   كان التسريب يترك مراجع المستمعين معلقة في AudioService).
 * الوصول للحقول الخاصة عبر Reflection (محرك tts + الإجراء المنتظر) لأنها
 * منفّذة داخلياً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnnouncementSpeakerFocusTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val appContext: Context get() = context.applicationContext

    private val audioManager: AudioManager
        get() = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val shadowAudio: ShadowAudioManager
        get() = shadowOf(audioManager)

    private val arLocale = Locale.forLanguageTag("ar")

    private fun speaker(): AnnouncementSpeaker = AnnouncementSpeaker(context)

    private fun fieldOf(instance: Any, name: String): Any? {
        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(instance)
    }

    private fun ttsIsNull(s: AnnouncementSpeaker): Boolean = fieldOf(s, "tts") == null

    private fun pendingActionIsNull(s: AnnouncementSpeaker): Boolean =
        fieldOf(s, "pendingFocusAction") == null

    @Test
    fun focusRequestFailed_cancelsSilently_noEngineInit() {
        val s = speaker()
        shadowAudio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        s.speak("اختبار المكالمة", arLocale, 1f, 1f, 1f)
        // لا أي تباطؤ زمني ولا تهيئة محرك: الإعلان أُلغي صامتاً (كانت الحلقة
        // السابقة تجدول نطقاً بعد 400ms فوق صوتٍ ناشطٍ محجوز — المكالمة).
        assertTrue("لا محرك يتهيأ بعد رفض التركيز", ttsIsNull(s))
        assertTrue("لا إجراء نطق معلّق", pendingActionIsNull(s))
        s.shutdown()
    }

    @Test
    fun delayedFocus_timeoutWithoutGain_cancelsSilently() {
        val s = speaker()
        shadowAudio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        s.speak("اختبار مؤجل", arLocale, 1f, 1f, 1f)
        // الإجراء مسجّل بانتظار التركيز قبل انقضاء المهلة.
        assertFalse("الإجراء مسجّل بانتظار التركيز", pendingActionIsNull(s))
        // انقضاء مؤقّت الأمان (3 ثوانٍ) من دون وصول AUDIOFOCUS_GAIN:
        // الآن لا يُنطق شيء (الحارس يتحقق من بلوغ التركيز الفعلي).
        ShadowLooper.idleMainLooper(3200, TimeUnit.MILLISECONDS)
        assertTrue("لا محرك يتهيأ بعد انقضاء المهلة بلا تركيز", ttsIsNull(s))
        assertTrue("الإجراء المؤجل أُلغي", pendingActionIsNull(s))
        s.shutdown()
    }

    @Test
    fun delayedFocus_onGain_consumesPendingActionAndStartsSpeech() {
        val s = speaker()
        shadowAudio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_DELAYED)
        s.speak("وصول التركيز لاحقاً", arLocale, 1f, 1f, 1f)
        val listener = shadowAudio.getLastAudioFocusRequest().listener
        assertNotNull("المستمع مسجّل في طلب التركيز", listener)
        // النظام يسلم التركيز فعلاً → يُستهلك الإجراء المعلّق وتُحرَّك دورة النطق.
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue("الإجراء اُستهلك عند تسليم التركيز", pendingActionIsNull(s))
        s.shutdown()
    }

    @Test
    @Config(sdk = [24])
    fun preO_abandon_passesRegisteredListener_notNull() {
        // على أندرويد قبل 8.0: الإخلاء بلا مستمع (null) كان يترك تسجيل المستمع
        // معلقاً في AudioService — الآن يُمرَّر المستمع الفعلي فيُسجَّل الإخلاء.
        val s = speaker()
        s.stop()
        assertNotNull("تمرير المستمع عند الإخلاء", shadowAudio.getLastAbandonedAudioFocusListener())
    }
}