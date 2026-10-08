package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * مدير تنزيل وتثبيت وحذف أصوات
 * ومكونات Nuance Vocalizer Expressive 2 المدمجة.
 */
object VocalizerDownloader {

    private const val TAG = "VocalizerDownloader"

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
     * تنزيل حزمة صوت Vocalizer Expressive 2
     * وفك ضغط ملفات .dat و .hdr تلقائياً داخل المجلد.
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
                IllegalStateException("Cannot create Vocalizer directory")
            )
        }

        val tempZipFile = File(
            context.cacheDir,
            "vocalizer_${item.id}_${System.currentTimeMillis()}.zip"
        )

        try {
            // 1. تنزيل ملف الـ Zip مع متابعة نسبة التقدم
            val url = URL(item.downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Android; Mobile)"
            )

            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext Result.failure(
                    IllegalStateException("HTTP error $code from server")
                )
            }

            val totalBytes = conn.contentLength.toLong()
            var downloadedBytes = 0L

            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(tempZipFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val percent = (
                                (downloadedBytes * 100) / totalBytes
                            ).toInt().coerceIn(0, 95)
                            onProgress(percent)
                        }
                    }
                }
            }
            conn.disconnect()

            // 2. فك ضغط ملفات الـ Zip داخل مجلد VocalizerEx2
            onProgress(96)
            unzipToDirectory(tempZipFile, destination)
            onProgress(100)

            // ع٢٩-9: أي تغيير في المحتوى يرفع رقم إصدار التخزين ويبطل الكاش.
            EmbeddedStorage.bumpVersion()
            EnginePicker.invalidateAfterStorageChange()

            Log.i(
                TAG,
                "Voice ${item.voiceName} installed at " +
                    destination.absolutePath
            )
            Result.success(true)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to download voice ${item.id}", t)
            Result.failure(t)
        } finally {
            if (tempZipFile.exists()) {
                tempZipFile.delete()
            }
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
    ) {
        val canonicalTarget = targetDir.canonicalFile
        var extractedBytes = 0L
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
                                    "Extraction exceeds " +
                                        "$maxBytes bytes"
                                )
                            }
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
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

            // 1. حذف ملف الـ Header
            val hdrFile = File(dir, item.headerFileName)
            if (hdrFile.exists()) {
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

        return deletedAny.also {
            if (it) {
                EmbeddedStorage.bumpVersion()
                EnginePicker.invalidateAfterStorageChange()
            }
        }
    }
}
