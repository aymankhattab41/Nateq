package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LanguageCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات استقرار صوت الأرقام وتوحيد الحسم مع المعاينة والتتبع (ع٥٧-أمر 4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class NumberVoiceStabilityTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var settings: SettingsRepository

    @Before
    fun setup() {
        settings = SettingsRepository.create(context)
        VoiceResolutionPolicy.init(context)
        NumberSegmentTraceTracker.clear()
    }

    @After
    fun tearDown() {
        NumberSegmentTraceTracker.clear()
    }

    @Test
    fun `preview and live reading resolve to the same engine and voice`() {
        val lang = LanguageCode.AR.tag

        settings.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            "com.google.android.tts"
        )
        settings.setPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            "ar-xa-x-ard-local"
        )

        val liveResolution = VoiceResolutionPolicy.resolveSegmentVoice(
            role = SpeechRole.NUMBER,
            languageTag = lang,
            settings = settings
        )

        val previewResolution = VoiceResolutionPolicy.resolveSegmentVoice(
            role = SpeechRole.NUMBER,
            languageTag = lang,
            context = context
        )

        assertEquals(liveResolution.engine, previewResolution.engine)
        assertEquals(liveResolution.voiceName, previewResolution.voiceName)
        assertEquals(liveResolution.source, previewResolution.source)
        assertEquals("com.google.android.tts", liveResolution.engine)
        assertEquals("ar-xa-x-ard-local", liveResolution.voiceName)
    }

    @Test
    fun `number segment trace tracker records traces up to 100 entries`() {
        NumberSegmentTraceTracker.record(
            text = "123",
            requestedEngine = "vocalizer_embedded",
            requestedVoice = "ara-SAU-Leila",
            actualEngine = "vocalizer_embedded",
            actualVoice = "ara-SAU-Leila",
            hostReady = true,
            waitDurationMs = 15L,
            fallbackReason = null,
            importance = 100
        )

        var list = NumberSegmentTraceTracker.getRecent()
        assertEquals(1, list.size)
        assertEquals("123", list[0].text)
        assertTrue(list[0].hostReady)
        assertEquals(15L, list[0].waitDurationMs)

        // اختبار السقف 100
        for (i in 1..150) {
            NumberSegmentTraceTracker.record(
                text = "num_$i",
                requestedEngine = "eng",
                requestedVoice = "voice",
                actualEngine = "eng",
                actualVoice = "voice",
                hostReady = true
            )
        }

        list = NumberSegmentTraceTracker.getRecent()
        assertEquals(100, list.size)

        val formatted = NumberSegmentTraceTracker.format()
        assertNotNull(formatted)
        assertTrue(formatted.contains("تتبع المقاطع الرقمية"))
    }

    @Test
    fun `host not ready fallback records reason and maintains same language`() {
        val importance = NumberSegmentTraceTracker.currentImportance(context)
        NumberSegmentTraceTracker.record(
            text = "٤٥٦",
            requestedEngine = "vocalizer_embedded",
            requestedVoice = "ara-SAU-Leila",
            actualEngine = "com.google.android.tts",
            actualVoice = "ar-xa-x-ard-local",
            hostReady = false,
            waitDurationMs = 250L,
            fallbackReason = "host_not_ready",
            importance = importance
        )

        val traces = NumberSegmentTraceTracker.getRecent()
        assertEquals(1, traces.size)
        assertFalse(traces[0].hostReady)
        assertEquals("host_not_ready", traces[0].fallbackReason)
        assertEquals(250L, traces[0].waitDurationMs)

        // التحقق من أن التراجع كان لصوت من نفس اللغة
        val voiceLang = VoiceResolutionPolicy.languageOfVoiceId(
            traces[0].actualVoice
        )
        assertEquals(LanguageCode.AR.tag, voiceLang)
    }
}
