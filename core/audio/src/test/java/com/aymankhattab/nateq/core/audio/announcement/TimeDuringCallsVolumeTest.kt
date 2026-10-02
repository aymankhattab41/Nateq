package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.engine.SynthesisRequestHandler
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارسُ شريط «الساعة أثناء المكالمات» المستقلّ (قرار المدير: مستوىٌ
 * وحده بلا نبرةٍ ولا سرعة).
 *
 * الاسمُ في كل اختبار = الكسرُ الذي يحرسه:
 * - `in call` يقود إلى الشريط المستقلّ.
 * - `other scenes` يمنع **تسريبَ الشريط المستقلّ إلى غير المكالمات**
 *   (وهو الكسر الذي يجعل صوت الساعة العادية ينخفض بلا سببٍ خفي).
 * - `announce now` يمنع اعتبارُ طلب المستخدم الصريح مكالمةً جاريةً.
 * - `out of range` يمنع قيمةً جامحةً من العبور إلى المحرك.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TimeDuringCallsVolumeTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private fun manager(settings: SettingsRepository): TimeAnnouncementManager {
        val catalog = VoiceCatalog(emptyList())
        return TimeAnnouncementManager(
            context,
            settings,
            catalog,
            SynthesisRequestHandler(catalog, settings)
        )
    }

    @Test
    fun `in call reads the dedicated calls volume`() {
        val settings = SettingsRepository.create(context)
        settings.setTimeDuringCallsVolume(0.25f)
        assertEquals(
            "أثناء المكالمة: الشريط المستقلّ",
            0.25f,
            manager(settings).resolveTimeSpeechVolume(
                inCall = true,
                categoryVolume = 1f,
                callsVolume = settings.getTimeDuringCallsVolume()
            ),
            0.0001f
        )
    }

    @Test
    fun `other scenes keep the category volume`() {
        val settings = SettingsRepository.create(context)
        settings.setTimeDuringCallsVolume(0.1f)
        assertEquals(
            "خارج المكالمة: مستوى فئة الساعة لا الشريط المستقلّ",
            0.8f,
            manager(settings).resolveTimeSpeechVolume(
                inCall = false,
                categoryVolume = 0.8f,
                callsVolume = settings.getTimeDuringCallsVolume()
            ),
            0.0001f
        )
    }

    @Test
    fun `announce now never counts as an in call scene`() {
        val settings = SettingsRepository.create(context)
        // الشريط منخفض جداً — فلوmisesерен في طلب «أعلن الآن» لانخفض النطق.
        settings.setTimeDuringCallsVolume(0f)
        assertEquals(
            "الطلب الصريح خارج المكالمة فلا ينزل الصوت",
            1f,
            manager(settings).resolveTimeSpeechVolume(
                inCall = false,
                categoryVolume = 1f,
                callsVolume = settings.getTimeDuringCallsVolume()
            ),
            0.0001f
        )
    }

    @Test
    fun `calls volume out of range is clamped to the audio range`() {
        val settings = SettingsRepository.create(context)
        settings.setTimeDuringCallsVolume(5f)
        assertEquals(
            "قيمةٌ جامحة لا تعبر إلى المحرك",
            1f,
            manager(settings).resolveTimeSpeechVolume(
                inCall = true,
                categoryVolume = 1f,
                callsVolume = settings.getTimeDuringCallsVolume()
            ),
            0.0001f
        )
        settings.setTimeDuringCallsVolume(-3f)
        assertEquals(
            "وسالبٌ يُقصّ إلى الصفر لا يكون صوتاً سالباً",
            0f,
            manager(settings).resolveTimeSpeechVolume(
                inCall = true,
                categoryVolume = 1f,
                callsVolume = settings.getTimeDuringCallsVolume()
            ),
            0.0001f
        )
    }

    @Test
    fun `default calls volume is full`() {
        val settings = SettingsRepository.create(context)
        assertEquals(
            "الافتراضي 100% كما طلب المدير",
            1f,
            settings.getTimeDuringCallsVolume(),
            0.0001f
        )
    }
}