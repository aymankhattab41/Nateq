package com.aymankhattab.nateq.settings

import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.accessibility.A11yStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class ScreenServicesTileServiceTest {

    private lateinit var context: Context
    private lateinit var service: ScreenServicesTileService

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        service = Robolectric.buildService(
            ScreenServicesTileService::class.java
        ).create().get()
    }

    @Test
    fun testServiceLifecycle() {
        assertNotNull(service)
        service.onStartListening()
        service.onStopListening()
    }

    @Test
    fun testStateStoreInteraction() {
        val store = A11yStateStore.getInstance(context)
        assertNotNull(store)
        store?.setPaused(false)
        assertEquals(false, store?.isPaused())

        store?.setPaused(true)
        assertEquals(true, store?.isPaused())
    }
}
