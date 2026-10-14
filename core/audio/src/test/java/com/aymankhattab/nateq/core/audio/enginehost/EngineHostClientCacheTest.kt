package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات الذاكرة المؤقتة لتهيئة [EngineHostClient] (ع٣٠-9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineHostClientCacheTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EngineHostClient.clearInitialized()
    }

    @After
    fun tearDown() {
        EngineHostClient.clearInitialized()
    }

    @Test
    fun init_memoizesSuccess_andReturnsCachedWithoutIpc() = runBlocking {
        val engineId = HostProtocol.ENGINE_VOCALIZER
        val dataDir = "/test/vocalizer/dir"

        assertFalse(EngineHostClient.isEngineInitialized(engineId, dataDir))

        // محاكاة وضع التهيئة الناجحة في الكاش
        // عند النداء الثاني بنفس dataDir يعود بنجاح فوري
        // بدون محاولة الاتصال بالخدمة
        // (في بيئة الاختبار الخدمة غير مشغلة فلو حاول الاتصال لفشل)
        // نتحقق من أن النداء بعد الحفظ في الكاش يعود بـ ok=true
        // مباشرة.
        val field = EngineHostClient::class.java
            .getDeclaredField("initializedEngines")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(EngineHostClient) as
            java.util.concurrent.ConcurrentHashMap<String, String>
        map[engineId] = dataDir

        assertTrue(EngineHostClient.isEngineInitialized(engineId, dataDir))

        val cachedRes = EngineHostClient.init(
            context,
            engineId,
            dataDir,
            force = false
        )
        assertTrue(cachedRes.ok)
    }

    @Test
    fun release_clearsInitializedCache() {
        val engineId = HostProtocol.ENGINE_VOCALIZER
        val dataDir = "/test/vocalizer/dir"

        val field = EngineHostClient::class.java
            .getDeclaredField("initializedEngines")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(EngineHostClient) as
            java.util.concurrent.ConcurrentHashMap<String, String>
        map[engineId] = dataDir

        assertTrue(EngineHostClient.isEngineInitialized(engineId, dataDir))

        EngineHostClient.release(context, engineId)

        assertFalse(EngineHostClient.isEngineInitialized(engineId, dataDir))
    }

    @Test
    fun onHostDied_clearsInitializedCache() {
        val engineId = HostProtocol.ENGINE_VOCALIZER
        val dataDir = "/test/vocalizer/dir"

        val field = EngineHostClient::class.java
            .getDeclaredField("initializedEngines")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(EngineHostClient) as
            java.util.concurrent.ConcurrentHashMap<String, String>
        map[engineId] = dataDir

        assertTrue(EngineHostClient.isEngineInitialized(engineId, dataDir))

        EngineHostClient.onHostDied(engineId)

        assertFalse(EngineHostClient.isEngineInitialized(engineId, dataDir))
    }

    @Test
    fun aliases_shareSameInitializedCacheEntry() = runBlocking {
        val canonical = HostProtocol.ENGINE_VOCALIZER
        val alias = "vocalizer_embedded"
        val dataDir = "/test/vocalizer/dir"

        val field = EngineHostClient::class.java
            .getDeclaredField("initializedEngines")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(EngineHostClient) as
            java.util.concurrent.ConcurrentHashMap<String, String>
        map[canonical] = dataDir

        // الاستعلام بالاسم البديل يرى الكاش المسجل بالاسم القانوني
        assertTrue(EngineHostClient.isEngineInitialized(alias, dataDir))

        // إرجاع التهيئة الناجحة فوراً بدون IPC عند استخدام الاسم البديل
        val cachedRes = EngineHostClient.init(
            context,
            alias,
            dataDir,
            force = false
        )
        assertTrue(cachedRes.ok)

        // تحرير الاسم البديل يفرغ الكاش للاسم القانوني أيضاً
        EngineHostClient.release(context, alias)
        assertFalse(EngineHostClient.isEngineInitialized(canonical, dataDir))
        assertFalse(EngineHostClient.isEngineInitialized(alias, dataDir))
    }

    @Test
    fun onHostDied_withAlias_clearsCanonicalCacheEntry() {
        val canonical = HostProtocol.ENGINE_VOCALIZER
        val alias = "vocalizer_embedded"
        val dataDir = "/test/vocalizer/dir"

        val field = EngineHostClient::class.java
            .getDeclaredField("initializedEngines")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(EngineHostClient) as
            java.util.concurrent.ConcurrentHashMap<String, String>
        map[canonical] = dataDir

        assertTrue(EngineHostClient.isEngineInitialized(canonical, dataDir))
        assertTrue(EngineHostClient.isEngineInitialized(alias, dataDir))

        EngineHostClient.onHostDied(alias)

        assertFalse(EngineHostClient.isEngineInitialized(canonical, dataDir))
        assertFalse(EngineHostClient.isEngineInitialized(alias, dataDir))
    }
}
