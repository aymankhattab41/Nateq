package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class AccessibilityHealthMonitorTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `health monitor returns valid status`() {
        val status = AccessibilityHealthMonitor.checkHealth(context)
        assertNotNull(status)
        assertEquals(
            AccessibilityHealthMonitor.HealthStatus.DISABLED_IN_SYSTEM,
            status
        )
    }

    @Test
    fun `session warning resets properly`() {
        AccessibilityHealthMonitor.resetSessionWarning()
        val shouldWarn =
            AccessibilityHealthMonitor.shouldWarnDisconnected(context)
        // Disabled in system shouldn't trigger disconnect warning
        assertEquals(false, shouldWarn)
    }
}
