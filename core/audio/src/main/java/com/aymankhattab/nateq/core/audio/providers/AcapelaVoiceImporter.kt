package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * مستورد أصوات Acapela من حزم ZIP.
 * يستخرج الملفات إلى مجلد الأصوات المدمجة لـ Acapela بأمان كامل.
 */
object AcapelaVoiceImporter {

    private const val TAG = "AcapelaVoiceImporter"
    private const val BUFFER_SIZE = 8192

    data class ImportResult(
        val success: Boolean,
        val importedVoices: List<String> = emptyList(),
        val errorMessage: String? = null
    )

    /**
     * استيراد صوت Acapela من ملف ZIP عبر [Uri].
     */
    suspend fun importFromZip(
        context: Context,
        zipUri: Uri,
        suggestedName: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val baseDir = EmbeddedStorage.baseDir(
                context,
                EmbeddedStorage.Engine.ACAPELA
            )
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            // حفظ نسخة مؤقتة من الملف لفحصه قبل الاستخراج
            val tempZip = File(context.cacheDir, "temp_acapela_import.zip")
            if (tempZip.exists()) {
                tempZip.delete()
            }

            context.contentResolver.openInputStream(zipUri)?.use { input ->
                FileOutputStream(tempZip).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            } ?: return@withContext ImportResult(
                success = false,
                errorMessage = "تعذر فتح ملف ZIP المحدد"
            )

            // فحص أسماء المدخلات لمعرفة هل هي مجلد أم ملفات مباشرة
            val entryNames = mutableListOf<String>()
            var hasConfig = false
            var voiceCandidateName = suggestedName
                ?.removeSuffix(".zip")
                ?.removeSuffix(".ZIP")
                ?.trim()

            java.util.zip.ZipFile(tempZip).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    entryNames.add(name)
                    val lower = name.lowercase()
                    if (lower.endsWith(".inf") || lower.endsWith(".ini")) {
                        hasConfig = true
                        val filePart = File(name).name
                        val nameWithoutExt = filePart.substringBeforeLast('.')
                        if (voiceCandidateName.isNullOrBlank()) {
                            voiceCandidateName = nameWithoutExt
                        }
                    }
                }
            }

            if (!hasConfig && entryNames.none {
                val l = it.lowercase()
                l.endsWith(".bvcu") || l.endsWith(".dat")
            }) {
                tempZip.delete()
                return@withContext ImportResult(
                    success = false,
                    errorMessage = "الملف لا يحتوي على بيانات صوت Acapela صالحة"
                )
            }

            // تحديد مجلد الوجهة: إن كانت الملفات كلها داخل مجلد فرعي
            // تُستخرج إلى baseDir مباشرة، وإلا في مجلد باسم الصوت
            val rootEntries = entryNames.map {
                it.split('/', '\\').firstOrNull().orEmpty()
            }.filter { it.isNotBlank() }.distinct()

            val targetDir = if (rootEntries.size == 1 &&
                entryNames.all { it.startsWith(rootEntries.first()) }
            ) {
                baseDir
            } else {
                val voiceDirName = voiceCandidateName?.ifBlank { "voice" }
                    ?: "voice"
                File(baseDir, voiceDirName).apply { mkdirs() }
            }

            val canonicalBase = targetDir.canonicalPath

            // استخراج المحتويات مع حماية كاملة من ثغرة Zip Slip
            ZipInputStream(tempZip.inputStream()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.replace('\\', '/')
                    val outFile = File(targetDir, entryName)
                    val canonicalOut = outFile.canonicalPath
                    if (!canonicalOut.startsWith(canonicalBase)) {
                        throw SecurityException(
                            "محاولة اختراق مسار عبر Zip Slip: $entryName"
                        )
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos, BUFFER_SIZE)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            tempZip.delete()

            // إبطال كاش التخزين والمحركات لتحديث الواجهة فوراً
            EmbeddedStorage.bumpVersion()
            EnginePicker.invalidateAfterStorageChange()

            val settings = SettingsRepository.create(context)
            if (settings.isFilterEnginesEnabled()) {
                val cur = settings.getFilterEngines().toMutableSet()
                cur.add(AcapelaEmbeddedVoiceProvider.PROVIDER_ID)
                cur.add("acapela")
                settings.setFilterEngines(cur)
                VisibleEngines.setFilter(true, cur)
            }

            val installedVoices = AcapelaVoiceManifest.listVoices(baseDir)
            if (installedVoices.isNotEmpty()) {
                EngineStatusRegistry.noteFilesPresent(
                    AcapelaEmbeddedVoiceProvider.PROVIDER_ID
                )
                ImportResult(
                    success = true,
                    importedVoices = installedVoices.map { it.displayName }
                )
            } else {
                ImportResult(
                    success = false,
                    errorMessage =
                        "لم يتم العثور على أصوات Acapela صالحة بعد فك الضغط"
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "فشل استخراج ملف ZIP لـ Acapela", t)
            ImportResult(
                success = false,
                errorMessage = t.message ?: "حدث خطأ غير متوقع أثناء الاستيراد"
            )
        }
    }

    /**
     * استيراد أصوات Acapela من مجلد محدد عبر SAF [Uri].
     */
    suspend fun importFromTreeUri(
        context: Context,
        treeUri: Uri
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext ImportResult(
                    success = false,
                    errorMessage = "تعذر فتح مجلد الأصوات المحدد"
                )

            val baseDir = EmbeddedStorage.baseDir(
                context,
                EmbeddedStorage.Engine.ACAPELA
            )
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            val children = rootDoc.listFiles()
            var copiedFilesCount = 0

            val hasDirectConfig = children.any { f ->
                val name = f.name?.lowercase().orEmpty()
                name.endsWith(".inf") || name.endsWith(".ini")
            }
            val hasDirectData = children.any { f ->
                val name = f.name?.lowercase().orEmpty()
                name.endsWith(".bvcu") || name.endsWith(".dat")
            }

            if (hasDirectConfig || hasDirectData) {
                val voiceDirName = rootDoc.name?.ifBlank { "voice" } ?: "voice"
                val targetDir = File(baseDir, voiceDirName).apply { mkdirs() }
                copiedFilesCount += copyDocFiles(context, children, targetDir)
            } else {
                for (subDoc in children) {
                    if (subDoc.isDirectory) {
                        val subFiles = subDoc.listFiles()
                        val isVoice = subFiles.any { f ->
                            val n = f.name?.lowercase().orEmpty()
                            n.endsWith(".inf") || n.endsWith(".ini") ||
                                n.endsWith(".bvcu") || n.endsWith(".dat")
                        }
                        if (isVoice) {
                            val subName = subDoc.name?.ifBlank { "voice" }
                                ?: "voice"
                            val targetDir = File(baseDir, subName).apply {
                                mkdirs()
                            }
                            copiedFilesCount += copyDocFiles(
                                context, subFiles, targetDir
                            )
                        }
                    }
                }
            }

            if (copiedFilesCount == 0) {
                return@withContext ImportResult(
                    success = false,
                    errorMessage =
                        "لم يتم العثور على ملفات صوت Acapela صالحة داخل المجلد"
                )
            }

            EmbeddedStorage.bumpVersion()
            EnginePicker.invalidateAfterStorageChange()

            val settings = SettingsRepository.create(context)
            if (settings.isFilterEnginesEnabled()) {
                val cur = settings.getFilterEngines().toMutableSet()
                cur.add(AcapelaEmbeddedVoiceProvider.PROVIDER_ID)
                cur.add("acapela")
                settings.setFilterEngines(cur)
                VisibleEngines.setFilter(true, cur)
            }

            val installedVoices = AcapelaVoiceManifest.listVoices(baseDir)
            if (installedVoices.isNotEmpty()) {
                EngineStatusRegistry.noteFilesPresent(
                    AcapelaEmbeddedVoiceProvider.PROVIDER_ID
                )
                ImportResult(
                    success = true,
                    importedVoices = installedVoices.map { it.displayName }
                )
            } else {
                ImportResult(
                    success = false,
                    errorMessage =
                        "تم نسخ الملفات ولكن لم تكتمل بنية صوت Acapela"
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "فشل استيراد مجلد أصوات Acapela", t)
            ImportResult(
                success = false,
                errorMessage =
                    t.message ?: "حدث خطأ غير متوقع أثناء استيراد المجلد"
            )
        }
    }

    private fun copyDocFiles(
        context: Context,
        docFiles: Array<DocumentFile>,
        targetDir: File
    ): Int {
        var count = 0
        for (df in docFiles) {
            if (df.isFile) {
                val fname = df.name ?: continue
                val outFile = File(targetDir, fname)
                context.contentResolver.openInputStream(df.uri)?.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output, BUFFER_SIZE)
                    }
                    count++
                }
            } else if (df.isDirectory) {
                val subDir = File(targetDir, df.name ?: continue).apply {
                    mkdirs()
                }
                count += copyDocFiles(context, df.listFiles(), subDir)
            }
        }
        return count
    }

    /**
     * استيراد مباشر من مجلد محلي على القرص (للاختبارات أو النقل المباشر).
     */
    suspend fun importFromDirectory(
        context: Context,
        sourceDir: File
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                return@withContext ImportResult(
                    success = false,
                    errorMessage = "المجلد المحدد غير موجود"
                )
            }

            val baseDir = EmbeddedStorage.baseDir(
                context,
                EmbeddedStorage.Engine.ACAPELA
            )
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            val voiceDirs = AcapelaVoiceManifest.completeVoiceDirs(sourceDir)
            if (voiceDirs.isEmpty()) {
                return@withContext ImportResult(
                    success = false,
                    errorMessage =
                        "لا توجد أصوات Acapela صالحة داخل المجلد المحدد"
                )
            }

            for (vd in voiceDirs) {
                val target = File(baseDir, vd.name).apply { mkdirs() }
                vd.copyRecursively(target, overwrite = true)
            }

            EmbeddedStorage.bumpVersion()
            EnginePicker.invalidateAfterStorageChange()

            val settings = SettingsRepository.create(context)
            if (settings.isFilterEnginesEnabled()) {
                val cur = settings.getFilterEngines().toMutableSet()
                cur.add(AcapelaEmbeddedVoiceProvider.PROVIDER_ID)
                cur.add("acapela")
                settings.setFilterEngines(cur)
                VisibleEngines.setFilter(true, cur)
            }

            val installedVoices = AcapelaVoiceManifest.listVoices(baseDir)
            if (installedVoices.isNotEmpty()) {
                EngineStatusRegistry.noteFilesPresent(
                    AcapelaEmbeddedVoiceProvider.PROVIDER_ID
                )
                ImportResult(
                    success = true,
                    importedVoices = installedVoices.map { it.displayName }
                )
            } else {
                ImportResult(
                    success = false,
                    errorMessage = "فشل التعرف على الأصوات بعد نسخها"
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "فشل نسخ مجلد أصوات Acapela", t)
            ImportResult(
                success = false,
                errorMessage = t.message ?: "حدث خطأ غير متوقع"
            )
        }
    }
}
