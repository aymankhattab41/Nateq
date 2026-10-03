package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import com.aymankhattab.nateq.core.data.SettingsChangeProvider
import com.aymankhattab.nateq.core.data.SpeechLock
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.audio.engine.SynthesisRequestHandler
import com.aymankhattab.nateq.core.audio.providers.SystemVoiceProvider
import com.aymankhattab.nateq.core.common.TimeProvider
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

    private fun deferredQueueSize(s: AnnouncementSpeaker): Int =
        (fieldOf(s, "deferredWhileSpeaking") as Collection<*>).size

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
        AnnouncementSpeaker.resetSharedForTesting()
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
        assertEquals(
            "لم يُطلق الإعلان أثناء القفل",
            0L,
            s.speechDispatchedCount
        )

        // القراءة انتهت: :tts يخفض العلم ويُبثّ الإشعار — يُحرَّر الإعلان
        // المؤجَّل فوراً فيبدأ بالمسار المعتاد.
        SpeechLock.setSpeaking(appContext, false)
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
        assertTrue(
            "الإعلان ينطلق بعد هبوط العلم",
            s.speechDispatchedCount > 0L
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

        assertTrue(
            "المهلة القصوى تُطلق الإعلان حتى مع بقاء القفل",
            s.speechDispatchedCount > 0L
        )
        s.shutdown()
    }

    @Test
    fun eventCategory_time_interruptsSpeakingLockImmediately() {
        SpeechLock.setSpeaking(appContext, true)
        val s = speaker()

        s.speak(
            "الساعة الآن الخامسة",
            arLocale,
            1f,
            1f,
            1f,
            category = SettingsRepository.VOICE_CATEGORY_TIME
        )

        // يقطع فوراً بلا انتظار: لا يُدرج في طابور تأجيل القفل إطلاقاً
        assertEquals(
            "فئة الوقت لا تُدرج في طابور تأجيل القفل",
            0,
            deferredQueueSize(s)
        )
        assertTrue(
            "المتحدث ينطلق فوراً لفئة الوقت رغم القفل",
            s.speechDispatchedCount > 0L
        )
        // التحقق من سمات الصوت: مسار الوسائط لغير فئة المتصل (وقت).
        // ملاحظة: نمرّر الفئة صراحةً لأن [speechAudioAttributes] صار
        // يميّز فئات المتصل (إشعار) عن غيرها (وسائط) — انظر حارس
        // AnnouncementSpeakerTest.
        val method = AnnouncementSpeaker::class.java
            .getDeclaredMethod(
                "speechAudioAttributes",
                String::class.java
            )
        method.isAccessible = true
        val attrs = method.invoke(
            s,
            SettingsRepository.VOICE_CATEGORY_TIME
        ) as android.media.AudioAttributes
        assertEquals(
            "مسار نطق الحدث موحد على مسار الوسائط",
            android.media.AudioAttributes.USAGE_MEDIA,
            attrs.usage
        )

        s.shutdown()
    }

    @Test
    fun announceNow_duringSpeakingLock_interruptsImmediately() {
        val repo = SettingsRepository(appContext)
        SpeechLock.setSpeaking(appContext, true)

        val providers = listOf(
            SystemVoiceProvider(context, repo)
        )
        val catalog = VoiceCatalog(providers)
        val handler = SynthesisRequestHandler(catalog, repo)
        val clock = object : TimeProvider {
            override fun now(): java.util.Calendar =
                java.util.Calendar.getInstance()
            override fun currentTimeMillis(): Long =
                System.currentTimeMillis()
        }
        val manager = TimeAnnouncementManager(
            context, repo, catalog, handler, clock
        )
        val s = AnnouncementSpeaker.getInstance(context)
        val initialCount = s.speechDispatchedCount
        manager.announceNow()
        var waited = 0
        while (s.speechDispatchedCount == initialCount && waited < 100) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            waited++
        }

        assertTrue(
            "announceNow ينطلق فوراً بلا انتظار هبوط القفل",
            s.speechDispatchedCount > initialCount
        )
        assertEquals(
            "طابور تأجيل القفل فارغ لـ announceNow",
            0,
            deferredQueueSize(s)
        )
    }

    @Test
    fun batteryAndCallerEvents_interruptSpeakingLockImmediately() {
        SpeechLock.setSpeaking(appContext, true)
        val s = speaker()

        s.speak(
            "البطارية 20 بالمئة",
            arLocale,
            1f,
            1f,
            1f,
            category = SettingsRepository.VOICE_CATEGORY_BATTERY
        )
        assertEquals(
            "فئة البطارية لا تُدرج في طابور تأجيل القفل",
            0,
            deferredQueueSize(s)
        )
        assertTrue(
            "المتحدث ينطلق فوراً للبطارية",
            s.speechDispatchedCount > 0L
        )

        val countBeforeCaller = s.speechDispatchedCount
        s.speak(
            "مكالمة واردة",
            arLocale,
            1f,
            1f,
            1f,
            category = SettingsRepository.ANNOUNCE_CATEGORY_CALLER
        )
        assertEquals(
            "فئة المتصل لا تُدرج في طابور تأجيل القفل",
            0,
            deferredQueueSize(s)
        )
        assertTrue(
            "المتحدث ينطلق فوراً للمتصل",
            s.speechDispatchedCount > countBeforeCaller
        )

        s.shutdown()
    }

    @Test
    fun notificationCategory_firesImmediatelyLikeOtherEvents() {
        SpeechLock.setSpeaking(appContext, true)
        val s = speaker()

        s.speak(
            "إشعار واتساب عادي",
            arLocale,
            1f,
            1f,
            1f,
            category = SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
        )
        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)

        // الإشعارات ضمن فئة الأحداث (EVENT_CATEGORIES) فتُنطق فوراً
        // كالوقت والبطارية والمتصل: لا تؤجَّل خلف قراءةٍ طويلة.
        assertEquals(
            "فئة الإشعارات تُنطق فوراً فلا تُدرج في طابور التأجيل",
            0,
            deferredQueueSize(s)
        )
        assertTrue(
            "المتحدث ينطلق فوراً لإشعار الواتساب رغم القفل",
            s.speechDispatchedCount > 0L
        )
        s.shutdown()
    }
}
