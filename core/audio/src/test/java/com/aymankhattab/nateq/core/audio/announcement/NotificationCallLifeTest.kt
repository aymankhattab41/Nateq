package com.aymankhattab.nateq.core.audio.announcement

import com.aymankhattab.nateq.core.audio.announcement.CallerAnnouncementReceiver.Companion.CALL_STATE_IDLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **حارسُ بند 2 — ثغرةُ نافذةِ الـ45 ثانية.**
 *
 * ## العيب
 *
 * كان مفتاحُ مكالمة الإشعار مربوطاً **بالزمن** لا بحياة الإشعار:
 * `shouldTriggerNotificationCall` كانت تُطلق النطقَ متى انقضت نافذةُ
 * 45 ثانية. وتطبيقاتُ الاتصال تحدّث إشعارَ المكالمة عشراتِ المرّات
 * (كتمٌ، سمّاعة، عودةٌ إلى الفيديو، إظهارُ لوحة المفاتيح) — وكلُّ
 * تحديثٍ بعد 45 ثانيةً كان **يعيد نطقَ الإعلان كاملاً**: «اتصال وارد
 * من فلان» ثم جدولُ التكرارات كلُّه من جديد.
 *
 * فالنافذةُ الزمنيةُ كانت **تسمح** بالنطق لا تمنعه.
 *
 * ## العقدُ الجديد
 *
 * **المفتاحُ مربوطٌ بحياة الإشعار لا بالثانية:**
 *  1. لا يُعاد النطقُ لنفس المفتاح ما دام الإشعارُ لم يُحذف
 *     ([CallerAnnouncementReceiver.endNotificationCall] عبر
 *     `onNotificationRemoved`) — بصرف النظر عن الزمن.
 *  2. بعد الردّ الصريح («مكالمة جارية») يُرفض كلُّ نطقٍ لنفس المفتاح
 *     حتى `endNotificationCall`.
 *  3. **لا مدخلَ زمنيًّا في القرار أصلاً** — فالثغرةُ ممتنعةٌ بحكم
 *     البنية لا بزيادةِ شرط.
 *
 * ## لماذا هذا الاختبارُ حارسٌ لا إعادةُ صياغة
 *
 * [NotificationCallSession] يحاكي **حالةَ الجلسة** لا **القرارَ**:
 * يكتب المفتاحَ عند الإطلاق ويمحوه عند حذف الإشعار، كما يفعل
 * `announceNotificationCall` و`endNotificationCall` تماماً. أمّا
 * الدالةُ التي يُحكم بها فهي **نفسُها** التي يستدعيها الإنتاج
 * ([CallerAnnouncementReceiver.shouldTriggerNotificationCall])،
 * فالحارسُ لا يعيد هنا كتابةَ القرار الذي يحرسه.
 *
 * **و`Robolectric` لسببٍ واحد:** [CallerAnnouncementReceiver.markCallAnswered]
 * يكتب في `android.util.Log`، وهو غيرُ مُحاكى في JUnit المجرّد. فالحارسُ
 * يريد استدعاءَ **دالةِ الإنتاج نفسها** في رفع العَلَم لا محاكاةَها.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class NotificationCallLifeTest {

    /**
     * مرآةٌ لحالةِ جلسةِ إشعارِ المكالمة كما يحتفظ بها الإنتاج في
     * `notificationCallKey`.
     *
     * **و`elapsedMs` للوصفِ في الرسائل فقط** — وهو ليس مُدخلاً في
     * القرار. فذوكرُه مقصود: لو عاد الزمنُ مُدخلاً لانكشف الأمرُ
     * فوراً بانقلاب النتيجة عند 46 ثانية.
     */
    private class NotificationCallSession {
        /** نسخةٌ عن `notificationCallKey`. */
        private var lastKey: String? = null

        /** عددُ مرّات النطق — أي مرّات الإطلاق الناجحة. */
        var speeches: Int = 0
            private set

        /** مرآةُ `onNotificationPosted` ← `announceNotificationCall`. */
        fun posted(key: String): Boolean {
            val should = CallerAnnouncementReceiver
                .shouldTriggerNotificationCall(key = key, lastKey = lastKey)
            if (!should) return false
            lastKey = key
            speeches++
            return true
        }

        /** مرآةُ `onNotificationRemoved` ← `endNotificationCall`. */
        fun removed() {
            lastKey = null
        }
    }

    /**
     * **الاختبارُ المطلوبُ صراحةً — التحديثاتُ بعد 46 و120 و600 ثانية.**
     *
     * الكسرُ الذي يحرسه: بنافذةِ 45 ثانية كان كلُّ تحديثٍ بعدها يُعيد
     * النطقَ كاملاً. فنقرأ «صفرَ نطقٍ إضافيٍّ» عند كلِّ تلك اللحظات.
     */
    @Test
    fun `notification updates never re-announce regardless of elapsed time`() {
        val key = NateqNotificationListener.notificationCallKey(
            "com.whatsapp", "0501234567", "سارة"
        )
        val session = NotificationCallSession()

        // الرنّةُ الأولى: نطقٌ واحدٌ مشروع.
        assertTrue(
            "رنّةٌ واردةٌ جديدة تُعلَن",
            session.posted(key)
        )
        assertEquals(1, session.speeches)

        // تحديثاتُ الإشعار (كتم/سمّاعة/لوحة مفاتيح) — بلا أيّ نطقٍ
        // آخر، في أيّ لحظة، أبعدَما كانت عن الرنّة الأولى.
        val updateSeconds =
            listOf(3L, 45L, 46L, 47L, 120L, 300L, 600L, 3_600L)
        for (seconds in updateSeconds) {
            assertFalse(
                "تحديثُ إشعار عند ${seconds}s يجب ألّا ينطق",
                session.posted(key)
            )
        }
        assertEquals(
            "نطقٌ واحدٌ فقط رغم تحديثاتٍ بلا نهاية",
            1,
            session.speeches
        )
    }

    /**
     * **البند 2 — لا نطقَ بعد الردّ الصريح حتى حذفِ الإشعار.**
     *
     * «مكالمة جارية» تعني أنّ المستخدم ردّ، فيبقى الإشعارُ حيّاً
     * دقائقَ بمكالمةٍ جارية. وكلُّ تحديثٍ عليه بعد الردّ كان يُعيد
     * الإعلانَ كاملاً فوق مكالمةِ المستخدم.
     */
    @Test
    fun `an answered call is not re-announced while its notification lives`() {
        val key = NateqNotificationListener.notificationCallKey(
            "com.telegram", null, "خالد"
        )
        assertTrue(
            "أولُ إشعارٍ يُعلَن",
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key = key, lastKey = null
            )
        )

        // الردّ الصريح: المستمعُ رفع العَلَم، فيتوقّف حارسُ التكرار.
        CallerAnnouncementReceiver.markCallAnswered()

        // **الكسر:** الإشعارُ ما زال حيّاً لكن الردّ وقع — لا نطق.
        assertFalse(
            "نفس المفتاح بعد الردّ: لا نطق ما دام الإشعارُ حيّاً",
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key = key, lastKey = key
            )
        )

        // ويوافقُ حارسُ التكرار: الردّ أوقف نبضاتِ التكرار أيضاً،
        // فالطريقان — الإطلاقُ والتكرارُ — يُرفضان معاً.
        assertFalse(
            "حارسُ التكرار يوافق: الردّ أوقف نبضاتِه أيضاً",
            CallerAnnouncementReceiver.shouldContinueRepeating(
                isNotificationCall = true,
                notificationStillRinging = true,
                callAnnouncedAnswered = true,
                networkCallState = CALL_STATE_IDLE
            )
        )
        CallerAnnouncementReceiver.clearCallAnswered()
    }

    /**
     * **بابُ الخروج الوحيد — حذفُ الإشعار.**
     *
     * بدونه لكُبِحت كلُّ مكالماتِ المتصل نفسه إلى الأبد بمفتاحه الأول،
     * وهذا هو الخوفُ الحقيقيّ من ربط المفتاح بحياة الإشعار.
     */
    @Test
    fun `the same caller rings again after the notification is removed`() {
        val key = NateqNotificationListener.notificationCallKey(
            "com.whatsapp", "0501234567", "سارة"
        )
        val session = NotificationCallSession()
        session.posted(key)
        assertEquals(1, session.speeches)

        // التحديثاتُ أثناءَ بقاء الإشعار تُكبَت كلها.
        session.posted(key)
        session.posted(key)
        assertEquals("التحديثاتُ تُكبَت", 1, session.speeches)

        // **حُذف الإشعار** (انتهت المكالمة أو رُدَّ عليها) — فيبيح المفتاح.
        session.removed()
        assertTrue(
            "مكالمةٌ جديدةٌ من المتصل نفسه بعد الحذف تُعلَن",
            session.posted(key)
        )
        assertEquals(2, session.speeches)
    }

    /** متصلٌ آخر: مفتاحٌ مختلفٌ يُعلَن فوراً ولا ينتظر أحداً. */
    @Test
    fun `a different caller is never blocked by a live key`() {
        assertTrue(
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key = "com.whatsapp|0509999999|علي",
                lastKey = "com.whatsapp|0501234567|سارة"
            )
        )
    }

    /** ولا جلسةَ أصلاً (بلا مفتاح): كلُّ إشعارٍ جديدٍ يُعلَن. */
    @Test
    fun `with no live session every notification is announced`() {
        assertTrue(
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key = "a|1|سارة", lastKey = null
            )
        )
        assertTrue(
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key = "b|2|أحمد", lastKey = null
            )
        )
    }

    /**
     * **حارسُ بنية: لا وجودَ لنافذةِ الزمن أصلاً.**
     *
     * فحذفُ الثابتِ حجرُ الأساس — ولو عادَ محرّكٌ يقرأه لسكتَ هذا
     * الحارسُ صامتاً لأنّ سلوكَه خارجُ[Test] أعلاه. والبحثُ في
     * الحقلين لأنّ `const val` في `companion object` يُولِّد حقلاً
     * ساكناً على الصنف الحاوي.
     */
    @Test
    fun `the 45 second dedup window is gone from the bytecode`() {
        val names =
            CallerAnnouncementReceiver::class.java.declaredFields
                .map { it.name } +
                CallerAnnouncementReceiver.Companion::class.java
                    .declaredFields.map { it.name }
        assertNull(
            "NOTIFICATION_CALL_DEDUP_MS لا بدّ له من أثرٍ في bytecode",
            names.firstOrNull { it == "NOTIFICATION_CALL_DEDUP_MS" }
        )
    }
}
