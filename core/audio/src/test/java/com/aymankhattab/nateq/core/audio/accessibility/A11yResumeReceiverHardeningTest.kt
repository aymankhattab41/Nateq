package com.aymankhattab.nateq.core.audio.accessibility

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * اختبارات انحدار لتحصين مستقبل الاستئناف وإلغاء المنبه القديم (الأمر 21):
 * 1. 15 دقيقة -> يدوي -> وصول التنبيه القديم: لا يستأنف وتظل الخدمة موقوفة.
 * 2. تغيير مدة إلى أطول: المنبه الأقصر القديم لا يستأنف الخدمة قبل موعدها.
 * 3. الاستئناف اليدوي المباشر يلغي المنبه ويعيد الخدمة للعمل.
 * 4. تكرار وصول التنبيه لا يغير شيئاً إذا كانت الخدمة تعمل بالفعل.
 * 5. الاستئناف الصحيح عند انتهاء المدة المحددة الفعلية.
 * 6. فحص الإقلاع handleBootCompleted عند انتهاء المدة أو بقائها.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class A11yResumeReceiverHardeningTest {

    private lateinit var context: Context
    private lateinit var store: A11yStateStore
    private lateinit var receiver: A11yResumeReceiver
    private lateinit var alarmManager: AlarmManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = A11yStateStore.getInstance(context)!!
        store.setConnected(true)
        store.setPaused(false, 0L, 1)
        receiver = A11yResumeReceiver()
        alarmManager = context.getSystemService(
            Context.ALARM_SERVICE
        ) as AlarmManager
    }

    @After
    fun tearDown() {
        store.setPaused(false, 0L, 1)
        store.setConnected(false)
        A11yPauseScheduler.cancelResumeAlarm(context)
    }

    @Test
    fun timedPause_then_switch_to_manual_cancelsAlarm_and_ignoresStaleAlarm() {
        val now = System.currentTimeMillis()
        val fifteenMinutesLater = now + 15 * 60 * 1000L

        // 1. إيقاف مؤقت لمدة 15 دقيقة
        store.setPaused(true, fifteenMinutesLater, 1)
        val gen1 = store.getPauseGeneration()
        A11yPauseScheduler.scheduleResumeAlarm(
            context,
            fifteenMinutesLater,
            gen1
        )

        val shadowAlarm = Shadows.shadowOf(alarmManager)
        assertNotNull(shadowAlarm.nextScheduledAlarm)

        // 2. التحويل إلى إيقاف يدوي (untilEpoch = 0)
        store.setPaused(true, 0L, 1)
        val gen2 = store.getPauseGeneration()
        assertTrue(gen2 > gen1)
        assertTrue(store.isPaused())
        assertEquals(0L, store.getPausedUntilEpoch())

        // التحقق من أن المنبه أُلغي في AlarmManager
        assertNull(
            "المنبه القديم يجب أن يُلغى فور التحويل للإيقاف اليدوي",
            shadowAlarm.nextScheduledAlarm
        )

        // 3. محاكاة وصول Intent المنبه القديم (بجيل gen1 وموعد 15m)
        val staleIntent =
            Intent(context, A11yResumeReceiver::class.java).apply {
                action = A11yPauseScheduler.ACTION_RESUME_A11Y
                putExtra(
                    A11yPauseScheduler.EXTRA_RESUME_EPOCH,
                    fifteenMinutesLater
                )
                putExtra(A11yPauseScheduler.EXTRA_PAUSE_GENERATION, gen1)
            }
        receiver.onReceive(context, staleIntent)

        // يجب أن تظل الخدمة موقوفة ولا تستأنف خلافاً لرغبة المستخدم
        assertTrue(
            "الخدمة يجب أن تظل موقوفة يدوياً ولا تستأنف بالتنبيه القديم",
            store.isPaused()
        )
        assertEquals(0L, store.getPausedUntilEpoch())
    }

    @Test
    fun changeDuration_toLonger_ignoresEarlierStaleAlarm() {
        val now = System.currentTimeMillis()
        val shortDuration = now + 15 * 60 * 1000L
        val longDuration = now + 60 * 60 * 1000L

        // 1. إيقاف لـ 15 دقيقة
        store.setPaused(true, shortDuration, 1)
        val genShort = store.getPauseGeneration()
        A11yPauseScheduler.scheduleResumeAlarm(context, shortDuration, genShort)

        // 2. تمديد الإيقاف إلى ساعة (مدة أطول)
        store.setPaused(true, longDuration, 1)
        val genLong = store.getPauseGeneration()
        A11yPauseScheduler.scheduleResumeAlarm(context, longDuration, genLong)

        // 3. وصول المنبه القديم الخاص بـ 15 دقيقة
        val staleIntent =
            Intent(context, A11yResumeReceiver::class.java).apply {
                action = A11yPauseScheduler.ACTION_RESUME_A11Y
                putExtra(A11yPauseScheduler.EXTRA_RESUME_EPOCH, shortDuration)
                putExtra(A11yPauseScheduler.EXTRA_PAUSE_GENERATION, genShort)
            }
        receiver.onReceive(context, staleIntent)

        // يجب ألا تستأنف الخدمة وتظل موقوفة حتى الموعد الأطول
        assertTrue(store.isPaused())
        assertEquals(longDuration, store.getPausedUntilEpoch())

        // 4. وصول المنبه الجديد الصحيح
        val validIntent =
            Intent(context, A11yResumeReceiver::class.java).apply {
                action = A11yPauseScheduler.ACTION_RESUME_A11Y
                putExtra(A11yPauseScheduler.EXTRA_RESUME_EPOCH, longDuration)
                putExtra(A11yPauseScheduler.EXTRA_PAUSE_GENERATION, genLong)
            }
        receiver.onReceive(context, validIntent)

        // الآن تستأنف الخدمة بنجاح
        assertFalse(
            "الخدمة تستأنف عند وصول المنبه الصحيح المطابق",
            store.isPaused()
        )
    }

    @Test
    fun manualResume_cancelsAlarm_and_receiverIgnoresWhenAlreadyRunning() {
        val now = System.currentTimeMillis()
        val until = now + 30 * 60 * 1000L

        store.setPaused(true, until, 1)
        A11yPauseScheduler.scheduleResumeAlarm(context, until)
        val shadowAlarm = Shadows.shadowOf(alarmManager)
        assertNotNull(shadowAlarm.nextScheduledAlarm)

        // استئناف يدوي عبر مخزن الحالة (يلغي المنبه تلقائياً)
        store.setPaused(false, 0L, 1)
        assertFalse(store.isPaused())

        assertNull(shadowAlarm.nextScheduledAlarm)

        // وصول تنبيه والخدمة تعمل أصلاً
        val intent = Intent(context, A11yResumeReceiver::class.java).apply {
            action = A11yPauseScheduler.ACTION_RESUME_A11Y
        }
        receiver.onReceive(context, intent)

        assertFalse(store.isPaused())
    }

    @Test
    fun handleBootCompleted_expiredPause_resumesService() {
        val pastEpoch = System.currentTimeMillis() - 1000L
        store.setPaused(true, pastEpoch, 1)

        A11yResumeReceiver.handleBootCompleted(context)

        assertFalse(
            "عند الإقلاع وبعد انقضاء الموعد، تستأنف الخدمة تلقائياً",
            store.isPaused()
        )
    }

    @Test
    fun handleBootCompleted_futurePause_reschedulesAlarm() {
        val futureEpoch = System.currentTimeMillis() + 60 * 60 * 1000L
        store.setPaused(true, futureEpoch, 1)

        val shadowAlarm = Shadows.shadowOf(alarmManager)
        A11yResumeReceiver.handleBootCompleted(context)

        assertTrue("الخدمة تظل موقوفة", store.isPaused())
        assertNotNull(
            "تتم إعادة جدولة المنبه للموعد المتبقي في المستقبل",
            shadowAlarm.nextScheduledAlarm
        )
    }
}
