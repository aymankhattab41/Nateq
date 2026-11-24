package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * مستورد حزم الأصوات المضغوطة (ZIP) لجميع المحركات المدمجة
 * (Acapela و Vocalizer) مع فصل مرحلة الفحص المسبق عن التنفيذ،
 * وحدود أمان صارمة للمساحة والحجم، وتبديل ذري مع قابلية الرجوع.
 */
object VoicePackageImporter {

    private const val TAG = "VoicePackageImporter"
    private const val BUFFER_SIZE = 8192

    /** سقف حجم ملف ZIP المنسوخ من URI: 2 جيجابايت. */
    const val MAX_URI_COPY_BYTES = 2L * 1024 * 1024 * 1024

    /** سقف عدد ملفات الأرشيف قبل فك الضغط. */
    const val MAX_ZIP_ENTRIES = 20_000

    /** جلسة الاستيراد النشطة حالياً لمنع التصادم عبر onNewIntent. */
    private val activeSessionId = AtomicReference<String?>(null)
    private val activeCancellation = AtomicReference<AtomicBoolean?>(null)

    data class InspectionResult(
        val sessionId: String,
        val archiveFile: File,
        val engine: EmbeddedStorage.Engine,
        val engineDisplayName: String,
        val voicesFound: List<String>,
        val voicesToReplace: List<String>,
        val requiredSpaceBytes: Long,
        val availableSpaceBytes: Long,
        val isSupported: Boolean,
        val errorMessage: String? = null
    )

    data class Result(
        val success: Boolean,
        val engineName: String = "",
        val importedVoices: List<String> = emptyList(),
        val rejectedVoices: List<Pair<String, String>> = emptyList(),
        val errorMessage: String? = null,
        val cancelled: Boolean = false
    )

    /** هل توجد جلسة استيراد قيد التنفيذ حالياً. */
    fun isImportInProgress(): Boolean = activeSessionId.get() != null

    /** إلغاء الجلسة النشطة الحالية إن وجدت. */
    fun cancelActiveSession() {
        activeCancellation.get()?.set(true)
    }

    /** إنهاء معرف الجلسة. */
    fun endSession(sessionId: String) {
        activeSessionId.compareAndSet(sessionId, null)
        activeCancellation.set(null)
    }

    /**
     * نسخ تدفقي مقيد بالسقف وإمكانية الإلغاء دون الاعتماد على حجم URI المعلن.
     */
    internal fun copyUriWithBounds(
        context: Context,
        zipUri: Uri,
        destFile: File,
        maxBytes: Long = MAX_URI_COPY_BYTES,
        cancellationSignal: AtomicBoolean? = null
    ): Long {
        if (destFile.exists()) destFile.delete()
        var total = 0L
        val stream = context.contentResolver.openInputStream(zipUri)
            ?: throw IOException("تعذر فتح مصدر ملف الحزمة")
        stream.use { input ->
            FileOutputStream(destFile).use { output ->
                val buf = ByteArray(BUFFER_SIZE)
                var r: Int
                while (input.read(buf).also { r = it } != -1) {
                    if (cancellationSignal?.get() == true) {
                        throw CancellationException("تم إلغاء نسخ الحزمة")
                    }
                    total += r.toLong()
                    if (total > maxBytes) {
                        throw IllegalStateException(
                            "تجاوز حجم ملف الحزمة الحد الأقصى " +
                                "(${maxBytes / (1024 * 1024)} ميجابايت)"
                        )
                    }
                    output.write(buf, 0, r)
                }
                output.flush()
            }
        }
        return total
    }

    /**
     * فحص حزمة ZIP مسبقاً وتحديد المحرك والأصوات والمساحة دون التثبيت
     * فوق القديم مباشرة.
     */
    suspend fun inspectZipPackage(
        context: Context,
        zipUri: Uri,
        suggestedName: String? = null,
        cancellationSignal: AtomicBoolean? = null
    ): InspectionResult = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        if (!activeSessionId.compareAndSet(null, sessionId)) {
            return@withContext InspectionResult(
                sessionId = sessionId,
                archiveFile = File(context.cacheDir, "invalid_$sessionId.tmp"),
                engine = EmbeddedStorage.Engine.ACAPELA,
                engineDisplayName = "Acapela",
                voicesFound = emptyList(),
                voicesToReplace = emptyList(),
                requiredSpaceBytes = 0L,
                availableSpaceBytes = 0L,
                isSupported = false,
                errorMessage = "هناك عملية استيراد جارية بالفعل. " +
                    "يرجى الانتظار حتى تكتمل."
            )
        }
        activeCancellation.set(cancellationSignal)

