package com.aymankhattab.nateq.settings

import android.os.Build
import android.view.View
import com.aymankhattab.nateq.feature.settings.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class AccessibilityOnboardingActivityTest {

    @Test
    fun `activity launches and renders elements correctly`() {
        val controller =
            Robolectric.buildActivity(
                AccessibilityOnboardingActivity::class.java
            ).create().start().resume()
        val activity = controller.get()

        val titleView =
            activity.findViewById<View>(R.id.tv_onboarding_title)
        val btnEnable =
            activity.findViewById<View>(R.id.btn_enable_accessibility)
        val layoutRestricted =
            activity.findViewById<View>(R.id.layout_restricted_settings)

        assertNotNull(titleView)
        assertNotNull(btnEnable)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue(
                "Restricted settings visible on Android 13+",
                layoutRestricted.visibility == View.VISIBLE
            )
        }

        controller.pause().stop().destroy()
    }
}
