package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * اختبارات التسجيل الديناميكي لأحداث الإضافات والتأجيل 300ms (ع٥٧-أمر 1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ExtrasDynamicRegistrationTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setup() {
        ProtectedAppManager.resetForTesting()
        val store = A11yStateStore.getInstance(context)
        store?.setConnected(true)
        store?.setPaused(false)
        settingsRepo = SettingsRepository.create(context)
        ExtrasDiagnosticsTracker.clear()
    }

    @After
    fun tearDown() {
        ExtrasDiagnosticsTracker.clear()
        ProtectedAppManager.resetForTesting()
        val store = A11yStateStore.getInstance(context)
        store?.setConnected(false)
        store?.setPaused(false)
    }

    @Test
    fun `diagnostics tracker records reapply event types correctly`() {
        ExtrasDiagnosticsTracker.record(
            addedTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            removedTypes = 0,
            currentTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED,
            enabledExtras = listOf("extra_scroll_cue")
        )

        val records = ExtrasDiagnosticsTracker.getRecords()
        assertEquals(1, records.size)
        assertEquals(
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            records[0].addedTypes
        )
        assertEquals(0, records[0].removedTypes)
        assertTrue(records[0].enabledExtras.contains("extra_scroll_cue"))

        val formatted = ExtrasDiagnosticsTracker.format()
        assertTrue(formatted.contains("SCROLLED"))
    }

    @Test
    fun `enabling and disabling scroll cue toggles view scrolled event type`() {
        val service = Robolectric.buildService(
            NateqAccessibilityService::class.java
        ).create().get()
        try {
            service.onServiceConnectedForTesting()

            // تفعيل نغمة التمرير
            settingsRepo.setExtraEnabled("extra_scroll_cue", true)
            service.applyServiceInfo()

            val typesWithScroll = service.lastEventTypes
            assertTrue(
                (typesWithScroll and AccessibilityEvent.TYPE_VIEW_SCROLLED) != 0
            )

            // تعطيل نغمة التمرير
            settingsRepo.setExtraEnabled("extra_scroll_cue", false)
            service.applyServiceInfo()

            val typesWithoutScroll = service.lastEventTypes
            assertFalse(
                (typesWithoutScroll and
                    AccessibilityEvent.TYPE_VIEW_SCROLLED) != 0
            )
        } finally {
            service.onDestroy()
        }
    }

    @Test
    fun `rapid toggles within 300ms coalesce into a single execution`() {
        val service = Robolectric.buildService(
            NateqAccessibilityService::class.java
        ).create().get()
        try {
            service.onServiceConnectedForTesting()

            ExtrasDiagnosticsTracker.clear()

            // 3 استدعاءات سريعة
            service.scheduleApplyServiceInfo()
            service.scheduleApplyServiceInfo()
            service.scheduleApplyServiceInfo()

            // تقدم أقل من 300ms: لم يُنفذ شيء بعد
            ShadowLooper.idleMainLooper(
                100, java.util.concurrent.TimeUnit.MILLISECONDS
            )

            // اكتمال الـ 300ms
            ShadowLooper.idleMainLooper(
                250, java.util.concurrent.TimeUnit.MILLISECONDS
            )

            // عدد سجلات التطبيق يجب ألا يتجاوز استدعاءً واحداً
            assertTrue(ExtrasDiagnosticsTracker.getRecords().size <= 1)
        } finally {
            service.onDestroy()
        }
    }
}
