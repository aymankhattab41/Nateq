package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * مدير تنزيل وتثبيت وحذف أصوات
 * ومكونات Nuance Vocalizer Expressive 2 المدمجة (ع٣٠-5 و ع٣٠-6).
 */
object VocalizerDownloader {

    private const val TAG = "VocalizerDownloader"

    /** أقل عدد ملفات في حزمة صوت صالحة: رأس + ملف بيانات واحد. */
    private const val MIN_VOICE_FILES = 2

    /**
     * أرضية حجم الأرشيف المنزّل مقارنة بالحجم المتوقع في الكتالوج.
     * 10% فقط — تسمح بفروق الضغط والتقدير، لكنها تكشف التنزيل
     * المبتسر الذي يبدو ناجحاً ثم يفشل في منتصف النطق (البند ١٤).
     */
    private const val MIN_DOWNLOAD_SIZE_RATIO = 0.10

    /** خطأ معنون بمرحلة الفشل وسببه الحقيقي (البند ٢١). */
    private fun stepError(
        step: String,
        code: String,
        reason: String
    ): DownloadError = DownloadError(code, "step=$step; $reason")

    /** نتيجة فك ضغط الأرشيف: عدد الملفات المستخرجة وقائمتها. */
    internal data class UnzipResult(
        val count: Int,
        val fileNames: List<String>
    )

    /**
     * جميع المسارات المحتملة للبحث عن أصوات VocalizerEx2 —
     * من المصدر الموحّد (ع٢٩-3): الداخلي أولاً ثم الخارجي القديم.
     */
    fun getAllSearchDirs(context: Context): List<File> =
        EmbeddedStorage.allSearchDirs(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )

    /**
     * المسار الأساسي لمجلد أصوات VocalizerEx2.
     * يعيد مجلد البيانات الحالي إن وُجد، أو يُنشئ المجلد الداخلي
     * (filesDir) القابل للكتابة دائماً.
     */
    fun getVocalizerBaseDir(context: Context): File {
        val all = getAllSearchDirs(context)
        for (dir in all) {
            if (dir.exists() && dir.isDirectory) {
                val hasData = dir.listFiles { f ->
                    f.extension == "dat" || f.extension == "hdr"
                }
                if (!hasData.isNullOrEmpty()) {
                    return dir
                }
            }
        }

        val base = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        if (!base.exists()) base.mkdirs()
        return base
    }

