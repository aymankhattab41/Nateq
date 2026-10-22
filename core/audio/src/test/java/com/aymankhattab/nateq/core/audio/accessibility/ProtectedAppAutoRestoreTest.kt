package com.aymankhattab.nateq.core.audio.accessibility

import android.app.Application
import android.content.Context
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * اختبارات الجولة 51 — الأمر 1:
 * إعادة تفعيل خدمة إمكانية الوصول تلقائياً بعد إغلاق إنستاباي.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ProtectedAppAutoRestoreTest {

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
        settings.setSuspendedProtectedApp(null)
    }

    @After
    fun tearDown() {
        ProtectedAppManager.resetForTesting()
        ProtectedAppSuspensionStore.clear(context)
        RestoreDiagnosticsTracker.clear(context)
        settings.setSuspendedProtectedApp(null)
    }

    @Test
    fun testWithoutUsageStats_preventsFullDisable() {
        val readiness = ProtectedAppManager.checkReadiness(context)
        assertFalse(
            "بدون إذن الاستخدام يجب ألا يُسمح بالإيقاف الكامل",
            readiness.canEnableFullDisable
        )

        val protectedApp = ProtectedApp(
            packageName = "com.egyptianbanks.instapay",
            label = "InstaPay",
            mode = ProtectedApp.MODE_FULL_DISABLE
        )

        ProtectedAppManager.handleAppEntry(context, null, protectedApp)

        assertTrue(ProtectedAppManager.isPartiallySuspended)
        assertEquals(
            "com.egyptianbanks.instapay",
            ProtectedAppManager.partiallySuspendedPkg
        )
        assertFalse(
            "يجب ألا تُسجل في التخزين كخدمة معطلة كلياً",
            ProtectedAppManager.isSuspended(context)
        )
    }

    @Test
    fun testRestartRestoresFromDeviceProtectedStorage() {
        val now = System.currentTimeMillis() - 10_000L
        ProtectedAppSuspensionStore.save(
            context = context,
            packageName = "com.instapay",
            timestamp = now,
            mode = ProtectedApp.MODE_FULL_DISABLE
        )

        val loaded = ProtectedAppSuspensionStore.load(context)
        assertNotNull(loaded)
        assertEquals("com.instapay", loaded?.packageName)
        assertEquals(now, loaded?.timestamp)
        assertTrue(ProtectedAppManager.isSuspended(context))
    }

    @Test
    fun testWritingWithoutConnection_triggersNudgeAndFallsBack() {
        shadowOf(app).grantPermissions(
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        )
        ProtectedAppSuspensionStore.save(
            context = context,
            packageName = "com.instapay",
            timestamp = System.currentTimeMillis() - 5000L
        )

        val restored = ProtectedAppManager.checkAndRestore(
            context = context,
            reason = "force",
            pollIntervalMs = 5L,
            maxWaitMs = 20L,
            nudgeWaitMs = 5L
        )

        assertFalse("يجب أن يفشل الاسترجاع لعدم اتصال الخدمة", restored)
        assertTrue(
            "يجب ألا تُمحى حالة الإيقاف عند فشل الاتصال",
            ProtectedAppManager.isSuspended(context)
        )

        val attempts = RestoreDiagnosticsTracker.getRecentAttempts(context)
        assertTrue("يجب تسجيل المحاولة في سجل التشخيص", attempts.isNotEmpty())
        val last = attempts.last()
        assertEquals("force", last.reason)
        assertEquals(2, last.nudgeAttempts)
        assertFalse(last.serviceConnected)
    }

    @Test
    fun testKillForegroundWatcher_restoresViaAlarmOrListener() {
        shadowOf(app).grantPermissions(
            android.Manifest.permission.WRITE_SECURE_SETTINGS
        )
        ProtectedAppSuspensionStore.save(
            context = context,
            packageName = "com.instapay",
            timestamp = System.currentTimeMillis() - 60_000L
        )

        val dummyService = NateqAccessibilityService()
        AccessibilityBridge.registerService(dummyService)
        try {
            val restored = ProtectedAppManager.checkAndRestore(
                context = context,
                reason = "force",
                pollIntervalMs = 5L,
                maxWaitMs = 30L,
                nudgeWaitMs = 5L
            )
            assertTrue(restored)
            assertFalse(ProtectedAppManager.isSuspended(context))
        } finally {
            AccessibilityBridge.unregisterService(dummyService)
        }
    }
}
