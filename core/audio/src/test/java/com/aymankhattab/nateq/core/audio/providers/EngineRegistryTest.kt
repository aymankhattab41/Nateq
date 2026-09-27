package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات سجلّ المحركات — منطق نقي بلا أجهزة. */
class EngineRegistryTest {

    @Test
    fun isScreenReader_identifiesKnownScreenReaders() {
        assertTrue(
            EngineRegistry.isScreenReader("com.google.android.marvin.talkback")
        )
        assertTrue(
            EngineRegistry.isScreenReader("com.samsung.accessibility")
        )
        assertTrue(
            EngineRegistry.isScreenReader("com.nirenr.talkman")
        )
    }

    @Test
    fun isScreenReader_doesNotFlagNormalTtsEngines() {
        assertFalse(EngineRegistry.isScreenReader("org.nobody.multitts"))
        assertFalse(EngineRegistry.isScreenReader("com.google.android.tts"))
        assertFalse(EngineRegistry.isScreenReader("com.svox.pico"))
        assertFalse(EngineRegistry.isScreenReader("com.samsung.SMT"))
    }
}