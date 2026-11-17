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
 * اختبار استخراج أنصّ وأكمل نص متاح من الإشعار (بند ١٧): يُقارَن
 * EXTRA_TEXT مع BIG_TEXT وTEXT_LINES ومحتوى MessagingStyle، ويُختار
 * الأطول — حتى لا تُقرأ بداية الرسالة المقتطعة فقط.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
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
    fun bodyText_prefersLongestAndMostCompleteCandidate() {
        // بند ١٧: كان short EXTRA_TEXT يُفضَّل على BIG_TEXT الأطول
        // فتُقرأ بداية الرسالة فقط — صار الأطول والأكمل هو المختار.
        assertEquals(
            "هذا هو النص الموسّع الكامل للرسالة",
            NateqNotificationListener.notificationBodyText(
                extras(
                    text = "قصيرة",
                    bigText = "هذا هو النص الموسّع الكامل للرسالة",
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

    @Test
    fun bodyText_messagingStyleContentIsUsedForText() {
        // بند ١٧: كان محتوى MessagingStyle يُستخدم للمرسل فقط، فيُقرأ
        // EXTRA_TEXT المختصر بدل نص الرسالة الكامل داخل النمط.
        val context = org.robolectric.RuntimeEnvironment.application
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val channel = android.app.NotificationChannel(
                "test-channel", "test",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = context.getSystemService(
                android.app.NotificationManager::class.java
            )
            manager?.createNotificationChannel(channel)
        }
        val person = androidx.core.app.Person.Builder()
            .setName("أحمد").build()
        val style = androidx.core.app.NotificationCompat
            .MessagingStyle(person)
            .addMessage("هذه رسالة كاملة من نمط المراسلة", 0L, person)
        val notification = androidx.core.app.NotificationCompat
            .Builder(context, "test-channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("أحمد")
            .setContentText("مقتطع")
            .setStyle(style)
            .build()
        assertEquals(
            "هذه رسالة كاملة من نمط المراسلة",
            NateqNotificationListener.notificationBodyText(notification)
        )
    }
}
