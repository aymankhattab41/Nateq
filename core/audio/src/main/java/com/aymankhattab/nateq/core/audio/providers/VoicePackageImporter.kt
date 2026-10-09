package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
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

    /**
     * استيراد باقة صوت من مجلد (DocumentFile tree) — ع٢٩-8 بند 4.
     * ينسخ محتويات المجلد المحدّد إلى مجلد المحرك المناسب
     * بدون الحاجة لملف ZIP. لا يحتاج إذن تخزين.
     */
    suspend fun importFromDocumentTree(
        context: Context,
        treeUri: Uri,
        onProgress: ((Float) -> Unit)? = null
    ): VoiceImportResult = withContext(Dispatchers.IO) {
        try {
            val docFile = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext VoiceImportResult(
                    isSuccess = false,
                    engineType = ENGINE_UNKNOWN,
                    voiceName = "",
                    targetDir = null,
                    extractedFilesCount = 0,
                    errorMessage = "Cannot build DocumentFile from tree URI"
                )

            // افحص الملفات لتحديد المحرك
            val (engineType, detectedVoiceName) = inspectDocumentTree(docFile)
            if (engineType == ENGINE_UNKNOWN) {
                return@withContext VoiceImportResult(
                    isSuccess = false,
                    engineType = ENGINE_UNKNOWN,
                    voiceName = detectedVoiceName,
                    targetDir = null,
                    extractedFilesCount = 0,
                    errorMessage = "Unrecognized voice package format in folder"
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

            val copiedCount = copyDocumentTree(
                docFile,
                targetDir,
                context,
                onProgress
            )
            val finalVoiceName = if (detectedVoiceName.isNotEmpty()) {
                detectedVoiceName
            } else {
                "Voice"
            }

            // ع٢٩-9: اكتمال الاستيراد يرفع إصدار التخزين ويبطل الكاش.
            if (copiedCount > 0) {
                EmbeddedStorage.bumpVersion()
                EnginePicker.invalidateAfterStorageChange()
            }

            VoiceImportResult(
                isSuccess = true,
                engineType = engineType,
                voiceName = finalVoiceName,
                targetDir = targetDir,
                extractedFilesCount = copiedCount
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Error importing from folder: ${t.message}", t)
            VoiceImportResult(
                isSuccess = false,
                engineType = ENGINE_UNKNOWN,
                voiceName = "",
                targetDir = null,
                extractedFilesCount = 0,
                errorMessage = t.message ?: "Folder import failed"
            )
        }
    }

    private fun inspectDocumentTree(
        docFile: DocumentFile
    ): Pair<String, String> {
        var isAcapela = false
        var isPiper = false
        var isVocalizer = false
        var detectedVoice = ""

        fun recurse(file: DocumentFile) {
            if (file.isDirectory) {
                file.listFiles()?.forEach { recurse(it) }
            } else {
                val name = file.name?.lowercase() ?: ""
                val entryBaseName = File(file.name ?: "").name
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
                            val fName =
                                File(file.name ?: "").nameWithoutExtension
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
            }
        }

        recurse(docFile)

        val engine = when {
            isAcapela -> ENGINE_ACAPELA
            isPiper -> ENGINE_PIPER
            isVocalizer -> ENGINE_VOCALIZER
            else -> ENGINE_UNKNOWN
        }

        if (detectedVoice.isEmpty() && engine == ENGINE_ACAPELA) {
            val name = docFile.name ?: ""
            detectedVoice = name.lowercase()
                .removePrefix("acapela-")
                .replace("-old", "")
                .replace("ara-sau-", "")
        }

        return Pair(engine, detectedVoice)
    }

    private fun copyDocumentTree(
        src: DocumentFile,
        dest: File,
        context: Context,
        onProgress: ((Float) -> Unit)? = null
    ): Int {
        var count = 0
        val files = collectFiles(src)
        val total = files.size.toFloat().coerceAtLeast(1f)
        var processed = 0f

        for (file in files) {
            // DocumentFile.name is nullable, fallback to empty
            val relativePath = file.name ?: ""
            val destFile = File(dest, relativePath)
            destFile.parentFile?.mkdirs()

            context.contentResolver.openInputStream(file.uri).use { input ->
                if (input != null) {
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                    count++
                }
            }
            processed++
            onProgress?.invoke((processed / total).coerceIn(0f, 1f))
        }
        return count
    }

    private fun collectFiles(docFile: DocumentFile): List<DocumentFile> {
        val list = mutableListOf<DocumentFile>()
        fun recurse(f: DocumentFile) {
            if (f.isDirectory) {
                f.listFiles()?.forEach { recurse(it) }
            } else {
                list.add(f)
            }
        }
        recurse(docFile)
        return list
    }
}
