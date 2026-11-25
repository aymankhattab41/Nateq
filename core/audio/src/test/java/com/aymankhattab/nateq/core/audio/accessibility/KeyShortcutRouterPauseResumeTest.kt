package com.aymankhattab.nateq.core.audio.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات انحدار لموجه اختصارات المفاتيح والاستئناف أثناء الإيقاف (الأمر 20):
 * 1. اختصار TOGGLE_PAUSE يوقف الخدمة.
 * 2. نفس الاختصار يستأنف الخدمة بعد الإيقاف بنجاح.
 * 3. الأفعال الأخرى (قراءة الشاشة، OCR، الحافظة...) تظل ممنوعة أثناء الإيقاف.
 * 4. أزرار الصوت العادية غير المربوطة بـ TOGGLE_PAUSE لا تُستهلك أثناء الإيقاف.
 * 5. عند إطفاء ميزة اختصارات المفاتيح بالكامل، لا يُستهلك المفتاح.
 * 6. استمرار مسار الطوارئ وأزرار النظام دون تأثر.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyShortcutRouterPauseResumeTest {

    private lateinit var context: Context
    private lateinit var service: AccessibilityService
    private lateinit var settings: SettingsRepository
    private var stateStore: A11yStateStore? = null
    private lateinit var router: KeyShortcutRouter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stateStore = A11yStateStore.getInstance(context)
        stateStore?.setConnected(true)
        stateStore?.setPaused(false)

        settings = SettingsRepository.create(context)
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_KEY_SHORTCUTS,
            true
        )

        service = Robolectric.buildService(
            NateqAccessibilityService::class.java
        ).create().get()

        router = KeyShortcutRouter(service, settings, stateStore)
    }

    @After
    fun tearDown() {
        stateStore?.setPaused(false)
        stateStore?.setConnected(false)
        router.reset()
    }

    private fun sendVolumeDownLongPress(now: Long = 1000L): Boolean {
        val down = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            0
        )
        val repeatDown = KeyEvent(
            now,
            now + 600L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            1
        )
        val up = KeyEvent(
            now,
            now + 650L,
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            0
        )
        router.onKeyEvent(down)
        val repeatConsumed = router.onKeyEvent(repeatDown)
        val upConsumed = router.onKeyEvent(up)
        return repeatConsumed || upConsumed
    }

    private fun sendVolumeUpLongPress(now: Long = 1000L): Boolean {
        val down = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            0
        )
        val repeatDown = KeyEvent(
            now,
            now + 600L,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            1
        )
        val up = KeyEvent(
            now,
            now + 650L,
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_VOLUME_UP,
            0
        )
        router.onKeyEvent(down)
        val repeatConsumed = router.onKeyEvent(repeatDown)
        val upConsumed = router.onKeyEvent(up)
        return repeatConsumed || upConsumed
    }

    @Test
    fun togglePause_canPause_and_sameGesture_canResume() {
        // ربط الضغط المطول على خفض الصوت بتبديل الإيقاف TOGGLE_PAUSE
        val table = ShortcutBindingTable.defaults().with(
            ShortcutGesture.VOL_DOWN_LONG,
            ShortcutAction.TOGGLE_PAUSE
        )
        router.setBindings(table)

        assertFalse("الخدمة تعمل في البداية", stateStore?.isPaused() == true)

        // الضغطة الأولى: إيقاف الخدمة
        val pauseConsumed = sendVolumeDownLongPress(1000L)
        assertTrue("يجب استهلاك الحدث عند تنفيذ الإيقاف", pauseConsumed)
        assertTrue("الخدمة أصبحت موقوفة الآن", stateStore?.isPaused() == true)

        // الضغطة الثانية بنفس الإيماءة: استئناف الخدمة
        val resumeConsumed = sendVolumeDownLongPress(5000L)
        assertTrue("يجب استهلاك الحدث عند تنفيذ الاستئناف", resumeConsumed)
        assertFalse(
            "الخدمة استؤنفت بنجاح وعادت تعمل",
            stateStore?.isPaused() == true
        )
    }

    @Test
    fun otherActions_blocked_while_paused_and_do_not_consume_keys() {
        // ربط رفع الصوت بقراءة الشاشة وخفض الصوت بتبديل الإيقاف
        val table = ShortcutBindingTable.defaults()
            .with(ShortcutGesture.VOL_UP_LONG, ShortcutAction.READ_SCREEN)
            .with(ShortcutGesture.VOL_DOWN_LONG, ShortcutAction.TOGGLE_PAUSE)
        router.setBindings(table)

        // إيقاف الخدمة
        stateStore?.setPaused(true)
        assertTrue(stateStore?.isPaused() == true)

        // محاولة تنفيذ قراءة الشاشة أثناء الإيقاف
        val readConsumed = sendVolumeUpLongPress(2000L)
        assertFalse(
            "لا يجوز استهلاك مفتاح رفع الصوت أو قراءة الشاشة أثناء الإيقاف",
            readConsumed
        )

        // لكن اختصار الاستئناف يعمل ويستهلك
        val resumeConsumed = sendVolumeDownLongPress(4000L)
        assertTrue("اختصار الاستئناف يجب أن يعمل", resumeConsumed)
        assertFalse("الخدمة عادت للعمل", stateStore?.isPaused() == true)

        // بعد الاستئناف، قراءة الشاشة تستهلك وتعمل بصورة طبيعية
        val readAfterResumeConsumed = sendVolumeUpLongPress(7000L)
        assertTrue(
            "بعد الاستئناف تعود قراءة الشاشة للاستهلاك والعمل",
            readAfterResumeConsumed
        )
    }

    @Test
    fun emergency_bothHold_works_in_paused_and_unpaused() {
        // اختبار مسار الطوارئ executeGesture(BOTH_HOLD)
        assertFalse(stateStore?.isPaused() == true)

        router.executeGesture(ShortcutGesture.BOTH_HOLD)
        assertTrue("BOTH_HOLD يوقف الخدمة", stateStore?.isPaused() == true)

        router.executeGesture(ShortcutGesture.BOTH_HOLD)
        assertFalse("BOTH_HOLD يستأنف الخدمة", stateStore?.isPaused() == true)
    }

    @Test
    fun whenKeyShortcutsDisabled_noShortcutsWork() {
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_KEY_SHORTCUTS,
            false
        )
        val table = ShortcutBindingTable.defaults().with(
            ShortcutGesture.VOL_DOWN_LONG,
            ShortcutAction.TOGGLE_PAUSE
        )
        router.setBindings(table)

        val consumed = sendVolumeDownLongPress(1000L)
        assertFalse("لا يُستهلك الحدث عند تعطيل ميزة الاختصارات", consumed)
        assertFalse("لا تتغير حالة الإيقاف", stateStore?.isPaused() == true)
    }
}
