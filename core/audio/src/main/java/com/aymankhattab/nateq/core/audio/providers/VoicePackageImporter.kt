package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * نتيجة عملية استيراد حزمة صوتية مضغوطة.
 */
data class VoiceImportResult(
    val isSuccess: Boolean,
    val engineType: String,
    val voiceName: String,
    val targetDir: File?,
    val extractedFilesCount: Int,
    val errorMessage: String? = null
)

/**
 * مستورد حزم الأصوات المحلية الموحد لمشروع Lord TTS.
 * يدعم استيراد باقات ZIP لمحركات:
 * 1) Acapela TTS (مثل acapela-ara-SAU-Nizar-Old.zip)
 * 2) Piper TTS (Sherpa-ONNX)
 * 3) Vocalizer Expressive 2
 */
object VoicePackageImporter {

    private const val TAG = "VoicePackageImporter"

    const val ENGINE_ACAPELA = "acapela"
    const val ENGINE_PIPER = "piper"
    const val ENGINE_VOCALIZER = "vocalizer"
    const val ENGINE_UNKNOWN = "unknown"

    /** سقف حجم المحتوى المستخرج من حزمة واحدة (حماية zip bomb). */
    private const val MAX_EXTRACTION_BYTES = 1L * 1024 * 1024 * 1024

    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        onProgress: ((Float) -> Unit)? = null
    ): VoiceImportResult = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext VoiceImportResult(
                    isSuccess = false,
                    engineType = ENGINE_UNKNOWN,
                    voiceName = "",
                    targetDir = null,
                    extractedFilesCount = 0,
                    errorMessage = "Cannot open input stream for URI"
                )
            importFromZipStream(context, stream, onProgress)
        } catch (t: Throwable) {
            Log.e(TAG, "Error importing from URI: ${t.message}", t)
            VoiceImportResult(
                isSuccess = false,
                engineType = ENGINE_UNKNOWN,
                voiceName = "",
                targetDir = null,
                extractedFilesCount = 0,
                errorMessage = t.message ?: "Unknown error"
            )
        }
    }

    suspend fun importFromZipFile(
        context: Context,
        file: File,
        onProgress: ((Float) -> Unit)? = null
    ): VoiceImportResult = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || !file.canRead()) {
                return@withContext VoiceImportResult(
                    isSuccess = false,
                    engineType = ENGINE_UNKNOWN,
                    voiceName = file.nameWithoutExtension,
                    targetDir = null,
                    extractedFilesCount = 0,
                    errorMessage = "File does not exist or unreadable"
                )
            }
            val stream = FileInputStream(file)
            importFromZipStream(context, stream, onProgress)
        } catch (t: Throwable) {
            Log.e(TAG, "Error importing from File: ${t.message}", t)
            VoiceImportResult(
                isSuccess = false,
                engineType = ENGINE_UNKNOWN,
                voiceName = file.nameWithoutExtension,
                targetDir = null,
                extractedFilesCount = 0,
                errorMessage = t.message ?: "Unknown error"
            )
        }
    }

    suspend fun importFromZipStream(
        context: Context,
        inputStream: InputStream,
        onProgress: ((Float) -> Unit)? = null
    ): VoiceImportResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val tempZip = File(context.cacheDir, "import_temp_$now.zip")
        try {
            // حفظ الملف مؤقتاً لتفحصه أولاً واستخراجه بأمان
            saveStreamToFile(inputStream, tempZip)

            val (engineType, detectedVoiceName) = inspectZip(tempZip)
            if (engineType == ENGINE_UNKNOWN) {
                return@withContext VoiceImportResult(
                    isSuccess = false,
                    engineType = ENGINE_UNKNOWN,
                    voiceName = detectedVoiceName,
                    targetDir = null,
                    extractedFilesCount = 0,
                    errorMessage = "Unrecognized voice package format"
                )
            }

            val targetDir = when (engineType) {
                ENGINE_ACAPELA -> AcapelaEmbeddedVoiceProvider
                    .getAcapelaBaseDir(context)
                ENGINE_PIPER -> PiperDownloader
                    .getPiperBaseDir(context)
                ENGINE_VOCALIZER -> VocalizerDownloader
                    .getVocalizerBaseDir(context)
                else -> AcapelaEmbeddedVoiceProvider
                    .getAcapelaBaseDir(context)
            }

            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val extractedCount = extractZip(tempZip, targetDir, onProgress)
            val finalVoiceName = if (detectedVoiceName.isNotEmpty()) {
                detectedVoiceName
            } else {
                "Voice"
            }

// ع٢٩-9: اكتمال الاستيراد تغيّرٌ في المحتوى يُبطل الكاش فوراً.
            if (extractedCount > 0) {
                EmbeddedStorage.bumpVersion()
                EnginePicker.invalidateAfterStorageChange()
            }

            VoiceImportResult(
                isSuccess = true,
                engineType = engineType,
                voiceName = finalVoiceName,
                targetDir = targetDir,
                extractedFilesCount = extractedCount
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Error unpacking ZIP package: ${t.message}", t)
            VoiceImportResult(
                isSuccess = false,
                engineType = ENGINE_UNKNOWN,
                voiceName = "",
                targetDir = null,
                extractedFilesCount = 0,
                errorMessage = t.message ?: "Extraction failed"
            )
        } finally {
            runCatching { tempZip.delete() }
        }
    }

    private fun saveStreamToFile(input: InputStream, output: File) {
        input.use { inStream ->
            FileOutputStream(output).use { outStream ->
                inStream.copyTo(outStream)
            }
        }
    }

    /** فحص محتويات ملف ZIP لتحديد المحرك واسم الصوت */
    fun inspectZip(zipFile: File): Pair<String, String> {
        var isAcapela = false
        var isPiper = false
        var isVocalizer = false
        var detectedVoice = ""

        val stream = BufferedInputStream(FileInputStream(zipFile))
        ZipInputStream(stream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                val entryBaseName = File(entry.name).name

                when {
                    name.contains("acapela/") ||
                        name.endsWith(".bvcu") ||
                        name.endsWith(".bvcu.inf") ||
                        name.endsWith(".bvcu.ini") ||
                        name.contains("userdicos/") ||
                        name.contains("nlp/") ||
                        entryBaseName.startsWith("0x00") ||
                        entryBaseName == "voicelist" -> {
                        isAcapela = true
                        if (name.endsWith(".inf")) {
                            val c = extractVoiceNameFromEntry(entryBaseName)
                            if (c.isNotEmpty()) detectedVoice = c
                        } else if (
                            name.contains("22k") && detectedVoice.isEmpty()
                        ) {
                            val c = extractVoiceNameFromEntry(entryBaseName)
                            if (c.isNotEmpty()) detectedVoice = c
                        }
                    }
                    name.endsWith(".onnx") ||
                        name.endsWith(".onnx.json") ||
                        entryBaseName == "tokens.txt" ||
                        name.contains("espeak-ng-data") -> {
                        isPiper = true
                        if (
                            name.endsWith(".onnx") &&
                            !name.endsWith(".onnx.json") &&
                            detectedVoice.isEmpty()
                        ) {
                            val fName = File(entry.name).nameWithoutExtension
                            detectedVoice = fName
                        }
                    }
                    name.contains("ve_pipeline") ||
                        name.endsWith(".hdr") ||
                        name.contains("vocalizer") ||
                        name.contains("speech/components") -> {
                        isVocalizer = true
                        if (
                            detectedVoice.isEmpty() &&
                            entryBaseName.endsWith(".hdr")
                        ) {
                            detectedVoice = entryBaseName
                                .removePrefix("ve_pipeline_")
                                .removeSuffix(".hdr")
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val engine = when {
            isAcapela -> ENGINE_ACAPELA
            isPiper -> ENGINE_PIPER
            isVocalizer -> ENGINE_VOCALIZER
            else -> ENGINE_UNKNOWN
        }

        if (detectedVoice.isEmpty() && engine == ENGINE_ACAPELA) {
            detectedVoice = zipFile.nameWithoutExtension
                .replace("acapela-", "", ignoreCase = true)
                .replace("-Old", "", ignoreCase = true)
                .replace("ara-SAU-", "", ignoreCase = true)
        }

        return Pair(engine, detectedVoice)
    }

    private fun extractVoiceNameFromEntry(filename: String): String {
        val lower = filename.lowercase()
        return when {
            lower.contains("nizar") -> "Nizar"
            lower.contains("salma") -> "Salma"
            lower.contains("mehdi") -> "Mehdi"
            lower.contains("leila") || lower.contains("layla") -> "Leila"
            lower.contains("will") -> "Will"
            lower.contains("sharon") -> "Sharon"
            lower.contains("ryan") -> "Ryan"
            lower.contains("tracy") -> "Tracy"
            lower.contains("peter") -> "Peter"
            lower.contains("rachel") -> "Rachel"
            else -> ""
        }
    }

    /** استخراج محتويات ZIP بأمان ضد ثغرة Zip Slip */
    private fun extractZip(
        zipFile: File,
        targetDir: File,
        onProgress: ((Float) -> Unit)? = null
    ): Int {
        var count = 0
        val totalBytes = zipFile.length().toFloat().coerceAtLeast(1f)
        var readBytes = 0L

        val stream = BufferedInputStream(FileInputStream(zipFile))
        ZipInputStream(stream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)

                // فحص الأمان لمنع Zip Slip
                val canonicalDestPath = targetDir.canonicalPath
                val canonicalOutPath = outFile.canonicalPath
                val isSafe = canonicalOutPath.startsWith(
                    canonicalDestPath + File.separator
                ) || canonicalOutPath == canonicalDestPath

                if (!isSafe) {
                    Log.w(TAG, "Blocked ZipSlip entry: ${entry.name}")
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            readBytes += len
                            // سقف استخراج يمنع أرشيف القنبلة (ع٢٩-3)
                            if (readBytes > MAX_EXTRACTION_BYTES) {
                                throw IllegalStateException(
                                    "Import exceeds " +
                                        "$MAX_EXTRACTION_BYTES bytes"
                                )
                            }
                            onProgress?.invoke(
                                (readBytes / totalBytes).coerceIn(0f, 1f)
                            )
                            fos.write(buffer, 0, len)
                        }
                    }
                    count++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return count
    }
}
