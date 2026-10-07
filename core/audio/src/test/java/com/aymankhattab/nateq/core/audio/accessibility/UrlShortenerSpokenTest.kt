package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlShortenerSpokenTest {

    @Test
    fun `sanitizeUrlsForSpeech replaces long URLs with clean domain`() {
        val url = "https://www.youtube.com/watch?v=abcdef123"
        val input = "شاهد هذا الفيديو: $url"
        val output = UrlShortenerSpoken.sanitizeUrlsForSpeech(input)
        assertEquals("شاهد هذا الفيديو: رابط youtube.com", output)
    }

    @Test
    fun `sanitizeUrlsForSpeech leaves normal text unchanged`() {
        val input = "نص عادي بدون أي روابط"
        val output = UrlShortenerSpoken.sanitizeUrlsForSpeech(input)
        assertEquals(input, output)
    }
}
