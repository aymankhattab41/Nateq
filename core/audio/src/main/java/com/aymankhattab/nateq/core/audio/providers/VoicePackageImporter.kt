package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * مستورد حزم الأصوات المضغوطة (ZIP) لجميع المحركات المدمجة
 * (Acapela و Vocalizer).
 */
object VoicePackageImporter {

    private const val TAG = "VoicePackageImporter"
    private const val BUFFER_SIZE = 8192

    data class Result(
        val success: Boolean,
        val engineName: String = "",
        val importedVoices: List<String> = emptyList(),
        val errorMessage: String? = null
    )

    /**
     * استيراد حزمة أصوات من ملف ZIP وتحديد المحرك المناسب تلقائياً.
     */
    suspend fun importZip(
        context: Context,
        zipUri: Uri,
        suggestedName: String? = null
    ): Result = withContext(Dispatchers.IO) {
        val tempZip = File(context.cacheDir, "temp_voice_package_import.zip")
        try {
            if (tempZip.exists()) {
                tempZip.delete()
            }

            context.contentResolver.openInputStream(zipUri)?.use { input ->
                FileOutputStream(tempZip).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            } ?: return@withContext Result(
                success = false,
                errorMessage = "تعذر فتح ملف ZIP المحدد"
            )

            val entryNames = mutableListOf<String>()
            var isAcapela = false
            var isVocalizer = false

            ZipFile(tempZip).use { zf ->
                val it = zf.entries()
                while (it.hasMoreElements()) {
                    val name = it.nextElement().name.lowercase()
                    entryNames.add(name)
                    if (name.endsWith(".inf") || name.endsWith(".ini") ||
                        name.endsWith(".bvcu") || name.endsWith(".mdd")
                    ) {
                        isAcapela = true
                    }
                    if (name.endsWith(".hdr") || name.endsWith(".clsk")) {
                        isVocalizer = true
                    }
                }
            }

            if (!isAcapela && !isVocalizer) {
                if (entryNames.any { it.endsWith(".dat") }) {
                    val candidate = suggestedName?.lowercase().orEmpty()
                    val acapelaKeywords = listOf(
                        "acapela", "mehdi", "salma", "leila", "nizar",
                        "youssef", "tarik", "mariam"
                    )
                    if (acapelaKeywords.any { candidate.contains(it) }) {
                        isAcapela = true
                    } else {
                        isVocalizer = true
                    }
                }
            }

            if (isAcapela) {
                val acRes = AcapelaVoiceImporter.importFromZip(
                    context,
                    Uri.fromFile(tempZip),
                    suggestedName
                )
                Result(
                    success = acRes.success,
                    engineName = "Acapela",
                    importedVoices = acRes.importedVoices,
                    errorMessage = acRes.errorMessage
                )
            } else if (isVocalizer) {
                importVocalizerZip(context, tempZip, suggestedName)
            } else {
                Result(
                    success = false,
                    errorMessage =
                        "الملف لا يحتوي على حزمة أصوات مدعومة " +
                            "لـ Acapela أو Vocalizer"
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "خطأ أثناء استيراد حزمة الأصوات", t)
            Result(
                success = false,
                errorMessage = t.message ?: "خطأ أثناء استيراد حزمة الأصوات"
            )
        } finally {
            if (tempZip.exists()) {
                tempZip.delete()
            }
        }
    }

    /**
     * استخراج وتثبيت حزمة أصوات Vocalizer من ملف ZIP.
     */
    private fun importVocalizerZip(
        context: Context,
        tempZip: File,
        suggestedName: String?
    ): Result {
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        val canonicalBase = baseDir.canonicalPath
        val extractedFiles = mutableListOf<String>()

        ZipInputStream(tempZip.inputStream()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name.replace('\\', '/')
                val outFile = File(baseDir, entryName)
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
                    extractedFiles.add(outFile.name)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        EmbeddedStorage.bumpVersion()
        EnginePicker.invalidateAfterStorageChange()

        val settings = SettingsRepository.create(context)
        if (settings.isFilterEnginesEnabled()) {
            val cur = settings.getFilterEngines().toMutableSet()
            cur.add(VocalizerEmbeddedVoiceProvider.PROVIDER_ID)
            cur.add("vocalizer")
            settings.setFilterEngines(cur)
            VisibleEngines.setFilter(true, cur)
        }

        val hdrs = extractedFiles.filter {
            it.endsWith(".hdr", ignoreCase = true)
        }
        val names = if (hdrs.isNotEmpty()) {
            hdrs.map { it.substringBeforeLast('.') }
        } else {
            listOf(
                suggestedName?.removeSuffix(".zip")
                    ?.removeSuffix(".ZIP") ?: "Vocalizer"
            )
        }

        EngineStatusRegistry.noteFilesPresent(
            VocalizerEmbeddedVoiceProvider.PROVIDER_ID
        )

        return Result(
            success = true,
            engineName = "Vocalizer",
            importedVoices = names
        )
    }
}
