package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** اختبارات توجيه المحرك/الصوت لكل طلب — منطق نقي بلا أجهزة. */
class LanguageSpeechRouterTest {

    @Test
    fun convertEngine_wins_whenMatch() {
        val routed = LanguageSpeechRouter.route(
            matchesRequest = true,
            convertEngine = "com.google.android.tts",
            convertVoiceName = "goog-ar-eg",
            perLanguageEngine = "org.nobody.multitts",
            perLanguageVoiceName = "multitts-ar"
        )
        assertEquals("com.google.android.tts", routed.engine)
        assertEquals("goog-ar-eg", routed.voiceName)
    }

    @Test
    fun perLanguageEngine_used_whenNoConvertMatch() {
        // التحويل يريد لغةً غير لغة النص → يبقى تفضيل لغة الطلب سارياً
        val routed = LanguageSpeechRouter.route(
            matchesRequest = false,
            convertEngine = "com.google.android.tts",
            convertVoiceName = "goog-ar-eg",
            perLanguageEngine = "org.nobody.multitts",
            perLanguageVoiceName = "multitts-en"
        )
        assertEquals("org.nobody.multitts", routed.engine)
        assertEquals("multitts-en", routed.voiceName)
    }

    @Test
    fun perLanguageEngine_used_whenAutoConvertOff() {
        // التحويل معطّل (محرك/صوت null) → تفضيل اللغة يسد الفجوة
        val routed = LanguageSpeechRouter.route(
            matchesRequest = true,
            convertEngine = null,
            convertVoiceName = null,
            perLanguageEngine = "org.nobody.multitts",
            perLanguageVoiceName = "multitts-ar"
        )
        assertEquals("org.nobody.multitts", routed.engine)
        assertEquals("multitts-ar", routed.voiceName)
    }

    @Test
    fun nullsFlow_whenNothingConfigured() {
        val routed = LanguageSpeechRouter.route(
            matchesRequest = true,
            convertEngine = null,
            convertVoiceName = null,
            perLanguageEngine = null,
            perLanguageVoiceName = null
        )
        assertNull(routed.engine)
        assertNull(routed.voiceName)
    }

    @Test
    fun convertEngine_withoutVoice_usesPerLanguageVoice() {
        val routed = LanguageSpeechRouter.route(
            matchesRequest = true,
            convertEngine = "com.google.android.tts",
            convertVoiceName = null,
            perLanguageEngine = "org.nobody.multitts",
            perLanguageVoiceName = "multitts-ar"
        )
        assertEquals("com.google.android.tts", routed.engine)
        assertEquals("multitts-ar", routed.voiceName)
    }
}