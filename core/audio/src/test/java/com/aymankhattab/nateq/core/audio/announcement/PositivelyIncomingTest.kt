package com.aymankhattab.nateq.core.audio.announcement

import android.app.Notification
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات عقد «الإثبات بالوارد» —
 * [CallerAnnouncementReceiver.isPositivelyIncoming].
 *
 * **الدالةُ خالصةٌ بلا `Context`** فتُختبر بلا Robolectric: الدليلُ يُمرَّر
 * مفكوكاً — نوعُ المكالمة، ووجودُ `fullScreenIntent`، ووجودُ إجراءِ ردّ،
 * وحالةُ الخطّ، ونصُّ العبارة، ومستوى النظام.
 *
 * **وتصحيحُ مرجعية المنصّة:** ثوابتُ `Notification.CallStyle` الفعليةُ
 * أربعةٌ فقط لا غير: `UNKNOWN=0` و`INCOMING=1` و`ONGOING=2` و`SCREENING=3`.
 * **فلا وجودَ لـ`CALL_TYPE_OUTGOING` أصلاً** — وهو ما كشفه فشلُ التصريف
 * عند أوّل محاولة. والمقصودُ في الأمر بـ«الصادر» هو `CALL_TYPE_ONGOING`:
 * مكالمةٌ قائمةٌ لا رنّةٌ جديدة، وهو أخطرُ من الصادرة نفسِها لأنّ تحديثَ
 * إشعار مكالمةٍ جاريةٍ يقع في معناه. وكذلك لا وجودَ لـ
 * `SEMANTIC_ACTION_ANSWER` فدليلُ الردّ هو `SEMANTIC_ACTION_CALL`.
 * ونستثني انحرافَ نسخنا عن المنصّة بحارس
 * [platform call type constants are mirrored].
 */
class PositivelyIncomingTest {

    // ===== انحرافُ المنصّة: نسخُنا يطابقُ android.jar =====

    @Test
    fun `platform call type constants are mirrored`() {
        // نُنسخُ قيمَ الثوابت في كودنا بلا `hide` لأنها من نوع `int`
        // فتُضمَّن وقتَ التصريف، فيبقى الحارسُ آمناً على الأجهزة القديمة.
        assertEquals(
            Notification.CallStyle.CALL_TYPE_UNKNOWN,
            CallerAnnouncementReceiver.CALL_TYPE_UNKNOWN
        )
        assertEquals(
            Notification.CallStyle.CALL_TYPE_INCOMING,
            CallerAnnouncementReceiver.CALL_TYPE_INCOMING
        )
        assertEquals(
            Notification.CallStyle.CALL_TYPE_ONGOING,
            CallerAnnouncementReceiver.CALL_TYPE_ONGOING
        )
        assertEquals(
            Notification.CallStyle.CALL_TYPE_SCREENING,
            CallerAnnouncementReceiver.CALL_TYPE_SCREENING
        )
    }

    // ===== بند 1: صادر خلوي — نوعُ المكالمةُ يُرفض فوراً =====

    @Test
    fun `outgoing cellular call type is immediately rejected`() {
        // الدليلُ السلبيُّ الصريح يُقدَّم على كلِّ دليلٍ إيجابيٍّ آخر.
        assertFalse(
            "نوعُ المكالمةُ القائم يجب أن يُرفض فوراً",
            positive(callType = CallerAnnouncementReceiver.CALL_TYPE_ONGOING)
        )
    }

    @Test
    fun `screening call type is immediately rejected`() {
        // تطبيقُ الفرز يبلّغ بنتيجة فحصه لا برنّةٍ واردة.
        assertFalse(
            "نوعُ الفرز ليس رنّةً واردة",
            positive(
                callType = CallerAnnouncementReceiver.CALL_TYPE_SCREENING
            )
        )
    }

    // ===== بند 1: صادر واتساب — غيابُ الدليل = لا نطق =====

    @Test
    fun `outgoing voip call without any positive evidence is rejected`() {
        // واتساب صادر: بلا نوعِ مكالمة، وبلا نافذةِ ملءِ الشاشة، وبلا
        // زرِّ ردّ، والخطُّ مشغول — فلا دليلَ على ورودٍ أصلاً.
        assertFalse(
            "مكالمة واتساب صادرة: غيابُ الدليل = مرفوض",
            positive(
                callState = TelephonyManager.CALL_STATE_OFFHOOK
            )
        )
    }

    // ===== بند 1: وارد خلوي — الرنينُ دليلٌ قاطع =====

