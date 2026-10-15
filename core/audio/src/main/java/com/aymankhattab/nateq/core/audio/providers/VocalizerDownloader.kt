package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * مدير تنزيل وتثبيت وحذف أصوات
 * ومكونات Nuance Vocalizer Expressive 2 المدمجة (ع٣٠-5 و ع٣٠-6).
 */
object VocalizerDownloader {

    private const val TAG = "VocalizerDownloader"

    /** نتيجة فك ضغط الأرشيف: عدد الملفات المستخرجة وقائمتها. */
    internal data class UnzipResult(
        val count: Int,
        val fileNames: List<String>
    )

    /**
     * جميع المسارات المحتملة للبحث عن أصوات VocalizerEx2 —
     * من المصدر الموحّد (ع٢٩-3): الداخلي أولاً ثم الخارجي القديم.
     */
    fun getAllSearchDirs(context: Context): List<File> =
        EmbeddedStorage.allSearchDirs(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )

    /**
     * المسار الأساسي لمجلد أصوات VocalizerEx2.
     * يعيد مجلد البيانات الحالي إن وُجد، أو يُنشئ المجلد الداخلي
     * (filesDir) القابل للكتابة دائماً.
     */
    fun getVocalizerBaseDir(context: Context): File {
        val all = getAllSearchDirs(context)
        for (dir in all) {
            if (dir.exists() && dir.isDirectory) {
                val hasData = dir.listFiles { f ->
                    f.extension == "dat" || f.extension == "hdr"
                }
                if (!hasData.isNullOrEmpty()) {
                    return dir
                }
            }
        }

        val base = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        if (!base.exists()) base.mkdirs()
        return base
    }

    /**
     * تنزيل حزمة صوت Vocalizer Expressive 2 وفك ضغط ملفات .dat و .hdr
     * تلقائياً داخل المجلد مع التحقق الكامل ورموز الخطأ الواضحة (ع٣٠-6).
     */
    suspend fun downloadVoice(
        context: Context,
        item: VocalizerCatalogItem,
        targetDir: File? = null,
        onProgress: (percent: Int) -> Unit = {}
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val destination = targetDir ?: getVocalizerBaseDir(context)
        if (!destination.exists() && !destination.mkdirs()) {
            return@withContext Result.failure(
                DownloadError(
                    DownloadError.NETWORK,
                    "Cannot create Vocalizer directory: ${destination.path}"
                )
            )
        }

        // ع٣٠-6 بند 3: التحقق من المساحة الحرة قبل البدء (StatFs)
        try {
            DownloadHttpHelper.checkStorageSpace(destination, item.sizeMb)
        } catch (e: DownloadError) {
            return@withContext Result.failure(e)
        }

        val partZipFile = File(
            DownloadHttpHelper.getDownloadsDir(context),
            "vocalizer_${item.id}.zip.part"
        )

        try {
            val startTime = System.currentTimeMillis()

            // 1. تنزيل ملف الـ Zip مع دعم الاستئناف ومتابعة التقدم (ع٣٠-7)
            val downloadResult = DownloadHttpHelper.downloadToFileWithResume(
                urlString = item.downloadUrl,
                partFile = partZipFile
            ) { _, _, percent ->
                onProgress(percent.coerceIn(0, 95))
            }

            if (downloadResult.isFailure) {
                val ex = downloadResult.exceptionOrNull()
                val err = if (ex is DownloadError) ex else {
                    DownloadError(
                        DownloadError.NETWORK,
                        ex?.message ?: "Download failed"
                    )
                }
                return@withContext Result.failure(err)
            }

            val durationMs = System.currentTimeMillis() - startTime

            // ع٣٠-5 بند 4: توثيق القياسات في السجل
            Log.i(
                TAG,
                "تنزيل ${item.id}: size=${partZipFile.length()}B, " +
                    "time=${durationMs}ms"
            )

            // ع٣٠-5 بند 1 و ع٣٠-6: التحقق من التوقيع السحري لـ ZIP
            val head = ByteArray(200)
            val headRead = partZipFile.inputStream().use { it.read(head) }
            val isZip = headRead >= 4 &&
                head[0] == 0x50.toByte() &&
                head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() &&
                head[3] == 0x04.toByte()

            if (!isZip) {
                val headText = if (headRead > 0) {
                    String(head, 0, headRead, Charsets.UTF_8)
                        .replace("\r", "")
                        .replace("\n", " ")
                        .take(200)
                } else {
                    "(empty)"
                }
                Log.e(
                    TAG,
                    "الملف المنزّل ليس ZIP صالحاً [NOT_A_ZIP]: head=$headText"
                )
                partZipFile.delete()
                throw DownloadError(
                    DownloadError.NOT_A_ZIP,
                    "head=$headText"
                )
            }

            // 2. فك ضغط ملفات الـ Zip داخل مجلد VocalizerEx2
            onProgress(96)
            val unzipResult = unzipToDirectory(partZipFile, destination)

            // ع٣٠-5 بند 2 و ع٣٠-6: التحقق من وجود ملفات مستخرجة
            if (unzipResult.count == 0) {
                Log.e(TAG, "الأرشيف فارغ لم يستخرج أي ملف [EMPTY_ARCHIVE]")
                partZipFile.delete()
                throw DownloadError(
                    DownloadError.EMPTY_ARCHIVE,
                    "لم يستخرج أي ملف من الأرشيف"
                )
            }

            // ع٣٠-5 بند 3 و ع٣٠-6: التحقق من وجود ملف الـ Header وحجمه.
            // يُقبل الرأس بعائلة الإصدار نفسه (تطابق غير حرفي) لأن رقم
            // إصدار الرأس في الحزمة قد يخالف رقمه في الكتالوج.
            val hdrFile = VocalizerModelCatalog.installedHeaderFile(
                destination,
                item
            )
            if (hdrFile == null) {
                val extractedList = unzipResult.fileNames.joinToString(", ")
                Log.e(
                    TAG,
                    "ملف الـ header مفقود [HEADER_MISSING]: " +
                        "expected=${item.headerFileName}, " +
                        "extracted=[$extractedList]"
                )
                throw DownloadError(
                    DownloadError.HEADER_MISSING,
                    "متوقع ${item.headerFileName}، المستخرج: [$extractedList]"
                )
            }

            onProgress(100)

            // حذف الملف المؤقت فقط بعد التحقق الناجح
            partZipFile.delete()

            // ع٣٠-5 بند 5: رفع إصدار التخزين وإبطال الكاش فقط بعد التحقق
            EmbeddedStorage.bumpVersion()
            EnginePicker.invalidateAfterStorageChange()

            Log.i(
                TAG,
                "Voice ${item.voiceName} verified and installed at " +
                    destination.absolutePath +
                    " (${unzipResult.count} files)"
            )
            Result.success(true)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to download voice ${item.id}", t)
            val err = if (t is DownloadError) t else {
                DownloadError(
                    DownloadError.NETWORK,
                    t.message ?: "Network error"
                )
            }
            Result.failure(err)
        }
    }

