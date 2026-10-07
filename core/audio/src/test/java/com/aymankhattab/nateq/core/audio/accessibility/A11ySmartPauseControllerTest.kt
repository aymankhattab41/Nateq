package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class A11ySmartPauseControllerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `smart pause reasons toggle and evaluate properly`() {
        val controller = A11ySmartPauseController.getInstance(context)

        assertFalse(
            controller.isReasonActive(A11ySmartPauseController.REASON_IN_CALL)
        )
        assertFalse(controller.hasAnyActiveReason())

        controller.setReasonActive(
            A11ySmartPauseController.REASON_IN_CALL,
            true
        )
        assertTrue(
            controller.isReasonActive(A11ySmartPauseController.REASON_IN_CALL)
        )
        assertTrue(controller.hasAnyActiveReason())

        controller.setReasonActive(
            A11ySmartPauseController.REASON_IN_CALL,
            false
        )
        assertFalse(
            controller.isReasonActive(A11ySmartPauseController.REASON_IN_CALL)
        )
        assertFalse(controller.hasAnyActiveReason())
    }
}
