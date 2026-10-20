package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.ProtectedApp
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات التطبيقات المحمية وإنستاباي (الجولة 48 - الأمر 7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ProtectedAppRound48Test {

    private val context =
        ApplicationProvider.getApplicationContext<Context>()
    private val settings = SettingsRepository.create(context)

    @Before
    fun setUp() {
        ProtectedAppManager.resetForTesting()
        settings.setProtectedApps(emptyList())
        settings.setSuspendedProtectedApp(null)
    }

    @After
    fun tearDown() {
        ProtectedAppManager.resetForTesting()
        settings.setProtectedApps(emptyList())
        settings.setSuspendedProtectedApp(null)
    }

    @Test
    fun matchProtectedApp_matchesInstaPayByNameOrPackage() {
        settings.addProtectedApp(
            ProtectedApp(
                packageName = "com.instapay",
                label = "إنستاباي",
                mode = ProtectedApp.MODE_FULL_DISABLE
            )
        )

        val match1 = ProtectedAppManager.matchProtectedApp(context, "إنستاباي")
        assertNotNull(match1)
        assertEquals("com.instapay", match1!!.packageName)

        val match2 = ProtectedAppManager.matchProtectedApp(context, "انستاباي")
        assertNotNull(match2)
        assertEquals("com.instapay", match2!!.packageName)

        val match3 =
            ProtectedAppManager.matchProtectedApp(context, "com.instapay")
        assertNotNull(match3)
        assertEquals("com.instapay", match3!!.packageName)
    }

    @Test
    fun enableAccessibilityServiceDirectly_retainsExistingServices() {
        val cr = context.contentResolver
        val talkbackService =
            "com.google.android.marvin.talkback/.TalkBackService"
        Settings.Secure.putString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            talkbackService
        )

        val success =
            ProtectedAppManager.enableAccessibilityServiceDirectly(context)
        assertTrue(success)

        val updated = Settings.Secure.getString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        assertTrue(
            "Existing TalkBack service must be retained",
            updated.contains(talkbackService)
        )
        val lordService =
            "com.aymankhattab.nateq/.core.audio.accessibility" +
                ".NateqAccessibilityService"
        assertTrue(
            "Lord TTS service must be appended",
            updated.contains(lordService)
        )
    }

    @Test
    fun partialSuspend_togglesStateCorrectly() {
        assertFalse(ProtectedAppManager.isPartiallySuspended)

        val app = ProtectedApp(
            packageName = "com.banking.app",
            label = "بنك مصر",
            mode = ProtectedApp.MODE_PARTIAL_SUSPEND
        )
        ProtectedAppManager.handleAppEntry(context, null, app)

        assertTrue(ProtectedAppManager.isPartiallySuspended)
        assertEquals(
            "com.banking.app",
            ProtectedAppManager.partiallySuspendedPkg
        )

        ProtectedAppManager.handleAppExit(context, "com.banking.app", "بنك مصر")
        assertFalse(ProtectedAppManager.isPartiallySuspended)
    }

    @Test
    fun fullDisable_recordsSuspendedAppAndRestoresState() {
        val app = ProtectedApp(
            packageName = "com.instapay",
            label = "إنستاباي",
            mode = ProtectedApp.MODE_FULL_DISABLE
        )
        ProtectedAppManager.handleAppEntry(context, null, app)

        assertEquals("com.instapay", settings.getSuspendedProtectedApp())
        assertTrue(settings.getSuspendedProtectedAppTimestamp() > 0L)

        ProtectedAppManager.handleAppExit(context, "com.instapay", "إنستاباي")
        assertEquals(null, settings.getSuspendedProtectedApp())
    }
}
