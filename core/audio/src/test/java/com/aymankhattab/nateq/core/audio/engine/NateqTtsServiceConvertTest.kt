package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * اختبار أن [NateqTtsService.resolveConvertTarget] يستخدم لغة المقطع
 * (languageTag) لا لغة الطلب (request.language)، وأن voiceName وحدها
 * تمنع الإرجاع المبكر في (بند 2.7 — الكلمة الإنجليزية المفردة).
 *
 * السيناريو: نص لاتيني بحت + request.language = "ara" + تفضيل تحويل
 * عربي مضبوط ⇒ finalLocale يساوي en لا ar، والسرعة من تفضيل en.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class NateqTtsServiceConvertTest {

    private fun createService(
        block: (SettingsRepository) -> Unit
    ): NateqTtsService {
        val app = RuntimeEnvironment.getApplication()
        val repo = SettingsRepository.create(app)
        block(repo)
        val service = NateqTtsService::class.java
            .getDeclaredConstructor().newInstance()
        val field = NateqTtsService::class.java
            .getDeclaredField("settings")
        field.isAccessible = true
        field.set(service, repo)
        return service
    }

    @Test
    fun latinText_usesEnglishPref_notArabic() {
        val service = createService { repo ->
            repo.setAutoConvertEnabled(true)
            repo.setEnginePreferenceForLanguage(
                "ar", "com.ar.eng", "ar-voice",
                1.5f, 1.0f, 1.0f
            )
            repo.setEnginePreferenceForLanguage(
                "en", "com.en.eng", "en-voice",
                1.1f, 1.0f, 1.0f
            )
        }
        val target = service.resolveConvertTarget("en")
        assertNotNull(target)
        assertEquals("en", target!!.convertLocale?.language)
        assertEquals(1.1f, target.convertRate, 0.01f)
        assertNotEquals(1.5f, target.convertRate, 0.01f)
    }

    @Test
    fun voiceNameOnly_doesNotReturnEarly() {
        val service = createService { repo ->
            repo.setAutoConvertEnabled(true)
            repo.setEnginePreferenceForLanguage(
                "en", null, "en-voice",
                1.0f, 1.0f, 1.0f
            )
        }
        val target = service.resolveConvertTarget("en")
        assertNotNull(
            "voiceName وحدها تمنع الإرجاع المبكر",
            target
        )
        assertEquals("en", target!!.convertLocale?.language)
    }

    @Test
    fun defaultsOnly_returnsNull() {
        val service = createService { repo ->
            repo.setAutoConvertEnabled(true)
        }
        assertNull(service.resolveConvertTarget("en"))
        assertNull(service.resolveConvertTarget("ar"))
    }

    @Test
    fun autoConvertOff_ignoresAllPrefs() {
        val service = createService { repo ->
            repo.setAutoConvertEnabled(false)
            repo.setEnginePreferenceForLanguage(
                "en", "com.en.eng", "en-voice",
                1.3f, 0.9f, 0.8f
            )
        }
        val target = service.resolveConvertTarget("en")
        // engine و voiceName = null لأن autoConvert معطّل
        // لكن rate/pitch/volume ≠ 1.0 → يُرجع ConvertTarget
        assertNotNull(target)
        assertNull(target!!.convertEngine)
        assertNull(target.convertVoiceName)
        assertEquals(1.3f, target.convertRate, 0.01f)
    }
}
