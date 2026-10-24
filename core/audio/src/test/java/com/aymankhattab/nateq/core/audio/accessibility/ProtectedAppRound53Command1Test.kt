package com.aymankhattab.nateq.core.audio.accessibility

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * اختبارات الجولة 53 — الأمر 1:
 * تسريع إعادة التفعيل، منع الاسترجاع المبكر أثناء البقاء بالتطبيق المحمي،
 * والانتقال الفوري للمشغل.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ProtectedAppRound53Command1Test {

    private lateinit var context: Context
    private lateinit var app: Application
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        context = app
        settings = SettingsRepository.create(context)
        ProtectedAppManager.resetForTesting()
        ProtectedAppSuspensionStore.clear(context)
        RestoreDiagnosticsTracker.clear(context)
        ProtectedAppTimelineTracker.endQuietPeriod()
        ProtectedAppTimelineTracker.clear()
        settings.setSuspendedProtectedApp(null)
        ProtectedAppManager.disableAccessibilityServiceDirectly(context)
    }

    @After
    fun tearDown() {
        ProtectedAppManager.resetForTesting()
        ProtectedAppSuspensionStore.clear(context)
        RestoreDiagnosticsTracker.clear(context)
        ProtectedAppTimelineTracker.endQuietPeriod()
        ProtectedAppTimelineTracker.clear()
        settings.setSuspendedProtectedApp(null)
        ProtectedAppManager.disableAccessibilityServiceDirectly(context)
    }

    @Test
    fun stayingInProtectedApp60s_withoutNewEvents_doesNotRestore() =
        runBlocking {
            shadowOf(app).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS
            )
            val pkg = "com.egyptianbanks.instapay"
            val now = System.currentTimeMillis()

            ProtectedAppSuspensionStore.save(
                context = context,
                packageName = pkg,
                timestamp = now - 60_000L
            )
            settings.setSuspendedProtectedApp(pkg, now - 60_000L)
            ProtectedAppManager.ForegroundTracker.setForTesting(pkg)

            val restored = ProtectedAppManager.checkAndRestore(
                context = context,
                reason = "watcher"
            )

            assertFalse(
                "البقاء داخل التطبيق المحمي 60s لا يجب أن يعيد التفعيل",
                restored
            )
            assertTrue(
                "يجب أن يبقى التطبيق في حالة إيقاف",
                ProtectedAppManager.isSuspended(context)
            )
        }

    @Test
    fun exitingToLauncher_triggersInstantRestore() = runBlocking {
        shadowOf(app).grantPermissions(
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        )
        ProtectedAppManager.disableAccessibilityServiceDirectly(context)
        val pkg = "com.egyptianbanks.instapay"
        val launcherPkg = "com.google.android.apps.nexuslauncher"
        val now = System.currentTimeMillis()

        ProtectedAppSuspensionStore.save(
            context = context,
            packageName = pkg,
            timestamp = now - 5_000L
        )
        settings.setSuspendedProtectedApp(pkg, now - 5_000L)

        ProtectedAppManager.ForegroundTracker.setForTesting(launcherPkg)

        // يجب أن يلاحظ الخروج للمشغل فورياً بلا اشتراط غياب 1200ms
        val restored = ProtectedAppManager.checkAndRestore(
            context = context,
            reason = "watcher",
            pollIntervalMs = 5L,
            maxWaitMs = 15L
        )

        // تم فحص الخروج وبدء الاسترجاع (الكتابة على Settings.Secure تمت)
        assertTrue(
            ProtectedAppManager.isServiceEnabledInSettings(context)
        )
    }

    @Test
    fun watcherPollingInterval_is500msThen1000ms() {
        assertEquals(
            500L,
            ProtectedAppWatcherService.computePollIntervalMs(0L)
        )
        assertEquals(
            500L,
            ProtectedAppWatcherService.computePollIntervalMs(119_999L)
        )
        assertEquals(
            1000L,
            ProtectedAppWatcherService.computePollIntervalMs(120_000L)
        )
        assertEquals(
            1000L,
            ProtectedAppWatcherService.computePollIntervalMs(300_000L)
        )
    }

    @Test
    fun onServiceConnected_returnsVeryQuickly() {
        val controller = Robolectric.buildService(
            NateqAccessibilityService::class.java
        )
        val service = controller.create().get()

        val start = System.currentTimeMillis()
        service.onServiceConnectedForTesting()
        val elapsed = System.currentTimeMillis() - start

        assertTrue(
            "onServiceConnected must complete in <100ms ($elapsed ms)",
            elapsed < 100L
        )
    }
}
