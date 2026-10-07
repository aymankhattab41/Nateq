package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class A11ySpeechCoordinatorTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `canSpeak returns true when screen reader is inactive`() {
        val canSpeak = A11ySpeechCoordinator.canSpeak(
            context,
            isInterruptAllowed = false
        )
        assertTrue(canSpeak)
    }

    @Test
    fun `canSpeak returns true when interrupt is allowed`() {
        val canSpeak = A11ySpeechCoordinator.canSpeak(
            context,
            isInterruptAllowed = true
        )
        assertTrue(canSpeak)
    }
}
