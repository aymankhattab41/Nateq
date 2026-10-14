package com.aymankhattab.nateq.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس عقد اختصار الروابط (ع٤١-ب7): يقرأ «رابط النطاق» ويقتطع المسار
 * والترقيم، ويحفظ الرابط كاملاً عند الطلب.
 */
class UrlSpeechTest {

    @Test
    fun shorten_schemeUrl_readsDomainOnly() {
        assertEquals(
            "رابط youtube.com",
            UrlSpeech.shorten("https://www.youtube.com/watch?v=abcdef123")
        )
    }

    @Test
    fun shorten_bareDomainWithPath_readsDomainOnly() {
        assertEquals(
            "رابط example.com",
            UrlSpeech.shorten("example.com/path/to/page")
        )
    }

    @Test
    fun shorten_stripsTrailingPunctuationAndBracket() {
        assertEquals(
            "رابط example.com",
            UrlSpeech.shorten("https://example.com.")
        )
        assertEquals(
            "رابط example.com",
            UrlSpeech.shorten("(https://example.com)")
        )
        assertEquals(
            "رابط example.org",
            UrlSpeech.shorten("www.example.org).")
        )
    }

    @Test
    fun shorten_keepsFullUrlWhenRequested() {
        assertEquals(
            "رابط https://www.youtube.com/watch?v=abcdef123",
            UrlSpeech.shorten(
                "https://www.youtube.com/watch?v=abcdef123",
                keepFullUrl = true
            )
        )
    }

    @Test
    fun shorten_leavesPlainTextUnchanged() {
        assertEquals(
            "نص عادي بدون أي روابط",
            UrlSpeech.shorten("نص عادي بدون أي روابط")
        )
    }

    @Test
    fun shorten_handlesBlankInput() {
        assertEquals("", UrlSpeech.shorten(""))
        assertEquals("", UrlSpeech.shorten(null))
    }

    @Test
    fun findUrls_returnsEachUrlInOrder() {
        val text = "زر https://a.com/x و www.b.org/y وبلا رابط"
        assertEquals(
            listOf("https://a.com/x", "www.b.org/y"),
            UrlSpeech.findUrls(text)
        )
    }

    @Test
    fun findUrls_emptyForPlainText() {
        assertTrue(UrlSpeech.findUrls("لا روابط هنا").isEmpty())
    }
}
