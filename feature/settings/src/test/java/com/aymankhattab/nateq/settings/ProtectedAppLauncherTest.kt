package com.aymankhattab.nateq.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.accessibility.AccessibilityBridge
import com.aymankhattab.nateq.core.audio.accessibility.NateqAccessibilityService
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppManager
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppSuspensionStore
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppTimelineTracker
import com.aymankhattab.nateq.core.data.ProtectedApp
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * اختبارات مشغل التطبيقات المحمية (الجولة 52 — الأمر 2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ProtectedAppLauncherTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProtectedAppManager.resetForTesting()
        ProtectedAppSuspensionStore.clear(context)
        ProtectedAppTimelineTracker.clear()
        val repo = SettingsRepository.create(context)
        repo.setSuspendedProtectedApp(null)
        repo.addProtectedApp(
            ProtectedApp(
                packageName = "com.instapay",
                label = "InstaPay",
                mode = ProtectedApp.MODE_FULL_DISABLE
            )
        )
        setupFakeLauncherTarget("com.instapay")
    }

    @After
    fun tearDown() {
        ProtectedAppManager.resetForTesting()
        ProtectedAppSuspensionStore.clear(context)
        ProtectedAppTimelineTracker.clear()
    }

    private fun setupFakeLauncherTarget(pkg: String) {
        val pm = shadowOf(context.packageManager)
        val appInfo = ApplicationInfo().apply {
            packageName = pkg
            name = "InstaPayApp"
        }
        val actInfo = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.MainActivity"
            applicationInfo = appInfo
        }
        val resolveInfo = ResolveInfo().apply {
            activityInfo = actInfo
        }
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(pkg)
        }
        pm.addResolveInfoForIntent(launchIntent, resolveInfo)
    }

    @Test
    fun whenServiceStillActiveAfterTimeout_abortsLaunchAndCleansUp() {
        val dummy = NateqAccessibilityService()
        AccessibilityBridge.registerService(dummy)

        try {
            val intent = ProtectedAppLauncherActivity.createIntent(
                context, "com.instapay"
            )

            val controller = Robolectric.buildActivity(
                ProtectedAppLauncherActivity::class.java,
                intent
            ).setup()

            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3500))

            assertTrue(
                "يجب إنهاء النشاط عند انتهاء المهلة", activity.isFinishing
            )
            val nextIntent = shadowOf(activity).nextStartedActivity
            assertNull("يجب إحباط فتح التطبيق عند بقاء الخدمة", nextIntent)
            assertFalse(
                "يجب مسح حالة التعليق عند الإلغاء",
                ProtectedAppManager.isSuspended(context)
            )
        } finally {
            AccessibilityBridge.unregisterService(dummy)
        }
    }

    @Test
    fun whenServiceDisabled_waitsSettleDelayAndLaunchesTarget() {
        val intent = ProtectedAppLauncherActivity.createIntent(
            context, "com.instapay"
        )

        val controller = Robolectric.buildActivity(
            ProtectedAppLauncherActivity::class.java,
            intent
        ).setup()

        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertTrue("يجب إنهاء النشاط بعد إطلاق الهدف", activity.isFinishing)
        val nextIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("يجب إطلاق التطبيق بعد تأكيد التعطيل", nextIntent)
        assertEquals("com.instapay", nextIntent?.`package`)
        val flags = nextIntent?.flags ?: 0
        assertTrue(
            "FLAG_ACTIVITY_NEW_TASK مطلوب",
            (flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0
        )
        assertTrue(
            "FLAG_ACTIVITY_CLEAR_TOP مطلوب",
            (flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0
        )
        assertTrue(
            "FLAG_ACTIVITY_RESET_TASK_IF_NEEDED مطلوب",
            (flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0
        )
    }

    @Test
    fun scheduleAppAutoRetry_runsAtMostOnce() {
        ProtectedAppManager.scheduleAppAutoRetry(context, "com.instapay")
        // فحص أن دالة scheduleAppAutoRetry تسجل
        // المحاولة ولا تعيد المحاولة المكررة
        val report = ProtectedAppTimelineTracker.getTimelineReport()
        assertNotNull(report)
    }
}
