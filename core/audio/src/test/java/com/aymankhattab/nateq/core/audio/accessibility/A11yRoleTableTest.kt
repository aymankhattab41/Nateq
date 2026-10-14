package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class A11yRoleTableTest {

    @Test
    fun buttons() {
        assertEquals("زر", A11yRoleTable.roleFor("android.widget.Button"))
        assertEquals(
            "زر صورة",
            A11yRoleTable.roleFor("android.widget.ImageButton")
        )
    }

    @Test
    fun textFields() {
        assertEquals("حقل نص", A11yRoleTable.roleFor("android.widget.EditText"))
    }

    @Test
    fun selections() {
        assertEquals(
            "مربع اختيار",
            A11yRoleTable.roleFor("android.widget.CheckBox")
        )
        assertEquals(
            "زر اختيار",
            A11yRoleTable.roleFor("android.widget.RadioButton")
        )
        assertEquals(
            "مفتاح تبديل",
            A11yRoleTable.roleFor("androidx.appcompat.widget.SwitchCompat")
        )
    }

    @Test
    fun unknownClassNameFallsBackToElement() {
        assertEquals("عنصر", A11yRoleTable.roleFor("com.x.CustomThing"))
        assertEquals("عنصر", A11yRoleTable.roleFor(""))
        assertEquals("عنصر", A11yRoleTable.roleFor(null))
    }
}
