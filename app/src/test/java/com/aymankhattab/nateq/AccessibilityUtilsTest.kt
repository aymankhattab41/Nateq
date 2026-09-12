package com.aymankhattab.nateq

import android.content.Context
import android.provider.Settings
import com.aymankhattab.nateq.util.AccessibilityUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبار كشف قارئ الشاشة: التمييز عبر استكشاف اللمس، وعبر قائمة خدمات
 * الإتاحة المفعّلة بحثاً عن حزم قارئات معروفة (TalkBack…). يغطي
 * تعايش النطق المستقل مع القراءة (بند TalkBack).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityUtilsTest {

    @Test
    fun noReaderEnabled_returnsFalse() {
        val context = androidx.test.core.app
            .ApplicationProvider.getApplicationContext<Context>()
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ""
        )
        assertFalse(AccessibilityUtils.isScreenReaderEnabled(context))
    }

    @Test
    fun talkBackEnabled_returnsTrue() {
        val context = androidx.test.core.app
            .ApplicationProvider.getApplicationContext<Context>()
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.some.app/NoService:" +
                "com.google.android.marvin.talkback/TalkBackService"
        )
        assertTrue(AccessibilityUtils.isScreenReaderEnabled(context))
    }

    @Test
    fun otherAccessibilityService_returnsFalse() {
        val context = androidx.test.core.app
            .ApplicationProvider.getApplicationContext<Context>()
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.other.app/MyAccessibilityService"
        )
        assertFalse(AccessibilityUtils.isScreenReaderEnabled(context))
    }
}