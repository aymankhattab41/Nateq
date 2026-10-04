package com.aymankhattab.nateq.core.audio.announcement

import com.aymankhattab.nateq.core.audio.announcement.CallerAnnouncementReceiver.Companion.CALL_STATE_IDLE
import com.aymankhattab.nateq.core.audio.announcement.CallerAnnouncementReceiver.Companion.CALL_STATE_OFFHOOK
import com.aymankhattab.nateq.core.audio.announcement.CallerAnnouncementReceiver.Companion.CALL_STATE_RINGING

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارسُ الأمر 6 — **توحيدُ حياةِ جلسةِ التكرار على حقيقةِ النظام**.
 *
 * **جذرُ البند:** كان الحارسُ يشترط `notificationStillRinging` **بلا
 * تمييزِ مسار**، وهو عَلَمٌ لا يرفعه إلا إشعارُ مكالمة. فمكالمةُ
 * الشبكة — التي لا إشعارَ لها إطلاقاً — كان حارسُها `false` دائماً،
 * فيُمنعُ التكرارُ عنها **من أوّل مرّة**: «عددُ التكرارات» إعدادٌ
 * ميتٌ في المسار الخلويّ وحده، حيٌّ في تطبيقٍ لا يعمل.
 *
 * **والحلُّ توحيدُ المصدر لا إضافةِ شرط:** لكلِّ مسارٍ حقيقتُه في
 * النظام، والمُدخلُ يُقرأ من **قراءةٍ واحدة** لحالة الشبكة فيتناقضُ
 * القراءتين المستقلّتين.
 *
 * | المسار | حقيقةُ الرنين |
 * |---|---|
 * | شبكة (بثّ هاتف) | `callState == RINGING` |
 * | إشعار (VoIP) | الإشعارُ حيّ **و** لم يُجَب |
 */
class RepeatTruthSourceTest {

    /**
     * **الاختبارُ المطلوب في الأمر: «تكرار=3 على مكالمةٍ خلوية بدون
     * إشعار = ثلاثُ نطقات».**
     *
     * **الكسرُ الذي يحرسه:** `notificationStillRinging = false` هنا
     * (لا إشعارَ أصلاً) — ومع ذلك يجب أن يبقى التكرارُ مشروعاً لأن
     * حالةَ الهاتف `RINGING`. كان الحارسُ القديم يُعيد `false`
     * فيُسكَتُ بعد النطق الأول مهما بلغ العددُ المضبوط.
     */
    @Test
    fun `cellular call repeats three times with no notification`() {
        repeat(3) { beat ->
            assertTrue(
                "النغمةُ رقم $beat من التكرار يجب أن تكون مشروعة",
                CallerAnnouncementReceiver.shouldContinueRepeating(
                    isNotificationCall = false,
                    notificationStillRinging = false,
                    callAnnouncedAnswered = false,
                    networkCallState = CALL_STATE_RINGING
                )
            )
        }
    }

    /** الرنينُ الخَلويُّ هو **دليلُ ورودٍ بالإيجاب** — لا يحتاج إشعاراً. */
    @Test
    fun `cellular ringing needs no notification as evidence`() {
        assertTrue(
            CallerAnnouncementReceiver.shouldContinueRepeating(
                isNotificationCall = false,
                notificationStillRinging = false,
                callAnnouncedAnswered = false,
                networkCallState = CALL_STATE_RINGING
            )
        )
    }

    /**
     * **الردُّ يُوقف فوراً:** `OFFHOOK` يعني إجابةَ أو خروجَ المكالمة،
     * فيتوقفُ النطقُ فوق المكالمةِ الجارية.
     */
    @Test
    fun `cellular call stops repeating once answered`() {
        assertFalse(
            CallerAnnouncementReceiver.shouldContinueRepeating(
                isNotificationCall = false,
                notificationStillRinging = true,
                callAnnouncedAnswered = false,
                networkCallState = CALL_STATE_OFFHOOK
            )
        )
    }

    /** انتهاءُ المكالمة (`IDLE`) يُسقط التكرارَ كذلك. */
    @Test
    fun `cellular call stops repeating once idle`() {
        assertFalse(
            CallerAnnouncementReceiver.shouldContinueRepeating(
                isNotificationCall = false,
                notificationStillRinging = true,
                callAnnouncedAnswered = false,
                networkCallState = CALL_STATE_IDLE
            )
        )
    }