    @Test
    fun `cellular incoming call accepted via ringing call state`() {
        // تطبيقُ الهاتف على الأجهزة القديمة لا يضع نوعَ مكالمة، لكنّ
        // رنينَ الخطّ لحظةَ وصول الإشعار دليلٌ قاطعٌ على الورود.
        assertTrue(
            "رنينُ الخطّ دليلٌ إيجابيٌّ على الورود",
            positive(
                callState = TelephonyManager.CALL_STATE_RINGING
            )
        )
    }

    @Test
    fun `cellular incoming with CALL_TYPE_INCOMING accepted`() {
        // من الإصدار 31 فصاعداً: أقوى دليلٍ ممكن.
        assertTrue(
            "نوعُ المكالمةُ الوارد دليلٌ قاطع",
            positive(
                callType = CallerAnnouncementReceiver.CALL_TYPE_INCOMING
            )
        )
    }

    // ===== بند 1: وارد واتساب وتلجرام وجوجل ميت =====

    @Test
    fun `voip incoming accepted via fullScreenIntent evidence`() {
        // واتساب وتلجرام يضعان نافذةَ ملءِ الشاشة على إشعار المكالمة
        // الواردة لإظهار واجهة الاتصال فوق قفل الشاشة — دليلٌ موثوق.
        assertTrue(
            "نافذةُ ملءِ الشاشة دليلُ ورود",
            positive(fullScreenIntent = true)
        )
    }

    @Test
    fun `voip incoming accepted via answer semantic action`() {
        // لا يوجد زرُّ ردٍّ على مكالمةٍ أنتَ من بدأتها.
        assertTrue(
            "إجراءُ الردّ دليلُ ورود",
            positive(answerAction = true)
        )
    }

    @Test
    fun `google meet ongoing incoming accepted via fullScreenIntent`() {
        // جوجل ميت يُعلِّم إشعارَه جارياً من لحظة الرنّ، ومع ذلك يوضع
        // عليه نافذةُ ملءِ الشاشة فيثبُت الورودُ بالدليل لا بغياب العَلَم.
        assertTrue(
            "Meet الوارد: نافذةُ ملءِ الشاشة تُثبت الورود",
            positive(fullScreenIntent = true)
        )
    }

    @Test
    fun `telegram incoming with answer action accepted`() {
        assertTrue(
            "تلجرام وارد: إجراءُ الردّ",
            positive(answerAction = true)
        )
    }

    // ===== بند 5: تحديثُ إشعار مكالمةٍ جاريةٍ بعد 60 ثانية =====

    @Test
    fun `ongoing call update after 60 seconds is not announced`() {
        // بعد ستّين ثانية من بدء المكالمة: الخطُّ مشغول، ولا نوعَ
        // مكالمة، ولا نافذةَ ملءِ شاشة، ولا زرَّ ردّ — فليس هذا رنّةً.
        assertFalse(
            "تحديثُ إشعار مكالمةٍ جاريةٍ لا يُعلَن",
            positive(
                callState = TelephonyManager.CALL_STATE_OFFHOOK
            )
        )
    }

    @Test
    fun `explicit ongoing call type is rejected even while ringing`() {
        // تطبيقٌ يضع نوعَ المكالمةِ جارياً صراحةً أثناء رنين مكالمةٍ
        // أخرى: النصريحُ السلبيُّ يسودُ على كلِّ ما عداه.
        assertFalse(
            "نوعُ المكالمةِ الجاري يُسود حتى مع الرنين",
            CallerAnnouncementReceiver.isPositivelyIncoming(
                callTypeExtra = CallerAnnouncementReceiver.CALL_TYPE_ONGOING,
                hasFullScreenIntent = true,
                hasAnswerAction = true,
                telephonyCallState = TelephonyManager.CALL_STATE_RINGING,
                sdkInt = 37,
                legacyIncomingPhrase = true
            )
        )
    }

    // ===== بند 2: غيابُ كلِّ الأدلّة = لا نطق =====

    @Test
    fun `no positive evidence means no announcement`() {
        // **انعكاسُ المنطق القديم:** كان السكوتُ عند دليلٍ سلبيٍّ فقط،
        // فغيابُ الدليل كان يُجيزُ النطق. وصار العكس: لا دليل = صمت.
        assertFalse(
            "لا دليلَ إيجابياً = لا نطق",
            positive(callState = TelephonyManager.CALL_STATE_IDLE)
        )
    }

    // ===== أولويةُ الدليل السلبي على الإيجابي =====