        val tempZip = File(context.cacheDir, "voice_import_$sessionId.zip")
        try {
            copyUriWithBounds(
                context = context,
                zipUri = zipUri,
                destFile = tempZip,
                maxBytes = MAX_URI_COPY_BYTES,
                cancellationSignal = cancellationSignal
            )

            var entryCount = 0
            var uncompressedBytes = 0L
            val entryNames = mutableListOf<String>()
            var isAcapela = false
            var isVocalizer = false

            ZipFile(tempZip).use { zf ->
                val it = zf.entries()
                while (it.hasMoreElements()) {
                    if (cancellationSignal?.get() == true) {
                        throw CancellationException("تم إلغاء فحص الحزمة")
                    }
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        throw IllegalStateException(
                            "تجاوز الأرشيف الحد الأقصى المسموح " +
                                "لعدد الملفات ($MAX_ZIP_ENTRIES)"
                        )
                    }
                    val entry = it.nextElement()
                    val size = entry.size
                    if (size > 0L) {
                        uncompressedBytes += size
                    }
                    val name = entry.name.lowercase(Locale.ROOT)
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
                    val candidate = suggestedName?.lowercase(Locale.ROOT)
                        .orEmpty()
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

            if (!isAcapela && !isVocalizer) {
                tempZip.delete()
                endSession(sessionId)
                return@withContext InspectionResult(
                    sessionId = sessionId,
                    archiveFile = tempZip,
                    engine = EmbeddedStorage.Engine.ACAPELA,
                    engineDisplayName = "غير مدعوم",
                    voicesFound = emptyList(),
                    voicesToReplace = emptyList(),
                    requiredSpaceBytes = 0L,
                    availableSpaceBytes = 0L,
                    isSupported = false,
                    errorMessage = "الملف لا يحتوي على حزمة أصوات مدعومة " +
                        "لـ Acapela أو Vocalizer"
                )
            }

            val engine = if (isAcapela) {
                EmbeddedStorage.Engine.ACAPELA
            } else {
                EmbeddedStorage.Engine.VOCALIZER
            }
            val engineDisplayName = if (isAcapela) "Acapela" else "Vocalizer"

            // جمع أسماء الأصوات المتضمنة
            val voicesFound = if (isAcapela) {
                detectAcapelaVoicesInEntries(entryNames, suggestedName)
            } else {
                detectVocalizerVoicesInEntries(entryNames, suggestedName)
            }

            // فحص الأصوات المثبتة مسبقاً لرصد الاستبدال
            val baseDir = EmbeddedStorage.baseDir(context, engine)
            val installedVoiceNames = if (baseDir.exists()) {
                baseDir.listFiles { f -> f.isDirectory }?.map {
                    AcapelaVoiceManifest.cleanAcapelaSpeaker(it.name)
                        .lowercase(Locale.ROOT)
                }.orEmpty()
            } else {
                emptyList()
            }

            val voicesToReplace = voicesFound.filter { v ->
                val cleanV = AcapelaVoiceManifest.cleanAcapelaSpeaker(v)
                    .lowercase(Locale.ROOT)
                installedVoiceNames.contains(cleanV)
            }

            val uncompressedEst = if (uncompressedBytes > 0L) {
                uncompressedBytes
            } else {
                (tempZip.length() * 2.5).toLong()
            }
            // سقف مساحة يتطلب حجم الفك + هامش أمان 50 ميجابايت
            val requiredSpace = uncompressedEst + (50L * 1024 * 1024)
            val availableSpace = context.filesDir.usableSpace

            if (availableSpace < requiredSpace) {
                val reqMb = requiredSpace / (1024 * 1024)
                val availMb = availableSpace / (1024 * 1024)
                tempZip.delete()
                endSession(sessionId)
                return@withContext InspectionResult(
                    sessionId = sessionId,
                    archiveFile = tempZip,
                    engine = engine,
                    engineDisplayName = engineDisplayName,
                    voicesFound = voicesFound,
                    voicesToReplace = voicesToReplace,
                    requiredSpaceBytes = requiredSpace,
                    availableSpaceBytes = availableSpace,
                    isSupported = false,
                    errorMessage =
                        "مساحة التخزين المتاحة ($availMb ميجابايت) " +
                            "غير كافية للاستيراد " +
                            "(مطلوب $reqMb ميجابايت على الأقل)"
                )
            }

            InspectionResult(
                sessionId = sessionId,
                archiveFile = tempZip,
                engine = engine,
                engineDisplayName = engineDisplayName,
                voicesFound = voicesFound,
                voicesToReplace = voicesToReplace,
                requiredSpaceBytes = requiredSpace,
                availableSpaceBytes = availableSpace,
                isSupported = true
            )
        } catch (t: Throwable) {
            tempZip.delete()
            endSession(sessionId)
            val isCancelled = t is CancellationException ||
                cancellationSignal?.get() == true
            InspectionResult(
                sessionId = sessionId,
                archiveFile = tempZip,
                engine = EmbeddedStorage.Engine.ACAPELA,
                engineDisplayName = "خطأ",
                voicesFound = emptyList(),
                voicesToReplace = emptyList(),
                requiredSpaceBytes = 0L,
                availableSpaceBytes = 0L,
                isSupported = false,
                errorMessage = if (isCancelled) {
                    "تم إلغاء فحص الحزمة"
                } else {
                    t.message ?: "خطأ أثناء فحص حزمة الأصوات"
                }
            )
        }
    }

