package com.aymankhattab.nateq

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.nav.SettingsOpenRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبار سجلّ وجهة فتح الإشعارات: الافتراضي المحايد (شاشة الإقلاع) ما لم
 * تُسجَّل الوجهة، والعودة للوجهة المسجَّلة حين توجد — بلا تسمية صف نصية.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsOpenRegistryTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        SettingsOpenRegistry.resetForTests()
    }

    @Test
    fun withoutRegistration_resolvesReasonableFallback() {
        val intent = SettingsOpenRegistry.resolve(context)
        // الوجهة المحايدة: إما تشغيل شاشة الإقلاع الحقيقية لحزمة التطبيق،
        // أو (حين لا يُحلّ مشغّلٌ ما تحت بيئة الاختبار) صفحة تفاصيل
        // التطبيق — الاثنان «وجهة معقولة» لفتح الإشعار.
        val launchesApp =
            intent.component?.packageName == context.packageName
        val opensAppDetails =
            intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS &&
                intent.data?.scheme == "package" &&
                intent.data?.schemeSpecificPart == context.packageName
        assertTrue(
            "افتراضيٌ معقول يفتح التطبيقَ أو صفحة تفاصيله",
            launchesApp || opensAppDetails
        )
        assertFalse(intent.action == null && intent.component == null)
    }

    @Test
    fun withRegistration_resolvesRegisteredTarget() {
        SettingsOpenRegistry.register { ctx ->
            Intent(ctx, SettingsActivityStub::class.java)
        }
        val intent = SettingsOpenRegistry.resolve(context)
        assertTrue(
            intent.component?.className ==
                SettingsActivityStub::class.java.name
        )
    }
}

// واجهة لا Activity فعلياً تكفي لفحص التوجيه (لا تُطلق في الاختبار).
class SettingsActivityStub