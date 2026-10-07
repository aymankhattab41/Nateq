package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPolicyTest {

    @Test
    fun `default whitelist allows WhatsApp and Telegram`() {
        assertTrue(AppPolicy.isAllowed("com.whatsapp"))
        assertTrue(AppPolicy.isAllowed("com.whatsapp.w4b"))
        assertTrue(AppPolicy.isAllowed("org.telegram.messenger"))
    }

    @Test
    fun `default whitelist rejects untrusted apps`() {
        assertFalse(AppPolicy.isAllowed("com.random.app"))
        assertFalse(AppPolicy.isAllowed(null))
        assertFalse(AppPolicy.isAllowed(""))
    }

    @Test
    fun `system sensitive apps are always blocked even in allow all mode`() {
        assertTrue(AppPolicy.isSystemSensitive("com.android.settings"))
        assertTrue(AppPolicy.isSystemSensitive("com.android.systemui"))
        assertTrue(AppPolicy.isSystemSensitive("com.android.keyguard"))
        assertTrue(AppPolicy.isSystemSensitive("com.mybank.app"))
        assertTrue(AppPolicy.isSystemSensitive("com.wallet.payment"))

        assertFalse(
            AppPolicy.isAllowed(
                "com.android.settings",
                allowAllApps = true
            )
        )
        assertFalse(
            AppPolicy.isAllowed(
                "com.mybank.app",
                allowAllApps = true
            )
        )
    }

    @Test
    fun `custom user allowed packages respected`() {
        val userSet = setOf("com.google.android.gm", "com.twitter.android")
        assertTrue(AppPolicy.isAllowed("com.google.android.gm", userSet))
        assertTrue(AppPolicy.isAllowed("com.twitter.android", userSet))
        assertFalse(AppPolicy.isAllowed("com.whatsapp", userSet))
    }
}
