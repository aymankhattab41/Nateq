package com.aymankhattab.nateq.core.audio.accessibility

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.ProtectedApp
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
 * اختبارات الجولة 51 و 52 — الأمر 1:
 * إعادة تفعيل خدمة إمكانية الوصول وحماية عقود الاسترجاع.
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
    fun testWritingWithoutConnection_triggersNudgeAndFallsBack() =
        runBlocking {
            shadowOf(app).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS
            )
            ProtectedAppManager.enableAccessibilityServiceDirectly(context)
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

            val attempts =
                RestoreDiagnosticsTracker.getRecentAttempts(context)
            assertTrue(
                "يجب تسجيل المحاولة في سجل التشخيص",
                attempts.isNotEmpty()
            )
            val last = attempts.last()
            assertEquals("force", last.reason)
            assertEquals(1, last.nudgeAttempts)
            assertFalse(last.serviceConnected)
        }

    @Test
    fun testKillForegroundWatcher_restoresViaAlarmOrListener() =
        runBlocking {
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

    @Test
    fun serviceAlreadyConnected_immediateCleanExitWithoutTouchingSettings() =
        runBlocking {
            shadowOf(app).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS
            )
            ProtectedAppSuspensionStore.save(
                context = context,
                packageName = "com.instapay",
                timestamp = System.currentTimeMillis() - 5000L
            )
            ProtectedAppManager.enableAccessibilityServiceDirectly(context)

            val initialSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )

            val dummyService = NateqAccessibilityService()
            AccessibilityBridge.registerService(dummyService)
            try {
                val restored = ProtectedAppManager.checkAndRestore(
                    context = context,
                    reason = "screen",
                    pollIntervalMs = 5L,
                    maxWaitMs = 20L
                )
                assertTrue("خروج فوري ناجح عند اتصال الخدمة", restored)
                assertFalse(
                    "يجب تصفير حالة التعليق",
                    ProtectedAppManager.isSuspended(context)
                )
                val afterSetting = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                assertEquals(
                    "يجب عدم لمس إعدادات النظام عند اتصال الخدمة",
                    initialSetting,
                    afterSetting
                )
            } finally {
                AccessibilityBridge.unregisterService(dummyService)
            }
        }

    @Test
    fun actionA11yConnected_clearsSuspensionSilently() = runBlocking {
        ProtectedAppSuspensionStore.save(
            context = context,
            packageName = "com.instapay",
            timestamp = System.currentTimeMillis() - 5000L
        )
        assertTrue(ProtectedAppManager.isSuspended(context))

        val receiver = ProtectedAppRestoreReceiver()
        val intent = Intent(ProtectedAppManager.ACTION_A11Y_CONNECTED)
        receiver.onReceive(context, intent)
        kotlinx.coroutines.delay(100L)

        assertFalse(
            "استقبال ACTION_A11Y_CONNECTED يصفّر التعليق بصمت",
            ProtectedAppManager.isSuspended(context)
        )
    }

    @Test
    fun repeatedScreenEvents_doNotDuplicateFailureAnnouncement() =
        runBlocking {
            shadowOf(app).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS
            )
            ProtectedAppSuspensionStore.save(
                context = context,
                packageName = "com.instapay",
                timestamp = System.currentTimeMillis() - 5000L
            )
            assertFalse(
                ProtectedAppSuspensionStore.isFailureAnnounced(context)
            )

            ProtectedAppManager.checkAndRestore(
                context = context,
                reason = "force",
                pollIntervalMs = 5L,
                maxWaitMs = 20L,
                nudgeWaitMs = 5L
            )

            assertTrue(
                "بعد الفشل الأول يُسجل أن التحذير أُعلن",
                ProtectedAppSuspensionStore.isFailureAnnounced(context)
            )

            // محاولة لاحقة
            ProtectedAppManager.checkAndRestore(
                context = context,
                reason = "force",
                pollIntervalMs = 5L,
                maxWaitMs = 20L,
                nudgeWaitMs = 5L
            )

            assertTrue(
                "الراية تبقى true ولا يُعاد النطق",
                ProtectedAppSuspensionStore.isFailureAnnounced(context)
            )
        }

    @Test
    fun concurrentRestoreRequests_singleSessionLock() =
        runBlocking {
            shadowOf(app).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS
            )
            ProtectedAppSuspensionStore.save(
                context = context,
                packageName = "com.instapay",
                timestamp = System.currentTimeMillis() - 5000L
            )

            val results = (1..5).map {
                async {
                    ProtectedAppManager.checkAndRestore(
                        context = context,
                        reason = "concurrent_$it",
                        pollIntervalMs = 20L,
                        maxWaitMs = 100L,
                        nudgeWaitMs = 20L
                    )
                }
            }.awaitAll()

            val attempts =
                RestoreDiagnosticsTracker.getRecentAttempts(context)
            // الجلسة الفعالة محصورة بالقفل الذري
            assertTrue(
                "جلسة واحدة فقط تعمل وتمنع التزامن",
                attempts.size <= 2
            )
        }
}
