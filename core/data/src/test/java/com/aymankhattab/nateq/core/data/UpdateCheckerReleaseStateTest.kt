package com.aymankhattab.nateq.core.data

import com.aymankhattab.nateq.core.data.UpdateChecker.ApkPick
import com.aymankhattab.nateq.core.data.UpdateChecker.CheckResult
import com.aymankhattab.nateq.util.NateqJson
import com.google.gson.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * اختبارات حالات مرفقات الإصدار والتخزين المؤقت في [UpdateChecker]:
 * - مرفقات فارغة أو في حالة starter تعود بحالة ReleaseNotReady.
 * - مرفقات غير APK أو خليط مع starter تعود بحالة ReleaseNotReady.
 * - مرفق مكتمل مطابق لمعمارية الجهاز يعود بـ UpdateAvailable.
 * - المرفق العام الشامل (nateq.apk) يُعتمد كبديل متوافق عند غياب
 *   معمارية بعينها.
 * - عدم تطابق حقيقي (مرفقات معمارية أخرى فقط بلا مرفق عام) يعود بـ
 *   NoCompatibleVersion.
 * - الحالات السلبية (ReleaseNotReady و NetworkError) لا تُخزَّن مؤقتاً،
 *   والفحص اليدوي (preferCache=false) يتجاوز الكاش دائماً.
 */
class UpdateCheckerReleaseStateTest {

    @Before
    fun setUp() {
        UpdateChecker.clearCache()
    }

    @Test
    fun parseCompletedApkAssets_emptyArray_returnsEmptyList() {
        val emptyArray = JsonArray()
        val result = UpdateChecker.parseCompletedApkAssets(emptyArray)
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseCompletedApkAssets_starterOnly_returnsEmptyList() {
        val json = """
            [
              {
                "name": "nateq-arm64-v8a.apk",
                "state": "starter",
                "size": 0,
                "browser_download_url": "https://example.com/starter.apk"
              }
            ]
        """.trimIndent()
        val array = NateqJson.parseElement(json)?.asJsonArray
        val result = UpdateChecker.parseCompletedApkAssets(array)
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseCompletedApkAssets_mixedStarterAndNonApk_returnsEmptyList() {
        val json = """
            [
              {
                "name": "nateq-arm64-v8a.apk",
                "state": "starter",
                "size": 0,
                "browser_download_url": "https://example.com/starter.apk"
              },
              {
                "name": "checksums.txt",
                "state": "uploaded",
                "size": 256,
                "browser_download_url": "https://example.com/checksums.txt"
              }
            ]
        """.trimIndent()
        val array = NateqJson.parseElement(json)?.asJsonArray
        val result = UpdateChecker.parseCompletedApkAssets(array)
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseCompletedApkAssets_validUploadedApk_isParsedCorrectly() {
        val json = """
            [
              {
                "name": "nateq-arm64-v8a.apk",
                "state": "uploaded",
                "size": 74219335,
                "browser_download_url": "https://example.com/arm64.apk"
              }
            ]
        """.trimIndent()
        val array = NateqJson.parseElement(json)?.asJsonArray
        val result = UpdateChecker.parseCompletedApkAssets(array)
        assertEquals(1, result.size)
        assertEquals("nateq-arm64-v8a.apk", result[0].name)
        assertEquals("https://example.com/arm64.apk", result[0].downloadUrl)
        assertEquals(74219335L, result[0].size)
        assertEquals("uploaded", result[0].state)
    }

    @Test
    fun pickApkAsset_matchingDeviceAbi_returnsMatched() {
        val json = """
            [
              {
                "name": "nateq-arm64-v8a.apk",
                "state": "uploaded",
                "size": 74219335,
                "browser_download_url": "https://example.com/arm64.apk"
              },
              {
                "name": "nateq-armeabi-v7a.apk",
                "state": "uploaded",
                "size": 57821032,
                "browser_download_url": "https://example.com/armv7.apk"
              }
            ]
        """.trimIndent()
        val array = NateqJson.parseElement(json)?.asJsonArray
        val assets = UpdateChecker.parseCompletedApkAssets(array)
        val preferred = listOf("nateq-arm64-v8a.apk")
        val pick = UpdateChecker.pickApkAsset(
            assets,
            preferredNames = preferred,
            nameOf = { it.name }
        )
        assertTrue(pick is ApkPick.Matched)
        val matched = (pick as ApkPick.Matched).asset
        assertEquals("nateq-arm64-v8a.apk", matched.name)
    }

    @Test
    fun pickApkAsset_universalApkFallback_returnsUniversal() {
        val json = """
            [
              {
                "name": "nateq.apk",
                "state": "uploaded",
                "size": 180272683,
                "browser_download_url": "https://example.com/nateq.apk"
              }
            ]
        """.trimIndent()
        val array = NateqJson.parseElement(json)?.asJsonArray
        val assets = UpdateChecker.parseCompletedApkAssets(array)
        val preferred = listOf("nateq-arm64-v8a.apk")
        val pick = UpdateChecker.pickApkAsset(
            assets,
            preferredNames = preferred,
            nameOf = { it.name }
        )
        assertTrue(pick is ApkPick.Universal)
        val universal = (pick as ApkPick.Universal).asset
        assertEquals("nateq.apk", universal.name)
    }

    @Test
    fun pickApkAsset_trueAbiMismatch_returnsNoCompatible() {
        val json = """
            [
              {
                "name": "nateq-armeabi-v7a.apk",
                "state": "uploaded",
                "size": 57821032,
                "browser_download_url": "https://example.com/armv7.apk"
              }
            ]
        """.trimIndent()
        val array = NateqJson.parseElement(json)?.asJsonArray
        val assets = UpdateChecker.parseCompletedApkAssets(array)
        // الجهاز يدعم 64-بت فقط ولا يوجد مرفق عام
        val preferred = listOf("nateq-arm64-v8a.apk")
        val pick = UpdateChecker.pickApkAsset(
            assets,
            preferredNames = preferred,
            nameOf = { it.name }
        )
        assertEquals(ApkPick.NoCompatible, pick)
    }

    @Test
    fun rememberCheck_releaseNotReady_isNeverCached() {
        val notReady = CheckResult.ReleaseNotReady(
            tag = "v1.6.159",
            reason = "EMPTY_ASSET_LIST"
        )
        UpdateChecker.rememberCheck(notReady, "1.6.158", now = 1000L)
        val cached = UpdateChecker.cachedCheck("1.6.158", now = 2000L, true)
        assertNull(cached)
    }

    @Test
    fun cachedCheck_manualCheckWithPreferCacheFalse_bypassesCache() {
        val available = CheckResult.UpdateAvailable(
            tag = "v1.6.159",
            apkUrl = "https://example.com/nateq.apk"
        )
        UpdateChecker.rememberCheck(available, "1.6.158", now = 1000L)

        // فحص تلقائي يقرأ الكاش
        val autoCheck = UpdateChecker.cachedCheck("1.6.158", now = 2000L, true)
        assertNotNull(autoCheck)
        assertEquals(available, autoCheck)

        // فحص يدوي يتجاوز الكاش تماماً
        val manualCheck = UpdateChecker.cachedCheck(
            "1.6.158", now = 2000L, preferCache = false
        )
        assertNull(manualCheck)
    }
}
