package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ===== الاختيار التلقائي الذكي الصامت =====

    @Test
    fun pickPreferredEngineFrom_prefersRankedEngine() {
        val installed = listOf(
            "com.google.android.tts", "org.nobody.multitts", "com.svox.pico"
        )
        // بالترتيب المفضَّل: MultiTTS قبل pico وقبل Google
        assertEquals(
            "org.nobody.multitts",
            EngineRegistry.pickPreferredEngineFrom(installed)
        )
    }

    @Test
    fun pickPreferredEngineFrom_fallsBackToFirstNonScreenReader() {
        val installed = listOf(
            "com.google.android.marvin.talkback",
            "com.samsung.accessibility",
            "com.acme.engine"
        )
        // قارئات الشاشة مستثناة؛ يقع الاختيار على أول محرك حقيقي
        assertEquals(
            "com.acme.engine", EngineRegistry.pickPreferredEngineFrom(installed)
        )
    }

    @Test
    fun pickPreferredEngineFrom_emptyOrAllScreenReaders_isNull() {
        assertNull(EngineRegistry.pickPreferredEngineFrom(emptyList()))
        assertNull(
            EngineRegistry.pickPreferredEngineFrom(
                listOf("com.google.android.marvin.talkback")
            )
        )
    }

    @Test
    fun pickFallbackEngineFrom_excludesAllFailedEngines() {
        val installed = listOf(
            "org.nobody.multitts", "com.svox.pico", "com.google.android.tts"
        )
        // MultiTTS فشل → الاحتياط يقع على pico (المفضَّل التالي)
        assertEquals(
            "com.svox.pico",
            EngineRegistry.pickFallbackEngineFrom(
                installed, setOf("org.nobody.multitts")
            )
        )
        // فشل MultiTTS و pico معاً → Google ملاذٌ أخير
        assertEquals(
            "com.google.android.tts",
            EngineRegistry.pickFallbackEngineFrom(
                installed, setOf("org.nobody.multitts", "com.svox.pico")
            )
        )
        // كلها فاشلة → null (لا تراجع ممكن)
        assertNull(
            EngineRegistry.pickFallbackEngineFrom(installed, installed.toSet())
        )
    }

    @Test
    fun capableEnginesForLanguage_prefersExplicitChoiceFirst() {
        val capable = listOf(
            "com.google.android.tts", "org.nobody.multitts", "com.acme.special"
        )
        val chain = EngineRegistry.capableEnginesForLanguage(
            capable, preferredForLanguage = "com.acme.special"
        )
        assertEquals("com.acme.special", chain.first())
        // ثم المحركات المفضلة بالترتيب: MultiTTS قبل Google
        assertTrue(
            chain.indexOf("org.nobody.multitts")
                < chain.indexOf("com.google.android.tts")
        )
    }

    @Test
    fun capableEnginesForLanguage_excludesFailedAndScreenReaders() {
        val capable = listOf(
            "com.google.android.marvin.talkback",
            "org.nobody.multitts",
            "com.svox.pico"
        )
        val chain = EngineRegistry.capableEnginesForLanguage(
            capable, excludeFailed = setOf("org.nobody.multitts")
        )
        // قارئ الشاشة مستبعد دائماً، والفاشل مستبعد من المسارين
        assertEquals(listOf("com.svox.pico"), chain)
    }

    @Test
    fun capableEnginesForLanguage_explicitScreenReaderSurvivesInLead() {
        val capable = listOf(
            "com.google.android.marvin.talkback", "com.svox.pico"
        )
        // اختيارٌ صريحٌ لقارئ الشاشة محترَم في صدارة السلسلة
        val chain = EngineRegistry.capableEnginesForLanguage(
            capable,
            preferredForLanguage = "com.google.android.marvin.talkback"
        )
        assertEquals(
            listOf("com.google.android.marvin.talkback", "com.svox.pico"),
            chain
        )
    }
}