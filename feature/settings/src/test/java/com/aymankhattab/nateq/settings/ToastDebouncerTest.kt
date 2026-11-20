package com.aymankhattab.nateq.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.util.ToastDebouncer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * اختبارات مانع تكرار رسائل Toast وضابط معدل ظهورها (ع٣٢-1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ToastDebouncerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ToastDebouncer.resetForTesting()
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        ToastDebouncer.resetForTesting()
        ShadowToast.reset()
    }

    @Test
    fun show_displaysToastSuccessfully() {
        ToastDebouncer.show(context, "رسالة أولى")
        ShadowLooper.idleMainLooper()
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals("رسالة أولى", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun show_dropsIdenticalMessageWithinDedupeWindow() {
        ToastDebouncer.show(context, "نفس الرسالة")
        ShadowLooper.idleMainLooper()
        assertEquals(1, ShadowToast.shownToastCount())

        // استدعاء فوري بنفس الرسالة يجب إسقاطه
        ToastDebouncer.show(context, "نفس الرسالة")
        ShadowLooper.idleMainLooper()
        assertEquals(1, ShadowToast.shownToastCount())
    }

    @Test
    fun show_rateLimitsDifferentMessagesWithinOneSecond() {
        ToastDebouncer.show(context, "رسالة 1")
        ShadowLooper.idleMainLooper()
        assertEquals(1, ShadowToast.shownToastCount())

        // استدعاء فوري برسالة مختلفة يُسقط لاحتوائه في مهلة الثانية
        ToastDebouncer.show(context, "رسالة 2")
        ShadowLooper.idleMainLooper()
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals("رسالة 1", ShadowToast.getTextOfLatestToast())
    }
}
