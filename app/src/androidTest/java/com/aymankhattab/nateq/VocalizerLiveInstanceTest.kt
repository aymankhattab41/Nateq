package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aymankhattab.nateq.core.audio.enginehost.VocalizerHostSession
import com.nuance.android.vocalizer.VocalizerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * اختبار أجهزة لتأكيد تحرير كائنات Vocalizer الأصلية بعد فشل متكرر
 * (ع٣٣-1 بند 4).
 */
@RunWith(AndroidJUnit4::class)
class VocalizerLiveInstanceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        VocalizerEngine.resetLiveInstanceCount()
    }

    @Test
    fun repeatedInitFailures_doNotIncreaseLiveInstanceCount() {
        val initialCount = VocalizerEngine.getLiveInstanceCount()
        val session = VocalizerHostSession(context)

        // محاولات متكررة بمسار غير صالح
        for (i in 1..5) {
            session.init("/invalid/data/path/$i")
            assertEquals(
                "يجب ألا تتراكم الكائنات الحية بعد الفشل #$i",
                initialCount,
                VocalizerEngine.getLiveInstanceCount()
            )
        }
    }

    @Test
    fun singleEngine_destroyDecrementsLiveInstanceCount() {
        val countBefore = VocalizerEngine.getLiveInstanceCount()
        val engine = VocalizerEngine(context)
        assertEquals(countBefore + 1, VocalizerEngine.getLiveInstanceCount())

        try {
            val stat = engine.statInfo
            if (stat != null) {
                assertTrue(stat.mHeapBlocks >= 0)
            }
        } catch (_: Throwable) {
            // المكتبة الأصلية قد لا تكون محملة
        }

        engine.destroy()
        assertEquals(countBefore, VocalizerEngine.getLiveInstanceCount())

        // استدعاء مكرر لا يسبب عداً سالباً
        engine.destroy()
        assertEquals(countBefore, VocalizerEngine.getLiveInstanceCount())
    }
}
