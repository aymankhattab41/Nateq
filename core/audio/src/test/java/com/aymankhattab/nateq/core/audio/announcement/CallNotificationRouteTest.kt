package com.aymankhattab.nateq.core.audio.announcement

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات قرار مسار إشعار المكالمة —
 * [CallerAnnouncementReceiver.decideCallNotificationRoute].
 *
 * **ما تحرسه:** مصدرُ النطقِ الواحد. قبل هذا العقد كان لمكالمةِ الشبكة
 * مصدران — بثُّ `PHONE_STATE` **و**إشعارُ مُشغِّلِ الهاتف — فيتكرّر النطق.
 * فصار للحزمةِ الافتراضيةِ مسارٌ لا غيره: `PHONE_STATE` ينطق، وإشعارُها
 * ينشرُ الهويةَ في [RingCallerIdentity] فقط.
 *
 * **والموضعُ ميتاً من الذاكرة عمداً:** معاملاتُ الدالة كلُّها مشتقّةٌ من
 * النظام (حالةُ الخطّ، نتيجةُ إثباتِ الورود). فلا يمرُّ إليها عَلَمٌ في
 * الذاكرة قد يضيع بموتِ العملية — وهو ما يجعل
 * [network call notification after process death is not announced] حارساً
 * حقيقياً لا اختباراً شكلياً.
 */
class CallNotificationRouteTest {

    // ===== بند 4: حزمةُ مُشغِّلِ الهاتف الافتراضي =====

    @Test
    fun `default dialer is recognised only on exact package match`() {
        assertTrue(
            "الحزمةُ الافتراضيةُ تُعرف",
            CallerAnnouncementReceiver.isDefaultDialerPackage(
                "com.google.android.dialer", "com.google.android.dialer"
            )
        )
        assertFalse(
            "حزمةٌ أخرى ليست الافتراضية",
            CallerAnnouncementReceiver.isDefaultDialerPackage(
                "com.whatsapp", "com.google.android.dialer"
            )
        )
        assertFalse(
            "لا حزمةَ افتراضيةً على جهازٍ بلا هاتف",
            CallerAnnouncementReceiver.isDefaultDialerPackage(
                "com.whatsapp", null
            )
        )
        assertFalse(
            "لا اسمَ حزمةٍ عندنا فلا يُقارَن",
            CallerAnnouncementReceiver.isDefaultDialerPackage(
                null, "com.google.android.dialer"
            )
        )
    }

    @Test
    fun `dialer ringing publishes identity only and never announces`() {
        // **لا `ANNOUNCE` أبداً لحزمةِ الافتراضي** — مصدرُ النطق فيها
        // `PHONE_STATE` وحده، فإعلانُها من الإشعار يضاعفُ النطق.
        assertEquals(
            CallerAnnouncementReceiver.CallNotificationRoute
                .PUBLISH_IDENTITY_ONLY,
            CallerAnnouncementReceiver.decideCallNotificationRoute(
                isDefaultDialer = true,
                telephonyCallState = TelephonyManager.CALL_STATE_RINGING,
                positiveIncoming = true
            )
        )
    }

    @Test
    fun `dialer notification outside ringing is silent`() {
        // بعد انتهاء الرنين: إشعارُ «انتهت المكالمة» أو التحديثُ الجاري
        // من الافتراضي صامتٌ كلّياً — لا هويةَ تُنشر ولا نطق.
        assertEquals(
            CallerAnnouncementReceiver.CallNotificationRoute.SILENT,
            CallerAnnouncementReceiver.decideCallNotificationRoute(
                isDefaultDialer = true,
                telephonyCallState = TelephonyManager.CALL_STATE_IDLE,
                positiveIncoming = true
            )
        )
        assertEquals(
            CallerAnnouncementReceiver.CallNotificationRoute.SILENT,
            CallerAnnouncementReceiver.decideCallNotificationRoute(
                isDefaultDialer = true,
                telephonyCallState = TelephonyManager.CALL_STATE_OFFHOOK,
                positiveIncoming = false
            )
        )
    }

    // ===== بند 1: التطبيقاتُ غير الافتراضية تُعلَن بالإثبات =====

