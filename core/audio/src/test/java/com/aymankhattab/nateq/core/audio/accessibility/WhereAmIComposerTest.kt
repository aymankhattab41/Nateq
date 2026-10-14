package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhereAmIComposerTest {

    @Test
    fun fullSentenceHasFixedOrder() {
        val node = NodeView(
            text = "موافق",
            className = "android.widget.Button"
        )
        assertEquals(
            "أنت في واتساب، الشاشة Home، العنصر زر: موافق",
            WhereAmIComposer.compose("واتساب", "Home", node)
        )
    }

    @Test
    fun passwordHidesContent() {
        val node = NodeView(
            text = "سر123",
            className = "android.widget.EditText",
            isPassword = true
        )
        val text = WhereAmIComposer.compose("تطبيق", null, node)
        assertEquals("أنت في تطبيق، العنصر حقل كلمة مرور", text)
        assertFalse(text.contains("سر123"))
    }

    @Test
    fun checkedBoxIsMarkedSelected() {
        val node = NodeView(
            text = "اشترك",
            className = "android.widget.CheckBox",
            isCheckable = true,
            isChecked = true
        )
        assertTrue(
            WhereAmIComposer.compose("م", null, node).endsWith("، محدد")
        )
    }

    @Test
    fun uncheckedBoxIsMarkedNotSelected() {
        val node = NodeView(
            className = "android.widget.CheckBox",
            isCheckable = true,
            isChecked = false
        )
        assertTrue(
            WhereAmIComposer.compose("م", null, node).endsWith("، غير محدد")
        )
    }

    @Test
    fun disabledElementIsMarkedDisabled() {
        val node = NodeView(
            text = "إرسال",
            className = "android.widget.Button",
            isEnabled = false
        )
        assertTrue(
            WhereAmIComposer.compose("م", null, node).endsWith("، غير مفعّل")
        )
    }

    @Test
    fun noNodeGivesAppOnly() {
        assertEquals(
            "أنت في تطبيق",
            WhereAmIComposer.compose("تطبيق", null, null)
        )
    }

    @Test
    fun screenLabelStripsPackageAndActivity() {
        assertEquals(
            "Home",
            WhereAmIComposer.screenLabel("com.whatsapp.HomeActivity")
        )
        assertEquals("Main", WhereAmIComposer.screenLabel("com.x.MainActivity"))
        assertEquals("Settings", WhereAmIComposer.screenLabel("Settings"))
        assertNull(WhereAmIComposer.screenLabel(null))
        assertNull(WhereAmIComposer.screenLabel(""))
    }
}
