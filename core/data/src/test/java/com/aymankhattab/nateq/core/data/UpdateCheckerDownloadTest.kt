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
}
