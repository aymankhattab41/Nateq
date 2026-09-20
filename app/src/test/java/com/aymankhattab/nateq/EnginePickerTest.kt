package com.aymankhattab.nateq

import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات تصنيف محركات TTS — منطق نقي بلا أجهزة. */
class EnginePickerTest {

    @Test
    fun screenReaderDetection_identifiesKnownScreenReaders() {
        assertTrue(
            EnginePicker.isScreenReader("com.google.android.marvin.talkback")
        )
        assertTrue(EnginePicker.isScreenReader("com.samsung.accessibility"))
        assertTrue(EnginePicker.isScreenReader("com.nirenr.talkman"))
    }

    @Test
    fun screenReaderDetection_doesNotFlagNormalTtsEngines() {
        assertFalse(EnginePicker.isScreenReader("org.nobody.multitts"))
        assertFalse(EnginePicker.isScreenReader("com.google.android.tts"))
        assertFalse(EnginePicker.isScreenReader("com.svox.pico"))
        assertFalse(EnginePicker.isScreenReader("com.samsung.SMT"))
    }
}