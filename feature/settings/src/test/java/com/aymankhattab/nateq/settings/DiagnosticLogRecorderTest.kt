package com.aymankhattab.nateq.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/** اختبارات مسجِّل المراقبة الحية للسجل التقني (JVM بلا روبولكترك —
 *  قارئ التدفُّق مُستبدَل بمصدر اصطناعي فلا يُستدعى logcat حقيقي). */
class DiagnosticLogRecorderTest {

    @After
    fun restoreDefaults() {
        // إعادة المصدر الافتراضي حتى لا تتسرَّب اللقطة الاصطناعية
        // إلى اختبارات لاحقة أو لجلسة التطبيق.
        DiagnosticLogRecorder.resetToDefaultProvider()
        DiagnosticLogRecorder.stop()
    }

    private fun installFakeProvider(lines: List<String>) {
        DiagnosticLogRecorder.readerProvider = {
            BufferedReader(StringReader(lines.joinToString("\n")))
        }
    }

    @Test
    fun trimHead_dropsOldestBeyondMax() {
        val list = mutableListOf("a", "b", "c", "d")
        DiagnosticLogRecorder.trimHead(list, 2)
        assertEquals(listOf("c", "d"), list)
    }

    @Test
    fun trimHead_keepsListWhenWithinMax() {
        val list = mutableListOf("a", "b", "c")
        DiagnosticLogRecorder.trimHead(list, 3)
        assertEquals(listOf("a", "b", "c"), list)
    }

    @Test
    fun stop_whenIdle_returnsEmpty() {
        assertEquals(emptyList<String>(), DiagnosticLogRecorder.stop())
        assertFalse(DiagnosticLogRecorder.isRecording())
    }

    @Test
    fun start_thenSecondStartRejected() {
        installFakeProvider(listOf("l1", "l2", "l3"))
        try {
            assertTrue(DiagnosticLogRecorder.start())
            assertTrue(DiagnosticLogRecorder.isRecording())
            // ضغطة «بدء» ثانية أثناء جلسة مراقبة جارية تُرفض تفرداً.
            assertFalse(DiagnosticLogRecorder.start())
        } finally {
            DiagnosticLogRecorder.stop()
        }
    }

    @Test
    fun stop_returnsStartMarker_andResetsSession() {
        installFakeProvider(listOf("l1", "l2"))
        assertTrue(DiagnosticLogRecorder.start())
        val snapshot = DiagnosticLogRecorder.stop()
        // اللقطة الجاذبة تتضمن خط البداية ولو سطراً واحداً ملتقطاً.
        assertTrue(snapshot.isNotEmpty())
        assertTrue(
            "أول سطر يجب أن يكون خط بداية الالتقاط",
            snapshot.first().startsWith("=== بداية التقاط السجل التقني (")
        )
        assertFalse(DiagnosticLogRecorder.isRecording())
        // جلسة جديدة ممكنة مباشرة بعد الإيقاف.
        assertTrue(DiagnosticLogRecorder.start())
        DiagnosticLogRecorder.stop()
    }
}