    @Test
    fun `voip app with positive evidence is announced`() {
        // واتساب وتلجرام وجوجل ميت: الدليلُ إيجابيٌّ فيُعلَن.
        assertEquals(
            CallerAnnouncementReceiver.CallNotificationRoute.ANNOUNCE,
            CallerAnnouncementReceiver.decideCallNotificationRoute(
                isDefaultDialer = false,
                telephonyCallState = TelephonyManager.CALL_STATE_IDLE,
                positiveIncoming = true
            )
        )
    }

    @Test
    fun `voip app without positive evidence is silent`() {
        assertEquals(
            CallerAnnouncementReceiver.CallNotificationRoute.SILENT,
            CallerAnnouncementReceiver.decideCallNotificationRoute(
                isDefaultDialer = false,
                telephonyCallState = TelephonyManager.CALL_STATE_OFFHOOK,
                positiveIncoming = false
            )
        )
    }

    // ===== بند 5: موتُ العملية بين OFFHOOK والإشعار =====

    @Test
    fun `network call notification after process death is not announced`() {
        // **السيناريو:** تُموت العمليةُ بعد وصول `OFFHOOK` (مكالمةٌ صادرة)
        // وقبل وصول إشعارِ مُشغِّلِ الهاتف. فتفقد الذاكرةُ عَلَمَ
        // «مكالمةٌ صادرة» فلا يبقى في الذاكرة ما يمنع النطق — ويبقى
        // وحدَه ما يُنقذ: قراءةُ `callState` من النظام مباشرةً (مشغولٌ)
        // فتبقى صامتةً. **ولو اعتمدنا العَلَمَ في الذاكرة لنطقنا الصادرة
        // واردةً بعد كلِّ موتي.**
        assertEquals(
            CallerAnnouncementReceiver.CallNotificationRoute.SILENT,
            CallerAnnouncementReceiver.decideCallNotificationRoute(
                isDefaultDialer = false,
                telephonyCallState = TelephonyManager.CALL_STATE_OFFHOOK,
                positiveIncoming = false
            )
        )
    }

    @Test
    fun `dialer notification after process death defers to phone state`() {
        // بعد موتي في وسط مكالمةٍ قائمة: الافتراضيُّ لا يُعلَن، وإن كان
        // الخطُّ يرنّ فإنما ينشرُ الهويةَ و`PHONE_STATE` هو الناطِق.
        assertEquals(
            CallerAnnouncementReceiver.CallNotificationRoute.SILENT,
            CallerAnnouncementReceiver.decideCallNotificationRoute(
                isDefaultDialer = true,
                telephonyCallState = TelephonyManager.CALL_STATE_OFFHOOK,
                positiveIncoming = false
            )
        )
    }

    // ===== بند 5: تحديثُ مكالمةٍ جاريةٍ بعد 60 ثانية =====

    @Test
    fun `sixty second ongoing update from a voip app is silent`() {
        // التحديثُ المتأخّرُ لتطبيقٍ مُتصل: الخطُّ مشغولٌ ولا دليلَ
        // إيجابياً — فلا نطق. (الهويةُ وحدَها لا تكفي: بلغَنا اسمُ
        // المتصل من إشعارٍ سابقٍ لا دليلَ على أنّه رنّ الآن.)
        assertEquals(
            CallerAnnouncementReceiver.CallNotificationRoute.SILENT,
            CallerAnnouncementReceiver.decideCallNotificationRoute(
                isDefaultDialer = false,
                telephonyCallState = TelephonyManager.CALL_STATE_OFFHOOK,
                positiveIncoming = false
            )
        )
    }

    // ===== لا ازدواج: الافتراضيُّ لا ينطقُ ولا يُكتَبُ مرّتين =====

    @Test
    fun `dialer never reaches the announce route`() {
        // حارسُ ازدواجِ المصدرين: مهما كانت الأدلّةُ فإنّ حزمةَ
        // الافتراضي لا تُخرج `ANNOUNCE` أبداً.
        val states = listOf(
            TelephonyManager.CALL_STATE_IDLE,
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK
        )
        for (state in states) {
            for (positive in listOf(true, false)) {
                val route =
                    CallerAnnouncementReceiver.decideCallNotificationRoute(
                        isDefaultDialer = true,
                        telephonyCallState = state,
                        positiveIncoming = positive
                    )
                assertFalse(
                    "حزمةُ الافتراضي لا تُعلَن" +
                        " (state=$state, positive=$positive)",
                    route == CallerAnnouncementReceiver.CallNotificationRoute
                        .ANNOUNCE
                )
            }
        }
    }
}
