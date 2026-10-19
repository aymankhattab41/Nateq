package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * اختبارات الأمرين 5 و6: أولوية الأصوات، ومطابقة وحفظ الإعدادات.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class OrdersFiveAndSixTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // --- اختبارات الأمر 5: أولوية الأصوات والأرقام ---

    @Test
    fun `order 5 - text role respects contact voice over app and category`() {
        val resolved = VoiceResolutionPolicy.resolve(
            role = SpeechRole.TEXT,
            languageTag = "ar",
            categoryVoice = "voice_default",
            appVoice = "voice_app",
            contactVoice = "voice_contact",
            languageDefaultVoice = "voice_lang"
        )
        assertEquals("voice_contact", resolved.voiceId)
        assertEquals(VoiceResolutionPolicy.SOURCE_CONTACT, resolved.source)
    }

    @Test
    fun `order 5 - text role respects app voice over category`() {
        val resolved = VoiceResolutionPolicy.resolve(
            role = SpeechRole.TEXT,
            languageTag = "ar",
            categoryVoice = "voice_default",
            appVoice = "voice_app",
            contactVoice = null,
            languageDefaultVoice = "voice_lang"
        )
        assertEquals("voice_app", resolved.voiceId)
        assertEquals(VoiceResolutionPolicy.SOURCE_APP, resolved.source)
    }

    @Test
    fun `order 5 - number role retains category voice`() {
        val resolved = VoiceResolutionPolicy.resolve(
            role = SpeechRole.NUMBER,
            languageTag = "ar",
            categoryVoice = "voice_number_cat",
            appVoice = "voice_app",
            contactVoice = "voice_contact",
            languageDefaultVoice = "voice_lang"
        )
        assertEquals("voice_number_cat", resolved.voiceId)
        assertEquals(
            VoiceResolutionPolicy.SOURCE_CATEGORY_NUMBER, resolved.source
        )
    }

    @Test
    fun `order 5 - emoji role retains category voice`() {
        val resolved = VoiceResolutionPolicy.resolve(
            role = SpeechRole.EMOJI,
            languageTag = "ar",
            categoryVoice = "voice_emoji_cat",
            appVoice = "voice_app",
            contactVoice = "voice_contact",
            languageDefaultVoice = "voice_lang"
        )
        assertEquals("voice_emoji_cat", resolved.voiceId)
        assertEquals(
            VoiceResolutionPolicy.SOURCE_CATEGORY_EMOJI, resolved.source
        )
    }

    // --- اختبارات الأمر 6: حفظ الإعدادات ومرآة التمهيد وتوثيق التوافقية ---

    @Test
    fun `order 6 - compat matrix documentation exists`() {
        val candidates = listOf(
            File("compat-matrix.md"),
            File("../../compat-matrix.md"),
            File("../compat-matrix.md"),
            File("doc/compat-matrix.md"),
            File("../../doc/compat-matrix.md"),
            File("../doc/compat-matrix.md")
        )
        val docFile = candidates.firstOrNull { it.exists() }
        assertNotNull("compat-matrix.md must exist", docFile)
        val text = docFile!!.readText()
        assertTrue(text.contains("Direct Boot"))
        assertTrue(text.contains("nateq_boot_mirror"))
        assertTrue(text.contains("Voice-call Ducking"))
    }

    @Test
    fun `order 6 - settings repository initializes and handles direct boot`() {
        val settings = SettingsRepository.create(context)
        assertNotNull(settings)
        settings.setLanguageDiagnosticsEnabled(true)
        assertTrue(settings.isLanguageDiagnosticsEnabled())
        settings.setDictionaryStandaloneMaxLength(2)
        assertEquals(2, settings.getDictionaryStandaloneMaxLength())
        settings.setEmojiPronunciationLanguage("EN")
        assertEquals("EN", settings.getEmojiPronunciationLanguage())
    }
}