    private fun detectAcapelaVoicesInEntries(
        entryNames: List<String>,
        suggestedName: String?
    ): List<String> {
        val found = mutableSetOf<String>()
        for (name in entryNames) {
            val filePart = name.substringAfterLast('/')
            if (filePart.endsWith(".ini") || filePart.endsWith(".inf")) {
                val stem = filePart.substringBeforeLast('.')
                val clean = AcapelaVoiceManifest.cleanAcapelaSpeaker(stem)
                if (clean.isNotBlank() && clean.length > 2) {
                    found.add(clean)
                }
            }
        }
        if (found.isEmpty()) {
            val fallback = suggestedName?.removeSuffix(".zip")
                ?.removeSuffix(".ZIP")?.trim().orEmpty()
            if (fallback.isNotBlank()) {
                found.add(AcapelaVoiceManifest.cleanAcapelaSpeaker(fallback))
            } else {
                found.add("AcapelaVoice")
            }
        }
        return found.toList()
    }

    private fun detectVocalizerVoicesInEntries(
        entryNames: List<String>,
        suggestedName: String?
    ): List<String> {
        val hdrs = entryNames.filter { it.endsWith(".hdr") }
        if (hdrs.isNotEmpty()) {
            return hdrs.map {
                it.substringAfterLast('/').substringBeforeLast('.')
            }.distinct()
        }
        val fallback = suggestedName?.removeSuffix(".zip")
            ?.removeSuffix(".ZIP")?.trim().orEmpty()
        return if (fallback.isNotBlank()) {
            listOf(fallback)
        } else {
            listOf("Vocalizer")
        }
    }

    /**
     * تنفيذ الاستيراد الفعلي بعد موافقة المستخدم مع استخراج لـ staging
     * وتبديل ذري وحذف ملفات الجلسة حصراً دون مس الأصوات القديمة عند الفشل.
     */
    suspend fun executeImport(
        context: Context,
        inspection: InspectionResult,
        cancellationSignal: AtomicBoolean? = null
    ): Result = withContext(Dispatchers.IO) {
        if (cancellationSignal?.get() == true) {
            cleanupInspection(inspection)
            return@withContext Result(
                success = false,
                cancelled = true,
                errorMessage = "تم إلغاء الاستيراد من قبل المستخدم"
            )
        }
        try {
            if (inspection.engine == EmbeddedStorage.Engine.ACAPELA) {
                val acRes = AcapelaVoiceImporter.importFromArchiveFile(
                    context = context,
                    archiveFile = inspection.archiveFile,
                    sessionId = inspection.sessionId,
                    cancellationSignal = cancellationSignal
                )
                Result(
                    success = acRes.success,
                    engineName = "Acapela",
                    importedVoices = acRes.importedVoices,
                    errorMessage = acRes.errorMessage
                )
            } else {
                importVocalizerStaged(
                    context = context,
                    archiveFile = inspection.archiveFile,
                    sessionId = inspection.sessionId,
                    suggestedName = inspection.voicesFound.firstOrNull(),
                    cancellationSignal = cancellationSignal
                )
            }
        } finally {
            cleanupInspection(inspection)
        }
    }

