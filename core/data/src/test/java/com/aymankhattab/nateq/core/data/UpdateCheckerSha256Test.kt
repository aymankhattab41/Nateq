package com.aymankhattab.nateq.core.data

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * التحقق من بصمة SHA-256 للـ APK المُنزَّل قبل التثبيت («الأمان المتوسط»
 * في تقارير الأمان): مطابقة تامة/فشل على الملفات المفقودة والفارغة
 * والتالف، مع حساسية المقارنة لحالة الأحرف السداسية. اختبار JVM نقي
 * بلا Robolectric (يمر حتى بلا اتصال إنترنت).
 */
class UpdateCheckerSha256Test {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read >= 0) {
                if (read > 0) digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun matchingDigest_verifies() {
        val file = tmp.newFile("lord_tts.apk")
        file.writeBytes(ByteArray(4096) { it.toByte() })
        val expected = sha256Hex(file)
        assertTrue(UpdateChecker.verifyApkSha256(file, expected))
    }

    @Test
    fun wrongDigest_orTamperedFile_fails() {
        val file = tmp.newFile("lord_tts.apk")
        file.writeBytes(ByteArray(4096) { 0x11 })
        val expected = sha256Hex(file)
        // تلاعُب بايت واحد بعد حساب البصمة المتوقعة → فشل.
        file.writeBytes(ByteArray(4096) { 0x12 })
        assertFalse(UpdateChecker.verifyApkSha256(file, expected))
        // بصمة متوقعة مغلوطة لملف سليم → فشل.
        assertFalse(
            UpdateChecker.verifyApkSha256(
                file, "0000000000000000000000000000000000000000" +
                    "00000000000000000000000000"
            )
        )
    }

    @Test
    fun missingOrEmptyFile_fails() {
        val missing = File(tmp.root, "missing.apk")
        assertFalse(UpdateChecker.verifyApkSha256(missing, "00".repeat(32)))
        val empty = tmp.newFile("empty.apk")
        assertFalse(UpdateChecker.verifyApkSha256(empty, "00".repeat(32)))
        // بصمة فارغة/مسافات لا تُقبل أبداً.
        val file = tmp.newFile("ok.apk")
        file.writeBytes(ByteArray(64))
        assertFalse(UpdateChecker.verifyApkSha256(file, ""))
        assertFalse(UpdateChecker.verifyApkSha256(file, "   "))
    }

    @Test
    fun hexComparison_isCaseInsensitive() {
        val file = tmp.newFile("lord_tts.apk")
        file.writeBytes(ByteArray(2048) { 0x2A })
        val expected = sha256Hex(file)
        assertTrue(UpdateChecker.verifyApkSha256(file, expected.uppercase()))
        assertTrue(UpdateChecker.verifyApkSha256(file, expected.lowercase()))
    }
}