    /**
     * **الانحدارُ الذي يجب ألّا يقع:** مكالمةُ إشعارٍ (VoIP) لا حيالَ
     * لها عند النظام — `callState` يبقى `IDLE` طوالها لأنها لا تمرّ
     * ببثّ الهاتف. فحكمُ التكرارِ عليها بالحالة الخلوية كان يُسكتها من
     * أوّل مرّة. الإشعارُ الحيُّ بلا جوابٍ هو حقيقتها وحدَها.
     */
    @Test
    fun `notification call repeats while its notification is live`() {
        repeat(3) { beat ->
            assertTrue(
                "نبضةُ الإشعار $beat يجب أن تكون مشروعة",
                CallerAnnouncementReceiver.shouldContinueRepeating(
                    isNotificationCall = true,
                    notificationStillRinging = true,
                    callAnnouncedAnswered = false,
                    networkCallState = CALL_STATE_IDLE
                )
            )
        }
    }

    /**
     * **حارسُ «لا نطقَ فوق مكالمةٍ جارية»:** الردُّ على مكالمةِ تطبيقٍ
     * يحوّل الإشعارَ إلى «جارية» ولا يحذفه، فيبقى `notificationCallActive`
     * صحيحاً — فلولا [callAnnouncedAnswered] لمتمَّدّ الاسمُ فوق
     * مكالمةِ المستخدم حتى آخر نبضة.
     */
    @Test
    fun `answered notification call stops repeating`() {
        assertFalse(
            CallerAnnouncementReceiver.shouldContinueRepeating(
                isNotificationCall = true,
                notificationStillRinging = true,
                callAnnouncedAnswered = true,
                networkCallState = CALL_STATE_IDLE
            )
        )
    }

    /** حذفُ إشعارِ المكالمة (انتهاؤها) يوقف التكرار. */
    @Test
    fun `dismissed notification call stops repeating`() {
        assertFalse(
            CallerAnnouncementReceiver.shouldContinueRepeating(
                isNotificationCall = true,
                notificationStillRinging = false,
                callAnnouncedAnswered = false,
                networkCallState = CALL_STATE_IDLE
            )
        )
    }

    /**
     * **قراءةٌ واحدة لا قراءتان متعارضتان:** كان الحارسُ القديم يقرأ
     * `callState` مرتين (`isNetworkCallAnswered` في كلِّ نبضة) فيمكن أن
     * تُقرأ RINGING ثم OFFHOOK في نبضةٍ واحدة.
     * و
etworkCallState قيمةٌ واحدةٌ متّسقةٌ فلا يتناقض الحكم.
     */
    @Test
    fun `one state read decides the whole gate`() {
        // الحالةُ الواحدةُ تقطع المسارين: OFFHOOK توقفُ الاثنين.
        assertFalse(
            CallerAnnouncementReceiver.shouldContinueRepeating(
                isNotificationCall = true,
                notificationStillRinging = true,
                callAnnouncedAnswered = false,
                networkCallState = CALL_STATE_OFFHOOK
            )
        )
        assertFalse(
            CallerAnnouncementReceiver.shouldContinueRepeating(
                isNotificationCall = false,
                notificationStillRinging = true,
                callAnnouncedAnswered = false,
                networkCallState = CALL_STATE_OFFHOOK
            )
        )
    }

    /**
     * **ثوابتُ حالةِ الاتصال مُثبَّتةٌ بـ`javap` على `android.jar` (37):**
     * `IDLE = 0` و`RINGING = 1` و`OFFHOOK = 2`. والقيمُ مستنسخةٌ في
     * هذا الملف لا مُحيلةً إلى `TelephonyManager` — وهي `int` فيُضمَّن
     * مقدارُها وقتَ التصريف فلا يحتاج الاختبارُ بيئةَ أندرويد.
     */
    @Test
    fun `platform telephony call state constants are mirrored`() {
        assertEquals(0, CALL_STATE_IDLE)
        assertEquals(1, CALL_STATE_RINGING)
        assertEquals(2, CALL_STATE_OFFHOOK)
    }
}
