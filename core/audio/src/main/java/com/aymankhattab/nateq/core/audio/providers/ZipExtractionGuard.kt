package com.aymankhattab.nateq.core.audio.providers

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry

/**
 * حارس استخراج ZIP مشترك بين مستوردي الأصوات.
 *
 * يعالج ثلاثة أخطاء أمنية/موردية في حلقة الاستخراج:
 *  1. Zip Slip: كان يُكتفى بـ `canonicalPath.startsWith(basePath)` فيقبل
 *     مجلدٌ شقيقٌ بنفس البادئة (مثلاً `.../voice_evil` حين يكون الأساس
 *     `.../voice`). نضيف فاصل مسار حتى لا تكون البادئة جزئية.
 *  2. حجم فك غير محدود (Zip Bomb): نحسب بايتات الإخراج الفعلية أثناء
 *     النسخ لا حجم الإعلان في [ZipEntry] لأنه قد يكون مجهولاً (-1).
 *  3. عدد ملفات غير محدود: سقف صريح على عدد الإدخالات.
 *
 * يعمل على minSdk 24 لذا لا يستخدم `java.nio.file.Path` (API 26+).
 */
internal class ZipExtractionGuard(
    private val baseDir: File,
    private val maxTotalBytes: Long = MAX_TOTAL_UNCOMPRESSED_BYTES,
    private val maxEntries: Int = MAX_ENTRIES
) {
    private var totalBytes = 0L
    private var entryCount = 0

    /** تحقق من مسار الإدخال وإرجاع الملف الهدف الآمن. */
    fun resolve(entryName: String): File {
        val target = File(baseDir, entryName)
        val canonicalBase = baseDir.canonicalPath
        val prefix = if (canonicalBase.endsWith(File.separator)) {
            canonicalBase
        } else {
            canonicalBase + File.separator
        }
        val canonicalOut = target.canonicalPath
        // المسار نفسه (المجلد الأساسي) مسموح؛ أي مسار آخر يجب أن يبدأ
        // بالبادئة المنتهية بفاصل حتى يُستبعد الشقيق ذو البادئة المشتركة.
        if (canonicalOut != canonicalBase && !canonicalOut.startsWith(prefix)) {
            throw SecurityException(
                "محاولة اختراق مسار عبر Zip Slip: $entryName"
            )
        }
        return target
    }

    /** عدّ الإدخال ورفض تجاوز السقف قبل booked أي بايتات. */
    fun onEntry(entry: ZipEntry) {
        entryCount++
        if (entryCount > maxEntries) {
            throw SecurityException(
                "تجاوز الاستخراج الحد الأقصى لعدد الملفات ($maxEntries)"
            )
        }
    }

    /** نسخ محتوى الإدخال مع محاسبة الحجم الفعلي لحظة بلحظة. */
    fun copy(
        input: InputStream,
        output: FileOutputStream,
        buffer: ByteArray
    ) {
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            totalBytes += read.toLong()
            if (totalBytes > maxTotalBytes) {
                throw SecurityException(
                    "تجاوز الاستخراج الحد الأقصى لحجم فك الضغط " +
                        "($maxTotalBytes بايت)"
                )
            }
            output.write(buffer, 0, read)
        }
    }

    companion object {
        /** سقف الحجم الكلي المفكوك: 2 جيجابايت. */
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024

        /** سقف عدد الإدخالات في الحزمة الواحدة. */
        const val MAX_ENTRIES = 20_000
    }
}
