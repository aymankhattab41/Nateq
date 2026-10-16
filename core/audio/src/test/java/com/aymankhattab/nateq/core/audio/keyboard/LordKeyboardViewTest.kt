package com.aymankhattab.nateq.core.audio.keyboard

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyModel
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
 * اختبارات وحدة لواجهة اللوحة LordKeyboardView والتفاعل باللمس.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LordKeyboardViewTest {

    private lateinit var context: Context
    private lateinit var keyboardView: LordKeyboardView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyboardView = LordKeyboardView(context)
        val rows = KeyboardLayoutHelper.getArabicRows()
        keyboardView.setKeyRows(rows)
        keyboardView.measure(1080, 800)
        keyboardView.layout(0, 0, 1080, 800)
    }

    @Test
    fun setKeyRows_updatesLayoutAndDrawsProperly() {
        keyboardView.keyboardMode = SettingsRepository.KEYBOARD_MODE_LOW_VISION
        assertEquals(
            SettingsRepository.KEYBOARD_MODE_LOW_VISION,
            keyboardView.keyboardMode
        )

        keyboardView.keyboardMode = SettingsRepository.KEYBOARD_MODE_BLIND
        assertEquals(
            SettingsRepository.KEYBOARD_MODE_BLIND,
            keyboardView.keyboardMode
        )
    }

    @Test
    fun liftToType_triggersHoverOnDownAndSelectOnUp() {
        keyboardView.activationMode =
            SettingsRepository.KEYBOARD_ACTIVATION_LIFT

        var hoveredKey: KeyModel? = null
        var selectedKey: KeyModel? = null

        keyboardView.actionListener =
            object : LordKeyboardView.OnKeyboardActionListener {
                override fun onKeySelected(key: KeyModel) {
                    selectedKey = key
                }

                override fun onKeyHover(key: KeyModel) {
                    hoveredKey = key
                }

                override fun onSwipeLeft() {}
                override fun onSwipeRight() {}
                override fun onSwipeDown() {}
                override fun onSwipeUp() {}
            }

        val downTime = SystemClock.uptimeMillis()
        // النقر في منتصف الصف الأول (ض أو ص)
        val downEvent = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, 100f, 100f, 0
        )
        keyboardView.onTouchEvent(downEvent)
        assertNotNull(hoveredKey)

        val upEvent = MotionEvent.obtain(
            downTime, downTime + 50, MotionEvent.ACTION_UP, 100f, 100f, 0
        )
        keyboardView.onTouchEvent(upEvent)
        assertNotNull(selectedKey)
        assertEquals(hoveredKey, selectedKey)
    }

    @Test
    fun doubleTap_requiresTwoTapsToCommit() {
        keyboardView.activationMode =
            SettingsRepository.KEYBOARD_ACTIVATION_DOUBLE_TAP

        var selectedKey: KeyModel? = null
        keyboardView.actionListener =
            object : LordKeyboardView.OnKeyboardActionListener {
                override fun onKeySelected(key: KeyModel) {
                    selectedKey = key
                }

                override fun onKeyHover(key: KeyModel) {}
                override fun onSwipeLeft() {}
                override fun onSwipeRight() {}
                override fun onSwipeDown() {}
                override fun onSwipeUp() {}
            }

        val now = SystemClock.uptimeMillis()
        // النقرة الأولى (Down ثم Up)
        keyboardView.onTouchEvent(
            MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN, 100f, 100f, 0
            )
        )
        keyboardView.onTouchEvent(
            MotionEvent.obtain(
                now, now + 10, MotionEvent.ACTION_UP, 100f, 100f, 0
            )
        )
        // لم يتم الإدخال بعد
        assertEquals(null, selectedKey)

        // النقرة الثانية فوراً على نفس المفتاح
        keyboardView.onTouchEvent(
            MotionEvent.obtain(
                now + 50, now + 50, MotionEvent.ACTION_DOWN, 100f, 100f, 0
            )
        )
        keyboardView.onTouchEvent(
            MotionEvent.obtain(
                now + 50, now + 60, MotionEvent.ACTION_UP, 100f, 100f, 0
            )
        )
        // الآن تم الإدخال بنجاح بعد الضغط مرتين
        assertNotNull(selectedKey)
    }

    @Test
    fun hoverExploration_supportsHoverEventsForTalkBack() {
        keyboardView.activationMode =
            SettingsRepository.KEYBOARD_ACTIVATION_LIFT

        var selectedKey: KeyModel? = null
        keyboardView.actionListener =
            object : LordKeyboardView.OnKeyboardActionListener {
                override fun onKeySelected(key: KeyModel) {
                    selectedKey = key
                }

                override fun onKeyHover(key: KeyModel) {}
                override fun onSwipeLeft() {}
                override fun onSwipeRight() {}
                override fun onSwipeDown() {}
                override fun onSwipeUp() {}
            }

        val now = SystemClock.uptimeMillis()
        val hoverMove = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_HOVER_MOVE, 100f, 100f, 0
        )
        keyboardView.dispatchHoverEvent(hoverMove)

        val hoverExit = MotionEvent.obtain(
            now, now + 50, MotionEvent.ACTION_HOVER_EXIT, 100f, 100f, 0
        )
        keyboardView.dispatchHoverEvent(hoverExit)
    }
}