    /**
     * تنظيف ملف الأرشيف الخاص بالجلسة وحدها وإنهاء معرف الجلسة.
     */
    fun cleanupInspection(inspection: InspectionResult) {
        if (inspection.archiveFile.exists()) {
            runCatching { inspection.archiveFile.delete() }
        }
        endSession(inspection.sessionId)
    }

    /**
     * استخراج وتثبيت حزمة Vocalizer عبر staging مع حماية التبديل الذري.
     */
    private fun importVocalizerStaged(
        context: Context,
        archiveFile: File,
        sessionId: String,
        suggestedName: String?,
        cancellationSignal: AtomicBoolean?
    ): Result {
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        recoverInterruptedTransactions(baseDir)

        val stagingDir = File(context.cacheDir, "vocalizer_staging_$sessionId")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val backupDir = File(baseDir, ".backup_vocalizer_$sessionId")
        val journalFile = File(baseDir, ".journal_vocalizer_$sessionId.json")
        val backedUpFiles = mutableListOf<Pair<File, File>>()
        val createdFiles = mutableListOf<File>()

        return try {
            val guard = ZipExtractionGuard(stagingDir)
            val extractedFiles = mutableListOf<String>()

            ZipInputStream(archiveFile.inputStream()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (cancellationSignal?.get() == true) {
                        throw CancellationException(
                            "تم إلغاء استخراج Vocalizer"
                        )
                    }
                    val entryName = entry.name.replace('\\', '/')
                    guard.onEntry(entry)
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
                        extractedFiles.add(outFile.name)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (cancellationSignal?.get() == true) {
                throw CancellationException("تم إلغاء الاستيراد")
            }

            // التحقق الصارم من اكتمال حزمة الصوت في staging قبل المساس بالحي
            val validHdrs = stagingDir.walkTopDown().filter {
                it.isFile && it.extension.equals("hdr", ignoreCase = true) &&
                    it.length() > 0L
            }.toList()
            if (validHdrs.isEmpty()) {
                return Result(
                    success = false,
                    errorMessage =
                        "لم يتم العثور على ملفات ترويسة صالحة " +
                            "(.hdr) لـ Vocalizer"
                )
            }

            // معاملة واضحة ومسار نسخ احتياطي لا يتصادم مع حفظ المسار النسبي
            if (backupDir.exists()) backupDir.deleteRecursively()
            if (!backupDir.mkdirs()) {
                throw IOException("تعذر إنشاء مجلد النسخ الاحتياطي للمعاملة")
            }

            // نسخ الملفات من staging إلى baseDir مع حفظ النسخ السابقة
            stagingDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val rel = file.relativeTo(stagingDir).path
                    val target = File(baseDir, rel)
                    if (target.exists()) {
                        val backup = File(backupDir, rel)
                        backup.parentFile?.mkdirs()
                        if (!target.renameTo(backup)) {
                            copyFileVerified(target, backup)
                            if (!target.delete()) {
                                throw IOException(
                                    "تعذر إخلاء الملف الأصلي: ${target.name}"
                                )
                            }
                        }
                        backedUpFiles.add(target to backup)
                    } else {
                        createdFiles.add(target)
                    }

                    target.parentFile?.mkdirs()
                    val tempTarget = File(
                        target.parentFile ?: baseDir,
                        ".tmp_${target.name}_$sessionId"
                    )
                    copyFileVerified(file, tempTarget)
                    if (!tempTarget.renameTo(target)) {
                        if (target.exists()) target.delete()
                        copyFileVerified(tempTarget, target)
                        tempTarget.delete()
                    }
                }
            }

            // تدوين سجل المعاملة لتأكيد التعافي عند التوقف المفاجئ
            writeVocalizerJournal(
                journalFile = journalFile,
                sessionId = sessionId,
                backupDir = backupDir,
                createdFiles = createdFiles,
                backedUpFiles = backedUpFiles,
                baseDir = baseDir
            )

            // التحقق من اكتمال التثبيت بعد التبديل
            val installedHdrs = baseDir.walkTopDown().filter {
                it.isFile && it.extension.equals("hdr", ignoreCase = true) &&
                    it.length() > 0L
            }.toList()
            if (installedHdrs.isEmpty()) {
                throw IllegalStateException(
                    "فشل التحقق من التثبيت: لم يتم العثور على ملفات .hdr"
                )
            }

            // نجاح المعاملة: تحديث التخزين والكاشات حصراً بعد اكتمال التثبيت
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

            val names = validHdrs.map {
                it.nameWithoutExtension
            }.distinct()

            EngineStatusRegistry.noteFilesPresent(
                VocalizerEmbeddedVoiceProvider.PROVIDER_ID
            )

            // نجاح التبديل التام: حذف النسخ الاحتياطية وسجل المعاملة
            runCatching { backupDir.deleteRecursively() }
            runCatching { journalFile.delete() }

            Result(
                success = true,
                engineName = "Vocalizer",
                importedVoices = names
            )
        } catch (t: Throwable) {
            // التراجع الصارم: حذف الملفات الجديدة واستعادة القديمة بالكامل
            rollbackVocalizer(
                baseDir = baseDir,
                createdFiles = createdFiles,
                backedUpFiles = backedUpFiles,
                backupDir = backupDir,
                journalFile = journalFile
            )
            val isCancelled = t is CancellationException ||
                cancellationSignal?.get() == true
            Result(
                success = false,
                cancelled = isCancelled,
                errorMessage = if (isCancelled) {
                    "تم إلغاء الاستيراد من قبل المستخدم"
                } else {
                    t.message ?: "فشل استخراج وتثبيت حزمة Vocalizer"
                }
            )
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /**
     * استيراد حزمة أصوات من ملف ZIP مع التوافقية السابقة.
     */
    suspend fun importZip(
        context: Context,
        zipUri: Uri,
        suggestedName: String? = null
    ): Result = withContext(Dispatchers.IO) {
        val inspection = inspectZipPackage(context, zipUri, suggestedName)
        if (!inspection.isSupported || inspection.errorMessage != null) {
            cleanupInspection(inspection)
            return@withContext Result(
                success = false,
                errorMessage = inspection.errorMessage
                    ?: "الملف لا يحتوي على حزمة أصوات مدعومة"
            )
        }
        executeImport(context, inspection)
    }

    /**
     * نسخ ملف تدفقياً مع التحقق من اكتمال الحجم ومطابقته بدقة.
     */
    internal fun copyFileVerified(src: File, dest: File) {
        dest.parentFile?.mkdirs()
        src.inputStream().use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output, BUFFER_SIZE)
            }
        }
        if (!dest.exists() || dest.length() != src.length()) {
            throw IOException(
                "فشل التحقق من نسخ الملف: ${dest.name} " +
                    "(${dest.length()} != ${src.length()})"
            )
        }
    }

    /**
     * تدوين سجل المعاملة للاسترجاع بعد التوقف المفاجئ.
     */
    private fun writeVocalizerJournal(
        journalFile: File,
        sessionId: String,
        backupDir: File,
        createdFiles: List<File>,
        backedUpFiles: List<Pair<File, File>>,
        baseDir: File
    ) {
        runCatching {
            val json = org.json.JSONObject()
            json.put("sessionId", sessionId)
            json.put("backupDir", backupDir.name)
            val crArr = org.json.JSONArray()
            for (cf in createdFiles) {
                crArr.put(cf.relativeTo(baseDir).path)
            }
            json.put("created", crArr)
            val buArr = org.json.JSONArray()
            for ((tgt, bak) in backedUpFiles) {
                val item = org.json.JSONObject()
                item.put("target", tgt.relativeTo(baseDir).path)
                item.put("backup", bak.relativeTo(backupDir).path)
                buArr.put(item)
            }
            json.put("backedUp", buArr)
            journalFile.writeText(json.toString(2), Charsets.UTF_8)
        }
    }

    /**
     * التراجع الكامل عند أي فشل أو إلغاء في Vocalizer:
     * حذف الملفات التي أُنشئت حديثاً، واستعادة الملفات السابقة كما كانت.
     */
    internal fun rollbackVocalizer(
        baseDir: File,
        createdFiles: List<File>,
        backedUpFiles: List<Pair<File, File>>,
        backupDir: File,
        journalFile: File
    ) {
        // 1. حذف كل ملف تم إنشاؤه في المعاملة
        for (file in createdFiles) {
            if (file.exists()) {
                runCatching { file.delete() }
            }
        }

        // 2. استرجاع الملفات السابقة من النسخ الاحتياطية
        for ((target, backup) in backedUpFiles) {
            if (target.exists()) {
                runCatching { target.delete() }
            }
            if (backup.exists()) {
                target.parentFile?.mkdirs()
                if (!backup.renameTo(target)) {
                    runCatching {
                        copyFileVerified(backup, target)
                        backup.delete()
                    }
                }
            }
        }

        // 3. تنظيف أي مجلدات فرعية فارغة بقيت بعد حذف المنشأ الجديد
        cleanEmptyDirs(baseDir)

        // 4. حذف النسخ الاحتياطية وسجل المعاملة
        runCatching { backupDir.deleteRecursively() }
        runCatching { journalFile.delete() }

        // 5. إزالة أي ملفات مؤقتة متبقية للجلسة
        baseDir.walkTopDown().maxDepth(5).forEach { f ->
            if (f.isFile && f.name.startsWith(".tmp_")) {
                runCatching { f.delete() }
            }
        }
    }

    /**
     * تنظيف المجلدات الفارغة داخل المجلد المعطى تصاعدياً.
     */
    internal fun cleanEmptyDirs(root: File) {
        if (!root.exists() || !root.isDirectory) return
        val subDirs = root.walkBottomUp().filter {
            it.isDirectory && it != root
        }
        for (d in subDirs) {
            val children = d.listFiles()
            if (children != null && children.isEmpty()) {
                d.delete()
            }
        }
    }

    /**
     * التعافي من المعاملات غير المكتملة في Vocalizer
     * بعد توقف التطبيق أو تعطله.
     */
    internal fun recoverInterruptedTransactions(baseDir: File) {
        if (!baseDir.exists() || !baseDir.isDirectory) return
        val journals = baseDir.listFiles { f ->
            f.isFile && f.name.startsWith(".journal_vocalizer_") &&
                f.name.endsWith(".json")
        } ?: emptyArray()

        for (jf in journals) {
            runCatching {
                val text = jf.readText(Charsets.UTF_8)
                val json = org.json.JSONObject(text)
                val backupDirName = json.optString("backupDir")
                val backupDir = File(baseDir, backupDirName)
                val createdArr = json.optJSONArray("created")
                val backedUpArr = json.optJSONArray("backedUp")
                val createdList = mutableListOf<File>()
                if (createdArr != null) {
                    for (i in 0 until createdArr.length()) {
                        val relPath = createdArr.getString(i)
                        createdList.add(File(baseDir, relPath))
                    }
                }
                val backedUpList = mutableListOf<Pair<File, File>>()
                if (backedUpArr != null) {
                    for (i in 0 until backedUpArr.length()) {
                        val item = backedUpArr.getJSONObject(i)
                        val target = File(baseDir, item.getString("target"))
                        val backup = File(backupDir, item.getString("backup"))
                        backedUpList.add(target to backup)
                    }
                }
                rollbackVocalizer(
                    baseDir = baseDir,
                    createdFiles = createdList,
                    backedUpFiles = backedUpList,
                    backupDir = backupDir,
                    journalFile = jf
                )
            }
            runCatching { jf.delete() }
        }

        // تنظيف أي ملفات مؤقتة متبقية
        baseDir.walkTopDown().maxDepth(5).forEach { f ->
            if (f.isFile && f.name.startsWith(".tmp_")) {
                runCatching { f.delete() }
            }
        }
    }
}
