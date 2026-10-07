package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class A11yStateStoreTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `store reflects pause and resume changes`() {
        val store = A11yStateStore.getInstance(context)!!
        store.setConnected(true)
        store.setPaused(
            paused = true,
            untilEpoch = System.currentTimeMillis() + 60_000L,
            level = 1
        )

        assertTrue(store.isConnected())
        assertTrue(store.isPaused())
        assertEquals(1, store.getPauseLevel())

        store.setPaused(false)
        assertFalse(store.isPaused())
    }

    @Test
    fun `store listener is notified on state changes`() {
        val store = A11yStateStore.getInstance(context)!!
        var notified = false
        val listener: (A11yStateStore) -> Unit = {
            notified = true
        }

        store.addListener(listener)
        store.setPaused(true)
        assertTrue(notified)

        store.removeListener(listener)
    }
}
