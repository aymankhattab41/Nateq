package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.net.Uri
import android.util.Log
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
import java.nio.charset.StandardCharsets
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
     * نسخ حرفي لشجرة كاملة مع الحفاظ على كل مسار نسبي.
     *
     * **لماذا حرفي:** حزمة MultiTTS الأصلية تضع `PRO "NLP/ar_sa.oso"` داخل
     * `leila22k_hq/ar_sa_hd_leila_22k_lf.bvcu.ini` وتضع ملفات NLP تحت
     * `NLP/`. أي تسطيح أو توليد `.ini` يفقد هذه التوجيهات فيظهر
     * `nlpInitError=-34` ثم `eb=-27` ثم `nSpeak=-2` و`PCM=0`.
     * لا يُستبعد هنا إلا `logfile.txt` (7.98 جيجابايت في المصدر المرجعي).
     *
     * الدالة نقية: لا تولّد تهيئة، لا تُطبع امتدادات، لا تُسطّح مجلدات،
     * ولا تضيف أي ملف غير موجود في المصدر.
     */
    internal fun copyVoiceTreeVerbatim(source: File, target: File): Int {
        if (!source.isDirectory) return 0
        var copied = 0
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source).path
                .replace(File.separatorChar, '/')
            if (relative.isEmpty() ||
                relative.substringAfterLast('/').equals(
                    AcapelaVoiceDiagnostics.LOG_FILE_NAME,
                    ignoreCase = true
                )
            ) {
                return@forEach
            }
            val out = File(target, relative)
            if (file.isDirectory) {
                out.mkdirs()
            } else if (file.isFile) {
                out.parentFile?.mkdirs()
                file.inputStream().use { input ->
                    FileOutputStream(out).use { output ->
                        input.copyTo(output, BUFFER_SIZE)
                    }
                }
                copied++
            }
        }
        return copied
    }

    /**
     * إيجاد جذر `voice/` الذي يحوي `acapela/` داخل شجرة مُستخرَجة.
     * يُفضَّل الفرع `files/voice` على `voice` لأنه المصدر المرجعي
     * المستخدم في القياس.
     */
    private fun findVerbatimVoiceRoot(root: File): File? {
        if (File(root, "acapela").isDirectory) return root
        val direct = File(root, "voice")
        if (File(direct, "acapela").isDirectory) return direct
        return root.walkTopDown().maxDepth(5).firstOrNull { dir ->
            dir.isDirectory &&
                dir.name.equals("voice", ignoreCase = true) &&
                File(dir, "acapela").isDirectory
        }?.let { found ->
            root.walkTopDown().maxDepth(5).firstOrNull { dir ->
                dir.isDirectory &&
                    dir.name.equals("voice", ignoreCase = true) &&
                    File(dir, "acapela").isDirectory &&
                    dir.parentFile?.name.equals("files", ignoreCase = true)
            } ?: found
        }
    }

    /**
     * نسخ config.yaml وملفات التسجيل المطابقة للصوت من أي مكان داخل الحزمة
     * المستخرجة إلى شجرة الهدف، حتى لو كانت خارج مجلد voice أو أعلى منه.
     */
    fun copyMatchingConfigsToTree(
        sourceTree: File,
        targetTree: File
    ): Int {
        if (!sourceTree.exists() || !targetTree.exists()) return 0
        val allYamls = sourceTree.walkTopDown().filter {
            it.isFile && it.name.equals("config.yaml", ignoreCase = true)
        }.toList()
        val allRegs = sourceTree.walkTopDown().filter {
            it.isFile && (
                it.name.matches(AcapelaVoiceManifest.HEX_REGISTRATION_REGEX) ||
                it.name.startsWith("0x") ||
                it.name.equals("registration.txt", ignoreCase = true) ||
                it.extension.equals("hex", ignoreCase = true)
            )
        }.toList()

        var copied = 0
        val targetVoiceDirs = targetTree.walkTopDown().maxDepth(5).filter { d ->
            d.isDirectory &&
                !AcapelaVoiceManifest.isIgnoredOrNonVoiceDirectory(d) &&
                d.listFiles { f ->
                    f.isFile && (
                        f.extension.equals("inf", ignoreCase = true) ||
                        f.extension.equals("ini", ignoreCase = true)
                    )
                }?.isNotEmpty() == true
        }.toList()

        for (tvd in targetVoiceDirs) {
            val info = runCatching {
                AcapelaVoiceManifest.parseVoice(tvd)
            }.getOrNull()
            val rawSpeaker = info?.rawSpeaker?.ifBlank { null }
                ?: info?.voiceName?.ifBlank { null }
                ?: tvd.name
            val speaker = AcapelaVoiceManifest.cleanAcapelaSpeaker(rawSpeaker)
            val matchingYaml = allYamls.firstOrNull {
                isValidConfigYaml(it, speaker)
            }
            if (matchingYaml != null) {
                val targetYaml = File(tvd, "config.yaml")
                if (!targetYaml.exists() ||
                    !isValidConfigYaml(targetYaml, speaker)
                ) {
                    if (copyConfigFileSafely(matchingYaml, targetYaml)) {
                        copied++
                    }
                }
            }
            val matchingReg = if (allRegs.size == 1) {
                allRegs.first()
            } else if (allRegs.size > 1) {
                AcapelaPackageResolver.selectHexBySpeaker(allRegs, speaker)
            } else {
                null
            }
            if (matchingReg != null) {
                val destReg = File(tvd, matchingReg.name)
                if (!destReg.exists()) {
                    runCatching {
                        matchingReg.copyTo(destReg, overwrite = true)
                    }
                }
            }
        }
        return copied
    }

    /** إبطال الكاش وإظهار محرك Acapela بعد أي نسخ فعلي للملفات. */
    private fun notifyStorageChanged(context: Context) {
        AcapelaVoiceManifest.invalidateVoicesCache()
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
    }

    private val CONFIG_YAML_PARAM_REGEX = Regex(
        """(?:^|\n)\s*"?param"?\s*[:=]\s*"?([^"\r\n#]+)"?""",
        setOf(RegexOption.MULTILINE)
    )

    /**
     * قراءة قيمة param مباشرة من ملف config.yaml محدد.
     */
    fun readParamFromFile(yamlFile: File): String? {
        if (!yamlFile.isFile || !yamlFile.exists() || yamlFile.length() <= 0L) {
            return null
        }
        if (yamlFile.length() > 512 * 1024L) return null
        return runCatching {
            val content = yamlFile.readText(StandardCharsets.UTF_8)
            CONFIG_YAML_PARAM_REGEX.find(content)
                ?.groupValues?.getOrNull(1)?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * التحقق من صلاحية ملف config.yaml:
     * صالح فقط إذا كان طوله أكبر من صفر، ويحوي سطر param صالحاً،
     * ويطابق المتحدث المطلوب.
     */
    fun isValidConfigYaml(
        yamlFile: File,
        expectedSpeaker: String? = null
    ): Boolean {
        if (!yamlFile.isFile || !yamlFile.exists() || yamlFile.length() <= 0L) {
            return false
        }
        val rawParam = readParamFromFile(yamlFile) ?: return false
        if (expectedSpeaker.isNullOrBlank()) return true
        val targetSpeaker = AcapelaVoiceManifest
            .cleanAcapelaSpeaker(expectedSpeaker)
            .lowercase(Locale.ROOT)
        if (targetSpeaker.isBlank()) return true
        val parsed = AcapelaVoiceManifest.splitConfigYamlParam(rawParam)
        val speakerCandidate = parsed?.first ?: rawParam
        val speaker = AcapelaVoiceManifest
            .cleanAcapelaSpeaker(speakerCandidate)
            .lowercase(Locale.ROOT)
        if (speaker.isBlank()) return false
        val pToken = AcapelaVoiceManifest.tokenOfIdentity(speaker)
        val tToken = AcapelaVoiceManifest.tokenOfIdentity(targetSpeaker)
        if (pToken != null && tToken != null) {
            return pToken == tToken
        }
        return speaker == targetSpeaker ||
            targetSpeaker.contains(speaker) ||
            speaker.contains(targetSpeaker)
    }

    /**
     * نسخ ملف تهيئة بأمان عبر ملف مؤقت ثم إعادة تسمية، والتحقق من تطابق الحجم.
     */
    fun copyConfigFileSafely(
        source: File,
        destination: File
    ): Boolean {
        if (!source.isFile || !source.exists() || source.length() <= 0L) {
            Log.e(TAG, "الملف المصدر غير موجود أو فارغ: ${source.absolutePath}")
            return false
        }
        val destDir = destination.parentFile
        if (destDir != null && !destDir.exists()) {
            destDir.mkdirs()
        }
        val tempFile = File(
            destDir ?: destination.parentFile,
            "${destination.name}.tmp_${UUID.randomUUID().toString().take(8)}"
        )
        return try {
            source.copyTo(tempFile, overwrite = true)
            if (tempFile.length() != source.length()) {
                Log.e(
                    TAG,
                    "حجم الملف المؤقت (${tempFile.length()}) لا يطابق " +
                        "المصدر (${source.length()}): ${source.absolutePath}"
                )
                tempFile.delete()
                return false
            }
            if (destination.exists()) {
                destination.delete()
            }
            val renamed = tempFile.renameTo(destination)
            if (!renamed) {
                tempFile.copyTo(destination, overwrite = true)
                tempFile.delete()
            }
            if (!destination.exists() ||
                destination.length() != source.length()
            ) {
                Log.e(
                    TAG,
                    "حجم الوجهة (${destination.length()}) لا يطابق " +
                        "المصدر (${source.length()}): " +
                        destination.absolutePath
                )
                destination.delete()
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "استثناء أثناء نسخ ${source.absolutePath} إلى " +
                    destination.absolutePath,
                t
            )
            tempFile.delete()
            false
        }
    }

    /**
     * إيجاد ملف config.yaml المطابق حصراً لهوية المتحدث المطلوب
     * دون نسخ ملف متحدث آخر (مثل Salma) إلى صوت مختلف (مثل Leila).
     * لا يُرجع ملفاً فارغاً أو بلا param.
     */
    fun findMatchingConfigYamlForVoice(
        voiceDir: File,
        candidateYamls: List<File>
    ): File? {
        val targetSpeaker = AcapelaVoiceManifest
            .cleanAcapelaSpeaker(voiceDir.name)
            .lowercase(Locale.ROOT)
        val direct = File(voiceDir, "config.yaml")
        if (isValidConfigYaml(direct, targetSpeaker)) {
            return direct
        }

        for (yaml in candidateYamls) {
            if (isValidConfigYaml(yaml, targetSpeaker)) {
                return yaml
            }
        }
        return null
    }

    /**
     * التحقق من وجود config.yaml صالح لكل صوت من Salma وLeila وNizar وغيرها
     * بعد الاستيراد وعند بدء التشغيل. وإن فُقد فأعد استنساخه من مصدر
     * الاستيراد الأصلي أو من الحزمة المطابقة.
     */
    fun ensureValidConfigYamlForVoices(
        context: Context,
        baseDir: File = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        ),
        stagingDir: File? = null
    ): Int {
        if (!baseDir.exists() || !baseDir.isDirectory) return 0
        val voiceDirs = baseDir.walkTopDown().maxDepth(4).filter { f ->
            f.isDirectory &&
                !AcapelaVoiceManifest.isIgnoredOrNonVoiceDirectory(f) &&
                f.listFiles { c ->
                    c.isFile && (
                        c.extension.equals("inf", ignoreCase = true) ||
                        c.extension.equals("ini", ignoreCase = true)
                    )
                }?.isNotEmpty() == true
        }.toList().ifEmpty {
            baseDir.listFiles { f ->
                f.isDirectory &&
                    !AcapelaVoiceManifest.isIgnoredOrNonVoiceDirectory(f)
            }?.toList().orEmpty()
        }
        if (voiceDirs.isEmpty()) return 0
        var repairedCount = 0
        val searchDirs = EmbeddedStorage.allSearchDirs(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        val stageDirs = mutableListOf<File>()
        if (stagingDir != null && stagingDir.isDirectory) {
            stageDirs.add(stagingDir)
        }
        context.cacheDir.listFiles { f ->
            f.isDirectory && (
                f.name.startsWith("acapela_staging_") ||
                f.name == "acapela_stage"
            )
        }?.let { stageDirs.addAll(it) }

        val allCandidateYamls = (searchDirs + stageDirs + listOf(baseDir))
            .filter { it.isDirectory }
            .flatMap { d ->
                d.walkTopDown().maxDepth(5).filter {
                    it.isFile && it.name.equals("config.yaml", true)
                }.toList()
            }.distinctBy { it.absolutePath }

        for (vd in voiceDirs) {
            val targetYaml = File(vd, "config.yaml")
            if (isValidConfigYaml(targetYaml, vd.name)) {
                continue
            }
            Log.w(
                TAG,
                "ملف config.yaml مفقود أو غير صالح للصوت ${vd.name}؛ " +
                    "جارٍ البحث عن نسخة مطابقة لاستعادتها..."
            )
            val resolvedPkg = runCatching {
                AcapelaPackageResolver.resolvePackage(vd)
            }.getOrNull()
            val pkgYaml = resolvedPkg?.metadataYaml
            if (pkgYaml != null && isValidConfigYaml(pkgYaml, vd.name)) {
                if (copyConfigFileSafely(pkgYaml, targetYaml)) {
                    Log.i(
                        TAG,
                        "تم استعادة config.yaml للصوت ${vd.name} " +
                            "من الحزمة المطابقة: ${pkgYaml.absolutePath}"
                    )
                    repairedCount++
                    continue
                }
            }

            val matchedYaml = allCandidateYamls.firstOrNull { candidate ->
                isValidConfigYaml(candidate, vd.name)
            }
            if (matchedYaml != null) {
                if (copyConfigFileSafely(matchedYaml, targetYaml)) {
                    Log.i(
                        TAG,
                        "تم استنساخ config.yaml للصوت ${vd.name} " +
                            "من المصدر: ${matchedYaml.absolutePath}"
                    )
                    repairedCount++
                } else {
                    Log.e(
                        TAG,
                        "فشل استنساخ config.yaml للصوت ${vd.name}"
                    )
                }
            } else {
                Log.w(
                    TAG,
                    "لم يُعثر على config.yaml مطابق للصوت ${vd.name}"
                )
            }
        }
        return repairedCount
    }

    /**
     * استيراد صوت Acapela من ملف أرشيف محلي في Cache عبر staging
     * مع التحقق من الهوية ومنع تسريب ملفات الدعم بين الأصوات المختلفة.
     */
    suspend fun importFromArchiveFile(
        context: Context,
        archiveFile: File,
        sessionId: String,
        cancellationSignal: java.util.concurrent.atomic.AtomicBoolean? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        recoverInterruptedTransactions(baseDir, context)

        val tempUnpack = File(context.cacheDir, "acapela_staging_$sessionId")
        if (tempUnpack.exists()) tempUnpack.deleteRecursively()
        tempUnpack.mkdirs()

        val backupParent = File(context.filesDir, "acapela_backups")
        if (!backupParent.exists()) backupParent.mkdirs()
        val backupDir = File(backupParent, "acapela_backup_$sessionId")
        val journalFile = File(backupParent, ".journal_acapela_$sessionId.json")
        val backups = mutableListOf<Pair<File, File>>()
        val createdTargets = mutableListOf<File>()

        // 5) سجل المعاملة يُكتب قبل نسخ أي ملف وقبل نقل المجلد القديم
        writeAcapelaJournal(
            journalFile = journalFile,
            sessionId = sessionId,
            backupDir = backupDir,
            createdTargets = createdTargets,
            backups = backups,
            baseDir = baseDir
        )

        try {
            val guard = ZipExtractionGuard(tempUnpack)
            ZipInputStream(archiveFile.inputStream()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (cancellationSignal?.get() == true) {
                        throw kotlinx.coroutines.CancellationException(
                            "تم إلغاء استخراج Acapela"
                        )
                    }
                    val entryName = entry.name.replace('\\', '/')
                    guard.onEntry(entry)
                    if (entryName.substringAfterLast('/').equals(
                            AcapelaVoiceDiagnostics.LOG_FILE_NAME,
                            ignoreCase = true
                        )
                    ) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    val outFile = guard.resolve(entryName)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            guard.copy(
                                input = zis,
                                output = fos,
                                buffer = ByteArray(BUFFER_SIZE),
                                isCancelled = {
                                    cancellationSignal?.get() == true
                                }
                            )
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (cancellationSignal?.get() == true) {
                throw kotlinx.coroutines.CancellationException(
                    "تم إلغاء الاستيراد"
                )
            }

            // مسار MultiTTS الحرفي: فحص اكتمال الملفات قبل لمس الحي
            val verbatimRoot = findVerbatimVoiceRoot(tempUnpack)
            val isVerbatimValid = verbatimRoot != null &&
                verbatimRoot.walkTopDown().any { f ->
                    f.isFile && (
                        f.extension.lowercase(Locale.ROOT) in
                            AcapelaVoiceManifest.DATA_EXTENSIONS ||
                        f.extension.lowercase(Locale.ROOT) in
                            AcapelaVoiceManifest.CONFIG_EXTENSIONS ||
                        f.name.equals("voicelist", ignoreCase = true)
                    )
                }

            if (isVerbatimValid && verbatimRoot != null) {
                if (backupDir.exists()) backupDir.deleteRecursively()
                backupDir.mkdirs()
                val targetVoiceDir = File(baseDir, "voice")
                if (targetVoiceDir.exists()) {
                    val backupVoiceDir = File(backupDir, "voice")
                    if (!targetVoiceDir.renameTo(backupVoiceDir)) {
                        targetVoiceDir.copyRecursively(
                            backupVoiceDir,
                            overwrite = true
                        )
                        targetVoiceDir.deleteRecursively()
                    }
                    backups.add(targetVoiceDir to backupVoiceDir)
                } else {
                    createdTargets.add(targetVoiceDir)
                }
                writeAcapelaJournal(
                    journalFile = journalFile,
                    sessionId = sessionId,
                    backupDir = backupDir,
                    createdTargets = createdTargets,
                    backups = backups,
                    baseDir = baseDir
                )
                val copied = copyVoiceTreeVerbatim(
                    verbatimRoot,
                    targetVoiceDir
                )
                if (copied > 0) {
                    copyMatchingConfigsToTree(tempUnpack, targetVoiceDir)
                    copyMatchingConfigsToTree(tempUnpack, baseDir)
                    Log.i(
                        TAG,
                        "نسخ MultiTTS حرفي: $copied ملفاً تحت " +
                            targetVoiceDir.absolutePath
                    )
                    writeAcapelaJournal(
                        journalFile = journalFile,
                        sessionId = sessionId,
                        backupDir = backupDir,
                        createdTargets = createdTargets,
                        backups = backups,
                        baseDir = baseDir
                    )
                    val finishResult = finishImport(
                        context = context,
                        baseDir = baseDir,
                        emptyMessage =
                            "لم يُعثر على أصوات Acapela بعد النسخ الحرفي",
                        stagingDir = tempUnpack
                    )
                    if (!finishResult.success) {
                        rollbackAcapela(
                            baseDir = baseDir,
                            createdTargets = createdTargets,
                            backups = backups,
                            backupDir = backupDir,
                            journalFile = journalFile,
                            context = context
                        )
                        return@withContext finishResult
                    }
                    notifyStorageChanged(context)
                    runCatching { backupDir.deleteRecursively() }
                    runCatching { journalFile.delete() }
                    return@withContext finishResult
                }
            }

            val allHexFiles = tempUnpack.walkTopDown().maxDepth(6).filter { f ->
                f.isFile && (f.name.matches(
                    AcapelaVoiceManifest.HEX_REGISTRATION_REGEX
                ) || f.name.equals("registration.txt", ignoreCase = true)) &&
                    f.length() > 0L
            }.toList()
            val singleHexFile = if (allHexFiles.size == 1) {
                allHexFiles.first()
            } else {
                null
            }

            val allConfigYamls = tempUnpack.walkTopDown()
                .filter { f ->
                    f.isFile && f.name.equals(
                        "config.yaml",
                        ignoreCase = true
                    )
                }.toList()

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

            if (backupDir.exists()) backupDir.deleteRecursively()
            backupDir.mkdirs()

            val sharedUserdicos = tempUnpack.walkTopDown().maxDepth(6)
                .firstOrNull {
                    it.isDirectory && it.name.equals(
                        "userdicos",
                        ignoreCase = true
                    )
                }

            val dedupedVoiceDirs = voiceDirs.distinctBy { vd ->
                val info = runCatching {
                    AcapelaVoiceManifest.parseVoice(vd)
                }.getOrNull()
                val rawVoiceName = info?.voiceName?.ifBlank { null } ?: vd.name
                AcapelaVoiceManifest.cleanAcapelaSpeaker(rawVoiceName)
                    .lowercase(Locale.ROOT)
            }

            for (vd in dedupedVoiceDirs) {
                val info = runCatching {
                    AcapelaVoiceManifest.parseVoice(vd)
                }.getOrNull()
                val rawVoiceName = info?.voiceName?.ifBlank { null }
                    ?: vd.name
                val voiceName = AcapelaVoiceManifest
                    .cleanAcapelaSpeaker(rawVoiceName)
                    .ifBlank { "voice" }

                val existingDir = baseDir.listFiles { f ->
                    f.isDirectory && AcapelaVoiceManifest.cleanAcapelaSpeaker(
                        f.name
                    ).equals(voiceName, ignoreCase = true)
                }?.firstOrNull()

                val targetDir = existingDir ?: File(baseDir, voiceName)
                if (targetDir.exists()) {
                    val backupVoiceDir = File(backupDir, targetDir.name)
                    if (!targetDir.renameTo(backupVoiceDir)) {
                        targetDir.copyRecursively(
                            backupVoiceDir,
                            overwrite = true
                        )
                        targetDir.deleteRecursively()
                    }
                    backups.add(targetDir to backupVoiceDir)
                } else {
                    createdTargets.add(targetDir)
                }
                writeAcapelaJournal(
                    journalFile = journalFile,
                    sessionId = sessionId,
                    backupDir = backupDir,
                    createdTargets = createdTargets,
                    backups = backups,
                    baseDir = baseDir
                )
                targetDir.mkdirs()

                vd.copyRecursively(targetDir, overwrite = true)
                AcapelaVoiceManifest.flattenNestedVoiceFiles(targetDir)
                val targetUserdicos = File(targetDir, "userdicos").apply {
                    mkdirs()
                }
                if (sharedUserdicos != null) {
                    val siblingUserdicos = File(
                        targetDir.parentFile ?: targetDir, "userdicos"
                    ).apply { mkdirs() }
                    if (sharedUserdicos != targetUserdicos) {
                        runCatching {
                            sharedUserdicos.copyRecursively(
                                targetUserdicos,
                                overwrite = true
                            )
                        }
                    }
                    if (sharedUserdicos != siblingUserdicos) {
                        runCatching {
                            sharedUserdicos.copyRecursively(
                                siblingUserdicos,
                                overwrite = true
                            )
                        }
                    }
                }
                val voiceSpeaker = voiceName.ifBlank {
                    AcapelaVoiceManifest.cleanAcapelaSpeaker(targetDir.name)
                }
                val voiceHex = singleHexFile ?: AcapelaPackageResolver
                    .selectHexBySpeaker(allHexFiles, voiceSpeaker)
                    ?: AcapelaVoiceManifest.findHexRegistrationFile(
                        vd,
                        voiceSpeaker
                    )
                if (voiceHex != null) {
                    runCatching {
                        voiceHex.copyTo(
                            File(targetDir, voiceHex.name),
                            overwrite = true
                        )
                    }
                }

                // ربط config.yaml المطابق حصراً دون مس متحدث آخر
                val yamlToCopy = findMatchingConfigYamlForVoice(
                    voiceDir = vd,
                    candidateYamls = allConfigYamls
                )
                if (yamlToCopy != null && yamlToCopy.isFile) {
                    val destYaml = File(targetDir, "config.yaml")
                    if (!destYaml.exists() ||
                        !isValidConfigYaml(destYaml, targetDir.name)
                    ) {
                        val copied = copyConfigFileSafely(yamlToCopy, destYaml)
                        if (copied) {
                            Log.i(
                                TAG,
                                "نسخ config.yaml المطابق بأمان إلى " +
                                    targetDir.name
                            )
                        } else {
                            val msg =
                                "فشل نسخ config.yaml إلى ${targetDir.name}"
                            Log.e(TAG, msg)
                            return@withContext ImportResult(
                                success = false,
                                errorMessage = msg
                            )
                        }
                    }
                }

                AcapelaVoiceManifest.normalizeExtensionsCase(targetDir)
            }

            for (hf in allHexFiles) {
                val targetHex = File(baseDir, hf.name)
                if (targetHex.exists()) {
                    val backupHex = File(backupDir, hf.name)
                    if (!targetHex.renameTo(backupHex)) {
                        targetHex.copyTo(backupHex, overwrite = true)
                        targetHex.delete()
                    }
                    backups.add(targetHex to backupHex)
                } else {
                    createdTargets.add(targetHex)
                }
                runCatching {
                    hf.copyTo(
                        targetHex,
                        overwrite = true
                    )
                }
            }

            copyMatchingConfigsToTree(tempUnpack, baseDir)

            writeAcapelaJournal(
                journalFile = journalFile,
                sessionId = sessionId,
                backupDir = backupDir,
                createdTargets = createdTargets,
                backups = backups,
                baseDir = baseDir
            )

            // إنهاء وفحص النطق: النسخ الاحتياطية باقية حتى نجاح التحقق
            val finishResult = finishImport(
                context = context,
                baseDir = baseDir,
                emptyMessage =
                    "لم يتم العثور على أصوات Acapela صالحة بعد فك الضغط",
                stagingDir = tempUnpack
            )

            if (!finishResult.success) {
                rollbackAcapela(
                    baseDir = baseDir,
                    createdTargets = createdTargets,
                    backups = backups,
                    backupDir = backupDir,
                    journalFile = journalFile,
                    context = context
                )
                return@withContext finishResult
            }

            notifyStorageChanged(context)

            // نجاح التثبيت والتحقق: حذف النسخ الاحتياطية وسجل المعاملة
            runCatching { backupDir.deleteRecursively() }
            runCatching { journalFile.delete() }

            finishResult
        } catch (t: Throwable) {
            // استعادة النسخ الاحتياطية عند أي فشل أو إلغاء
            rollbackAcapela(
                baseDir = baseDir,
                createdTargets = createdTargets,
                backups = backups,
                backupDir = backupDir,
                journalFile = journalFile,
                context = context
            )
            val isCancelled = t is kotlinx.coroutines.CancellationException ||
                cancellationSignal?.get() == true
            ImportResult(
                success = false,
                errorMessage = if (isCancelled) {
                    "تم إلغاء الاستيراد من قبل المستخدم"
                } else {
                    t.message ?: "حدث خطأ غير متوقع أثناء استيراد Acapela"
                }
            )
        } finally {
            tempUnpack.deleteRecursively()
        }
    }

    /**
     * استيراد صوت Acapela من ملف ZIP عبر [Uri].
     */
    suspend fun importFromZip(
        context: Context,
        zipUri: Uri,
        suggestedName: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        val tempZip = File(
            context.cacheDir,
            "temp_acapela_import_$sessionId.zip"
        )
        try {
            VoicePackageImporter.copyUriWithBounds(
                context = context,
                zipUri = zipUri,
                destFile = tempZip
            )
            importFromArchiveFile(
                context = context,
                archiveFile = tempZip,
                sessionId = sessionId
            )
        } catch (t: Throwable) {
            ImportResult(
                success = false,
                errorMessage = t.message ?: "فشل فتح واستيراد ملف ZIP"
            )
        } finally {
            if (tempZip.exists()) {
                runCatching { tempZip.delete() }
            }
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
        emptyMessage: String,
        stagingDir: File? = null
    ): ImportResult {
        // تصفير سجل التعثر لإلغاء أي حظر طوارئ قائم فوراً
        CategoryFailureTracker.reset()
        ensureValidConfigYamlForVoices(context, baseDir, stagingDir)
        // الكتابة والقراءة معاً على IO: الاستيراد ينتهي بنسخ ملفات،
        // وقارئ المانيفست لا يُشغَّل على الخيط الرئيسي هنا إطلاقاً.
        val voices = withContext(Dispatchers.IO) {
            // اكتشاف/تحقق موحّد دون توليد أو حشو تهيئة.
            // الملفات تغيّرت للتوّ: لا يجوز أن تُقرأ لقطة سابقة.
            AcapelaVoiceManifest.invalidateVoicesCache()
            AcapelaVoiceManifest.listVoices(baseDir)
        }
        if (voices.isEmpty()) {
            val candidateDirs = baseDir.walkTopDown().maxDepth(4).filter { d ->
                d.isDirectory &&
                    !AcapelaVoiceManifest.isIgnoredOrNonVoiceDirectory(d) &&
                    d.listFiles { f ->
                        f.isFile && (
                            f.extension.equals("inf", ignoreCase = true) ||
                            f.extension.equals("ini", ignoreCase = true) ||
                            f.extension.lowercase(Locale.ROOT) in
                                AcapelaVoiceManifest.DATA_EXTENSIONS
                        )
                    }?.isNotEmpty() == true
            }.toList()

            val formattedMsg = if (candidateDirs.isNotEmpty()) {
                val reasons = candidateDirs.map { dir ->
                    val inf = dir.walkTopDown().maxDepth(2).firstOrNull { f ->
                        f.isFile && f.extension.equals("inf", true)
                    }
                    val data = dir.walkTopDown().maxDepth(2).firstOrNull { f ->
                        f.isFile && f.extension.lowercase(Locale.ROOT) in
                            AcapelaVoiceManifest.DATA_EXTENSIONS
                    }
                    val speaker = if (inf != null) {
                        AcapelaVoiceManifest.cleanAcapelaSpeaker(
                            inf.nameWithoutExtension
                        )
                    } else if (data != null) {
                        AcapelaVoiceManifest.cleanAcapelaSpeaker(
                            data.nameWithoutExtension
                        )
                    } else {
                        AcapelaVoiceManifest.cleanAcapelaSpeaker(dir.name)
                            .ifBlank { dir.name }
                    }
                    val hasConfigOrParam = AcapelaVoiceManifest
                        .findConfigYamlWithParam(dir) != null
                    val hasInf = inf != null
                    val hasData = AcapelaVoiceManifest.hasVoiceData(dir)
                    when {
                        !hasInf ->
                            "ملف التعريف (.inf) للصوت $speaker مفقود"
                        !hasData ->
                            "ملف البيانات (.dat/.bvcu) للصوت $speaker مفقود"
                        !hasConfigOrParam ->
                            "ملف config.yaml أو التسجيل (param) للصوت " +
                                "$speaker مفقود في الحزمة، أضفه بجانب " +
                                "مجلد الصوت وأعد الاستيراد"
                        else -> "حزمة الصوت $speaker غير مكتملة"
                    }
                }.distinct()
                reasons.joinToString("، ")
            } else {
                emptyMessage
            }
            return ImportResult(success = false, errorMessage = formattedMsg)
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
                success = false,
                errorMessage =
                    "فشل التحقق من نطق أصوات Acapela: ${check.message}"
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

            // إن كان المجلد المختار هو voice/ أو حاوياً له فالنسخ حرفي.
            val verbatimRoot = findVerbatimVoiceRoot(sourceDir)
            if (verbatimRoot != null) {
                val copied = copyVoiceTreeVerbatim(
                    verbatimRoot,
                    File(baseDir, "voice")
                )
                if (copied > 0) {
                    copyMatchingConfigsToTree(
                        sourceDir,
                        File(baseDir, "voice")
                    )
                    copyMatchingConfigsToTree(sourceDir, baseDir)
                    notifyStorageChanged(context)
                    return@withContext finishImport(
                        context = context,
                        baseDir = baseDir,
                        emptyMessage =
                            "فشل التعرف على الأصوات بعد النسخ الحرفي",
                        stagingDir = sourceDir
                    )
                }
            } else if (sourceDir.name.equals("acapela", ignoreCase = true)) {
                val copied = copyVoiceTreeVerbatim(
                    sourceDir,
                    File(baseDir, "voice/acapela")
                )
                if (copied > 0) {
                    copyMatchingConfigsToTree(
                        sourceDir,
                        File(baseDir, "voice/acapela")
                    )
                    copyMatchingConfigsToTree(sourceDir, baseDir)
                    notifyStorageChanged(context)
                    return@withContext finishImport(
                        context = context,
                        baseDir = baseDir,
                        emptyMessage =
                            "فشل التعرف على الأصوات بعد النسخ الحرفي",
                        stagingDir = sourceDir
                    )
                }
            }

            val voiceDirs = AcapelaVoiceManifest.completeVoiceDirs(sourceDir)
            if (voiceDirs.isEmpty()) {
                return@withContext ImportResult(
                    success = false,
                    errorMessage =
                        "لا توجد أصوات Acapela صالحة داخل المجلد المحدد"
                )
            }

            val allDirHexFiles = sourceDir.walkTopDown().maxDepth(6).filter {
                f ->
                f.isFile && (f.name.matches(
                    AcapelaVoiceManifest.HEX_REGISTRATION_REGEX
                ) || f.name.equals("registration.txt", ignoreCase = true)) &&
                    f.length() > 0L
            }.toList()
            val singleDirHexFile = if (allDirHexFiles.size == 1) {
                allDirHexFiles.first()
            } else {
                null
            }

            val allDirYamls = sourceDir.walkTopDown()
                .filter { f ->
                    f.isFile && f.name.equals(
                        "config.yaml",
                        ignoreCase = true
                    )
                }.toList()
            val dirYamlSource = allDirYamls.firstOrNull()

            for (vd in voiceDirs) {
                val target = File(baseDir, vd.name).apply { mkdirs() }
                vd.copyRecursively(target, overwrite = true)
                File(target, "userdicos").mkdirs()
                val info = runCatching {
                    AcapelaVoiceManifest.parseVoice(vd)
                }.getOrNull()
                val rawSpeaker = info?.rawSpeaker?.ifBlank { null }
                    ?: info?.voiceName?.ifBlank { null }
                    ?: vd.name
                val dirVoiceSpeaker = AcapelaVoiceManifest
                    .cleanAcapelaSpeaker(rawSpeaker)
                val voiceHex = singleDirHexFile ?: AcapelaPackageResolver
                    .selectHexBySpeaker(allDirHexFiles, dirVoiceSpeaker)
                    ?: AcapelaVoiceManifest.findHexRegistrationFile(
                        vd,
                        dirVoiceSpeaker
                    )
                if (voiceHex != null) {
                    runCatching {
                        voiceHex.copyTo(
                            File(target, voiceHex.name),
                            overwrite = true
                        )
                    }
                }
                val candidateYaml = File(vd, "config.yaml")
                    .takeIf { isValidConfigYaml(it, vd.name) }
                    ?: allDirYamls.firstOrNull {
                        it.parentFile == vd && isValidConfigYaml(it, vd.name)
                    }
                    ?: allDirYamls.firstOrNull {
                        it.parentFile == vd.parentFile &&
                            isValidConfigYaml(it, vd.name)
                    }
                    ?: allDirYamls.firstOrNull {
                        isValidConfigYaml(it, vd.name)
                    }
                if (candidateYaml != null && candidateYaml.isFile) {
                    val targetYaml = File(target, "config.yaml")
                    if (!targetYaml.exists() ||
                        !isValidConfigYaml(targetYaml, vd.name)
                    ) {
                        val copied = copyConfigFileSafely(
                            candidateYaml,
                            targetYaml
                        )
                        if (!copied) {
                            val msg =
                                "فشل نسخ config.yaml المطابق إلى ${target.name}"
                            Log.e(TAG, msg)
                            return@withContext ImportResult(
                                success = false,
                                errorMessage = msg
                            )
                        }
                    }
                }
            }

            for (hf in allDirHexFiles) {
                runCatching {
                    hf.copyTo(
                        File(baseDir, hf.name),
                        overwrite = true
                    )
                }
            }

            // سطر 1088: لا تنسخ أول config.yaml عشوائياً إلى الجذر؛
            // انسخ فقط إن وُجد ملف مطابق للمتحدث أو صوت معتمد.
            val rootCandidate = allDirYamls.firstOrNull { y ->
                voiceDirs.any { vd -> isValidConfigYaml(y, vd.name) }
            }
            if (rootCandidate != null) {
                val baseRootYaml = File(baseDir, "config.yaml")
                if (!baseRootYaml.exists() ||
                    !isValidConfigYaml(baseRootYaml)
                ) {
                    copyConfigFileSafely(rootCandidate, baseRootYaml)
                }
            }

            copyMatchingConfigsToTree(sourceDir, baseDir)
            notifyStorageChanged(context)

            finishImport(
                context = context,
                baseDir = baseDir,
                emptyMessage = "فشل التعرف على الأصوات بعد نسخها",
                stagingDir = sourceDir
            )
        } catch (t: Throwable) {
            Log.e(TAG, "فشل نسخ مجلد أصوات Acapela", t)
            ImportResult(
                success = false,
                errorMessage = t.message ?: "حدث خطأ غير متوقع"
            )
        }
    }

    /**
     * تدوين سجل المعاملة للاسترجاع بعد توقف العملية المفاجئ.
     */
    private fun writeAcapelaJournal(
        journalFile: File,
        sessionId: String,
        backupDir: File,
        createdTargets: List<File>,
        backups: List<Pair<File, File>>,
        baseDir: File
    ) {
        runCatching {
            journalFile.parentFile?.mkdirs()
            val json = org.json.JSONObject()
            json.put("sessionId", sessionId)
            json.put("backupDir", backupDir.name)
            json.put("backupDirPath", backupDir.absolutePath)
            json.put("baseDirPath", baseDir.absolutePath)
            val crArr = org.json.JSONArray()
            for (ct in createdTargets) {
                crArr.put(ct.absolutePath)
            }
            json.put("created", crArr)
            val buArr = org.json.JSONArray()
            for ((tgt, bak) in backups) {
                val item = org.json.JSONObject()
                item.put("target", tgt.absolutePath)
                item.put("backup", bak.absolutePath)
                buArr.put(item)
            }
            json.put("backedUp", buArr)
            journalFile.writeText(json.toString(2), Charsets.UTF_8)
        }
    }

    /**
     * التراجع الكامل عند فشل التحقق أو حدوث استثناء في Acapela:
     * حذف الملفات التي أُنشئت حديثاً، واستعادة النسخ السابقة بالكامل،
     * وإبطال الكاش وتحديث رقم التخزين ومطابقة حالة المحرك للملفات الفعلية.
     */
    internal fun rollbackAcapela(
        baseDir: File,
        createdTargets: List<File>,
        backups: List<Pair<File, File>>,
        backupDir: File,
        journalFile: File,
        context: Context? = null
    ) {
        // 1. حذف كل مجلد أو ملف تم إنشاؤه حديثاً
        for (target in createdTargets) {
            if (target.exists()) {
                runCatching { target.deleteRecursively() }
            }
        }

        // 2. استرجاع النسخ الاحتياطية السابقة (يدعم النقل بين أقسام التخزين)
        for ((target, backup) in backups) {
            if (target.exists()) {
                runCatching { target.deleteRecursively() }
            }
            if (backup.exists()) {
                target.parentFile?.mkdirs()
                if (!backup.renameTo(target)) {
                    runCatching {
                        backup.copyRecursively(target, overwrite = true)
                        backup.deleteRecursively()
                    }
                }
            }
        }

        // 3. تنظيف أي مجلدات فرعية فارغة
        VoicePackageImporter.cleanEmptyDirs(baseDir)

        // 4. حذف مجلد النسخ الاحتياطي وسجل المعاملة
        runCatching { backupDir.deleteRecursively() }
        runCatching { journalFile.delete() }

        // 5. إبطال الكاش وتحديث رقم التخزين والمنتقي وتعديل حالة المحرك
        AcapelaVoiceManifest.invalidateVoicesCache()
        EmbeddedStorage.bumpVersion(context)
        EnginePicker.invalidateAfterStorageChange(context)
        val completeDirs = AcapelaVoiceManifest.completeVoiceDirs(baseDir)
        if (completeDirs.isNotEmpty()) {
            EngineStatusRegistry.noteFilesPresent(
                AcapelaEmbeddedVoiceProvider.PROVIDER_ID
            )
        } else {
            EngineStatusRegistry.noteFilesAbsent(
                AcapelaEmbeddedVoiceProvider.PROVIDER_ID
            )
        }
    }

    /**
     * التعافي من المعاملات غير المكتملة في Acapela بعد توقف العملية.
     */
    internal fun recoverInterruptedTransactions(
        baseDir: File,
        context: Context? = null
    ) {
        val backupParent = if (context != null) {
            File(context.filesDir, "acapela_backups")
        } else {
            File(baseDir.parentFile ?: baseDir, "acapela_backups")
        }

        val journalDirs = listOfNotNull(
            backupParent.takeIf { it.exists() && it.isDirectory },
            baseDir.takeIf { it.exists() && it.isDirectory }
        ).distinctBy { it.absolutePath }

        val journals = journalDirs.flatMap { d ->
            d.listFiles { f ->
                f.isFile && f.name.startsWith(".journal_acapela_") &&
                    f.name.endsWith(".json")
            }?.toList().orEmpty()
        }

        for (jf in journals) {
            runCatching {
                val text = jf.readText(Charsets.UTF_8)
                val json = org.json.JSONObject(text)
                val backupDirPath = json.optString("backupDirPath")
                val backupDir = if (backupDirPath.isNotBlank()) {
                    File(backupDirPath)
                } else {
                    val backupDirName = json.optString("backupDir")
                    File(jf.parentFile ?: baseDir, backupDirName)
                }
                val baseDirPath = json.optString("baseDirPath")
                val targetBaseDir = if (baseDirPath.isNotBlank()) {
                    File(baseDirPath)
                } else {
                    baseDir
                }
                val createdArr = json.optJSONArray("created")
                val backedUpArr = json.optJSONArray("backedUp")
                val createdList = mutableListOf<File>()
                if (createdArr != null) {
                    for (i in 0 until createdArr.length()) {
                        val pathStr = createdArr.getString(i)
                        val file = if (pathStr.startsWith("/") ||
                            pathStr.contains(":\\")
                        ) {
                            File(pathStr)
                        } else {
                            File(targetBaseDir, pathStr)
                        }
                        createdList.add(file)
                    }
                }
                val backedUpList = mutableListOf<Pair<File, File>>()
                if (backedUpArr != null) {
                    for (i in 0 until backedUpArr.length()) {
                        val item = backedUpArr.getJSONObject(i)
                        val tStr = item.getString("target")
                        val bStr = item.getString("backup")
                        val target = if (tStr.startsWith("/") ||
                            tStr.contains(":\\")
                        ) {
                            File(tStr)
                        } else {
                            File(targetBaseDir, tStr)
                        }
                        val backup = if (bStr.startsWith("/") ||
                            bStr.contains(":\\")
                        ) {
                            File(bStr)
                        } else {
                            File(backupDir, bStr)
                        }
                        backedUpList.add(target to backup)
                    }
                }
                rollbackAcapela(
                    baseDir = targetBaseDir,
                    createdTargets = createdList,
                    backups = backedUpList,
                    backupDir = backupDir,
                    journalFile = jf,
                    context = context
                )
            }
            runCatching { jf.delete() }
        }

        // حذف مجلدات .backup_acapela_ القديمة المتبقية من baseDir
        if (baseDir.exists() && baseDir.isDirectory) {
            baseDir.listFiles { f ->
                f.isDirectory && f.name.startsWith(".backup_acapela_")
            }?.forEach { runCatching { it.deleteRecursively() } }
        }
    }
}
