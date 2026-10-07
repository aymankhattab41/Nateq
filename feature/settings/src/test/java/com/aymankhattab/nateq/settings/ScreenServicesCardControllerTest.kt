package com.aymankhattab.nateq.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.accessibility.A11yStateStore
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class ScreenServicesCardControllerTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var controller: ScreenServicesCardController

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository.create(context)
        controller = ScreenServicesCardController(context, settings)
    }

    @Test
    fun testInitialStatusText() {
        val statusText = controller.getServiceStatusText()
        assertNotNull(statusText)
        assertTrue(statusText.isNotBlank())
    }

    @Test
    fun testPauseAndResume() {
        val store = A11yStateStore.getInstance(context)
        assertNotNull(store)

        controller.resumeService()
        assertFalse(controller.isServicePaused())

        store?.setPaused(true, 0L, 1)
        assertTrue(controller.isServicePaused())

        controller.resumeService()
        assertFalse(controller.isServicePaused())
    }
}
