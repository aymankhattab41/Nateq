package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.engine.SynthesisRequestHandler
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.data.SettingsChangeProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.SpeechLock
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * «لا تُقاطع النغمة القراءة الجارية»: طلبُ إعلان وقتٍ (نقرة «أعلن الآن» أو
 * دقّة الساعة الدورية — كلاهما يمر عبر speakCurrentTime)
 * أثناء انشغال المتحدث بقراءةٍ طويلة لا يقطعها بـ FLUSH بل يُؤجَّل حتى يتحرر
 * المتحدث (بسقف ~30 ثانية ثم نطق قسري؛ المفتاح معطّل → نطق فوري كالسابق).
 *
 * المتحدث يُحقَن في [TimeAnnouncementManager] بمثيلٍ مستقل (لا المتحدث
 * المشترك الساكن) ليُتحكَّم بعلم النطق انعكاساً ويكون مجال المراقبة
 * (AudioManager) محلياً للحاضنة — لا تشويشَ بين حاضنات Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TimeNoInterruptReadingTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    private val shadowAudio
        get() = shadowOf(
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        )

    @Before
    fun installProvider() {
        // نثبّت مزوّد الإشعارات الحقيقي على سلطانه المعلن حتى يعمل
        // SpeechLock عبر content://…/speaking داخل بيئة الاختبار فعلياً.
        Robolectric.setupContentProvider(
            SettingsChangeProvider::class.java,
            SettingsChangeProvider.AUTHORITY
        )
    }

    @After
    fun tearDown() {
        SpeechLock.setSpeaking(context, false)
    }

    /** محاكاة «قراءةٍ طويلة جارية»: رفع علم نطق المتحدث مباشرة (الرفع
     *  الطبيعي يحدث في onStart وهبوطُه في onDone الختامي). */
    private fun setSpeaking(speaker: AnnouncementSpeaker, value: Boolean) {
        val field = AnnouncementSpeaker::class.java
            .getDeclaredField("nowSpeaking")
        field.isAccessible = true
        field.set(speaker, value)
    }

    /** مدير ينطق عبر المتحدث المستقل [speaker] (مسارات اختبارية معزولة). */
    private fun managerFor(
        settings: SettingsRepository,
        speaker: AnnouncementSpeaker
    ): TimeAnnouncementManager {
        val catalog = VoiceCatalog(emptyList())
        return TimeAnnouncementManager(
            context,
            settings,
            catalog,
            SynthesisRequestHandler(catalog, settings),
            speakerOverride = speaker
        )
    }

    @Test
    fun announceWhileSpeaking_defersThenSpeaksAfterFree() {
        val settings = SettingsRepository.create(context)
        settings.setTimeNoInterruptReadingEnabled(true)
        // رنة الساعة معطلة في الاختبار: المسار الصوتي بلا نغمة (لا وسائط).
        settings.setTimeChimeEnabled(false)
        val speaker = AnnouncementSpeaker(context)
        val manager = managerFor(settings, speaker)
        try {
            // قراءةٌ طويلة جارية: المتحدث مشغول فعلاً.
            setSpeaking(speaker, true)
            assertTrue("المتحدث يعترف بالانشغال", speaker.isCurrentlySpeaking())

            manager.announceNow()
            Thread.sleep(500)

            assertNull(
                "لا نطق وقتٍ أثناء الانشغال: لم يُطلب تركيزٌ (لا FLUSH)",
                shadowAudio.getLastAudioFocusRequest()
            )

            // القراءة اكتملت: بعد فاصل المحاولة يُعاد الفحص ويُنطق الوقت.
            setSpeaking(speaker, false)
            Thread.sleep(2600)

            assertNotNull(
                "الوقت يُنطق بعد تحرر المتحدث (اجتاز بوابة التأجيل)",
                shadowAudio.getLastAudioFocusRequest()
            )
        } finally {
            manager.stop()
            speaker.shutdown()
        }
    }

    @Test
    fun announceWhileSpeaking_withToggleOff_speaksImmediately() {
        val settings = SettingsRepository.create(context)
        settings.setTimeNoInterruptReadingEnabled(false)
        settings.setTimeChimeEnabled(false)
        val speaker = AnnouncementSpeaker(context)
        val manager = managerFor(settings, speaker)
        try {
            setSpeaking(speaker, true)
            manager.announceNow()
            Thread.sleep(500)

            assertNotNull(
                "المفتاح معطّل: يُنطق الوقت فوراً ولو كان المتحدث مشغولاً",
                shadowAudio.getLastAudioFocusRequest()
            )
        } finally {
            manager.stop()
            speaker.shutdown()
        }
    }

    @Test
    fun announceWhileIdle_speaksImmediatelyEvenWithToggleOn() {
        val settings = SettingsRepository.create(context)
        settings.setTimeNoInterruptReadingEnabled(true)
        settings.setTimeChimeEnabled(false)
        val speaker = AnnouncementSpeaker(context)
        val manager = managerFor(settings, speaker)
        try {
            setSpeaking(speaker, false)
            manager.announceNow()
            Thread.sleep(500)

            assertNotNull(
                "المتحدث متفرغ: مفعّل أو معطّل لا فرق — نطق فوري",
                shadowAudio.getLastAudioFocusRequest()
            )
        } finally {
            manager.stop()
            speaker.shutdown()
        }
    }

    @Test
    fun `speaker default is not speaking`() {
        // مثيل مستقل (لا المتحدث المشترك الساكن) — لا تشويش بين الاختبارات.
        val speaker = AnnouncementSpeaker(context)
        assertFalse("المتحدث متفرغ عند الإنشاء", speaker.isCurrentlySpeaking())
        setSpeaking(speaker, true)
        assertTrue(
            "رفعُ علم النطق ينعكس في الفحص", speaker.isCurrentlySpeaking()
        )
        setSpeaking(speaker, false)
        assertFalse(
            "هبوطُ العلم يعيد الفحص false", speaker.isCurrentlySpeaking()
        )
        speaker.shutdown()
    }
}