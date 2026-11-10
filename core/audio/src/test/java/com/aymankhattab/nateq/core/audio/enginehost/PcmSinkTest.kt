package com.aymankhattab.nateq.core.audio.enginehost

import android.os.SystemClock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارس [PcmSink] — أنبوب المضيف.
 *
 * العقود المحروسة:
 * 1. **إطار نهاية واحد**: العميل يتوقّف عند أول إطار نهاية، فكتابة
 *    ثانٍ تُخفى نتيجتها الحقيقية عن المُستدعي.
 * 2. **نبضة تقدّم**: إطار فارغ يُقرأ ولا يُفسَّر صوتاً، ولا يُكتب قبل
 *    الترويسة.
 * 3. **نافذة النداء الأصلي**: تنتهي بسقفها فلا تُخفي تعليقاً حقيقياً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PcmSinkTest {

    private fun sinkInto(
        out: ByteArrayOutputStream
    ): PcmSink = PcmSink(DataOutputStream(out))

    @Test
    fun end_isWrittenOnceAndKeepsTheFirstStatus() {
        val out = ByteArrayOutputStream()
        val sink = sinkInto(out)
        sink.writeHeader(22050, 1)
        sink.end(HostProtocol.STATUS_OK, "OK", "")
        sink.end(HostProtocol.STATUS_ERROR, "SECOND", "خطأ ثانٍ")

        val input = DataInputStream(
            ByteArrayInputStream(out.toByteArray())
        )
        assertTrue(
            HostProtocol.readFrame(input) is HostProtocol.HostFrame.Format
        )
        val end = HostProtocol.readFrame(input) as HostProtocol.HostFrame.End
        assertEquals(HostProtocol.STATUS_OK, end.status)
        assertNull("لا إطار نهاية ثانٍ", HostProtocol.readFrame(input))
    }

    @Test
    fun ping_isAnEmptyFrameEvenBeforeHeader() {
        val out = ByteArrayOutputStream()
        val sink = sinkInto(out)
        // الطلب المنتظر في الطابور ليس خمولاً: تُرسَل النبضة قبل
        // الترويسة، والعميل يعالجها مستقلةً عن الصيغة.
        sink.ping()
        sink.writeHeader(22050, 1)
        sink.ping()

        val input = DataInputStream(
            ByteArrayInputStream(out.toByteArray())
        )
        assertEquals(
            HostProtocol.HostFrame.Ping,
            HostProtocol.readFrame(input)
        )
        val format = HostProtocol.readFrame(input)
        assertTrue(format is HostProtocol.HostFrame.Format)
        assertEquals(
            HostProtocol.HostFrame.Ping,
            HostProtocol.readFrame(input)
        )
    }

    @Test
    fun pcm_beforeHeaderIsRejected() {
        val sink = sinkInto(ByteArrayOutputStream())
        try {
            sink.writePcm(byteArrayOf(1, 2), 2)
            fail("PCM قبل الترويسة يجب أن يُرفض")
        } catch (expected: IOException) {
            // مطلوب: لا يصل صوت بلا صيغة.
        }
    }

    @Test
    fun nativeWindow_expiresAtItsCap() {
        val sink = sinkInto(ByteArrayOutputStream())
        sink.enterNativeCall(1_000L)
        val started = SystemClock.uptimeMillis()
        assertTrue(sink.nativeWindowOpen(started))
        assertFalse(
            "النافذة تنتهي بسقفها",
            sink.nativeWindowOpen(started + 5_000L)
        )
        sink.exitNativeCall()
        assertFalse(sink.nativeWindowOpen(started))
    }
}
