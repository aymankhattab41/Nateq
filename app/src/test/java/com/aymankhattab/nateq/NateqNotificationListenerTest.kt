package com.aymankhattab.nateq

import android.app.Notification
import android.os.Bundle
import com.aymankhattab.nateq.core.audio.announcement.NateqNotificationListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبار ترتيب سقوط استخراج نص الإشعار: EXTRA_TEXT أولاً ثم نصّ الموسّع
 * (EXTRA_BIG_TEXT للإشعارات متعددة الأسطر مثل واتساب/أميل) ثم أسطر
 * EXTRA_TEXT_LINES — حتى لا تُفقد رسالة طويلة النص (بند 26).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NateqNotificationListenerTest {

    private fun extras(
        text: String? = null,
        bigText: String? = null,
        lines: Array<String>? = null
    ): Bundle = Bundle().apply {
        if (text != null) {
            putCharSequence(Notification.EXTRA_TEXT, text)
        }
        if (bigText != null) {
            putCharSequence(Notification.EXTRA_BIG_TEXT, bigText)
        }
        if (lines != null) {
            putCharSequenceArray(
                Notification.EXTRA_TEXT_LINES, lines
            )
        }
    }

    @Test
    fun bodyText_prefersExtraText() {
        assertEquals(
            "قصيرة",
            NateqNotificationListener.notificationBodyText(
                extras(
                    text = "قصيرة",
                    bigText = "موسّعة",
                    lines = arrayOf("س1", "س2")
                )
            )
        )
    }

    @Test
    fun bodyText_blankText_fallsBackToBigText() {
        val result = NateqNotificationListener.notificationBodyText(
            extras(text = "   ", bigText = "موسّعة")
        )
        assertEquals("موسّعة", result)
    }

    @Test
    fun bodyText_noText_fallsBackToBigText() {
        val result = NateqNotificationListener.notificationBodyText(
            extras(bigText = "المحتوى الكامل للرسالة")
        )
        assertEquals("المحتوى الكامل للرسالة", result)
    }

    @Test
    fun bodyText_noTextOrBig_fallsBackToTextLines() {
        val result = NateqNotificationListener.notificationBodyText(
            extras(lines = arrayOf("أول سطر", "ثاني سطر"))
        )
        assertEquals("أول سطر\nثاني سطر", result)
    }

    @Test
    fun bodyText_none_returnsNull() {
        assertNull(NateqNotificationListener.notificationBodyText(extras()))
    }
}