    /** سقف حجم المحتوى المستخرج من أرشيف واحد (حماية zip bomb). */
    private const val MAX_EXTRACTION_BYTES = 1L * 1024 * 1024 * 1024

    /**
     * فك ضغط محتويات ملف Zip مباشرة في المجلد الهدف.
     * حماية zip-slip (ع٢٩-3): يُرفض أي اسم يحوي `..` أو مساراً
     * خارج المجلد الهدف، ويُفرض سقف حجم الاستخراج [maxBytes].
     * (internal ليصل إليه حارس الاختبار).
     */
    internal fun unzipToDirectory(
        zipFile: File,
        targetDir: File,
        maxBytes: Long = MAX_EXTRACTION_BYTES
    ): UnzipResult {
        val canonicalTarget = targetDir.canonicalFile
        var extractedBytes = 0L
        val extractedNames = mutableListOf<String>()

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(32 * 1024)
            while (entry != null) {
                val cleanName = File(entry.name).name
                val traversal = entry.name.contains("..")
                val outFile = File(targetDir, cleanName)
                val inside = try {
                    outFile.canonicalFile.parentFile
                        ?.startsWith(canonicalTarget) == true
                } catch (_: Throwable) {
                    false
                }
                if (traversal || !inside) {
                    Log.w(
                        TAG,
                        "Rejected unsafe zip entry: ${entry.name}"
                    )
                } else if (cleanName.isNotBlank() && !entry.isDirectory) {
                    FileOutputStream(outFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            extractedBytes += len
                            if (extractedBytes > maxBytes) {
                                throw IllegalStateException(
                                    "Extraction exceeds $maxBytes bytes"
                                )
                            }
                            fos.write(buffer, 0, len)
                        }
                    }
                    extractedNames.add(cleanName)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return UnzipResult(extractedNames.size, extractedNames)
    }

    /**
     * حذف ملفات صوت محدد من مجلدات VocalizerEx2.
     */
    fun deleteVoice(
        context: Context,
        item: VocalizerCatalogItem,
        targetDir: File? = null
    ): Boolean {
        val dirs = if (targetDir != null) listOf(targetDir)
            else getAllSearchDirs(context)
        var deletedAny = false
        val cleanPrefix = item.voiceName.lowercase()

        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory) continue

            // 1. حذف ملف الـ Header (باسمه الفعلي المُثبَّت، لا الحرفي في
            //    الكتالوج) — قد يختلف رقم إصداره عن الكتالوج.
            val hdrFile = VocalizerModelCatalog.installedHeaderFile(dir, item)
            if (hdrFile != null) {
                deletedAny = hdrFile.delete() || deletedAny
            }

            // 2. فحص وحذف ملفات البيانات المرتبطة باسم الصوت
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.contains(
                        cleanPrefix,
                        ignoreCase = true
                    )
                ) {
                    deletedAny = file.delete() || deletedAny
                }
            }
        }

        // 3. حذف أي ملف تنزيل مؤقت متبقٍ
        val partFile = File(
            DownloadHttpHelper.getDownloadsDir(context),
            "vocalizer_${item.id}.zip.part"
        )
        if (partFile.exists()) {
            deletedAny = partFile.delete() || deletedAny
        }

        return deletedAny.also {
            if (it) {
                EmbeddedStorage.bumpVersion()
                EnginePicker.invalidateAfterStorageChange()
            }
        }
    }
}
