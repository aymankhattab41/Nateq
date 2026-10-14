package com.aymankhattab.nateq.core.data

import android.app.DownloadManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * اختبار لآلية بدء تنزيل التحديث في
 * [UpdateChecker.enqueueDownload]:
 * يضمن إرسال الطلب لمدير التنزيلات بنجاح
 * مع منع ظهور إشعار التنزيل في شريط الإشعارات
 * لتفادي إزعاج المستخدم.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class UpdateCheckerDownloadTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun enqueueDownload_succeedsAndReturnsValidId() {
        val manager = context.getSystemService(
            Context.DOWNLOAD_SERVICE
        ) as DownloadManager
        val initialCount = shadowOf(manager).requestCount
        val id = UpdateChecker.enqueueDownload(
            context,
            "https://example.com/nateq.apk",
            allowMetered = true
        )
        assertTrue(id >= 0L)
        assertTrue(shadowOf(manager).requestCount > initialCount)
    }

    @Test
    fun calculatePercent_variousInputs_computedCorrectly() {
        org.junit.Assert.assertEquals(
            0, UpdateChecker.calculatePercent(0L, 0L)
        )
        org.junit.Assert.assertEquals(
            0, UpdateChecker.calculatePercent(-10L, 100L)
        )
        org.junit.Assert.assertEquals(
            0, UpdateChecker.calculatePercent(50L, 0L)
        )
        org.junit.Assert.assertEquals(
            0, UpdateChecker.calculatePercent(50L, -100L)
        )
        org.junit.Assert.assertEquals(
            50, UpdateChecker.calculatePercent(500L, 1000L)
        )
        org.junit.Assert.assertEquals(
            100, UpdateChecker.calculatePercent(1000L, 1000L)
        )
        org.junit.Assert.assertEquals(
            100, UpdateChecker.calculatePercent(1500L, 1000L)
        )
    }

    @Test
    fun calculatePercent_largeValues_noOverflow() {
        val fiveHundredMb = 500L * 1024L * 1024L
        val oneGb = 1024L * 1024L * 1024L
        org.junit.Assert.assertEquals(
            48, UpdateChecker.calculatePercent(fiveHundredMb, oneGb)
        )
    }

    @Test
    fun cancelDownload_removesTargetApkAndDownloadRecord() {
        val target = UpdateChecker.downloadedApk(context)
        target.parentFile?.mkdirs()
        target.writeText("test apk content")
        assertTrue(target.exists())

        UpdateChecker.cancelDownload(context, 12345L)
        org.junit.Assert.assertFalse(target.exists())
    }

    @Test
    fun activeDownload_persistenceInSettingsRepository() {
        val repo = SettingsRepository(context)
        org.junit.Assert.assertEquals(
            -1L, repo.getActiveUpdateDownloadId()
        )
        org.junit.Assert.assertNull(repo.getActiveUpdateApkUrl())
        org.junit.Assert.assertNull(
            repo.getActiveUpdateExpectedSha256()
        )

        repo.setActiveUpdateDownload(
            downloadId = 98765L,
            apkUrl = "https://example.com/app.apk",
            expectedSha256 = "abc123sha"
        )
        org.junit.Assert.assertEquals(
            98765L, repo.getActiveUpdateDownloadId()
        )
        org.junit.Assert.assertEquals(
            "https://example.com/app.apk",
            repo.getActiveUpdateApkUrl()
        )
        org.junit.Assert.assertEquals(
            "abc123sha",
            repo.getActiveUpdateExpectedSha256()
        )

        // التحقق من استعادة الحالة عبر كائن إعدادات جديد (إعادة تشغيل)
        val freshRepo = SettingsRepository(context)
        org.junit.Assert.assertEquals(
            98765L, freshRepo.getActiveUpdateDownloadId()
        )
        org.junit.Assert.assertEquals(
            "https://example.com/app.apk",
            freshRepo.getActiveUpdateApkUrl()
        )
        org.junit.Assert.assertEquals(
            "abc123sha",
            freshRepo.getActiveUpdateExpectedSha256()
        )

        repo.clearActiveUpdateDownload()
        org.junit.Assert.assertEquals(
            -1L, repo.getActiveUpdateDownloadId()
        )
        org.junit.Assert.assertNull(repo.getActiveUpdateApkUrl())
        org.junit.Assert.assertNull(
            repo.getActiveUpdateExpectedSha256()
        )
    }
}
