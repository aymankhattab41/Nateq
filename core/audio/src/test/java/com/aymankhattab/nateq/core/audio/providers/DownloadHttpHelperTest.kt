package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import java.net.URL

class DownloadHttpHelperTest {

    @Test
    fun validateProtocol_acceptsHttps() {
        val url = URL("https://example.com/model.onnx")
        DownloadHttpHelper.validateProtocol(url)
    }

    @Test
    fun validateProtocol_acceptsAllowedCleartextDomain() {
        val url = URL(
            "http://downloads.codefactoryglobal.com/vocalizer/voice.zip"
        )
        DownloadHttpHelper.validateProtocol(url)
    }

    @Test
    fun validateProtocol_rejectsOtherCleartextHttp() {
        val url = URL("http://example.com/voice.zip")
        try {
            DownloadHttpHelper.validateProtocol(url)
            fail("Expected DownloadError for unauthorized cleartext HTTP")
        } catch (e: DownloadError) {
            assertEquals(DownloadError.REDIRECT_BLOCKED, e.code)
        }
    }

    @Test
    fun downloadError_formatsCodeAndDetailsCorrectly() {
        val err = DownloadError(
            code = DownloadError.NOT_A_ZIP,
            details = "Content-Type=text/html"
        )
        assertEquals(DownloadError.NOT_A_ZIP, err.code)
        assertEquals("Content-Type=text/html", err.details)
        assertEquals("[NOT_A_ZIP] Content-Type=text/html", err.message)
    }

    @Test
    fun downloadError_httpHelperMethod() {
        assertEquals("HTTP_404", DownloadError.http(404))
        assertEquals("HTTP_500", DownloadError.http(500))
    }
}
