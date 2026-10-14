package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات تهيئة [VocalizerHostSession] ورسائل الفشل
 * الصريحة (ع٣٠-3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VocalizerHostSessionInitTest {

    private lateinit var context: Context
    private lateinit var session: VocalizerHostSession

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        session = VocalizerHostSession(context)
    }

    @Test
    fun init_withBlankDataDir_returnsNoDataDirFailure() {
        val res = session.init("   ")
        assertFalse(res.ok)
        assertEquals("NO_DATA_DIR", res.code)
    }

    @Test
    fun init_whenNativeEngineNotCreated_returnsNativeCreateFailed() {
        // في Robolectric لا تُحمّل المكتبة فيرمي
        // createNativeEngine خطأ أو يبقى المؤشر 0.
        val res = session.init("/dummy/dir")
        assertFalse(res.ok)
        assertEquals("NATIVE_CREATE_FAILED", res.code)
        val msg = res.message ?: ""
        assertTrue(
            "رسالة الخطأ يجب أن توضح سبب الفشل: $msg",
            msg.contains("UnsatisfiedLinkError") ||
                msg.contains("mNativeEnginePtr")
        )
    }

    @Test
    fun outputSampleRateHz_convertsEngineKhzToAcceptableHz() {
        // المحرّك يعيد 22 (كيلوهرتز) — يجب ألا يُبنى AudioTrack
        // بمعدّل كيلوهرتزيّ فيرفضه النظام («Invalid sample rate»).
        val rate = VocalizerHostSession.outputSampleRateHz(22)
        assertEquals(22000, rate)
        assertTrue("المعدل يجب أن يقبله AudioTrack: $rate", rate > 8000)
    }

    @Test
    fun outputSampleRateHz_keepsAlreadyHzValuesUnchanged() {
        // القيمة 22050 هرتز أصلاً (الافتراضي) لا تُضاعف خطأً.
        assertEquals(
            22050,
            VocalizerHostSession.outputSampleRateHz(22050)
        )
    }

    @Test
    fun outputSampleRateHz_fallsBackWhenInvalid() {
        val fallback =
            VocalizerHostSession.DEFAULT_OUTPUT_SAMPLE_RATE_HZ
        assertEquals(
            fallback,
            VocalizerHostSession.outputSampleRateHz(0)
        )
        assertEquals(
            fallback,
            VocalizerHostSession.outputSampleRateHz(-5)
        )
        assertEquals(
            fallback,
            VocalizerHostSession.outputSampleRateHz(500_000)
        )
    }
}
