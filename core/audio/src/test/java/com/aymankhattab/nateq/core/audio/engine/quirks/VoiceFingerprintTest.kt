package com.aymankhattab.nateq.core.audio.engine.quirks

import android.speech.tts.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceFingerprintTest {

    private val enginePkg = "es.codefactory.vocalizertts"

    @Test
    fun fingerprint_fromVoice_createsCorrectFingerprint() {
        val v = createVoice("ar-SA-Standard-A", "ar", "SA")
        val fp = VoiceFingerprint.fromVoice(enginePkg, v)
        assertEquals(enginePkg, fp.enginePackage)
        assertEquals("ar", fp.languageTag)
        assertEquals("SA", fp.countryTag)
        assertEquals("ar-SA-Standard-A", fp.originalName)
    }

    @Test
    fun fingerprint_matchesSameName() {
        val fp = VoiceFingerprint(
            enginePackage = enginePkg,
            languageTag = "ar",
            countryTag = "SA",
            originalName = "ar-SA-Standard-A"
        )
        val v = createVoice("ar-SA-Standard-A", "ar", "SA")
        assertTrue("Same name matches", fp.matches(v))
    }

    @Test
    fun fingerprint_matchesLocaleAndCountry() {
        val fp = VoiceFingerprint(
            enginePackage = enginePkg,
            languageTag = "ar",
            countryTag = "SA",
            originalName = "old-name"
        )
        val v = createVoice("new-ar-SA-voice", "ar", "SA")
        assertTrue("Same locale+country matches", fp.matches(v))
    }

    @Test
    fun fingerprint_matchesLocaleOnly() {
        val fp = VoiceFingerprint(
            enginePackage = enginePkg,
            languageTag = "ar",
            countryTag = "SA",
            originalName = "old-name"
        )
        val v = createVoice("ar-EG-voice", "ar", "EG")
        assertTrue("Same language matches", fp.matches(v))
    }

    @Test
    fun fingerprint_noMatchDifferentLanguage() {
        val fp = VoiceFingerprint(
            enginePackage = enginePkg,
            languageTag = "ar",
            countryTag = "SA",
            originalName = "old-name"
        )
        val v = createVoice("en-US-voice", "en", "US")
        assertFalse("Different language doesn't match", fp.matches(v))
    }

    private fun createVoice(
        name: String,
        lang: String,
        country: String
    ): Voice {
        return Voice(
            name,
            java.util.Locale.forLanguageTag("$lang-$country"),
            Voice.QUALITY_HIGH,
            Voice.LATENCY_LOW,
            false,
            emptySet()
        )
    }
}