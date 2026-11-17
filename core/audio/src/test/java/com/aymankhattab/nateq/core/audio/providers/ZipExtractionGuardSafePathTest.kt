package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * حارس المسار الآمن لاستخراج ZIP: ثلاثة حواجز (Zip Slip إلى مجلد شقيق
 * ذي بادئة مشتركة، سقف حجم فك الضغط، سقف عدد الإدخالات). الاختبار
 * يبني أرشيفاً حقيقياً في الذاكرة ويمرّره عبر [ZipInputStream] إلى
 * واجهة [ZipExtractionGuard] العامة — بلا شبكة ولا جهاز حقيقي.
 */
class ZipExtractionGuardSafePathTest {

    /** مجلد مؤقت مستقل لكل حالة (بلا android APIs). */
    private fun tempDir(): File {
        val dir = File.createTempFile("nateq_zip_guard", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    /** أرشيف ZIP في الذاكرة من أزواج (اسم الإدخال، بايتاته). */
    private fun buildZip(
        entries: List<Pair<String, ByteArray>>
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /**
     * يمرّر الأرشيف كاملاً عبر الحارس ويعيد أول استثناء، أو null حين
     * ينجح الاستخراج — فيُفحص النوع في كل حالة بلا افتراض.
     */
    private fun drain(
        zipBytes: ByteArray,
        guard: ZipExtractionGuard
    ): Throwable? = runCatching {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = guard.resolve(entry.name)
                guard.onEntry(entry)
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    guard.copy(zis, out, ByteArray(4))
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }.exceptionOrNull()

    @Test
    fun siblingDirectoryWithCommonPrefix_isRejected() {
        val root = tempDir()
        try {
            val base = File(root, "voice").apply { mkdirs() }
            File(root, "voice_evil").apply { mkdirs() }
            val guard = ZipExtractionGuard(base)

            // البادئة النصية وحدها كانت تقبل "voice_evil" كابن لـ "voice".
            val thrown = runCatching {
                guard.resolve("../voice_evil/evil.bin")
            }.exceptionOrNull()

            assertTrue(
                "المجلد الشقيق ذو البادئة المشتركة يجب أن يُرفض",
                thrown is SecurityException
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun plainParentTraversal_isRejected() {
        val root = tempDir()
        try {
            val base = File(root, "voice").apply { mkdirs() }
            val guard = ZipExtractionGuard(base)
            val thrown = runCatching {
                guard.resolve("../../outside.bin")
            }.exceptionOrNull()
            assertTrue(
                "تجاوز المجلد الأساسي إلى الأعلى يجب أن يُرفض",
                thrown is SecurityException
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun safeNestedPath_isResolvedInsideBase() {
        val root = tempDir()
        try {
            val base = File(root, "voice").apply { mkdirs() }
            val guard = ZipExtractionGuard(base)
            val target = guard.resolve("sub/voice.dat")
            assertEquals(
                "المسار الآمن يجب أن يبقى داخل المجلد الأساسي",
                File(base, "sub/voice.dat").canonicalPath,
                target.canonicalPath
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun entryExceedingSizeCap_isRejected() {
        val root = tempDir()
        try {
            val base = File(root, "voice").apply { mkdirs() }
            val zip = buildZip(
                listOf("payload.bin" to ByteArray(16) { 7 })
            )
            // السقف 8 بايتات والحزمة 16: الفائض يُرفض أثناء النسخ لا
            // من حجم الإدخال المُعلَن (قد يكون -1).
            val guard = ZipExtractionGuard(base, maxTotalBytes = 8L)
            val thrown = drain(zip, guard)
            assertTrue(
                "تجاوز سقف حجم فك الضغط يجب أن يُرفض",
                thrown is SecurityException
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun entryCountExceedingCap_isRejected() {
        val root = tempDir()
        try {
            val base = File(root, "voice").apply { mkdirs() }
            val names = listOf("a.bin", "b.bin", "c.bin")
            val zip = buildZip(names.map { it to ByteArray(1) { 1 } })
            val guard = ZipExtractionGuard(base, maxEntries = 2)
            val thrown = drain(zip, guard)
            assertTrue(
                "تجاوز سقف عدد الملفات يجب أن يُرفض",
                thrown is SecurityException
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun safeArchiveWithinCaps_extractsAllEntries() {
        val root = tempDir()
        try {
            val base = File(root, "voice").apply { mkdirs() }
            val zip = buildZip(
                listOf(
                    "sub/a.bin" to ByteArray(4) { 1 },
                    "sub/b.bin" to ByteArray(4) { 2 }
                )
            )
            val guard = ZipExtractionGuard(
                base, maxTotalBytes = 100L, maxEntries = 5
            )
            assertNull("حزمة آمنة يجب أن تمرّ بلا استثناء", drain(zip, guard))
            assertEquals(
                "الملف الأول لم يُكتب داخل المجلد الأساسي",
                4L,
                File(base, "sub/a.bin").length()
            )
            assertEquals(
                "الملف الثاني لم يُكتب داخل المجلد الأساسي",
                4L,
                File(base, "sub/b.bin").length()
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
