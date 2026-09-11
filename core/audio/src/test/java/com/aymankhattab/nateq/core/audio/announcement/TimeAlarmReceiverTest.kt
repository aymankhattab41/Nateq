package com.aymankhattab.nateq.core.audio.announcement

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** اختبار جدولة منبه إعلان الوقت (بند 13.3): بالإذن الدقيق يجدول
 *  [AlarmManager.setExactAndAllowWhileIdle] على أندرويد 12+، وبدونه يتراجع
 *  تلقائياً وبأمان إلى [AlarmManager.setAndAllowWhileIdle] (نافذة إرشادية —
 *  يطلق قرب الهدف ويوقظ من Doze بلا أي إذن) فيبقى الإعلان يعمل على أندرويد
 *  17 حتى إذن رُفض افتراضياً؛ وعلى ما قبل 12 يعمل الدقيق بلا إذن. كل المسارات
 *  بنفس الزمن المستهدف ونفس الـ PendingIntent الواحد غير القابل للتعديل. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeAlarmReceiverTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private val triggerAtMillis = 1_700_000_000_000L

    private fun alarmManager(): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun shadowAlarmManager(): ShadowAlarmManager {
        val shadow = Shadows.shadowOf(alarmManager())
        // افتراضياً الإذن ممنوح؛ الاختبارات الناقصة تُبطله صراحةً.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // تسجيل المنبهات فقط دون إطلاقٍ تلقائي على المحاكي الزمني.
        ShadowAlarmManager.setAutoSchedule(false)
        return shadow
    }

    @Test
    // الحقل operation مهجّر بلا getter بديل في واجهة ScheduledAlarm، لذا
    // الوصول المباشر مقصود (انظر assertSingleImmutablePendingIntent).
    @Suppress("DEPRECATION")
    fun `exact permission granted schedules a precise doze alarm`() {
        val shadow = shadowAlarmManager()

        TimeAlarmReceiver.scheduleNext(context, triggerAtMillis)

        val alarms = shadow.getScheduledAlarms()
        assertEquals("منبهٌ واحدٌ مجدول", 1, alarms.size)
        val alarm = alarms[0]
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType())
        assertEquals(triggerAtMillis, alarm.getTriggerAtMs())
        assertEquals(
            "دقيق: بلا نافذة تسامح (WINDOW_EXACT=0)",
            0L, alarm.getWindowLengthMs()
        )
        assertTrue(
            "يوقظ من Doze العميق",
            alarm.isAllowWhileIdle()
        )
        assertSingleImmutablePendingIntent(alarm.operation)
    }

    @Test
    // الحقل operation مهجّر بلا getter بديل في واجهة ScheduledAlarm، لذا
    // الوصول المباشر مقصود (انظر assertSingleImmutablePendingIntent).
    @Suppress("DEPRECATION")
    fun `missing exact permission falls back to a flexible doze alarm`() {
        val shadow = shadowAlarmManager()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        TimeAlarmReceiver.scheduleNext(context, triggerAtMillis)

        // تراجع آمن: لا SecurityException ولا فقدان جدولة — يبقى الإعلان
        // يعمل منبهاً مرناً يقترب من اللحظة الهدف.
        val alarms = shadow.getScheduledAlarms()
        assertEquals("منبهٌ واحدٌ مجدول", 1, alarms.size)
        val alarm = alarms[0]
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType())
        assertEquals(triggerAtMillis, alarm.getTriggerAtMs())
        assertEquals(
            "مرن: نافذة إرشادية (WINDOW_HEURISTIC=-1)",
            -1L, alarm.getWindowLengthMs()
        )
        assertTrue(alarm.isAllowWhileIdle())
        assertSingleImmutablePendingIntent(alarm.operation)
    }

    @Test
    @Config(sdk = [29])
    fun `pre android12 schedules precise without any permission`() {
        val shadow = shadowAlarmManager()

        TimeAlarmReceiver.scheduleNext(context, triggerAtMillis)

        val alarms = shadow.getScheduledAlarms()
        assertEquals(1, alarms.size)
        val alarm = alarms[0]
        assertEquals(triggerAtMillis, alarm.getTriggerAtMs())
        assertEquals(
            "دقيق رغم غياب نظام الإذن قبل 12",
            0L, alarm.getWindowLengthMs()
        )
        assertTrue(alarm.isAllowWhileIdle())
    }

    @Test
    fun `cancel removes the pending alarm`() {
        val shadow = shadowAlarmManager()

        TimeAlarmReceiver.scheduleNext(context, triggerAtMillis)
        assertEquals(1, shadow.getScheduledAlarms().size)

        TimeAlarmReceiver.cancel(context)
        assertEquals(0, shadow.getScheduledAlarms().size)
    }

    @Test
    fun `wake lock window covers the full async broadcast window`() {
        // const val في الكيان في Kotlin يُجمَّع كثابت static على
        // الـ outer class مباشرةً (لا عبر Companion instance).
        val clazz = TimeAlarmReceiver::class.java
        val lockField = clazz.getDeclaredField("SHORT_WAKE_LOCK_MS")
        lockField.isAccessible = true
        val windowField = clazz.getDeclaredField("ALARM_ASYNC_WINDOW_MS")
        windowField.isAccessible = true

        // بند [4]: القفل يجب أن يغطي نافذة goAsync كاملةً (لتناسق بين
        // النطق والصحوة) — كان 5 ثوانٍ فقط دون نافذة البث 10 فينام
        // المعالج والنطق ناقص قبل اكتمال التهيئة.
        assertEquals(
            "WakeLock يغطي نافذة البث كاملة (بند [4])",
            (lockField.get(null) as Number).toLong(),
            (windowField.get(null) as Number).toLong()
        )
    }

    /** يتحقق أن الـ PendingIntent غير قابل للتعديل (FLAG_IMMUTABLE — أمان
     *  بث المنبه على أندرويد 12+) وأن نيته هي tick إعلان الوقت نفسه.
     *  يُمرَّر operation (حقل ScheduledAlarm المهجّر بلا getter بديل). */
    private fun assertSingleImmutablePendingIntent(operation: PendingIntent?) {
        assertTrue(
            "PendingIntent غير قابل للتعديل",
            operation!!.isImmutable
        )
        assertEquals(
            TimeAlarmReceiver.ACTION_TICK,
            Shadows.shadowOf(operation).savedIntent?.action
        )
    }
}