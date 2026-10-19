package com.aymankhattab.nateq.core.audio.keyboard

import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LordKeyboardServiceTest {

    @Test
    fun onCreateInputView_placesAllActionsIndividuallyInTopToolbar() {
        val controller =
            Robolectric.buildService(LordKeyboardService::class.java)
        val service = controller.create().get()

        val rootView = service.onCreateInputView() as? ViewGroup
        assertNotNull(rootView)

        // Root layout has: 1) Toolbar, 2) Tashkeel bar, 3) Keyboard view
        // Clipboard bar is removed and does not exist in root view
        assertEquals(3, rootView?.childCount)

        val toolbarScrollView =
            rootView?.getChildAt(0) as? HorizontalScrollView
        assertNotNull(toolbarScrollView)

        val toolbarStrip = toolbarScrollView?.getChildAt(0) as? LinearLayout
        assertNotNull(toolbarStrip)

        val buttonLabels =
            (0 until (toolbarStrip?.childCount ?: 0)).map { i ->
                (toolbarStrip?.getChildAt(i) as? Button)?.text?.toString() ?: ""
            }

        // Must include all clipboard tools individually in top row:
        assertTrue("Must contain نسخ", buttonLabels.contains("نسخ"))
        assertTrue("Must contain لصق", buttonLabels.contains("لصق"))
        assertTrue("Must contain قص", buttonLabels.contains("قص"))
        assertTrue(
            "Must contain تحديد الكل",
            buttonLabels.contains("تحديد الكل")
        )

        // Must NOT contain combined clipboard button
        assertFalse(
            "Must not contain combined clipboard button",
            buttonLabels.any { it.contains("حافظة") }
        )

        // Must contain other primary toolbar controls
        assertTrue("Must contain ع / En", buttonLabels.contains("ع / En"))
        assertTrue("Must contain ً حركات", buttonLabels.contains("ً حركات"))
        assertTrue("Must contain 🎤 صوت", buttonLabels.contains("🎤 صوت"))
        assertTrue("Must contain ⬇ إغلاق", buttonLabels.contains("⬇ إغلاق"))

        controller.destroy()
    }
}
