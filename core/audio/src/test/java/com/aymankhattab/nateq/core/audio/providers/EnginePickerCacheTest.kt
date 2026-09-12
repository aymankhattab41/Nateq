package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * اختبار كاش مسح المحركات: يُمسح النظام مرة واحدة خلال المهلة، ويُعاد
 * المسح بعد الإبطال (إزالة/تثبيت حزمة) أو بعد انقضاء المهلة.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EnginePickerCacheTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    private val clock = FakeClock()

    @Before
    fun setUp() {
        EnginePicker.invalidateCache()
        EnginePicker.nowProvider = { clock.now }
        // تثبيت محرك TTS وهمي في صندوق الحزم.
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val info = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = "com.example.tts"
                name = "ExampleEngineService"
            }
        }
        shadowOf(context.packageManager)
            .addResolveInfoForIntent(intent, info)
    }

    @Test
    fun cachesQueryAndInvalidateClears() {
        val first = EnginePicker.installedEngines(context)
        assertTrue(
            first.any { it.packageName == "com.example.tts" }
        )
        assertEquals(1, EnginePicker.cachedEngineCount())

        EnginePicker.invalidateCache()
        assertEquals(0, EnginePicker.cachedEngineCount())
        // بعد الإبطال يُعاد المسح من جديد (يوجد المحرك نفسه).
        assertEquals(1, EnginePicker.installedEngines(context).size)
    }

    @Test
    fun cacheExpiresAfterTtl() {
        EnginePicker.installedEngines(context)
        assertEquals(1, EnginePicker.cachedEngineCount())
        clock.advance(EnginePicker.CACHE_TTL_MS)
        // انقضت المهلة: المسح التالي يجري من جديد (الكاش ما زال يحمل
        // القيم القديمة حتى يُستبدل بالمسح الجديد).
        assertEquals(1, EnginePicker.installedEngines(context).size)
    }

    private class FakeClock {
        var now: Long = System.currentTimeMillis()
        fun advance(ms: Long) {
            now += ms
        }
    }
}