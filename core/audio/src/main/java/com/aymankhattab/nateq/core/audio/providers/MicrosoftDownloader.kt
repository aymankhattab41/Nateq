package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.util.Log
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * مدير تنزيل وتثبيت وحذف أصوات مايكروسوفت المدمجة (Microsoft Neural Embedded).
 */
object MicrosoftDownloader {

    private const val TAG = "MsDownloader"

    internal data class UnzipResult(
        val count: Int,
        val fileNames: List<String>
    )

    fun getAllSearchDirs(context: Context): List<File> =
        EmbeddedStorage.allSearchDirs(
            context,
            EmbeddedStorage.Engine.MICROSOFT
        )

    fun getMicrosoftBaseDir(context: Context): File {
        val base = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.MICROSOFT
        )
        if (!base.exists()) base.mkdirs()
        return base
    }

    suspend fun downloadVoice(
        context: Context,
        item: MicrosoftCatalogItem,
        targetDir: File? = null,
        onProgress: (percent: Int) -> Unit = {}
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val destination = targetDir ?: getMicrosoftBaseDir(context)
        if (!destination.exists() && !destination.mkdirs()) {
            return@withContext Result.failure(
                DownloadError(
                    DownloadError.NETWORK,
                    "Cannot create Microsoft dir: ${destination.path}"
                )
            )
        }

        try {
            DownloadHttpHelper.checkStorageSpace(destination, item.sizeMb)
        } catch (e: DownloadError) {
            return@withContext Result.failure(e)
        }

        val partZipFile = File(
            DownloadHttpHelper.getDownloadsDir(context),
            "microsoft_${item.id}.zip.part"
        )

        try {
            val startTime = System.currentTimeMillis()

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
            Log.i(
                TAG,
                "تنزيل ${item.id}: size=${partZipFile.length()}B, " +
                    "time=${durationMs}ms"
            )

            val head = ByteArray(200)
            val headRead = partZipFile.inputStream().use { it.read(head) }
            val isZip = headRead >= 4 &&
                head[0] == 0x50.toByte() &&
                head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() &&
                head[3] == 0x04.toByte()

            if (!isZip) {
                partZipFile.delete()
                throw DownloadError(
                    DownloadError.NOT_A_ZIP,
                    "Not a valid zip"
                )
            }

            onProgress(96)
            val unzipResult = unzipToDirectory(partZipFile, destination)

            if (unzipResult.count == 0) {
                partZipFile.delete()
                throw DownloadError(
                    DownloadError.EMPTY_ARCHIVE,
                    "لم يستخرج أي ملف من الأرشيف"
                )
            }

            val installedDir = MicrosoftModelCatalog.findInstalledVoiceDir(
                destination,
                item
            )
            if (installedDir == null) {
                partZipFile.delete()
                throw DownloadError(
                    DownloadError.HEADER_MISSING,
                    "ملف am_v5_decoder.bin مفقود"
                )
            }

            partZipFile.delete()
            EmbeddedStorage.bumpVersion()
            EnginePicker.invalidateAfterStorageChange()
            val settings = SettingsRepository.create(context)
            if (settings.isFilterEnginesEnabled()) {
                val cur = settings.getFilterEngines().toMutableSet()
                cur.add(MicrosoftEmbeddedVoiceProvider.PROVIDER_ID)
                cur.add("microsoft")
                settings.setFilterEngines(cur)
                VisibleEngines.setFilter(true, cur)
            }
            onProgress(100)
            Result.success(true)
        } catch (e: DownloadError) {
            Result.failure(e)
        } catch (t: Throwable) {
            Log.e(TAG, "فشل تنزيل صوت مايكروسوفت ${item.id}", t)
            Result.failure(
                DownloadError(
                    DownloadError.NETWORK,
                    t.message ?: "Unknown error"
                )
            )
        }
    }

    private fun unzipToDirectory(zipFile: File, outputDir: File): UnzipResult {
        var count = 0
        val extractedNames = mutableListOf<String>()
        val buffer = ByteArray(8192)

        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                if (entryName.contains("..")) {
                    entry = zis.nextEntry
                    continue
                }
                val outputFile = File(outputDir, entryName)
                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                    count++
                    extractedNames.add(outputFile.name)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return UnzipResult(count, extractedNames)
    }

    fun deleteVoice(context: Context, item: MicrosoftCatalogItem): Boolean {
        val baseDir = getMicrosoftBaseDir(context)
        var deleted = false
        val dir = File(baseDir, item.topDirName)
        if (dir.exists()) {
            deleted = dir.deleteRecursively() || deleted
        }
        val altDir = File(baseDir, item.id)
        if (altDir.exists()) {
            deleted = altDir.deleteRecursively() || deleted
        }
        if (deleted) {
            EmbeddedStorage.bumpVersion()
            EnginePicker.invalidateAfterStorageChange()
        }
        return deleted
    }
}