    /**
     * تنزيل حزمة صوت Vocalizer Expressive 2 وفك ضغط ملفات .dat و .hdr
     * تلقائياً داخل المجلد مع التحقق الكامل ورموز الخطأ الواضحة (ع٣٠-6).
     */
    /**
     * إعلان مسموع (أفضل جهد) عند فشل التحقق من بصمة الحزمة.
     */
    private fun announceHashMismatch(context: Context) {
        val appCtx = context.applicationContext
        val msg = "فشل تنزيل حزمة الصوت: بصمة التحقق غير متطابقة"
        try {
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
                }
            }
        } catch (_: Throwable) {
        }
        try {
            var tts: TextToSpeech? = null
            tts = TextToSpeech(appCtx) { status ->
                try {
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.forLanguageTag("ar")
                        @Suppress("DEPRECATION")
                        tts?.speak(
                            msg,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "hash_mismatch"
                        )
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    suspend fun downloadVoice(
        context: Context,
        item: VocalizerCatalogItem,
        targetDir: File? = null,
        onProgress: (percent: Int) -> Unit = {}
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val destination = targetDir ?: getVocalizerBaseDir(context)
        if (!destination.exists() && !destination.mkdirs()) {
            return@withContext Result.failure(
                DownloadError(
                    DownloadError.NETWORK,
                    "Cannot create Vocalizer directory: ${destination.path}"
                )
            )
        }

        // ع٣٠-6 بند 3: التحقق من المساحة الحرة قبل البدء (StatFs)
        try {
            DownloadHttpHelper.checkStorageSpace(destination, item.sizeMb)
        } catch (e: DownloadError) {
            return@withContext Result.failure(e)
        }

        val partZipFile = File(
            DownloadHttpHelper.getDownloadsDir(context),
            "vocalizer_${item.id}.zip.part"
        )

        try {
            val startTime = System.currentTimeMillis()

            // 1. تنزيل ملف الـ Zip مع دعم الاستئناف ومتابعة التقدم (ع٣٠-7)
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

            // البند ١٤: الأرشيف المبتسر يُكتشف قبل فك الضغط، ويُذكر
            // حجمه الفعلي والمتوقع كسبب حقيقي لا كخطأ شبكة مبهم.
            val expectedBytes = item.sizeMb * 1024L * 1024L
            val actualBytes = partZipFile.length()
            if (expectedBytes > 0L &&
                actualBytes < expectedBytes * MIN_DOWNLOAD_SIZE_RATIO
            ) {
                Log.e(
                    TAG,
                    "تنزيل مبتسر [STEP=SIZE]: $actualBytes B من " +
                        "حوالي $expectedBytes B لـ ${item.id}"
                )
                partZipFile.delete()
                throw stepError(
                    "SIZE", DownloadError.MODEL_TOO_SMALL,
                    "الحجم $actualBytes B أقل من المتوقع " +
                        "حوالي $expectedBytes B"
                )
            }

            // ع٣٠-5 بند 4: توثيق القياسات في السجل
            Log.i(
                TAG,
                "تنزيل ${item.id}: size=${partZipFile.length()}B, " +
                    "time=${durationMs}ms"
            )

            // ع٣٠-5 بند 1 و ع٣٠-6: التحقق من التوقيع السحري لـ ZIP
            val head = ByteArray(200)
            val headRead = partZipFile.inputStream().use { it.read(head) }
            val isZip = headRead >= 4 &&
                head[0] == 0x50.toByte() &&
                head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() &&
                head[3] == 0x04.toByte()

            if (!isZip) {
                val headText = if (headRead > 0) {
                    String(head, 0, headRead, Charsets.UTF_8)
                        .replace("\r", "")
                        .replace("\n", " ")
                        .take(200)
                } else {
                    "(empty)"
                }
                Log.e(
                    TAG,
                    "الملف المنزّل ليس ZIP صالحاً [NOT_A_ZIP]: head=$headText"
                )
                partZipFile.delete()
                throw stepError(
                    "ZIP_MAGIC", DownloadError.NOT_A_ZIP,
                    "head=$headText"
                )
            }

            // 2. فك ضغط ملفات الـ Zip داخل مجلد VocalizerEx2
            // بند 6: التحقق من بصمة SHA-256 للحزمة (إن توفرت) قبل فك الضغط.
            val expectedSha = item.sha256?.trim().orEmpty()
            if (expectedSha.isNotEmpty()) {
                val ok = runCatching {
                    DownloadHttpHelper.verifySha256(partZipFile, expectedSha)
                }.getOrDefault(false)
                if (!ok) {
                    Log.e(
                        TAG,
                        "بصمة SHA-256 غير متطابقة [STEP=HASH] للصوت ${item.id}"
                    )
                    partZipFile.delete()
                    announceHashMismatch(context)
                    throw stepError(
                        "HASH", DownloadError.HASH_MISMATCH,
                        "بصمة التحقق غير متطابقة"
                    )
                }
            }

            // 2. فك ضغط ملفات الـ Zip داخل مجلد VocalizerEx2
            onProgress(96)
            val unzipResult = unzipToDirectory(partZipFile, destination)

            // ع٣٠-5 بند 2 و ع٣٠-6: التحقق من وجود ملفات مستخرجة
            if (unzipResult.count == 0) {
                Log.e(TAG, "الأرشيف فارغ لم يستخرج أي ملف [EMPTY_ARCHIVE]")
                partZipFile.delete()
                throw stepError(
                    "UNZIP", DownloadError.EMPTY_ARCHIVE,
                    "لم يستخرج أي ملف من الأرشيف"
                )
            }

            // ع٣٠-5 بند 3 و ع٣٠-6: التحقق من وجود ملف الـ Header وحجمه.
            // يُقبل الرأس بعائلة الإصدار نفسه (تطابق غير حرفي) لأن رقم
            // إصدار الرأس في الحزمة قد يخالف رقمه في الكتالوج.
            val hdrFile = VocalizerModelCatalog.installedHeaderFile(
                destination,
                item
            )
            if (hdrFile == null) {
                val extractedList = unzipResult.fileNames.joinToString(", ")
                Log.e(
                    TAG,
                    "ملف الـ header مفقود [HEADER_MISSING]: " +
                        "expected=${item.headerFileName}, " +
                        "extracted=[$extractedList]"
                )
                throw stepError(
                    "HEADER", DownloadError.HEADER_MISSING,
                    "متوقع ${item.headerFileName}، المستخرج: [$extractedList]"
                )
            }

            // البند ١٤: الرأس وحده لا يكفي — ملفات البيانات الصفرية أو
            // الناقصة تجعل الصوت «مثبّتاً» بلا PCM، فيبدو النطق صامتاً.
            val integrityReason = verifyExtractedVoice(destination, item)
            if (integrityReason != null) {
                Log.e(TAG, "حزمة ناقصة [STEP=INTEGRITY]: $integrityReason")
                throw stepError(
                    "INTEGRITY", DownloadError.MODEL_TOO_SMALL,
                    integrityReason
                )
            }

            onProgress(100)

            // حذف الملف المؤقت فقط بعد التحقق الناجح
            partZipFile.delete()

            // ع٣٠-5 بند 5: رفع إصدار التخزين وإبطال الكاش فقط بعد التحقق
            EmbeddedStorage.bumpVersion()
            EnginePicker.invalidateAfterStorageChange()

            Log.i(
                TAG,
                "Voice ${item.voiceName} verified and installed at " +
                    destination.absolutePath +
                    " (${unzipResult.count} files)"
            )
            Result.success(true)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to download voice ${item.id}", t)
            val err = if (t is DownloadError) t else {
                DownloadError(
                    DownloadError.NETWORK,
                    t.message ?: "Network error"
                )
            }
            Result.failure(err)
        }
    }

    /**
     * البند ١٤: فحص سلامة الحزمة بعد فك الضغط؛ يعيد سبب الفشل أو null
     * عند السلامة. البحث متكرر لأن حزم Tarik/Mariam تضع الرأس والبيانات
     * داخل مجلد فرعي (البند ٢١)، فالفحص السطحي كان يرى الحزمة ناقصة.
     */
    internal fun verifyExtractedVoice(
        destination: File,
        item: VocalizerCatalogItem
    ): String? {
        val allFiles = destination.walkTopDown().filter { it.isFile }.toList()
        if (allFiles.size < MIN_VOICE_FILES) {
            return "عدد الملفات ${allFiles.size} أقل من $MIN_VOICE_FILES"
        }
        val header = VocalizerModelCatalog.installedHeaderFile(
            destination, item
        )
        if (header == null || header.length() <= 0L) {
            return "رأس الصوت مفقود أو فارغ"
        }
        val dataFiles = allFiles.filter { file ->
            file.extension.equals("dat", ignoreCase = true) ||
                file.extension.equals("bin", ignoreCase = true)
        }
        if (dataFiles.isEmpty()) {
            return "لا توجد ملفات بيانات (.dat/.bin) للصوت"
        }
        if (dataFiles.none { it.length() > 0L }) {
            return "كل ملفات البيانات بحجم صفر"
        }
        return null
    }

    /** سقف حجم المحتوى المستخرج من أرشيف واحد (حماية zip bomb). */
    private const val MAX_EXTRACTION_BYTES = 1L * 1024 * 1024 * 1024

    /**
     * فك ضغط محتويات ملف Zip مباشرة في المجلد الهدف.
     * حماية zip-slip (ع٢٩-3): يُرفض أي اسم يحوي `..` أو مساراً
     * خارج المجلد الهدف، ويُفرض سقف حجم الاستخراج [maxBytes].
     * (internal ليصل إليه حارس الاختبار).
     */
    internal fun unzipToDirectory(
        zipFile: File,
        targetDir: File,
        maxBytes: Long = MAX_EXTRACTION_BYTES
    ): UnzipResult {
        val canonicalTarget = targetDir.canonicalFile
        var extractedBytes = 0L
        val extractedNames = mutableListOf<String>()

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(32 * 1024)
            while (entry != null) {
                // البند ١٣: احتفظ بالمسار النسبي داخل الأرشيف بدل تسطيحه،
                // فحزم Tarik/Mariam تضع الرأس والبيانات في مجلدات فرعية،
                // وتسطيحها كان يكسر بنية الحزمة ويُخفي ملفاتها.
                val rawName = entry.name.replace('\\', '/')
                val traversal = rawName.contains("..")
                val segments = rawName.split('/').filter {
                    it.isNotBlank() && it != "." && it != ".."
                }
                val relativeName = segments.joinToString("/")
                val outFile = File(targetDir, relativeName)
                val inside = try {
                    outFile.canonicalFile.startsWith(canonicalTarget)
                } catch (_: Throwable) {
                    false
                }
                if (traversal || relativeName.isBlank() || !inside) {
                    Log.w(
                        TAG,
                        "Rejected unsafe zip entry: ${entry.name}"
                    )
                } else if (!entry.isDirectory) {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            extractedBytes += len
                            if (extractedBytes > maxBytes) {
                                throw IllegalStateException(
                                    "Extraction exceeds $maxBytes bytes"
                                )
                            }
                            fos.write(buffer, 0, len)
                        }
                    }
                    extractedNames.add(relativeName)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return UnzipResult(extractedNames.size, extractedNames)
    }

    /**
     * حذف ملفات صوت محدد من مجلدات VocalizerEx2.
     */
    fun deleteVoice(
        context: Context,
        item: VocalizerCatalogItem,
        targetDir: File? = null
    ): Boolean {
        val dirs = if (targetDir != null) listOf(targetDir)
            else getAllSearchDirs(context)
        var deletedAny = false
        // البند ١١: حدود الاسم صريحة — «Ava» لا تحذف "available.dat"،
        // ولا تُحذف ملفات صوت أخرى لمجرد اشتراك جزء من الاسم.
        val voiceNamePattern = Regex(
            "(?<![\\p{L}\\p{N}])" +
                Regex.escape(item.voiceName.trim()) +
                "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE
        )

        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory) continue

            // 1. حذف ملف الـ Header (باسمه الفعلي المُثبَّت، لا الحرفي في
            //    الكتالوج) — قد يختلف رقم إصداره عن الكتالوج.
            val hdrFile = VocalizerModelCatalog.installedHeaderFile(dir, item)
            if (hdrFile != null) {
                deletedAny = hdrFile.delete() || deletedAny
            }

            // 2. فحص وحذف ملفات البيانات المرتبطة باسم الصوت — بشكل
            // متكرر لأن الحزمة قد تكون داخل مجلد فرعي (البند ١٣).
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                if (voiceNamePattern.containsMatchIn(file.name)) {
                    deletedAny = file.delete() || deletedAny
                }
            }
        }

        // 3. حذف أي ملف تنزيل مؤقت متبقٍ
        val partFile = File(
            DownloadHttpHelper.getDownloadsDir(context),
            "vocalizer_${item.id}.zip.part"
        )
        if (partFile.exists()) {
            deletedAny = partFile.delete() || deletedAny
        }

        return deletedAny.also {
            if (it) {
                EmbeddedStorage.bumpVersion()
                EnginePicker.invalidateAfterStorageChange()
            }
        }
    }
}
