package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * اختبارات بوابة الحساسية الموحدة A11ySensitivityGate:
 * فحص event.isPassword وعقد الشجرة المتداخلة وأنواع حقول كلمات المرور
 * والتعامل الآمن مع المصادر الملغاة/المعدومة، وتشفير نطق صدى الكتابة.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class A11ySensitivityGateTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun isSensitive_eventPasswordTrue_returnsTrue() {
        val event = AccessibilityEvent.obtain()
        event.isPassword = true
        assertTrue(A11ySensitivityGate.isSensitive(event))
    }

    @Test
    fun isSensitive_nullEvent_returnsFalse() {
        assertFalse(A11ySensitivityGate.isSensitive(null))
    }

    @Test
    fun isSensitive_eventWithNoSource_returnsFalse() {
        val event = AccessibilityEvent.obtain()
        event.isPassword = false
        assertFalse(A11ySensitivityGate.isSensitive(event))
    }

    @Test
    fun isSensitive_eventWithNormalView_returnsFalse() {
        val event = AccessibilityEvent.obtain()
        event.isPassword = false
        val view = View(context)
        event.setSource(view)

        assertFalse(A11ySensitivityGate.isSensitive(event))
    }

    @Test
    fun isNodeSensitive_nodeWithPasswordTrue_returnsTrue() {
        val node = AccessibilityNodeInfo.obtain()
        node.isPassword = true
        assertTrue(A11ySensitivityGate.isNodeSensitive(node))
    }

    @Test
    fun isNodeSensitive_nullNode_returnsFalse() {
        assertFalse(A11ySensitivityGate.isNodeSensitive(null))
    }

    @Test
    fun isNodeSensitive_containerWithSensitiveChild_returnsTrue() {
        val parent = AccessibilityNodeInfo.obtain()
        val child = AccessibilityNodeInfo.obtain()
        child.isPassword = true
        shadowOf(parent).addChild(child)

        assertTrue(A11ySensitivityGate.isNodeSensitive(parent))
    }

    @Test
    fun isNodeSensitive_textPasswordInputType_returnsTrue() {
        val node = AccessibilityNodeInfo.obtain()
        node.inputType = (
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        assertTrue(A11ySensitivityGate.isNodeSensitive(node))
    }

    @Test
    fun isNodeSensitive_numberPasswordInputType_returnsTrue() {
        val node = AccessibilityNodeInfo.obtain()
        node.inputType = (
            InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
        assertTrue(A11ySensitivityGate.isNodeSensitive(node))
    }

    @Test
    fun isNodeSensitive_normalTextInputType_returnsFalse() {
        val node = AccessibilityNodeInfo.obtain()
        node.isPassword = false
        node.inputType = InputType.TYPE_CLASS_TEXT
        assertFalse(A11ySensitivityGate.isNodeSensitive(node))
    }

    @Test
    fun isSensitiveInputType_variationsCheck() {
        val visiblePassword = (
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        )
        assertTrue(A11ySensitivityGate.isSensitiveInputType(visiblePassword))

        val webPassword = (
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
        assertTrue(A11ySensitivityGate.isSensitiveInputType(webPassword))

        val normalNumber = InputType.TYPE_CLASS_NUMBER
        assertFalse(A11ySensitivityGate.isSensitiveInputType(normalNumber))
    }

    @Test
    fun sanitizeEchoText_sensitiveEvent_returnsBullet() {
        val event = AccessibilityEvent.obtain()
        event.isPassword = true

        val masked = A11ySensitivityGate.sanitizeEchoText(
            event,
            "SecretPassword123"
        )
        assertEquals("•", masked)
    }

    @Test
    fun sanitizeEchoText_nonSensitiveEvent_returnsOriginalText() {
        val event = AccessibilityEvent.obtain()
        event.isPassword = false

        val original = A11ySensitivityGate.sanitizeEchoText(
            event,
            "Hello World"
        )
        assertEquals("Hello World", original)
    }

    @Test
    fun sanitizeEchoText_nullOrBlank_returnsNull() {
        val event = AccessibilityEvent.obtain()
        assertNull(A11ySensitivityGate.sanitizeEchoText(event, null))
        assertNull(A11ySensitivityGate.sanitizeEchoText(event, "  "))
    }
}
