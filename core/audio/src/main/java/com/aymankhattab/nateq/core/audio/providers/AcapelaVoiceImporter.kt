package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * مستورد أصوات Acapela من حزم ZIP.
 * يستخرج الملفات إلى مجلد الأصوات المدمجة لـ Acapela بأمان كامل.
 *
 * **يتحقق من النطق فعلياً:** نجاح الاستخراج وحده لا يكفي — صوتٌ
 * موجود على القرص قد تفشل مكتبة Acapela في تحميله أو لا يصدر عنه
 * أي عيّنة، فيبدو «مستورداً بنجاح» وهو صامت. لذلك يُشغَّل المحرك
 * بعد الاستيراد ويُنطق بكل صوتٍ نصٌّ قصير، ولا يُعلَن النجاح إلا
 * بعيّنة صوتية حقيقية. وإن تعذّر تشغيل المضيف أصلاً (بيئة بلا
 * مكتبة أصلية) بقي الاستيراد ناجحاً مع تحذير.
 */
object AcapelaVoiceImporter {

    private const val TAG = "AcapelaVoiceImporter"
    private const val BUFFER_SIZE = 8192

    /** النصّ الذي يُنطق للتحقق: أقصر ما يُنتج عيّنة صوتية. */
    private const val VERIFY_TEXT = "1"

    /** سقف انتظار ربط المضيف عند التحقق (لا يعلّق الاستيراد). */
    private const val VERIFY_INIT_TIMEOUT_MS = 4_000L

    /**
     * رموز تعني «تعذّر التحقق لسببٍ بيئي» لا «الصوت تالف»، فلا تُفشل
     * الاستيراد (ومنها بيئة الاختبارات بلا مكتبة أصلية).
     */
    private val UNVERIFIABLE_CODES = setOf(
        "NATIVE_LOAD_ERROR",
        "HOST_UNAVAILABLE",
        "HOST_EOF",
        "HOST_ERROR",
        "HOST_DIED",
        "BAD_ENGINE",
        "UNKNOWN_ENGINE",
        "NOT_INITIALIZED",
        "CRASHED_LAST_RUN"
    )

    data class ImportResult(
        val success: Boolean,
        val importedVoices: List<String> = emptyList(),
        val verifiedVoices: List<String> = emptyList(),
        val errorMessage: String? = null,
        val warning: String? = null
    )

    /** نتيجة التحقق الأصلي بعد الاستيراد. */
    private sealed class NativeVerification {
        data class Passed(
            val voices: List<String>,
            val failed: List<String>
        ) : NativeVerification()

        data class Failed(val message: String) : NativeVerification()

        data class Unavailable(val reason: String) : NativeVerification()
    }

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
                l.endsWith(".bvcu") || l.endsWith(".dat") ||
                    l.endsWith(".mdd")
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

            finishImport(
                context = context,
                baseDir = baseDir,
                emptyMessage =
                    "لم يتم العثور على أصوات Acapela صالحة بعد فك الضغط"
            )
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

