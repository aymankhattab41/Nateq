package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات سجلّ المحركات وسلاسل اللغة — منطق نقي بلا أجهزة. */
class EngineRegistryTest {

    @Test
    fun chain_prefersExplicitLanguageEngine_First() {
        val chain = EngineRegistry.capableEnginesForLanguage(
            capable = listOf(
                "com.google.android.tts",
                "org.nobody.multitts"
            ),
            preferredForLanguage = "com.google.android.tts"
        )
        assertEquals(
            listOf(
                "com.google.android.tts",
                "org.nobody.multitts"
            ),
            chain
        )
    }

    @Test
    fun chain_respectsSoftPreferredOrder() {
        val chain = EngineRegistry.capableEnginesForLanguage(
            capable = listOf(
                "com.samsung.SMT",
                "com.svox.pico",
                "com.google.android.tts",
                "org.nobody.multitts"
            )
        )
        assertEquals(
            listOf(
                "org.nobody.multitts",
                "com.svox.pico",
                "com.samsung.SMT",
                "com.google.android.tts"
            ),
            chain
        )
    }

    @Test
    fun chain_prefersPeripheralEnginesBeforeNativeAndGoogle() {
        // السلسلة المرنة (المحور الثالث): الطرفية (MultiTTS/eSpeak) تسبق
        // النظامية (AOSP/Huawei Celia) فمصنّع سامسونج، وجوجل آخر الملاذات.
        val chain = EngineRegistry.capableEnginesForLanguage(
            capable = listOf(
                "com.google.android.tts",
                "com.svox.pico",
                "com.huawei.tts",
                "com.reecenetworks.espeak",
                "com.samsung.SMT",
                "org.nobody.multitts"
            )
        )
        assertEquals(
            listOf(
                "org.nobody.multitts",
                "com.reecenetworks.espeak",
                "com.svox.pico",
                "com.huawei.tts",
                "com.samsung.SMT",
                "com.google.android.tts"
            ),
            chain
        )
    }

    @Test
    fun chain_googleIsLastResort_WhenPeripheralMissing() {
        // غياب الطرفية: النظامية (pico) تسبق سامسونج، وجوجل يبقى الأخير.
        val chain = EngineRegistry.capableEnginesForLanguage(
            capable = listOf(
                "com.google.android.tts",
                "com.samsung.SMT",
                "com.svox.pico"
            )
        )
        assertEquals(
            listOf(
                "com.svox.pico",
                "com.samsung.SMT",
                "com.google.android.tts"
            ),
            chain
        )
    }

    @Test
    fun chain_keepsExplicitScreenReader_WhenChosenManually() {
        // المستخدم اختار قارئ الشاشة صراحةً للغة: يُحترم اختياره ويكون
        // أول السلسلة، لكن يبقى بعده محرك نطق حقيقي كمسار تراجع آمن.
        val chain = EngineRegistry.capableEnginesForLanguage(
            capable = listOf(
                "com.google.android.marvin.talkback",
                "com.google.android.tts"
            ),
            preferredForLanguage = "com.google.android.marvin.talkback"
        )
        assertEquals(
            listOf(
                "com.google.android.marvin.talkback",
                "com.google.android.tts"
            ),
            chain
        )
    }

    @Test
    fun chain_excludesScreenReaders_FromAutoFallback() {
        val chain = EngineRegistry.capableEnginesForLanguage(
            capable = listOf(
                "com.google.android.marvin.talkback",
                "com.samsung.accessibility",
                "com.random.othertts"
            )
        )
        assertEquals(listOf("com.random.othertts"), chain)
    }

    @Test
    fun chain_emptyCapable_returnsEmpty() {
        assertTrue(
            EngineRegistry.capableEnginesForLanguage(emptyList()).isEmpty()
        )
    }

    @Test
    fun chain_removesFailedEngines_FromAllSlots() {
        val chain = EngineRegistry.capableEnginesForLanguage(
            capable = listOf(
                "com.google.android.tts",
                "org.nobody.multitts"
            ),
            preferredForLanguage = "org.nobody.multitts",
            excludeFailed = setOf("org.nobody.multitts")
        )
        assertEquals(listOf("com.google.android.tts"), chain)
    }

    @Test
    fun chain_sortsRemainder_Alphabetically_IfNoPreferred() {
        val chain = EngineRegistry.capableEnginesForLanguage(
            capable = listOf(
                "com.zzz.engine",
                "com.aaa.engine",
                "com.google.android.tts"
            )
        )
        assertEquals(
            listOf(
                "com.google.android.tts",
                "com.aaa.engine",
                "com.zzz.engine"
            ),
            chain
        )
    }

    @Test
    fun chain_withoutPreferred_screenReadersCapable_onlyEmpty() {
        // القادرون كلهم قارئات شاشة (بلا اختيار صريح) → سلسلة فارغة
        assertTrue(
            EngineRegistry.capableEnginesForLanguage(
                listOf("com.samsung.accessibility")
            ).isEmpty()
        )
    }
}