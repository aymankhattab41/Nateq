package com.aymankhattab.nateq

import com.aymankhattab.nateq.providers.EnginePicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات اختيار محرك TTS التلقائي — منطق نقي بلا أجهزة. */
class EnginePickerTest {

    @Test
    fun pick_prefersMultiTts_WhenInstalled() {
        val installed = listOf(
            "com.svox.pico",
            "org.nobody.multitts",
            "com.google.android.tts"
        )
        assertEquals("org.nobody.multitts", EnginePicker.pickPreferredEngineFrom(installed))
    }

    @Test
    fun pick_fallsBackToGoogle_WhenNoPreferred() {
        val installed = listOf("com.google.android.tts")
        assertEquals("com.google.android.tts", EnginePicker.pickPreferredEngineFrom(installed))
    }

    @Test
    fun pick_skipsScreenReaders_AsFallback() {
        // فقط قارئ الشاشة + محرك غير مفضّل: يختار المحرك الحقيقي لا القارئ
        val installed = listOf(
            "com.google.android.marvin.talkback",
            "com.random.othertts"
        )
        assertEquals("com.random.othertts", EnginePicker.pickPreferredEngineFrom(installed))
    }

    @Test
    fun pick_fallbackFails_WhenOnlyScreenReaders() {
        val installed = listOf("com.google.android.marvin.talkback")
        // كل المحركات قارئات شاشة: لا اختيار تلقائي (عود null آمن بدل الخروج غلطاً)
        assertNull(EnginePicker.pickPreferredEngineFrom(installed))
    }

    @Test
    fun pick_empty_returnsNull() {
        assertNull(EnginePicker.pickPreferredEngineFrom(emptyList()))
    }

    @Test
    fun pick_duplicatesIgnored() {
        val installed = listOf(
            "com.google.android.tts",
            "com.google.android.tts",
            "org.nobody.multitts"
        )
        assertEquals("org.nobody.multitts", EnginePicker.pickPreferredEngineFrom(installed))
    }

    @Test
    fun pick_prefersInstalledSystemEngine_WhenNoMultiTts() {
        val installed = listOf("com.samsung.SMT", "com.google.android.tts")
        assertEquals("com.google.android.tts", EnginePicker.pickPreferredEngineFrom(installed))
    }

    @Test
    fun screenReaderDetection() {
        assertTrue(EnginePicker.isScreenReader("com.google.android.marvin.talkback"))
        assertTrue(EnginePicker.isScreenReader("com.samsung.accessibility"))
        assertFalse(EnginePicker.isScreenReader("org.nobody.multitts"))
        assertFalse(EnginePicker.isScreenReader("com.google.android.tts"))
    }
}