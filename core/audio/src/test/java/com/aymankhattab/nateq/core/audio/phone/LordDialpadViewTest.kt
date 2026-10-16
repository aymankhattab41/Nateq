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
}