            finishImport(
                context = context,
                baseDir = baseDir,
                emptyMessage =
                    "تم نسخ الملفات ولكن لم تكتمل بنية صوت Acapela"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "فشل استيراد مجلد أصوات Acapela", t)
            ImportResult(
                success = false,
                errorMessage =
                    t.message ?: "حدث خطأ غير متوقع أثناء استيراد المجلد"
            )
        }
    }

    /**
     * إنهاء الاستيراد: إزالة التهيئة المصطنعة فقط، ثم تعداد الأصوات
     * والتحقق الأصلي من نطقها.
     *
     * تعذّر تشغيل المضيف (بيئة بلا مكتبة أصلية) لا يُفشل الاستيراد
     * لأنه بيئي، وأي فشل صريح في تحميل الصوت أو صمته يُفشله فلا
     * يُعلَن صوتٌ صامت جاهزاً.
     */
    private suspend fun finishImport(
        context: Context,
        baseDir: File,
        emptyMessage: String
    ): ImportResult {
        AcapelaVoiceManifest.pruneSyntheticConfigs(baseDir)
        AcapelaVoiceManifest.ensureVoiceConfigs(baseDir)
        val voices = AcapelaVoiceManifest.listVoices(baseDir)
        if (voices.isEmpty()) {
            return ImportResult(success = false, errorMessage = emptyMessage)
        }
        EngineStatusRegistry.noteFilesPresent(
            AcapelaEmbeddedVoiceProvider.PROVIDER_ID
        )
        val imported = voices.map { it.displayName }
        return when (val check = verifyNative(context, baseDir, voices)) {
            is NativeVerification.Passed -> ImportResult(
                success = true,
                importedVoices = imported,
                verifiedVoices = check.voices,
                warning = if (check.failed.isEmpty()) {
                    null
                } else {
                    "أصوات لم تُثبت نطقها: ${check.failed.joinToString(", ")}"
                }
            )

            is NativeVerification.Unavailable -> ImportResult(
                success = true,
                importedVoices = imported,
                warning = "لم يُتحقق من النطق الآن (${check.reason})"
            )

            is NativeVerification.Failed -> ImportResult(
                success = false,
                errorMessage = check.message
            )
        }
    }

    /**
     * تشغيل المضيف ثم نطق نصٍّ قصير بكل صوت: الصوت الصامت يُكتشف
     * هنا لا عند أول استخدام.
     */
    private suspend fun verifyNative(
        context: Context,
        baseDir: File,
        voices: List<AcapelaVoiceManifest.VoiceInfo>
    ): NativeVerification {
        val engineId = HostProtocol.ENGINE_ACAPELA
        val init = withTimeoutOrNull(VERIFY_INIT_TIMEOUT_MS) {
            EngineHostClient.init(
                context,
                engineId,
                baseDir.absolutePath,
                force = true
            )
        }
        if (init == null) {
            return NativeVerification.Unavailable("انتهت مهلة ربط المحرك")
        }
        if (!init.ok) {
            val code = init.code.orEmpty()
            if (code in UNVERIFIABLE_CODES || code.isBlank()) {
                return NativeVerification.Unavailable(
                    code.ifBlank { "HOST_UNAVAILABLE" }
                )
            }
            return NativeVerification.Failed(
                init.message?.takeIf { it.isNotBlank() }
                    ?: "تعذر تحميل محرك Acapela [$code]"
            )
        }

        val verified = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var lastFailure: String? = null
        for (voice in voices) {
            var bytes = 0
            val res = EngineHostClient.synthesize(
                context = context,
                engineId = engineId,
                voiceId = voice.id,
                text = VERIFY_TEXT,
                rate = 1f,
                pitch = 1f,
                volume = 1f,
                onFormatInfo = { _, _ -> },
                onAudioChunk = { _, length -> bytes += length }
            )
            if (res.ok && bytes > 0) {
                verified.add(voice.displayName)
            } else {
                failed.add(voice.displayName)
                lastFailure = res.message?.takeIf { it.isNotBlank() }
                    ?: res.code ?: "فشل التحقق"
                Log.w(
                    TAG,
                    "تحقق Acapela [${voice.id}]: ${res.code} " +
                        "${res.message}"
                )
            }
        }
        return if (verified.isNotEmpty()) {
            NativeVerification.Passed(verified, failed)
        } else {
            NativeVerification.Failed(
                lastFailure
                    ?: "لم يُنتج أي صوت Acapela عيّنة صوتية"
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

            finishImport(
                context = context,
                baseDir = baseDir,
                emptyMessage = "فشل التعرف على الأصوات بعد نسخها"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "فشل نسخ مجلد أصوات Acapela", t)
            ImportResult(
                success = false,
                errorMessage = t.message ?: "حدث خطأ غير متوقع"
            )
        }
    }
}
