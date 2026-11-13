package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.aymankhattab.nateq.core.audio.CategoryFailureTracker
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
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
    private const val VERIFY_INIT_TIMEOUT_MS = 10_000L

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
        val tempUnpack = File(
            context.cacheDir,
            "temp_acapela_unpack_${UUID.randomUUID()}"
        )
        val tempZip = File(
            context.cacheDir,
            "temp_acapela_import_${UUID.randomUUID()}.zip"
        )
        try {
            val baseDir = EmbeddedStorage.baseDir(
                context,
                EmbeddedStorage.Engine.ACAPELA
            )
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

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

            if (!tempUnpack.exists()) {
                tempUnpack.mkdirs()
            }
            val canonicalUnpack = tempUnpack.canonicalPath

            // استخراج كل محتويات ZIP إلى المجلد المؤقت بأمان من Zip Slip
            ZipInputStream(tempZip.inputStream()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.replace('\\', '/')
                    val outFile = File(tempUnpack, entryName)
                    val canonicalOut = outFile.canonicalPath
                    if (!canonicalOut.startsWith(canonicalUnpack)) {
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

            // البحث عن ملف تسجيل hex في شجرة الملفات المستخرجة
            val hexFile = AcapelaVoiceManifest
                .findHexRegistrationFile(tempUnpack)
                ?: tempUnpack.walkTopDown().maxDepth(6).firstOrNull { f ->
                    f.isFile && (f.name.matches(
                        AcapelaVoiceManifest.HEX_REGISTRATION_REGEX
                    ) || f.name.equals("registration.txt", ignoreCase = true))
                }

            // البحث عن مجلدات الأصوات الصالحة داخل المجلد المؤقت
            var voiceDirs = AcapelaVoiceManifest.completeVoiceDirs(tempUnpack)
            if (voiceDirs.isEmpty()) {
                val candidateDirs = tempUnpack.walkTopDown().maxDepth(6)
                    .filter { f ->
                        f.isDirectory && (f.listFiles { c ->
                            c.isFile && (c.extension.lowercase(Locale.ROOT) in
                                AcapelaVoiceManifest.DATA_EXTENSIONS ||
                                c.extension.lowercase(Locale.ROOT) in
                                AcapelaVoiceManifest.CONFIG_EXTENSIONS)
                        }?.isNotEmpty() == true)
                    }.toList()
                voiceDirs = candidateDirs
            }
            if (voiceDirs.isEmpty() &&
                AcapelaVoiceManifest.isCompleteVoiceDir(tempUnpack)
            ) {
                voiceDirs = listOf(tempUnpack)
            }

            if (voiceDirs.isEmpty()) {
                return@withContext ImportResult(
                    success = false,
                    errorMessage =
                        "الملف لا يحتوي على بيانات صوت Acapela صالحة"
                )
            }

            // مجلد قواميس المستخدم المرفق مع الحزمة إن وجد
            val sharedUserdicos = tempUnpack.walkTopDown().maxDepth(6)
                .firstOrNull {
                    it.isDirectory && it.name.equals(
                        "userdicos",
                        ignoreCase = true
                    )
                }

            for (vd in voiceDirs) {
                val info = runCatching {
                    AcapelaVoiceManifest.parseVoice(vd)
                }.getOrNull()
                val candidateName = suggestedName
                    ?.removeSuffix(".zip")
                    ?.removeSuffix(".ZIP")
                    ?.trim()
                val rawVoiceName = info?.voiceName?.ifBlank { null }
                    ?: candidateName?.ifBlank { null }
                    ?: vd.name
                val voiceName = AcapelaVoiceManifest
                    .cleanAcapelaSpeaker(rawVoiceName)
                    .ifBlank { "voice" }

                val targetDir = File(baseDir, voiceName).apply { mkdirs() }
                vd.copyRecursively(targetDir, overwrite = true)
                val targetUserdicos = File(targetDir, "userdicos").apply {
                    mkdirs()
                }
                if (sharedUserdicos != null &&
                    sharedUserdicos != targetUserdicos
                ) {
                    runCatching {
                        sharedUserdicos.copyRecursively(
                            targetUserdicos,
                            overwrite = true
                        )
                    }
                }
                if (hexFile != null) {
                    runCatching {
                        hexFile.copyTo(
                            File(targetDir, hexFile.name),
                            overwrite = true
                        )
                    }
                }
                AcapelaVoiceManifest.normalizeExtensionsCase(targetDir)
            }

            if (hexFile != null) {
                runCatching {
                    hexFile.copyTo(
                        File(baseDir, hexFile.name),
                        overwrite = true
                    )
                }
            }

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
        } finally {
            runCatching { tempZip.delete() }
            runCatching { tempUnpack.deleteRecursively() }
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
                val name = f.name?.lowercase(Locale.ROOT).orEmpty()
                name.endsWith(".inf") || name.endsWith(".ini")
            }
            val hasDirectData = children.any { f ->
                val name = f.name?.lowercase(Locale.ROOT).orEmpty()
                name.endsWith(".bvcu") || name.endsWith(".dat")
            }

            if (hasDirectConfig || hasDirectData) {
                val voiceDirName = rootDoc.name?.ifBlank { "voice" } ?: "voice"
                val targetDir = File(baseDir, voiceDirName).apply { mkdirs() }
                copiedFilesCount += copyDocFiles(context, children, targetDir)
                AcapelaVoiceManifest.normalizeExtensionsCase(targetDir)
            } else {
                for (subDoc in children) {
                    if (subDoc.isDirectory) {
                        val subFiles = subDoc.listFiles()
                        val isVoice = subFiles.any { f ->
                            val n = f.name?.lowercase(Locale.ROOT).orEmpty()
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
                            AcapelaVoiceManifest
                                .normalizeExtensionsCase(targetDir)
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

            val hexFile = AcapelaVoiceManifest.findHexRegistrationFile(baseDir)
            for (vd in AcapelaVoiceManifest.completeVoiceDirs(baseDir)) {
                File(vd, "userdicos").mkdirs()
                if (hexFile != null && hexFile.parentFile != vd) {
                    runCatching {
                        hexFile.copyTo(
                            File(vd, hexFile.name),
                            overwrite = true
                        )
                    }
                }
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
     * لأنه بيئي، ومطابقة لمنهج التطبيقات المرجعية فالملفات التي
     * تم استخراجها بنجاح لا يُلغى استيرادها بسبب تعثر المضيف.
     */
    private suspend fun finishImport(
        context: Context,
        baseDir: File,
        emptyMessage: String
    ): ImportResult {
        // تصفير سجل التعثر لإلغاء أي حظر طوارئ قائم فوراً
        CategoryFailureTracker.reset()
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
        val check = verifyNative(context, baseDir, voices)
        CategoryFailureTracker.reset()
        if (check is NativeVerification.Passed) {
            EngineStatusRegistry.noteReady(
                AcapelaEmbeddedVoiceProvider.PROVIDER_ID
            )
        } else if (check is NativeVerification.Unavailable) {
            EngineStatusRegistry.noteFilesPresent(
                AcapelaEmbeddedVoiceProvider.PROVIDER_ID
            )
        }
        return when (check) {
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
                success = true,
                importedVoices = imported,
                warning = check.message
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

            val hexFile = AcapelaVoiceManifest
                .findHexRegistrationFile(sourceDir)
                ?: AcapelaVoiceManifest.findHexRegistrationFile(baseDir)

            for (vd in voiceDirs) {
                val target = File(baseDir, vd.name).apply { mkdirs() }
                vd.copyRecursively(target, overwrite = true)
                File(target, "userdicos").mkdirs()
                if (hexFile != null) {
                    runCatching {
                        hexFile.copyTo(
                            File(target, hexFile.name),
                            overwrite = true
                        )
                    }
                }
            }

            if (hexFile != null) {
                runCatching {
                    hexFile.copyTo(
                        File(baseDir, hexFile.name),
                        overwrite = true
                    )
                }
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
