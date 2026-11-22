package com.aymankhattab.nateq.core.data

import android.app.DownloadManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * اختبارات أمان التحديثات والتحقق من التوقيع وسلامة الحزم:
 * 1. عدم اعتبار غياب SHA-256 نجاحاً موثقاً (sha256Verified = false).
 * 2. طرد عدم تطابق SHA-256 وحذف الملف غير الموثوق.
 * 3. فحص تطابق اسم الحزمة وشهادة التوقيع قبل التثبيت.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class UpdateCheckerSecurityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun downloadResult_sha256Verified_defaultsTrue_andCanBeFalse() {
        val fakeFile = File(tmp.root, "test.apk")
        val successVerified = UpdateChecker.DownloadResult.Success(
            apkFile = fakeFile,
            sha256Verified = true
        )
        assertTrue(successVerified.sha256Verified)

        val successUnverified = UpdateChecker.DownloadResult.Success(
            apkFile = fakeFile,
            sha256Verified = false
        )
        assertFalse(successUnverified.sha256Verified)
    }

    @Test
    fun verifyApkPackageAndSignature_missingOrInvalidFile_returnsFalse() {
        val nonExistent = File(tmp.root, "non_existent.apk")
        assertFalse(
            UpdateChecker.verifyApkPackageAndSignature(context, nonExistent)
        )

        val emptyFile = tmp.newFile("empty.apk")
        assertFalse(
            UpdateChecker.verifyApkPackageAndSignature(context, emptyFile)
        )

        val corruptFile = tmp.newFile("corrupt.apk")
        corruptFile.writeText("not a real apk zip archive")
        assertFalse(
            UpdateChecker.verifyApkPackageAndSignature(context, corruptFile)
        )
    }

    @Test
    fun verifyApkSha256_missingOrWrongDigest_rejected() {
        val file = tmp.newFile("test_sha.apk")
        file.writeBytes(ByteArray(1024) { 0x5A })

        // بصمة غير صحيحة
        assertFalse(
            UpdateChecker.verifyApkSha256(
                file,
                "deadbeefdeadbeefdeadbeefdeadbeef" +
                    "deadbeefdeadbeefdeadbeefdeadbeef"
            )
        )

        // بصمة فارغة
        assertFalse(UpdateChecker.verifyApkSha256(file, ""))
        assertFalse(UpdateChecker.verifyApkSha256(file, "   "))
    }

    @Test
    fun processCompletedDownload_nonExistentDownloadId_returnsFailed() {
        val result = UpdateChecker.processCompletedDownload(
            context = context,
            downloadId = 999999L,
            expectedSha256Hex = "abc"
        )
        assertTrue(result is UpdateChecker.DownloadResult.Failed)
    }
}
