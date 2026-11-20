package com.aymankhattab.nateq.core.audio.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpeechFallbackTrackerTest {

    @Before
    fun setUp() {
        SpeechFallbackTracker.clear()
    }

    @After
    fun tearDown() {
        SpeechFallbackTracker.clear()
    }

    @Test
    fun record_storesEntriesAndFormatsDetails() {
        SpeechFallbackTracker.record(
            text = "مرحبا بكم في تطبيق لورد",
            languageTag = "ar",
            requestedEngine = "vocalizer",
            requestedVoice = "maged",
            actualEngine = "vocalizer",
            actualVoice = "maged",
            isFallback = false
        )

        val recent = SpeechFallbackTracker.getRecentFormatted()
        assertEquals(1, recent.size)
        assertTrue(recent[0].contains("مرحبا بكم"))
        assertTrue(recent[0].contains("maged"))
        assertTrue(recent[0].contains("[مباشر]"))
        assertFalse(SpeechFallbackTracker.isAcapelaFallbackActive())
    }

    @Test
    fun record_detectsAcapelaFallback() {
        SpeechFallbackTracker.record(
            text = "تجربة نطق نص صوت سلمى",
            languageTag = "ar",
            requestedEngine = "acapela",
            requestedVoice = "salma",
            actualEngine = "vocalizer",
            actualVoice = "layla",
            isFallback = true,
            fallbackReason = "تعذر تحميل صوت سلمى"
        )

        assertTrue(SpeechFallbackTracker.isAcapelaFallbackActive())
        assertEquals("layla", SpeechFallbackTracker.acapelaFallbackVoice)
        assertEquals("vocalizer", SpeechFallbackTracker.acapelaFallbackEngine)
        assertEquals(
            "تعذر تحميل صوت سلمى",
            SpeechFallbackTracker.acapelaFallbackReason
        )

        val formatted = SpeechFallbackTracker.getRecentFormatted()[0]
        assertTrue(formatted.contains("[بديل (Fallback)"))
        assertTrue(formatted.contains("تعذر تحميل صوت سلمى"))
    }

    @Test
    fun noteAcapelaFailure_updatesFallbackState() {
        SpeechFallbackTracker.noteAcapelaFailure(
            reason = "فشل JNI في nSpeak",
            substituteVoice = "maged",
            substituteEngine = "vocalizer"
        )

        assertTrue(SpeechFallbackTracker.isAcapelaFallbackActive())
        assertEquals(
            "فشل JNI في nSpeak",
            SpeechFallbackTracker.acapelaFallbackReason
        )
        assertEquals("maged", SpeechFallbackTracker.acapelaFallbackVoice)
        assertEquals("vocalizer", SpeechFallbackTracker.acapelaFallbackEngine)
    }

    @Test
    fun clear_resetsAllState() {
        SpeechFallbackTracker.noteAcapelaFailure(
            reason = "خطأ",
            substituteVoice = "v",
            substituteEngine = "e"
        )
        assertTrue(SpeechFallbackTracker.isAcapelaFallbackActive())

        SpeechFallbackTracker.clear()

        assertFalse(SpeechFallbackTracker.isAcapelaFallbackActive())
        assertNull(SpeechFallbackTracker.acapelaFallbackReason)
        assertNull(SpeechFallbackTracker.acapelaFallbackVoice)
        assertNull(SpeechFallbackTracker.acapelaFallbackEngine)
        assertTrue(SpeechFallbackTracker.getRecentFormatted().isEmpty())
    }

    @Test
    fun clearAcapelaFallbackState_resetsFallbackOnly() {
        SpeechFallbackTracker.record(
            text = "مرحبا",
            languageTag = "ar",
            requestedEngine = "acapela_embedded",
            requestedVoice = "Salma",
            actualEngine = "acapela_embedded",
            actualVoice = "Salma",
            isFallback = false
        )
        SpeechFallbackTracker.noteAcapelaFailure(
            reason = "CONFIG_YAML_MISSING",
            substituteVoice = "Laila",
            substituteEngine = "vocalizer_embedded"
        )
        assertTrue(SpeechFallbackTracker.isAcapelaFallbackActive())

        SpeechFallbackTracker.clearAcapelaFallbackState()

        assertFalse(SpeechFallbackTracker.isAcapelaFallbackActive())
        assertNull(SpeechFallbackTracker.acapelaFallbackReason)
        assertEquals(1, SpeechFallbackTracker.getRecentFormatted().size)
    }

    @Test
    fun failedAcapelaAttemptWithFallback_logsReasonRequestedAndSubstitute() {
        SpeechFallbackTracker.noteAcapelaFailure(
            reason = "فشل نطق Acapela [CONFIG_YAML_MISSING]: " +
                "ملف config.yaml غير موجود",
            substituteVoice = "Laila",
            substituteEngine = "vocalizer_embedded"
        )
        SpeechFallbackTracker.record(
            text = "مرحبا",
            languageTag = "ar",
            requestedEngine = "acapela_embedded",
            requestedVoice = "Salma",
            actualEngine = "vocalizer_embedded",
            actualVoice = "Laila",
            isFallback = true,
            fallbackReason = SpeechFallbackTracker.acapelaFallbackReason
        )

        val entry = SpeechFallbackTracker.getRecentFormatted()[0]
        assertTrue(entry.contains("acapela_embedded"))
        assertTrue(entry.contains("Salma"))
        assertTrue(entry.contains("vocalizer_embedded"))
        assertTrue(entry.contains("Laila"))
        assertTrue(entry.contains("CONFIG_YAML_MISSING"))
        assertTrue(entry.contains("بديل"))
    }

    @Test
    fun record_truncatesTextSnippetTo40Characters() {
        val longText = "أ".repeat(50)
        SpeechFallbackTracker.record(
            text = longText,
            languageTag = "ar",
            requestedEngine = "engine",
            requestedVoice = "voice",
            actualEngine = "engine",
            actualVoice = "voice",
            isFallback = false
        )

        val entry = SpeechFallbackTracker.getRecentFormatted()[0]
        assertTrue(entry.contains("أ".repeat(37) + "..."))
    }
}
