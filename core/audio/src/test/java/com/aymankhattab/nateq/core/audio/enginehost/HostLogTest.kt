package com.aymankhattab.nateq.core.audio.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * اختبارات وحدة لـ [HostLog] وحلقة السجلات (ع٣٠-2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostLogTest {

    @Before
    fun setUp() {
        HostLog.clear()
    }

    @Test
    fun ringBufferMaintainsCapacity() {
        val engine = "test_engine"
        for (i in 1..250) {
            HostLog.i(engine, "Message $i")
        }
        val log = HostLog.getLog(engine)
        val lines = log.lines()
        assertEquals(HostLog.BUFFER_CAPACITY, lines.size)
        // أقدم سطر يجب أن يكون 51، والأحدث 250
        assertTrue(lines.first().contains("Message 51"))
        assertTrue(lines.last().contains("Message 250"))
    }

    @Test
    fun getLogRespectsMaxLines() {
        val engine = "test_engine"
        for (i in 1..10) {
            HostLog.d(engine, "Line $i")
        }
        val log = HostLog.getLog(engine, maxLines = 3)
        val lines = log.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("Line 8"))
        assertTrue(lines[1].contains("Line 9"))
        assertTrue(lines[2].contains("Line 10"))
    }

    @Test
    fun enginesAreIsolated() {
        HostLog.i("engine_a", "Alpha")
        HostLog.i("engine_b", "Beta")

        val logA = HostLog.getLog("engine_a")
        val logB = HostLog.getLog("engine_b")

        assertTrue(logA.contains("Alpha"))
        assertFalse(logA.contains("Beta"))
        assertTrue(logB.contains("Beta"))
        assertFalse(logB.contains("Alpha"))
    }

    @Test
    fun logInitDetailsFormatsInfo() {
        val engine = "vocalizer"
        val tempDir = File.createTempFile("hostlog_test", "")
        tempDir.delete()
        tempDir.mkdirs()
        try {
            File(tempDir, "voice.hdr").writeText("hdr")
            File(tempDir, "voice.dat").writeText("dat")

            HostLog.logInitDetails(
                engine,
                tempDir.absolutePath,
                mapOf("mNativeEnginePtr" to "0x1234")
            )

            val log = HostLog.getLog(engine)
            assertTrue(log.contains("بدء تهيئة vocalizer"))
            assertTrue(log.contains("hdr=1"))
            assertTrue(log.contains("dat=1"))
            assertTrue(log.contains("0x1234"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
