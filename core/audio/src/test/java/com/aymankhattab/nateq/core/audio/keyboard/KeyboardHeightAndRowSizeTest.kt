package com.aymankhattab.nateq.core.audio.keyboard

import android.content.Context
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات انحدار لأبعاد وارتفاع لوحة المفاتيح LordKeyboardView (الأمر 15):
 * إزالة فرض 270dp في الخدمة، وتغيير ارتفاع الصف واللوحة بحسب
 * مستوى الحجم (صغير 40dp، متوسط 48dp، كبير 56dp)، مع عدد الصفوف
 * الإضافي، والدوران، وكثافات الشاشة، والمساحة المحدودة.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardHeightAndRowSizeTest {

    private lateinit var context: Context
    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsRepo = SettingsRepository.create(context)
    }

    @Test
    fun service_createsKeyboardViewWithWrapContentHeight_notFixed270dp() {
        val controller = Robolectric.buildService(
            LordKeyboardService::class.java
        )
        val service = controller.create().get()
        val root = service.onCreateInputView() as LinearLayout
        val kbView = service.keyboardView

        assertNotNull(kbView)
        val lp = kbView?.layoutParams
        assertNotNull(lp)
        assertEquals(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            lp?.height
        )
    }

    @Test
    fun sizeLevels_produceCorrectRowHeights_small40_medium48_large56() {
        val kbView = LordKeyboardView(context)
        val rows = KeyboardLayoutHelper.getArabicRows()
        kbView.setKeyRows(rows)
        val density = context.resources.displayMetrics.density

        // المستوى 0: صغير (40dp)
        kbView.sizeLevel = 0
        val widthSpec = MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(2000, MeasureSpec.AT_MOST)
        kbView.measure(widthSpec, heightSpec)

        val expectedSmallHeight = (rows.size * 40 * density).toInt()
        assertEquals(expectedSmallHeight, kbView.measuredHeight)

        // المستوى 1: متوسط (48dp)
        kbView.sizeLevel = 1
        kbView.measure(widthSpec, heightSpec)
        val expectedMediumHeight = (rows.size * 48 * density).toInt()
        assertEquals(expectedMediumHeight, kbView.measuredHeight)

        // المستوى 2: كبير (56dp)
        kbView.sizeLevel = 2
        kbView.measure(widthSpec, heightSpec)
        val expectedLargeHeight = (rows.size * 56 * density).toInt()
        assertEquals(expectedLargeHeight, kbView.measuredHeight)
    }

    @Test
    fun extraNumberRow_increasesTotalHeight_withoutSquishingExistingRows() {
        val kbView = LordKeyboardView(context)
        kbView.sizeLevel = 2 // كبير = 56dp
        val density = context.resources.displayMetrics.density

        val baseRows = KeyboardLayoutHelper.getArabicRows()
        val withNumberRow = listOf(
            KeyboardLayoutHelper.getNumberRow()
        ) + baseRows

        val widthSpec = MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(2000, MeasureSpec.AT_MOST)

        kbView.setKeyRows(baseRows)
        kbView.measure(widthSpec, heightSpec)
        val heightBaseRows = kbView.measuredHeight
        assertEquals((baseRows.size * 56 * density).toInt(), heightBaseRows)

        kbView.setKeyRows(withNumberRow)
        kbView.measure(widthSpec, heightSpec)
        val heightWithNumber = kbView.measuredHeight
        assertEquals(
            (withNumberRow.size * 56 * density).toInt(),
            heightWithNumber
        )

        assertTrue(heightWithNumber > heightBaseRows)
    }

    @Test
    fun limitedSpace_orLandscapeMode_respectsHeightConstraintAndScalesRows() {
        val kbView = LordKeyboardView(context)
        kbView.sizeLevel = 2 // كبير = 56dp (مطلوب 5 صفوف = 280px)
        val rows = KeyboardLayoutHelper.getArabicRows()
        kbView.setKeyRows(rows)

        val widthSpec = MeasureSpec.makeMeasureSpec(1920, MeasureSpec.EXACTLY)
        // مساحة مقيدة أصغر من الارتفاع المطلوب (مثلاً 200px في وضع أفقي)
        val constrainedHeightPx = 200
        val heightSpec = MeasureSpec.makeMeasureSpec(
            constrainedHeightPx,
            MeasureSpec.AT_MOST
        )
        kbView.measure(widthSpec, heightSpec)

        // يجب تقييد الارتفاع بالمساحة المتاحة
        assertEquals(constrainedHeightPx, kbView.measuredHeight)

        kbView.layout(0, 0, 1920, constrainedHeightPx)
        val expectedRowHeight = constrainedHeightPx.toFloat() / rows.size
        assertEquals(expectedRowHeight, kbView.getActiveRowHeightPx(), 0.01f)

        // التحقق من أن جميع المفاتيح تقع ضمن المساحة المحددة بدقة
        for (row in rows) {
            for (key in row) {
                val rect = kbView.getKeyRect(key)
                assertNotNull(rect)
                assertTrue(rect!!.top >= 0f)
                assertTrue(rect.bottom <= constrainedHeightPx.toFloat())
            }
        }
    }

    @Test
    fun keyRects_matchTouchAndDrawingBounds_withoutGapsOrOverlaps() {
        val kbView = LordKeyboardView(context)
        kbView.sizeLevel = 1 // متوسط
        val rows = KeyboardLayoutHelper.getArabicRows()
        kbView.setKeyRows(rows)

        val width = 1080
        val density = context.resources.displayMetrics.density
        val height = (rows.size * 48 * density).toInt()

        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(
            height,
            MeasureSpec.EXACTLY
        )
        kbView.measure(widthSpec, heightSpec)
        kbView.layout(0, 0, width, height)

        val rowHeight = height.toFloat() / rows.size
        for ((rowIndex, row) in rows.withIndex()) {
            val expectedTop = rowIndex * rowHeight
            val expectedBottom = (rowIndex + 1) * rowHeight

            var lastRight = 0f
            for (key in row) {
                val rect = kbView.getKeyRect(key)
                assertNotNull(rect)
                assertEquals(expectedTop, rect!!.top, 0.01f)
                assertEquals(expectedBottom, rect.bottom, 0.01f)
                assertEquals(lastRight, rect.left, 0.01f)
                lastRight = rect.right
            }
            assertEquals(width.toFloat(), lastRight, 0.01f)
        }
    }
}