    @Test
    fun `ongoing type overrides fullScreenIntent evidence`() {
        // عقدٌ يجب أن يبقى محدَّداً: النصريحُ السلبيُّ يسودُ دائماً.
        assertFalse(
            "نوعُ المكالمةِ الجارى يُسود فوق نافذةِ ملءِ الشاشة",
            positive(
                callType = CallerAnnouncementReceiver.CALL_TYPE_ONGOING,
                fullScreenIntent = true
            )
        )
    }

    @Test
    fun `screening type overrides answer action and ringing`() {
        assertFalse(
            "نوعُ الفرز يُسود فوق كلِّ الأدلّة الإيجابية",
            CallerAnnouncementReceiver.isPositivelyIncoming(
                callTypeExtra =
                    CallerAnnouncementReceiver.CALL_TYPE_SCREENING,
                hasFullScreenIntent = false,
                hasAnswerAction = true,
                telephonyCallState = TelephonyManager.CALL_STATE_RINGING,
                sdkInt = 37,
                legacyIncomingPhrase = false
            )
        )
    }

    @Test
    fun `unknown call type still allows other evidence`() {
        // المجهولُ يعني «لا أعلم» لا «صادر» — فلا يسود، بل يُترك البابُ
        // مفتوحاً لبقية الأدلّة، فإن غابت كان الجوابُ لا نطق.
        assertTrue(
            "المجهولُ يُبقي البابَ مفتوحاً لبقية الأدلّة",
            positive(
                callType = CallerAnnouncementReceiver.CALL_TYPE_UNKNOWN,
                answerAction = true
            )
        )
        assertFalse(
            "المجهولُ وحدَه بلا دليلٍ آخر = لا نطق",
            positive(
                callType = CallerAnnouncementReceiver.CALL_TYPE_UNKNOWN
            )
        )
    }

    // ===== بند 2: الطبقةُ الثانوية — العبارةُ النصّيةُ للقديمة فقط =====

    @Test
    fun `legacy incoming phrase accepted on old devices only`() {
        // قبل الإصدار 31 لا نوعَ مكالمةَ ولا إجراءً دلاليًّا موثوق:
        // فتبقى العبارةُ النصّيةُ الطبقةَ الثانية وحدَها هناك.
        assertTrue(
            "الجهاز القديم: العبارةُ النصّيةُ تكفي",
            CallerAnnouncementReceiver.isPositivelyIncoming(
                callTypeExtra = null,
                hasFullScreenIntent = false,
                hasAnswerAction = false,
                telephonyCallState = TelephonyManager.CALL_STATE_IDLE,
                sdkInt = 30,
                legacyIncomingPhrase = true
            )
        )
    }

    @Test
    fun `legacy incoming phrase is ignored on api 31 and above`() {
        // **الطبقةُ الثانوية محصورةٌ في الأجهزة القديمة:** العبارةُ
        // النصّيةُ تخمينٌ بلا سندٍ منطقي على الأجهزة الحديثة، وإلا عاد
        // سببُ الشكوى الأصلي وهو نطقُ الصادرةِ واردةً.
        assertFalse(
            "من الإصدار 31: العبارةُ النصّيةُ تُهمَل",
            CallerAnnouncementReceiver.isPositivelyIncoming(
                callTypeExtra = null,
                hasFullScreenIntent = false,
                hasAnswerAction = false,
                telephonyCallState = TelephonyManager.CALL_STATE_IDLE,
                sdkInt = 31,
                legacyIncomingPhrase = true
            )
        )
        assertFalse(
            "أحدثُ نظام: العبارةُ النصّيةُ تُهمَل",
            CallerAnnouncementReceiver.isPositivelyIncoming(
                callTypeExtra = null,
                hasFullScreenIntent = false,
                hasAnswerAction = false,
                telephonyCallState = TelephonyManager.CALL_STATE_IDLE,
                sdkInt = 37,
                legacyIncomingPhrase = true
            )
        )
    }

    /** اختصارُ الاستدعاء بجهازٍ حديثٍ بلا طبقةٍ نصّية. */
    private fun positive(
        callType: Int? = null,
        fullScreenIntent: Boolean = false,
        answerAction: Boolean = false,
        callState: Int = TelephonyManager.CALL_STATE_IDLE
    ): Boolean = CallerAnnouncementReceiver.isPositivelyIncoming(
        callTypeExtra = callType,
        hasFullScreenIntent = fullScreenIntent,
        hasAnswerAction = answerAction,
        telephonyCallState = callState,
        sdkInt = 37,
        legacyIncomingPhrase = false
    )
}
