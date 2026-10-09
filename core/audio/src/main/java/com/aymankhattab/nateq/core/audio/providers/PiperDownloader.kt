package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * مدير تنزيل وإلغاء تثبيت نماذج أصوات
 * Piper / Sherpa-ONNX مع التحقق الصارم ورموز الخطأ (ع٣٠-6).
 */
object PiperDownloader {

    private const val TAG = "PiperDownloader"
    private const val BUFFER_SIZE = 8 * 1024
    private const val MIN_ONNX_BYTES = 100L * 1024 // 100 KB

    /**
     * مجلد تثبيت نماذج Piper الأساسي —
     * داخل filesDir/SherpaTTS دائماً (ع٣١-3) وبلا مسارات `/sdcard`.
     */
    fun getPiperBaseDir(context: Context): File {
        val base = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.SHERPA
        )
        if (!base.exists()) base.mkdirs()
        return base
    }

    /**
     * تنزيل صوت Piper مع متابعة نسبة التقدم والتحقق الصارم
     * (ع٣٠-6 و ع٣٠-7).
     */
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

        // ع٣٠-6 بند 3: فحص المساحة الحرة قبل البدء
        try {
            DownloadHttpHelper.checkStorageSpace(baseDir, item.sizeMb)
        } catch (e: DownloadError) {
            return@withContext Result.failure(e)
        }

        val onnxFile = File(targetDir, item.modelFileName)
        val tokensFile = File(targetDir, item.tokensFileName)

        val downloadsDir = DownloadHttpHelper.getDownloadsDir(context)
        val tokensPart = File(downloadsDir, "piper_${item.id}.tokens.part")
        val onnxPart = File(downloadsDir, "piper_${item.id}.onnx.part")

        try {
            // 1. تنزيل ملف الرموز (tokens.txt) أولاً
            val tokensResult = DownloadHttpHelper.downloadToFileWithResume(
                urlString = item.getTokensUrl(),
                partFile = tokensPart
            ) { _, _, _ -> }

            if (tokensResult.isFailure) {
                val ex = tokensResult.exceptionOrNull()
                val err = if (ex is DownloadError) ex else {
                    DownloadError(
                        DownloadError.NETWORK,
                        ex?.message ?: "Failed downloading tokens"
                    )
                }
                return@withContext Result.failure(err)
            }

            // ع٣٠-6 بند 6: التحقق من أن tokens.txt غير فارغ
            if (!tokensPart.exists() || tokensPart.length() <= 0L) {
                tokensPart.delete()
                throw DownloadError(
                    DownloadError.TOKENS_EMPTY,
                    "ملف الرموز tokens.txt فارغ أو غير موجود"
                )
            }

            // 2. تنزيل ملف النموذج (.onnx) مع دعم الاستئناف
            // ونسبة التقدم (ع٣٠-7)
            val onnxResult = DownloadHttpHelper.downloadToFileWithResume(
                urlString = item.getOnnxUrl(),
                partFile = onnxPart,
                onProgress = onProgress
            )

            if (onnxResult.isFailure) {
                val ex = onnxResult.exceptionOrNull()
                val err = if (ex is DownloadError) ex else {
                    DownloadError(
                        DownloadError.NETWORK,
                        ex?.message ?: "Failed downloading model"
                    )
                }
                return@withContext Result.failure(err)
            }

            // ع٣٠-6 بند 6: التحقق من أن .onnx أكبر من 100KB
            if (!onnxPart.exists() || onnxPart.length() <= MIN_ONNX_BYTES) {
                val len = if (onnxPart.exists()) onnxPart.length() else 0L
                onnxPart.delete()
                tokensPart.delete()
                throw DownloadError(
                    DownloadError.MODEL_TOO_SMALL,
                    "حجم ملف النموذج ONNX غير كافٍ (${len}B) " +
                        "— الأدنى 100KB"
                )
            }

            // 3. تثبيت الملفات بعد اكتمال التنزيل والتحقق
            tokensPart.copyTo(tokensFile, overwrite = true)
            onnxPart.copyTo(onnxFile, overwrite = true)
            tokensPart.delete()
            onnxPart.delete()

            // إبطال كاش المحركات ليتعرف Lord TTS فورياً
            // على الصوت الجديد
            EmbeddedStorage.bumpVersion()
            EnginePicker.invalidateAfterStorageChange()

            Log.i(
                TAG,
                "Voice ${item.id} downloaded and verified in $targetDir"
            )
            Result.success(targetDir)
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "Failed downloading voice ${item.id}: ${t.message}",
                t
            )
            val err = if (t is DownloadError) t else {
                DownloadError(
                    DownloadError.NETWORK,
                    t.message ?: "Network error"
                )
            }
            Result.failure(err)
        }
    }

    /** حذف نموذج صوت مثبت لتحرير المساحة */
    fun deleteVoice(context: Context, item: PiperCatalogItem): Boolean {
        val baseDir = getPiperBaseDir(context)
        val targetDir = File(baseDir, item.modelDirName)
        var deleted = false
        if (targetDir.exists()) {
            deleted = targetDir.deleteRecursively()
        }
        val legacyDir = File(
            EmbeddedStorage.baseDir(context, EmbeddedStorage.Engine.PIPER),
            item.modelDirName
        )
        if (legacyDir.exists()) {
            deleted = legacyDir.deleteRecursively() || deleted
        }

        val downloadsDir = DownloadHttpHelper.getDownloadsDir(context)
        val tokensPart = File(downloadsDir, "piper_${item.id}.tokens.part")
        val onnxPart = File(downloadsDir, "piper_${item.id}.onnx.part")
        if (tokensPart.exists()) tokensPart.delete()
        if (onnxPart.exists()) onnxPart.delete()

        if (deleted) {
            EmbeddedStorage.bumpVersion()
            EnginePicker.invalidateAfterStorageChange()
        }
        return deleted
    }
}
