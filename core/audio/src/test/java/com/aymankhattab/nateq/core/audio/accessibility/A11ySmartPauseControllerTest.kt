package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class A11ySmartPauseControllerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var settings: SettingsRepository
    private lateinit var store: A11yStateStore

    @Before
    fun setUp() {
        A11ySmartPauseController.resetForTesting()
        settings = SettingsRepository.create(context)
        store = A11yStateStore.getInstance(context)!!
        store.setConnected(true)
        store.setPaused(false)
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_SMART_PAUSE_CALL,
            true
        )
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_SMART_PAUSE_GAME,
            true
        )
    }

    @After
    fun tearDown() {
        A11ySmartPauseController.resetForTesting()
        store.setPaused(false)
    }

    @Test
    fun `smart pause reasons toggle and evaluate properly`() {
        val controller = A11ySmartPauseController.getInstance(context)

        assertFalse(
            controller.isReasonActive(A11ySmartPauseController.REASON_IN_CALL)
        )
        assertFalse(controller.hasAnyActiveReason())

        controller.setReasonActive(
            A11ySmartPauseController.REASON_IN_CALL,
            true
        )
        assertTrue(
            controller.isReasonActive(A11ySmartPauseController.REASON_IN_CALL)
        )
        assertTrue(controller.hasAnyActiveReason())

        controller.setReasonActive(
            A11ySmartPauseController.REASON_IN_CALL,
            false
        )
        assertFalse(
            controller.isReasonActive(A11ySmartPauseController.REASON_IN_CALL)
        )
        assertFalse(controller.hasAnyActiveReason())
    }

    @Test
    fun `call and game overlap ends in call first then game`() {
        val controller = A11ySmartPauseController.getInstance(context)
        assertFalse(store.isPaused())

        // 1. بدء اللعبة
        controller.setReasonActive(
            A11ySmartPauseController.REASON_GAME,
            true
        )
        assertTrue(store.isPaused())

        // 2. ورود مكالمة أثناء اللعب
        controller.setReasonActive(
            A11ySmartPauseController.REASON_IN_CALL,
            true
        )
        assertTrue(store.isPaused())

        // 3. انتهاء المكالمة أولاً (اللعبة ما زالت تعمل)
        controller.setReasonActive(
            A11ySmartPauseController.REASON_IN_CALL,
            false
        )
        assertTrue(store.isPaused())

        // 4. انتهاء اللعبة: يستأنف النطق
        controller.setReasonActive(
            A11ySmartPauseController.REASON_GAME,
            false
        )
        assertFalse(store.isPaused())
    }

    @Test
    fun `call and game overlap ends in game first then call`() {
        val controller = A11ySmartPauseController.getInstance(context)
        assertFalse(store.isPaused())

        controller.setReasonActive(
            A11ySmartPauseController.REASON_GAME,
            true
        )
        assertTrue(store.isPaused())

        controller.setReasonActive(
            A11ySmartPauseController.REASON_IN_CALL,
            true
        )
        assertTrue(store.isPaused())

        // انتهاء اللعبة أولاً والمكالمة مستمرة
        controller.setReasonActive(
            A11ySmartPauseController.REASON_GAME,
            false
        )
        assertTrue(store.isPaused())

        // انتهاء المكالمة: يستأنف
        controller.setReasonActive(
            A11ySmartPauseController.REASON_IN_CALL,
            false
        )
        assertFalse(store.isPaused())
    }

    @Test
    fun `manual pause has precedence and is not resumed by smart pause`() {
        val controller = A11ySmartPauseController.getInstance(context)

        // المستخدم أوقف الخدمة يدوياً مسبقاً
        store.setPaused(true)
        assertTrue(store.isPaused())

        // ورود مكالمة
        controller.setReasonActive(
            A11ySmartPauseController.REASON_IN_CALL,
            true
        )
        assertTrue(store.isPaused())

        // انتهاء المكالمة: يجب ألا يُستأنف النطق لأن الإيقاف كان يدوياً
        controller.setReasonActive(
            A11ySmartPauseController.REASON_IN_CALL,
            false
        )
        assertTrue(store.isPaused())
    }

    @Test
    fun `disabled feature flag does not pause service`() {
        val controller = A11ySmartPauseController.getInstance(context)

        // تعطيل ميزة إيقاف المكالمة في الإعدادات
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_SMART_PAUSE_CALL,
            false
        )

        controller.setReasonActive(
            A11ySmartPauseController.REASON_IN_CALL,
            true
        )
        // السبب نشط لكن الميزة معطلة فلا تتوقف الخدمة
        assertTrue(
            controller.isReasonActive(A11ySmartPauseController.REASON_IN_CALL)
        )
        assertFalse(store.isPaused())

        // تفعيل الميزة أثناء استمرار المكالمة يؤدي للإيقاف
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_SMART_PAUSE_CALL,
            true
        )
        controller.evaluateSmartPause()
        assertTrue(store.isPaused())

        // انتهاء المكالمة يستأنف
        controller.setReasonActive(
            A11ySmartPauseController.REASON_IN_CALL,
            false
        )
        assertFalse(store.isPaused())
    }

    @Test
    fun `isGamePackage identifies system and normal apps correctly`() {
        assertFalse(A11ySmartPauseController.isGamePackage(context, null))
        assertFalse(A11ySmartPauseController.isGamePackage(context, ""))
        assertFalse(
            A11ySmartPauseController.isGamePackage(context, "android")
        )
        assertFalse(
            A11ySmartPauseController.isGamePackage(context, context.packageName)
        )
    }
}
