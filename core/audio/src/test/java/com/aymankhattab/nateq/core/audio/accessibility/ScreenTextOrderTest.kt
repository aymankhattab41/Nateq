package com.aymankhattab.nateq.core.audio.accessibility

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35, 37])
class ScreenTextOrderTest {

    private fun view(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) = NodeView(
        text = text,
        boundsInScreen = Rect(left, top, right, bottom)
    )

    @Test
    fun `orders texts top to bottom`() {
        val views = listOf(
            view("الثالث", 0, 300, 200, 340),
            view("الأول", 0, 0, 200, 40),
            view("الثاني", 0, 150, 200, 190)
        )
        assertEquals(
            listOf("الأول", "الثاني", "الثالث"),
            ScreenTextOrder.orderedTexts(views, rtl = true)
        )
    }

    @Test
    fun `rtl orders same row right to left`() {
        val views = listOf(
            view("يسار", 0, 100, 120, 140),
            view("يمين", 200, 100, 320, 140)
        )
        assertEquals(
            listOf("يمين", "يسار"),
            ScreenTextOrder.orderedTexts(views, rtl = true)
        )
    }

    @Test
    fun `ltr orders same row left to right`() {
        val views = listOf(
            view("right", 200, 100, 320, 140),
            view("left", 0, 100, 120, 140)
        )
        assertEquals(
            listOf("left", "right"),
            ScreenTextOrder.orderedTexts(views, rtl = false)
        )
    }

    @Test
    fun `drops blank readable texts`() {
        val views = listOf(
            view("", 0, 0, 10, 10),
            view("نص", 0, 50, 100, 90)
        )
        assertEquals(
            listOf("نص"),
            ScreenTextOrder.orderedTexts(views, rtl = true)
        )
    }

    @Test
    fun `dedupes consecutive identical texts`() {
        assertEquals(
            listOf("أ", "ب"),
            ScreenTextOrder.dedupeConsecutive(
                listOf("أ", "أ", "ب", "ب", "ب")
            )
        )
    }
}
