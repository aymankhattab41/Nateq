package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityButtonGateTest {

    @Test
    fun belowApi26_neverRegisters() {
        assertFalse(
            AccessibilityButtonGate.shouldRegister(
                featureActive = true,
                sdkInt = 25,
                buttonAvailable = true
            )
        )
    }

    @Test
    fun activeAndAvailable_registers() {
        assertTrue(
            AccessibilityButtonGate.shouldRegister(
                featureActive = true,
                sdkInt = 30,
                buttonAvailable = true
            )
        )
    }

    @Test
    fun inactive_doesNotRegister() {
        assertFalse(
            AccessibilityButtonGate.shouldRegister(
                featureActive = false,
                sdkInt = 34,
                buttonAvailable = true
            )
        )
    }

    @Test
    fun api30Unavailable_doesNotRegister() {
        assertFalse(
            AccessibilityButtonGate.shouldRegister(
                featureActive = true,
                sdkInt = 33,
                buttonAvailable = false
            )
        )
    }

    @Test
    fun api26To29_assumesAvailable() {
        assertTrue(
            AccessibilityButtonGate.shouldRegister(
                featureActive = true,
                sdkInt = 28,
                buttonAvailable = false
            )
        )
    }
}
