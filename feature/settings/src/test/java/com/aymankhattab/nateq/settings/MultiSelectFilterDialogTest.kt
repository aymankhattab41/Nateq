package com.aymankhattab.nateq.settings

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.feature.settings.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * اختبارات وحدة لإطار الحوار المشترك متعدد الاختيار [MultiSelectFilterDialog].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MultiSelectFilterDialogTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(
            com.google.android.material.R.style
                .Theme_MaterialComponents_DayNight
        )
    }

    @Test
    fun multiSelectItem_hasOnlyIdAndLabel() {
        val item = MultiSelectItem("pkg.a", "Engine A")
        assertEquals("pkg.a", item.id)
        assertEquals("Engine A", item.label)
    }

    @Test
    fun showDialog_confirmWithSelection_invokesOnSelected() {
        val items = listOf(
            MultiSelectItem("eng1", "Engine 1"),
            MultiSelectItem("eng2", "Engine 2")
        )
        var selectedResult: Set<String>? = null

        MultiSelectFilterDialog.show(
            context = context,
            title = "اختر المحركات",
            items = items,
            initialSelected = setOf("eng1"),
            emptyErrorMessage = "يجب اختيار محرك واحد على الأقل",
            onSelected = { selectedResult = it }
        )
        ShadowLooper.idleMainLooper()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotNull(dialog)
        assertTrue(dialog.isShowing)

        val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertNotNull(okButton)
        okButton.performClick()

        assertNotNull(selectedResult)
        assertEquals(setOf("eng1"), selectedResult)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun showDialog_emptySelection_showsToastAndDoesNotConfirm() {
        val items = listOf(
            MultiSelectItem("eng1", "Engine 1")
        )
        var invoked = false

        MultiSelectFilterDialog.show(
            context = context,
            title = "اختر المحركات",
            items = items,
            initialSelected = emptySet(),
            emptyErrorMessage = "خطأ: القائمة فارغة",
            onSelected = { invoked = true }
        )
        ShadowLooper.idleMainLooper()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertNotNull(dialog)

        val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        okButton.performClick()

        assertFalse("يجب ألا يتم التأكيد عند فراغ التحديد", invoked)
        assertTrue(dialog.isShowing)
        assertEquals("خطأ: القائمة فارغة", ShadowToast.getTextOfLatestToast())
    }
}
