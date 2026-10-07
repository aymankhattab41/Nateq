package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class A11yPauseSchedulerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `calculateNextMidnightEpoch returns timestamp in the future`() {
        val now = System.currentTimeMillis()
        val midnight = A11yPauseScheduler.calculateNextMidnightEpoch(now)
        assertTrue(midnight > now)

        val cal = Calendar.getInstance().apply { timeInMillis = midnight }
        assertTrue(cal.get(Calendar.HOUR_OF_DAY) == 0)
        assertTrue(cal.get(Calendar.MINUTE) == 0)
        assertTrue(cal.get(Calendar.SECOND) == 0)
    }

    @Test
    fun `scheduleResumeAlarm runs without crash`() {
        val futureEpoch = System.currentTimeMillis() + 60_000L
        A11yPauseScheduler.scheduleResumeAlarm(context, futureEpoch)
        A11yPauseScheduler.cancelResumeAlarm(context)
        assertNotNull(context)
    }
}
