package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.announcement.EventVoiceTrace
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات حل الأصوات وسرعة الكتالوج عند تناقض المحرك (الجولة 53 - الأمر 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class VoiceResolutionConflictTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EventVoiceTrace.getRecent().forEach { /* clear */ }
        VoiceCatalog.clearEngineVoicesCache()
    }

    @Test
    fun testVoiceResolution_withMismatchedEngine_discardsVoiceAndLogsTrace() {
        // فئة أرقام بمحرك أكابيلا ولكن الصوت المحفوظ فوكلايزر صريح (Tarik)
        val resolved = VoiceResolutionPolicy.resolve(
            role = SpeechRole.NUMBER,
            languageTag = "ar",
            categoryVoice = "Tarik",
            appVoice = null,
            contactVoice = null,
            languageDefaultVoice = "default_voice",
            categoryEngine = "com.acapelagroup.android.tts"
        )

        // يجب إهمال الصوت غير المطابق والرجوع للصوت الافتراضي
        assertEquals("default_voice", resolved.voiceId)

        val recent = EventVoiceTrace.getRecent()
        assertTrue("يجب تسجيل تراجع في EventVoiceTrace", recent.isNotEmpty())
        val last = recent.last()
        assertEquals("number", last.category)
        assertEquals("Tarik", last.savedVoiceId)
        assertEquals("com.acapelagroup.android.tts", last.resolvedEngine)
        assertEquals("صوت لا يتبع المحرك", last.fallbackReason)
    }

    @Test
    fun testVoiceResolution_withMatchingAcapelaVoice_resolvesDirectly() {
        // فئة أرقام بمحرك أكابيلا وصوت أكابيلا (ara-SAU-Leila)
        val resolved = VoiceResolutionPolicy.resolve(
            role = SpeechRole.NUMBER,
            languageTag = "ar",
            categoryVoice = "ara-SAU-Leila",
            appVoice = null,
            contactVoice = null,
            languageDefaultVoice = "default_voice",
            categoryEngine = "com.acapelagroup.android.tts"
        )

        assertEquals("ara-SAU-Leila", resolved.voiceId)
        assertEquals(
            VoiceResolutionPolicy.SOURCE_CATEGORY_NUMBER,
            resolved.source
        )
    }

    @Test
    fun testVoiceCatalog_cacheAndConcurrencyLimits() {
        assertEquals(3, VoiceCatalog.MAX_CONCURRENT_ENGINE_PROBES)

        val dummyVoice = Voice(
            "dummy_voice",
            Locale.ENGLISH,
            Voice.QUALITY_HIGH,
            Voice.LATENCY_LOW,
            false,
            emptySet()
        )

        assertNull(
            VoiceCatalog.getCachedEngineVoices("com.dummy.engine")
        )

        VoiceCatalog.cacheEngineVoices(
            "com.dummy.engine",
            listOf(dummyVoice)
        )

        val cached = VoiceCatalog.getCachedEngineVoices("com.dummy.engine")
        assertNotNull(cached)
        assertEquals(1, cached?.size)
        assertEquals("dummy_voice", cached?.first()?.name)

        VoiceCatalog.clearEngineVoicesCache()
        assertNull(
            VoiceCatalog.getCachedEngineVoices("com.dummy.engine")
        )
    }
}
