package com.aymankhattab.nateq.core.data

import com.aymankhattab.nateq.core.data.UpdateChecker.CheckResult
import com.aymankhattab.nateq.core.data.UpdateChecker.CheckResult.UpToDate
import com.aymankhattab.nateq.core.data.UpdateChecker.CheckResult.UpdateAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * اختبار كاش نتيجة الفحص: يُسترجع ضمن المهلة لنفس النسخة وللفحص التلقائي
 * فقط؛ ينقضي بالزمن، ويُكسر بتغيّر النسخة أو بالفحص اليدوي؛ وخطأ الشبكة
 * لا يُخزَّن إطلاقاً. منطق نقي بلا أندرويد.
 */
class UpdateCheckCacheTest {

    private val cachedAvailable = UpdateAvailable("v0.11.0", "https://url")

    @Test
    fun remembersAndReturnsWithinTtlForSameVersion() {
        UpdateChecker.rememberCheck(UpToDate, "0.10.0", now = 1000L)
        assertEquals(
            UpToDate,
            UpdateChecker.cachedCheck("0.10.0", now = 2000L, true)
        )
    }

    @Test
    fun expiredEntryIsNotReturned() {
        UpdateChecker.rememberCheck(UpToDate, "0.10.0", now = 1000L)
        assertNull(
            UpdateChecker.cachedCheck(
                "0.10.0", now = 1000L + UpdateChecker.CACHE_TTL_MS, true
            )
        )
    }

    @Test
    fun differentVersionIsNotReturned() {
        UpdateChecker.rememberCheck(cachedAvailable, "0.10.0", now = 1000L)
        assertNull(UpdateChecker.cachedCheck("0.11.0", now = 2000L, true))
    }

    @Test
    fun manualCheckBypassesCache() {
        UpdateChecker.rememberCheck(cachedAvailable, "0.10.0", now = 1000L)
        assertNull(UpdateChecker.cachedCheck("0.10.0", now = 2000L, false))
    }

    @Test
    fun networkErrorIsNeverRemembered() {
        UpdateChecker.rememberCheck(
            CheckResult.NetworkError, "0.10.0", now = 1000L
        )
        assertNull(UpdateChecker.cachedCheck("0.10.0", now = 2000L, true))
    }
}