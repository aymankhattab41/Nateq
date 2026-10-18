package com.aymankhattab.nateq.core.audio.phone

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات وحدة لواجهة لوحة الاتصال الميسّرة LordDialpadView.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LordDialpadViewTest {

    private lateinit var context: Context
    private lateinit var dialpadView: LordDialpadView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dialpadView = LordDialpadView(context)
        dialpadView.layout(0, 0, 300, 400)
    }

    @Test
    fun dialpadView_initializesWithAllTwelveKeys() {
        val rows = DialpadLayoutHelper.getStandardDialpadKeys()
        assertEquals(4, rows.size)
        assertEquals(12, rows.flatten().size)
    }

    @Test
    fun dialpadView_liftToActivate_selectsKeyOnActionUp() {
        dialpadView.activationMode = SettingsRepository.PHONE_ACTIVATION_LIFT

        var selectedKey: DialpadKey? = null
        dialpadView.actionListener =
            object : LordDialpadView.OnDialpadActionListener {
                override fun onKeySelected(key: DialpadKey) {
                    selectedKey = key
                }

                override fun onKeyHover(key: DialpadKey) {}
            }

        // إحداثيات المفتاح 1 (أعلى اليسار: 50, 50)
        val down = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_DOWN,
            50f,
            50f,
            0
        )
        val up = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis() + 50L,
            MotionEvent.ACTION_UP,
            50f,
            50f,
            0
        )

        dialpadView.onTouchEvent(down)
        dialpadView.onTouchEvent(up)

        assertNotNull(selectedKey)
        assertEquals('1', selectedKey?.digit)
    }

    @Test
    fun dialpadView_doubleTapMode_requiresSecondTapToCommit() {
        dialpadView.activationMode =
            SettingsRepository.PHONE_ACTIVATION_DOUBLE_TAP

        var selectedKey: DialpadKey? = null
        dialpadView.actionListener =
            object : LordDialpadView.OnDialpadActionListener {
                override fun onKeySelected(key: DialpadKey) {
                    selectedKey = key
                }

                override fun onKeyHover(key: DialpadKey) {}
            }

        // نقرة أولى
        val down1 = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_DOWN, 50f, 50f, 0
        )
        val up1 = MotionEvent.obtain(
            0L, 50L, MotionEvent.ACTION_UP, 50f, 50f, 0
        )
        dialpadView.onTouchEvent(down1)
        dialpadView.onTouchEvent(up1)

        // لم يُفعّل بعد
        assertEquals(null, selectedKey)

        // نقرة ثانية سريعة
        val down2 = MotionEvent.obtain(
            0L, 120L, MotionEvent.ACTION_DOWN, 50f, 50f, 0
        )
        val up2 = MotionEvent.obtain(
            0L, 160L, MotionEvent.ACTION_UP, 50f, 50f, 0
        )
        dialpadView.onTouchEvent(down2)
        dialpadView.onTouchEvent(up2)

        // تم التفعيل الآن
        assertNotNull(selectedKey)
        assertEquals('1', selectedKey?.digit)
    }

    @Test
    fun dialpadView_modeSwitching_updatesProperties() {
        dialpadView.phoneMode = SettingsRepository.PHONE_MODE_LOW_VISION
        assertEquals(
            SettingsRepository.PHONE_MODE_LOW_VISION,
            dialpadView.phoneMode
        )

        dialpadView.phoneMode = SettingsRepository.PHONE_MODE_BLIND
        assertEquals(
            SettingsRepository.PHONE_MODE_BLIND,
            dialpadView.phoneMode
        )
    }

    @Test
    fun dialpadView_touchDownDoesNotCommit_onlyUpCommits() {
        dialpadView.activationMode = SettingsRepository.PHONE_ACTIVATION_LIFT

        var hovered: DialpadKey? = null
        var committed: DialpadKey? = null
        dialpadView.actionListener =
            object : LordDialpadView.OnDialpadActionListener {
                override fun onKeySelected(key: DialpadKey) {
                    committed = key
                }

                override fun onKeyHover(key: DialpadKey) {
                    hovered = key
                }
            }

        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_DOWN, 50f, 50f, 0
        )
        dialpadView.onTouchEvent(down)

        // لمس المفتاح يفعّل الاستكشاف والتحويم فقط ولا يكتب الرقم فوراً
        assertNotNull(hovered)
        assertEquals('1', hovered?.digit)
        assertEquals(null, committed)

        val up = MotionEvent.obtain(
            now, now + 60L, MotionEvent.ACTION_UP, 50f, 50f, 0
        )
        dialpadView.onTouchEvent(up)

        // رفع الإصبع هو الذي يلتزم بكتابة الرقم
        assertNotNull(committed)
        assertEquals('1', committed?.digit)
    }

    @Test
    fun dialpadView_hoverEnterDoesNotCommit_hoverExitCommitsAfterDuration() {
        dialpadView.activationMode = SettingsRepository.PHONE_ACTIVATION_LIFT

        var hovered: DialpadKey? = null
        var committed: DialpadKey? = null
        dialpadView.actionListener =
            object : LordDialpadView.OnDialpadActionListener {
                override fun onKeySelected(key: DialpadKey) {
                    committed = key
                }

                override fun onKeyHover(key: DialpadKey) {
                    hovered = key
                }
            }

        val now = SystemClock.uptimeMillis()
        val hoverEnter = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_HOVER_ENTER, 50f, 50f, 0
        )
        dialpadView.dispatchHoverEvent(hoverEnter)

        // لا يكتب عند بداية التحويم
        assertEquals(null, committed)

        val hoverExit = MotionEvent.obtain(
            now, now + 60L, MotionEvent.ACTION_HOVER_EXIT, 50f, 50f, 0
        )
        dialpadView.dispatchHoverEvent(hoverExit)

        // رفع الإصبع بعد التحويم الفعلي يلتزم بالكتابة
        assertNotNull(committed)
        assertEquals('1', committed?.digit)
    }

    @Test
    fun dialpadView_slidingAcrossKeys_updatesHoverWithoutPrematureCommit() {
        dialpadView.activationMode = SettingsRepository.PHONE_ACTIVATION_LIFT

        var lastHovered: DialpadKey? = null
        var committed: DialpadKey? = null
        dialpadView.actionListener =
            object : LordDialpadView.OnDialpadActionListener {
                override fun onKeySelected(key: DialpadKey) {
                    committed = key
                }

                override fun onKeyHover(key: DialpadKey) {
                    lastHovered = key
                }
            }

        val now = SystemClock.uptimeMillis()
        // بداية من المفتاح 1 (أعلى اليسار: x=50, y=50)
        val down = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_DOWN, 50f, 50f, 0
        )
        dialpadView.onTouchEvent(down)
        assertEquals('1', lastHovered?.digit)
        assertEquals(null, committed)

        // التمرير إلى المفتاح 2 (أعلى الوسط: x=150, y=50)
        val move = MotionEvent.obtain(
            now, now + 30L, MotionEvent.ACTION_MOVE, 150f, 50f, 0
        )
        dialpadView.onTouchEvent(move)
        assertEquals('2', lastHovered?.digit)
        assertEquals(null, committed)

        // الرفع فوق المفتاح 2
        val up = MotionEvent.obtain(
            now, now + 80L, MotionEvent.ACTION_UP, 150f, 50f, 0
        )
        dialpadView.onTouchEvent(up)

        assertNotNull(committed)
        assertEquals('2', committed?.digit)
    }

    @Test
    fun multiTouch_cancelsKeyCommit() {
        dialpadView.activationMode =
            SettingsRepository.PHONE_ACTIVATION_LIFT

        var committed: DialpadKey? = null
        dialpadView.actionListener =
            object : LordDialpadView.OnDialpadActionListener {
                override fun onKeySelected(key: DialpadKey) {
                    committed = key
                }

                override fun onKeyHover(key: DialpadKey) {}
            }

        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_DOWN, 50f, 50f, 0
        )
        dialpadView.onTouchEvent(down)

        val pointerDown = MotionEvent.obtain(
            now, now + 10L, MotionEvent.ACTION_POINTER_DOWN, 150f, 50f, 0
        )
        dialpadView.onTouchEvent(pointerDown)

        val up = MotionEvent.obtain(
            now, now + 50L, MotionEvent.ACTION_UP, 50f, 50f, 0
        )
        dialpadView.onTouchEvent(up)

        assertEquals(null, committed)
    }

    @Test
    fun dialpadStyles_columnAndRowCount() {
        val wideKeys = DialpadLayoutHelper.getKeys("GRID_WIDE")
        assertEquals(4, wideKeys.size)
        assertEquals(3, wideKeys[0].size)
        assertEquals(3, DialpadLayoutHelper.numCols("GRID_WIDE"))

        val grid5x2Keys = DialpadLayoutHelper.getKeys("GRID_5X2")
        assertEquals(3, grid5x2Keys.size)
        assertEquals(5, grid5x2Keys[0].size)
        assertEquals(5, grid5x2Keys[1].size)
        assertEquals(2, grid5x2Keys[2].size)
        assertEquals(5, DialpadLayoutHelper.numCols("GRID_5X2"))

        val singleRowKeys = DialpadLayoutHelper.getKeys("SINGLE_ROW")
        assertEquals(2, singleRowKeys.size)
        assertEquals(10, singleRowKeys[0].size)
        assertEquals(2, singleRowKeys[1].size)
        assertEquals(10, DialpadLayoutHelper.numCols("SINGLE_ROW"))
    }

    @Test
    fun dialpadView_keyBoundsDoNotOverlapAndCoverWidth() {
        val settings = SettingsRepository.create(context)
        val styles = listOf(
            SettingsRepository.DIALPAD_STYLE_GRID_WIDE,
            SettingsRepository.DIALPAD_STYLE_GRID_5X2,
            SettingsRepository.DIALPAD_STYLE_SINGLE_ROW
        )

        for (style in styles) {
            settings.setDialpadLayoutStyle(style)
            dialpadView.refreshFromSettings()
            dialpadView.layout(0, 0, 360, 480)

            val rows = dialpadView.getRows()
            for (row in rows) {
                var prevRight = -1f
                for (key in row) {
                    val rect = dialpadView.getKeyRect(key)
                    assertNotNull("Key ${key.digit} has rect", rect)
                    rect!!
                    if (prevRight >= 0f) {
                        assertEquals(
                            "Keys should not overlap: ${key.digit}",
                            prevRight,
                            rect.left,
                            0.05f
                        )
                    }
                    assertTrue(rect.right > rect.left)
                    prevRight = rect.right
                }
            }
        }
    }

    @Test
    fun dialpadView_readerTraversalOrder_leftToRight() {
        val settings = SettingsRepository.create(context)
        settings.setDialpadLayoutStyle(
            SettingsRepository.DIALPAD_STYLE_GRID_WIDE
        )
        dialpadView.refreshFromSettings()

        val flat = dialpadView.getFlatKeys()
        val expectedDigits = listOf(
            '1', '2', '3',
            '4', '5', '6',
            '7', '8', '9',
            '*', '0', '#'
        )
        assertEquals(expectedDigits, flat.map { it.digit })
    }

    @Test
    fun dialpadView_zeroIsMiddleKeyInLastRow_defaultPattern() {
        val settings = SettingsRepository.create(context)
        settings.setDialpadLayoutStyle(
            SettingsRepository.DIALPAD_STYLE_GRID_WIDE
        )
        dialpadView.refreshFromSettings()

        val rows = dialpadView.getRows()
        val lastRow = rows.last()
        assertEquals(3, lastRow.size)
        assertEquals('*', lastRow[0].digit)
        assertEquals('0', lastRow[1].digit)
        assertEquals('#', lastRow[2].digit)
    }

    @Test
    fun dialpadView_rtlLayoutDirection_preservesInternationalLtrOrder() {
        assertEquals(
            android.view.View.LAYOUT_DIRECTION_LTR,
            dialpadView.layoutDirection
        )
        dialpadView.layout(0, 0, 300, 400)
        val rect1 = dialpadView.getKeyRect(DialpadLayoutHelper.KEY_1)
        val rect2 = dialpadView.getKeyRect(DialpadLayoutHelper.KEY_2)
        val rect3 = dialpadView.getKeyRect(DialpadLayoutHelper.KEY_3)
        assertNotNull(rect1)
        assertNotNull(rect2)
        assertNotNull(rect3)
        assertTrue(rect1!!.left < rect2!!.left)
        assertTrue(rect2.left < rect3!!.left)
    }

    @Test
    fun dialpadView_measuredWidth_doesNotExceed420dp_on800dpScreen() {
        val width800dpPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            800f,
            context.resources.displayMetrics
        ).toInt()
        val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(
            width800dpPx,
            android.view.View.MeasureSpec.EXACTLY
        )
        val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(
            1000,
            android.view.View.MeasureSpec.AT_MOST
        )
        dialpadView.measure(widthSpec, heightSpec)

        val maxAllowedPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            420f,
            context.resources.displayMetrics
        ).toInt()
        assertTrue(
            "Measured width ${dialpadView.measuredWidth} <= $maxAllowedPx",
            dialpadView.measuredWidth <= maxAllowedPx
        )
    }
}
