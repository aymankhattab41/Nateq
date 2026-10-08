package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** مدير تنزيل وإلغاء تثبيت نماذج
 *  أصوات Piper / Sherpa-ONNX */
object PiperDownloader {

    private const val TAG = "PiperDownloader"
    private const val BUFFER_SIZE = 8 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * مجلد تثبيت نماذج Piper الأساسي — داخل filesDir دائماً
     * (ع٢٩-3) وبلا مسارات `/sdcard`.
     */
    fun getPiperBaseDir(context: Context): File {
        val base = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.PIPER
        )
        if (!base.exists()) base.mkdirs()
        return base
    }

    /** تنزيل صوت Piper مع متابعة نسبة التقدم */
    suspend fun downloadVoice(
        context: Context,
        item: PiperCatalogItem,
        onProgress: (downloadedBytes: Long, totalBytes: Long, percent: Int) ->
            Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val baseDir = getPiperBaseDir(context)
        val targetDir = File(baseDir, item.modelDirName)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val onnxFile = File(targetDir, item.modelFileName)
        val tokensFile = File(targetDir, item.tokensFileName)

        val onnxTmp = File(targetDir, "${item.modelFileName}.tmp")
        val tokensTmp = File(targetDir, "${item.tokensFileName}.tmp")

        try {
            // 1. تنزيل ملف الرموز (tokens.txt) أولاً
            downloadFile(
                item.getTokensUrl(),
                tokensTmp
            ) { _, _, _ -> }

            if (!isActive) {
                tokensTmp.delete()
                return@withContext Result.failure(
                    IOException("Download cancelled")
                )
            }

            // 2. تنزيل ملف النموذج (.onnx)
            // مع تقرير نسبة التقدم
            downloadFile(
                item.getOnnxUrl(),
                onnxTmp,
                onProgress
            )

            if (!isActive) {
                onnxTmp.delete()
                tokensTmp.delete()
                return@withContext Result.failure(
                    IOException("Download cancelled")
                )
            }

            // 3. تثبيت الملفات بعد اكتمال التنزيل
            tokensTmp.renameTo(tokensFile)
            onnxTmp.renameTo(onnxFile)

            // إبطال كاش المحركات ليتعرف
            // Lord TTS فورياً على الصوت الجديد (ع٢٩-9)
            EmbeddedStorage.bumpVersion()
            EnginePicker.invalidateAfterStorageChange()

            Log.i(
                TAG,
                "Voice ${item.id} downloaded to $targetDir"
            )
            Result.success(targetDir)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed downloading voice ${item.id}: ${t.message}", t)
            onnxTmp.delete()
            tokensTmp.delete()
            Result.failure(t)
        }
    }

    private fun downloadFile(
        urlString: String,
        destFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long, percent: Int) ->
            Unit
    ) {
        if (destFile.exists()) {
            destFile.delete()
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP error code: $responseCode from $url")
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            BufferedInputStream(
                connection.inputStream,
                BUFFER_SIZE
            ).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val percent = if (totalBytes > 0) {
                            ((downloadedBytes * 100) / totalBytes).toInt()
                        } else {
                            0
                        }
                        onProgress(downloadedBytes, totalBytes, percent)
                    }
                    output.flush()
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    /** حذف نموذج صوت مثبت لتحرير المساحة */
    fun deleteVoice(context: Context, item: PiperCatalogItem): Boolean {
        val baseDir = getPiperBaseDir(context)
        val targetDir = File(baseDir, item.modelDirName)
        if (targetDir.exists()) {
            val success = targetDir.deleteRecursively()
            if (success) {
                EmbeddedStorage.bumpVersion()
                EnginePicker.invalidateAfterStorageChange()
            }
            return success
        }
        return false
    }
}
