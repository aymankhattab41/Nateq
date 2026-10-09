package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@Config(sdk = [37])
class EnginePickerCacheTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    private val clock = FakeClock()

    @Before
    @Suppress("DEPRECATION")
    fun setUp() {
        EnginePicker.invalidateCache()
        EnginePicker.nowProvider = { clock.now }
        // المزوّدات المدمجة سجلّ مفرد يبقى عبر اختبارات Robolectric
        // في نفس الصندوق؛ نظّفه حتى يعيد المزوّد بناءً على مجلد
        // الملفات الخاص بهذا الاختبار لا على مجلد اختبار سابق.
        EmbeddedProviders.shutdownAll()
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

    /** تنظيف أي ملف مدمج تركه اختبار آخر حتى لا يختلّ عدّ الكاش. */
    @After
    fun tearDown() {
        File(context.filesDir, "VocalizerEx2").deleteRecursively()
        File(context.filesDir, "AcapelaTTS").deleteRecursively()
        EnginePicker.invalidateCache()
        EmbeddedProviders.shutdownAll()
        EnginePicker.nowProvider = { System.currentTimeMillis() }
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

    /**
     * ع٢٩-9: المحركات المدمجة تُعاد بعد 15 ثانية وحدها، بينما يبقى
     * كاش محركات النظام صالحاً حتى مرور 5 دقائق.
     */
    @Test
    fun embeddedEnginesRescanAfterShortTtlWhileSystemStaysCached() {
        val vocalizerDir = File(context.filesDir, "VocalizerEx2")
        vocalizerDir.mkdirs()
        val hdrFile = File(vocalizerDir, "sample.hdr")
        hdrFile.writeBytes(ByteArray(100))
        val dataFile = File(vocalizerDir, "sample.dat")
        dataFile.writeBytes(ByteArray(1_048_576))

        val first = EnginePicker.installedEngines(context)
        assertTrue(
            first.any {
                it.packageName ==
                    VocalizerEmbeddedVoiceProvider.PROVIDER_ID
            }
        )

        // حذف الصوت لا يظهر قبل انقضاء مهلة المدمج (كاش 15 ثانية).
        assertTrue(dataFile.delete() && hdrFile.delete())
        val stillCached = EnginePicker.installedEngines(context)
        assertTrue(
            stillCached.any {
                it.packageName ==
                    VocalizerEmbeddedVoiceProvider.PROVIDER_ID
            }
        )

        // انقضت مهلة المدمج وحدها: يُعاد المسح فتختفي القائمة،
        // بينما يبقى كاش محركات النظام كما هو (٥ دقائق لم تنضج).
        clock.advance(EnginePicker.EMBEDDED_CACHE_TTL_MS)
        val afterShortTtl = EnginePicker.installedEngines(context)
        assertTrue(
            afterShortTtl.none {
                it.packageName ==
                    VocalizerEmbeddedVoiceProvider.PROVIDER_ID
            }
        )
        assertTrue(
            afterShortTtl.any { it.packageName == "com.example.tts" }
        )

        // محرك نظام جديد لا يظهر قبل انقضاء مهلته الكاملة.
        addSystemEngine("com.example.second", "SecondEngine")
        clock.advance(
            EnginePicker.CACHE_TTL_MS -
                EnginePicker.EMBEDDED_CACHE_TTL_MS
        )
        val afterLongTtl = EnginePicker.installedEngines(context)
        assertTrue(
            afterLongTtl.any { it.packageName == "com.example.second" }
        )
    }

    /**
     * ع٢٩-9: إبطال التخزين يمسح كاش المحركات معاً ويعيد ذاكرة
     * اكتشاف الأصوات إلى «تحتاج تحديثاً».
     */
    @Test
    fun invalidateAfterStorageChangeClearsCachesAndDiscovery() {
        EnginePicker.installedEngines(context)
        assertEquals(1, EnginePicker.cachedEngineCount())

        val catalog = VoiceCatalog(emptyList())
        catalog.applyDiscovery(emptyMap())
        assertFalse(catalog.needsRefresh(60_000L))

        EnginePicker.invalidateAfterStorageChange()

        assertEquals(0, EnginePicker.cachedEngineCount())
        assertTrue(catalog.needsRefresh(60_000L))
    }

    // حقن ResolveInfo في Robolectric لا يملك بديلاً غير المهجور.
    @Suppress("DEPRECATION")
    private fun addSystemEngine(pkg: String, engineName: String) {
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val info = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = pkg
                name = engineName
            }
        }
        shadowOf(context.packageManager)
            .addResolveInfoForIntent(intent, info)
    }

    private class FakeClock {
        var now: Long = System.currentTimeMillis()
        fun advance(ms: Long) {
            now += ms
        }
    }
}