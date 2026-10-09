package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadLogTest {

    @Before
    fun setUp() {
        DownloadLog.clear()
    }

    @Test
    fun recordAndGetError_recordsCorrectly() {
        DownloadLog.recordError(
            voiceId = "vits-piper-ar-kareem",
            engineId = "sherpa",
            errorCode = DownloadError.NO_SPACE,
            errorMessage = "المساحة غير كافية"
        )

        val err = DownloadLog.getError("vits-piper-ar-kareem")
        assertNotNull(err)
        assertEquals("vits-piper-ar-kareem", err?.voiceId)
        assertEquals("sherpa", err?.engineId)
        assertEquals(DownloadError.NO_SPACE, err?.errorCode)
        assertEquals("المساحة غير كافية", err?.errorMessage)
    }

    @Test
    fun clearError_removesRecordedEntry() {
        DownloadLog.recordError(
            voiceId = "vocalizer-laila",
            engineId = "vocalizer",
            errorCode = DownloadError.NOT_A_ZIP,
            errorMessage = "ليس ملف ZIP"
        )

        assertNotNull(DownloadLog.getError("vocalizer-laila"))
        DownloadLog.clearError("vocalizer-laila")
        assertNull(DownloadLog.getError("vocalizer-laila"))
    }

    @Test
    fun getAllErrorsForEngine_filtersByEngineId() {
        DownloadLog.recordError(
            voiceId = "voice1",
            engineId = "vocalizer",
            errorCode = DownloadError.TIMEOUT,
            errorMessage = "Timeout 1"
        )
        DownloadLog.recordError(
            voiceId = "voice2",
            engineId = "piper",
            errorCode = DownloadError.NETWORK,
            errorMessage = "Network err"
        )
        DownloadLog.recordError(
            voiceId = "voice3",
            engineId = "vocalizer",
            errorCode = DownloadError.HEADER_MISSING,
            errorMessage = "Header missing"
        )

        val vocErrors = DownloadLog.getAllErrorsForEngine("vocalizer")
        assertEquals(2, vocErrors.size)
        assertTrue(vocErrors.any { it.voiceId == "voice1" })
        assertTrue(vocErrors.any { it.voiceId == "voice3" })

        val piperErrors = DownloadLog.getAllErrorsForEngine("piper")
        assertEquals(1, piperErrors.size)
        assertEquals("voice2", piperErrors.first().voiceId)
    }
}
