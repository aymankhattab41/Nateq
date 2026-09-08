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
        // Jieshuo/Talkman قارئ ومحرك معاً — قراء أنه قارئ شاشة لا يُسهم بلغات eSpeak
        // النظرية (af/am…) في الاكتشاف، ويبقى اختياره يدوياً متاحاً
        assertTrue(EnginePicker.isScreenReader("com.nirenr.talkman"))
        assertFalse(EnginePicker.isScreenReader("org.nobody.multitts"))
        assertFalse(EnginePicker.isScreenReader("com.google.android.tts"))
    }

    @Test
    fun pick_skipsJieshuoTalkman_AsFallback() {
        // Talkman قارئ شاشة: محرك الاحتياط يتخطاه ولا يعتمد عليه
        val installed = listOf(
            "com.nirenr.talkman",
            "com.svox.pico"
        )
        assertEquals("com.svox.pico", EnginePicker.pickFallbackEngineFrom(installed, emptySet()))
    }

    // ===== pickFallbackEngineFrom: احتياطي بعد فشل المحرك الأصلي =====

    @Test
    fun fallback_prefersGoogle_WhenInstalledAndNotFailed() {
        val installed = listOf("com.samsung.SMT", "com.google.android.tts", "com.svox.pico")
        assertEquals(
            "com.google.android.tts",
            EnginePicker.pickFallbackEngineFrom(installed, setOf("org.nobody.multitts"))
        )
    }

    @Test
    fun fallback_thirdEngine_WhenGoogleMissing() {
        // سوق بلا خدمة جوجل (الصين مثلاً): يقع على أفضل محرك متبقٍ (سامسونج)
        val installed = listOf("com.samsung.SMT", "com.svox.pico", "com.thirdparty.tts")
        assertEquals(
            "com.samsung.SMT",
            EnginePicker.pickFallbackEngineFrom(installed, setOf("org.nobody.multitts"))
        )
    }

    @Test
    fun fallback_skipsFailedEngine() {
        // المحرك الفاشل نفسه (جوجل هنا) مستبعد — يجب ألا يُعاد
        val installed = listOf("com.google.android.tts", "com.svox.pico")
        assertEquals("com.svox.pico", EnginePicker.pickFallbackEngineFrom(installed, setOf("com.google.android.tts")))
    }

    @Test
    fun fallback_returnsNull_WhenNothingRemains() {
        assertNull(EnginePicker.pickFallbackEngineFrom(listOf("com.google.android.tts"), setOf("com.google.android.tts")))
        assertNull(EnginePicker.pickFallbackEngineFrom(emptyList(), emptySet()))
    }

    @Test
    fun fallback_noPingPong_acrossMultipleFailures() {
        // فشل A ثم B معاً (التراجع التراكمي): لا يُعاد A الأعلى أولويةً — هذا
        // بالضبط ما كان يسبب التأرجح اللانهائي A↔B باستبعادِ المحرك الأخير فقط.
        val installed = listOf("org.nobody.multitts", "com.google.android.tts", "com.svox.pico")
        // فشل MultiTTS (A) ثم جوجل (B): يقع على المحرك الثالث ولا يُعاد A.
        assertEquals(
            "com.svox.pico",
            EnginePicker.pickFallbackEngineFrom(installed, setOf("org.nobody.multitts", "com.google.android.tts"))
        )
        // فشل كل المحركات: توقف كامل بلا عودة للمحرك الأول.
        assertNull(EnginePicker.pickFallbackEngineFrom(installed, setOf("org.nobody.multitts", "com.google.android.tts", "com.svox.pico")))
        // محركان فقط وفشلا معاً: لا شيء يبقى ولا تُعاد إعادة أولوية.
        assertNull(EnginePicker.pickFallbackEngineFrom(listOf("org.nobody.multitts", "com.google.android.tts"), setOf("org.nobody.multitts", "com.google.android.tts")))
    }

    @Test
    fun fallback_skipsScreenReaders_AndUnrealTs() {
        // باقٍ محرك واحد فقط غير قارئ شاشة، يختاره حتى لو لم يكن مفضلاً
        val installed = listOf(
            "com.google.android.marvin.talkback",
            "com.random.othertts"
        )
        assertEquals("com.random.othertts", EnginePicker.pickFallbackEngineFrom(installed, emptySet()))
    }

    @Test
    fun fallback_prefersMultiTts_OverGoogle_AfterAnotherFails() {
        val installed = listOf("org.nobody.multitts", "com.google.android.tts")
        assertEquals("org.nobody.multitts", EnginePicker.pickFallbackEngineFrom(installed, setOf("com.samsung.SMT")))
    }
}