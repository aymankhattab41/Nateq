package com.aymankhattab.nateq.core.audio.providers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EngineLanguageSupportTest {

    @Before
    fun setUp() {
        EngineLanguageSupport.clear()
    }

    @After
    fun tearDown() {
        EngineLanguageSupport.clear()
    }

    @Test
    fun isLanguageSupported_returnsTrueByDefault() {
        assertTrue(
            EngineLanguageSupport.isLanguageSupported(
                "com.samsung.SMT", "ar"
            )
        )
        assertTrue(
            EngineLanguageSupport.isLanguageSupported(
                "org.nobody.multitts", "en"
            )
        )
    }

    @Test
    fun recordUnsupported_marksLanguageAsUnsupported() {
        EngineLanguageSupport.recordUnsupported("com.samsung.SMT", "ar")

        assertFalse(
            EngineLanguageSupport.isLanguageSupported(
                "com.samsung.SMT", "ar"
            )
        )
        // أشكال مختلفة من كود اللغة (مع الإقليم أو بحروف كبيرة)
        assertFalse(
            EngineLanguageSupport.isLanguageSupported(
                "com.samsung.SMT", "ar-SA"
            )
        )
        assertFalse(
            EngineLanguageSupport.isLanguageSupported(
                "com.samsung.SMT", "AR"
            )
        )

        // اللغات الأخرى للمحرك نفسه تبقى مدعومة
        assertTrue(
            EngineLanguageSupport.isLanguageSupported(
                "com.samsung.SMT", "en"
            )
        )

        // محركات أخرى لنفس اللغة تبقى مدعومة
        assertTrue(
            EngineLanguageSupport.isLanguageSupported(
                "org.nobody.multitts", "ar"
            )
        )
    }

    @Test
    fun unsupportedLanguagesFor_returnsRecordedLanguages() {
        EngineLanguageSupport.recordUnsupported("com.samsung.SMT", "ar")
        EngineLanguageSupport.recordUnsupported("com.samsung.SMT", "fa")

        val set = EngineLanguageSupport.unsupportedLanguagesFor(
            "com.samsung.SMT"
        )
        assertEquals(setOf("ar", "fa"), set)
    }

    @Test
    fun clear_removesAllRecordedUnsupportedLanguages() {
        EngineLanguageSupport.recordUnsupported("com.samsung.SMT", "ar")
        EngineLanguageSupport.clear()

        assertTrue(
            EngineLanguageSupport.isLanguageSupported(
                "com.samsung.SMT", "ar"
            )
        )
    }
}
