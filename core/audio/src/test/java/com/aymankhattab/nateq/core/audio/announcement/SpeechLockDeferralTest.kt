package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import com.aymankhattab.nateq.core.data.SettingsChangeProvider
import com.aymankhattab.nateq.core.data.SpeechLock
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * يغطي قفل النطق العابر مقابل متحدث الإعلانات: طلبٌ لنطق إعلانٍ أثناء
 * «نطق جارٍ» في :tts (العلم مرفوع عبر content://…/speaking) يُؤجَّل ولا
 * يصل إلى محرك النطق — لا طلب تركيزٍ ولا تهيئةِ محرك قبل انخفاض العلم؛
 * وعند هبوطِه يُحرَّر الإعلان فيبدأ التدفق عبر مسار التركيز المعتاد.
 * قناة الإتاحة تبقى [USAGE_ASSISTANCE_ACCESSIBILITY] أثناء القارئ (بلا
 * USAGE_MEDIA) فتتسلسل أصوات الإعلان والقراءة على المسار ذاته.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SpeechLockDeferralTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val appContext: Context get() = context.applicationContext

    private val audioManager: AudioManager
        get() = appContext.getSystemService(
            Context.AUDIO_SERVICE
        ) as AudioManager

    private val shadowAudio
        get() = shadowOf(audioManager)

    private val arLocale = Locale.forLanguageTag("ar")

    private fun speaker(): AnnouncementSpeaker = AnnouncementSpeaker(appContext)

    private fun fieldOf(instance: Any, name: String): Any? {
        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(instance)
    }

    private fun ttsIsNull(s: AnnouncementSpeaker): Boolean =
        fieldOf(s, "tts") == null

    @Before
    fun installProvider() {
        // نثبّت مزوّد الإشعارات الحقيقي على سلطانه المعلن حتى يعمل
        // SpeechLock عبر content://…/speaking داخل بيئة الاختبار فعلياً.
        // لا بد من تمرير السلطان صريحاً (الصيغة بلا سلطان لا تسجّل المزوّد
        // في اختبارات الوحدة).
        Robolectric.setupContentProvider(
            SettingsChangeProvider::class.java,
            SettingsChangeProvider.AUTHORITY
        )
    }

    @After
    fun tearDown() {
        SpeechLock.setSpeaking(appContext, false)
    }

    @Test
    fun announcementWhileSpeaking_isDeferred_notSentBeforeLockDrops() {
        // يحاكي بدءَ قراءةٍ مستمرة: محرك :tts يرفع العلم (بند قفل النطق).
        SpeechLock.setSpeaking(appContext, true)
        val s = speaker()

        s.speak("إعلانٌ أثناء قراءة قارئ الشاشة", arLocale, 1f, 1f, 1f)
        // لا مهلة تُحرِّر مبكراً؛ الإعلان يبقى معلّقاً فور الطلب.
        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)

        // **جوهر القفل:** لم يُطلب طلبُ تركيزٍ (المتحدث لم يمر بمسار النطق
        // إطلاقاً) ولم يُهيَّأ محركٌ — التأجيل قبلهما لا يليهما.
        assertNull(
            "لا طلب تركيزٍ أثناء القفل (لم يُرسل إلى tts.speak)",
            shadowAudio.getLastAudioFocusRequest()
        )
        assertTrue(
            "لا محرك يُهيَّأ أثناء القفل",
            ttsIsNull(s)
        )

        // القراءة انتهت: :tts يخفض العلم ويُبثّ الإشعار — يُحرَّر الإعلان
        // المؤجَّل فوراً فيبدأ بالمسار المعتاد (طلب التركيز أول خطوة).
        SpeechLock.setSpeaking(appContext, false)
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        assertNotNull(
            "الإعلان ينطلق بعد هبوط العلم",
            shadowAudio.getLastAudioFocusRequest()
        )
        s.shutdown()
    }

    @Test
    fun announcementWhileSpeaking_firesAfterTimeoutEvenIfStillSpeaking() {
        // مهلة الأمان القصوى (5 ثوانٍ): إن طالت القراءة ولم يهبط العلم
        // يُنطق الإعلان على أي حال — لا ضياع إعلانٍ وراء قراءةٍ طويلة.
        SpeechLock.setSpeaking(appContext, true)
        val s = speaker()

        s.speak("إعلانٌ بعد مهلة", arLocale, 1f, 1f, 1f)
        ShadowLooper.idleMainLooper(5600, TimeUnit.MILLISECONDS)

        assertNotNull(
            "المهلة القصوى تُطلق الإعلان حتى مع بقاء القفل",
            shadowAudio.getLastAudioFocusRequest()
        )
        s.shutdown()
    }
}
