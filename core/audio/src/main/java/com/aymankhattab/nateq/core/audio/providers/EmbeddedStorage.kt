package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * مصدر التخزين الوحيد للمزوّدات والمنزِّلات المدمجة (ع٢٩-3).
 *
 * القرار المعماري: المجلد الأساسي هو `filesDir/<EngineDir>` (داخلي)
 * فلا يحتاج إذناً وأسرع من `Android/data` (FUSE على أندرويد 11+) ولا
 * يختلف سلوكه بين الأجهزة. ويبقى
 * `getExternalFilesDir(null)/<EngineDir>` في قائمة البحث فقط لمن
 * نسخ ملفاته يدوياً، ويُهاجَر مرة واحدة إلى الداخلي. وكل مسار
 * `/sdcard` و`/storage/emulated/0` و
 * `Environment.getExternalStorageDirectory()` مُحذوف من المزوّدات
 * (أندرويد 10+ لا يسمح بها بلا MANAGE_EXTERNAL_STORAGE).
 */
object EmbeddedStorage {

    private const val TAG = "EmbeddedStorage"

    /** تفضيل هجرة المحتوى الخارجي إلى الداخلي لكل محرّك. */
    private const val MIGRATED_KEY = "embedded_storage_migrated_"

    /**
     * المحرّك المدمج ومجلده الأساسي وأسماء مجلداته القديمة.
     *
     * حصراً — المكتبة الأصلية ترفض أي مسار لا يحوي هذا الاسم.
     * [legacyInternalNames] مجلدات داخلية قديمة تُهاجَر مرة واحدة.
     */
    enum class Engine(
        val dirName: String,
        val legacyExternalNames: List<String>,
        val legacyInternalNames: List<String> = emptyList()
    ) {
        VOCALIZER(
            "VocalizerEx2",
            listOf("VocalizerEx2")
        )
    }

    /**
     * عنصر ملف واحد داخل جرد محرك مدمج (ع٣١-4).
     */
    data class InventoryFile(
        val file: File,
        val relativePath: String,
        val name: String,
        val size: Long,
        val isRecognized: Boolean
    )

    /**
     * نتيجة الجرد الحي لمحرّك مدمج (ع٣١-4).
     */
    data class EngineInventory(
        val engine: Engine,
        val files: List<InventoryFile>,
        val totalSize: Long,
        val completedVoices: List<String>
    ) {
        val isCompleteVoicePresent: Boolean
            get() = completedVoices.isNotEmpty()
    }

    /**
     * عدّاد يرتفع عند أي تغيير في محتوى المجلدات (ع٢٩-9):
     * الكاش يُبطَل برؤية رقمٍ أعلى بدل انتظار مهلته.
     */
    private val versionCounter = AtomicInteger(0)

    @Volatile
    internal var lastBumpTimestamp = 0L

    /** زمن آخر تغيير في التخزين (ملي ثانية). */
    val lastBumpTime: Long
        get() = lastBumpTimestamp

    /**
     * رقم الإصدار الحالي لمحتوى التخزين — يرتفع مع كل تغيير.
     */
    val version: Int
        get() = versionCounter.get()

    /**
     * رفع رقم الإصدار — يُستدعى بعد كل تنزيل/حذف/استيراد/نقل.
     */
    fun bumpVersion() {
        versionCounter.incrementAndGet()
        lastBumpTimestamp = System.currentTimeMillis()
    }

    /** تصفير زمن الـ bump للاختبارات فقط. */
    internal fun resetBumpTimeForTest(timestamp: Long = 0L) {
        lastBumpTimestamp = timestamp
    }

    private val ioScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private val migrationStarted = AtomicBoolean(false)

    @Volatile
    private var migrationListener:
        ((engine: Engine, done: Int, total: Int) -> Unit)? = null

    /**
     * مستمع شريط تقدّم الهجرة (شاشة التخزين في ع٢٩-8):
     * يستقبل المحرّك وعدد المنقول والإجمالي لكل دفعة.
     */
    fun setMigrationListener(
        listener: ((engine: Engine, done: Int, total: Int) -> Unit)?
    ) {
        migrationListener = listener
    }

    /**
     * إنجاز هجرة المجلدات الخارجية إلى الداخلي مرة واحدة
     * عند أول تشغيل على خيط IO في الخلفية — لا يعطّل فتح أي شاشة.
     */
    fun startBackgroundMigration(context: Context) {
        if (!migrationStarted.compareAndSet(false, true)) return
        val app = context.applicationContext
        ioScope.launch {
            runCatching {
                val moved = migrateAll(app) { engine, done, total ->
                    migrationListener?.invoke(engine, done, total)
                }
                // ع٢٩-9: اكتمال النقل تغيّرٌ في المحتوى يُبطل الكاش.
                if (moved > 0) {
                    EnginePicker.invalidateAfterStorageChange()
                }
            }.onFailure {
                Log.w(
                    TAG,
                    "Storage migration failed: ${it.message}"
                )
            }
        }
    }

    /** المجلد الأساسي الداخلي (دائماً تحت filesDir). */
    fun baseDir(context: Context, engine: Engine): File =
        File(context.filesDir, engine.dirName)

    /**
     * المجلدات الخارجية القديمة الموجودة فعلاً
     * (بحث فقط — تُهاجَر لاحقاً).
     */
    private fun externalDirs(
        context: Context,
        engine: Engine
    ): List<File> {
        val root = context.getExternalFilesDir(null)
            ?: return emptyList()
        return engine.legacyExternalNames
            .map { File(root, it) }
            .filter { it.isDirectory }
    }

    /**
     * كل المجلدات التي يقرأ منها المزوّد والمنزِّل — بنفس الترتيب:
     * الداخلي أولاً (الأولوية له) ثم الخارجي القديم ثم المجلدات
     * الإضافية الصريحة (مجلد مستورد أو مجلد اختبار).
     */
    fun allSearchDirs(
        context: Context,
        engine: Engine,
        extraDirs: List<File> = emptyList()
    ): List<File> {
        val dirs = LinkedHashSet<File>()
        dirs.add(baseDir(context, engine))
        dirs.addAll(externalDirs(context, engine))
        extraDirs.forEach { dirs.add(it) }
        return dirs.toList()
    }

    /**
     * جرد حي للملفات تحت مجلدات البحث لمحرّك معين (ع٣١-4).
     *
     * يفحص القرص دون أي أثر جانبي (بلا إنشاء مجلدات ولا نسخ ولا JNI).
     */
    fun inventory(
        context: Context,
        engine: Engine,
        extraDirs: List<File> = emptyList()
    ): EngineInventory {
        val searchDirs = allSearchDirs(context, engine, extraDirs)
        val allFiles = mutableListOf<File>()
        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.walkTopDown().forEach { f ->
                if (f.isFile) allFiles.add(f)
            }
        }
        val distinctFiles = allFiles.distinctBy { it.absolutePath }
        val totalSize = distinctFiles.sumOf { it.length() }

        val completedVoices = mutableListOf<String>()
        val inventoryFiles = mutableListOf<InventoryFile>()

        when (engine) {
            Engine.VOCALIZER -> {
                for (dir in searchDirs) {
                    completedVoices.addAll(
                        VocalizerVoiceManifest.completeVoiceBases(dir)
                    )
                }
                for (f in distinctFiles) {
                    val ext = f.extension.lowercase()
                    val isCatalogMatch =
                        VocalizerModelCatalog.ITEMS.any { item ->
                            val hdr = item.headerFileName
                            val dat = "${hdr.removeSuffix(".hdr")}.dat"
                            val voice = item.voiceName.lowercase()
                            f.name.equals(hdr, ignoreCase = true) ||
                                f.name.equals(dat, ignoreCase = true) ||
                                f.nameWithoutExtension.lowercase()
                                    .contains(voice) ||
                                f.name.startsWith(
                                    "ve_pipeline_",
                                    ignoreCase = true
                                )
                        }
                    val isAux = ext == "mp3" || ext == "cfg" ||
                        ext == "pcm" || ext == "txt"
                    val recognized = isCatalogMatch || isAux
                    inventoryFiles.add(
                        InventoryFile(
                            file = f,
                            relativePath = f.name,
                            name = f.name,
                            size = f.length(),
                            isRecognized = recognized
                        )
                    )
                }
            }
        }

        return EngineInventory(
            engine = engine,
            files = inventoryFiles,
            totalSize = totalSize,
            completedVoices = completedVoices.distinct()
        )
    }

    /**
     * هل يلزم إنجاز هجرة هذا المحرّك بعد؟ (فحص تفضيل بلا نقل).
     */
    fun needsMigration(context: Context, engine: Engine): Boolean {
        if (isMigrated(context, engine)) return false
        return externalDirs(context, engine).any { dir ->
            dir.listFiles()?.isNotEmpty() == true
        }
    }

    private fun isMigrated(context: Context, engine: Engine): Boolean =
        prefs(context)
            .getBoolean(MIGRATED_KEY + engine.name, false)

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            "embedded_storage",
            Context.MODE_PRIVATE
        )

    /**
     * نقل محتوى المجلدات الخارجية القديمة إلى الداخلي مرة واحدة.
     * يعيد عدد الملفات المنقولة؛ 0 يعني لا شيء (مُنجز سابقاً أو فارغ).
     *
     * [onProgress] يستقبل (المنقول، الإجمالي) — شريط تقدم الشاشة.
     */
    fun migrateExternalToInternal(
        context: Context,
        engine: Engine,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Int {
        if (isMigrated(context, engine)) return 0
        val base = baseDir(context, engine)
        if (!base.exists()) base.mkdirs()

        val externals = externalDirs(context, engine)
        val pending = mutableListOf<Pair<File, File>>()
        for (dir in externals) {
            dir.listFiles()?.forEach { child ->
                val target = File(base, child.name)
                if (!target.exists()) pending.add(child to target)
            }
        }

        if (pending.isEmpty()) {
            markMigrated(context, engine)
            return 0
        }

        var done = 0
        val total = pending.size
        onProgress(done, total)
        for ((src, dst) in pending) {
            val moved = src.renameTo(dst) || copyRecursively(src, dst)
            if (moved) {
                src.deleteRecursively()
                done++
            } else {
                Log.w(
                    TAG,
                    "Migration failed for ${src.absolutePath}"
                )
            }
            onProgress(done, total)
        }
        markMigrated(context, engine)
        bumpVersion()
        Log.i(
            TAG,
            "Migrated $done/$total entries of ${engine.name} " +
                "into ${base.absolutePath}"
        )
        return done
    }

    /**
     * نقل محتوى المجلدات الداخلية القديمة إلى المجلد الأساسي
     * (لا يجد المجلد القديم في المرة الثانية فيكون 0).
     */
    fun migrateLegacyInternalDirs(
        context: Context,
        engine: Engine
    ): Int {
        if (engine.legacyInternalNames.isEmpty()) return 0
        val base = baseDir(context, engine)
        if (!base.exists()) base.mkdirs()
        var moved = 0
        for (name in engine.legacyInternalNames) {
            val legacy = File(context.filesDir, name)
            if (!legacy.exists() || !legacy.isDirectory) continue
            val children = legacy.listFiles() ?: continue
            for (child in children) {
                val dst = File(base, child.name)
                if (dst.exists()) continue
                val ok = child.renameTo(dst) ||
                    copyRecursively(child, dst)
                if (ok) {
                    if (child.isDirectory) {
                        child.deleteRecursively()
                    }
                    moved++
                }
            }
        }
        if (moved > 0) {
            EnginePicker.invalidateAfterStorageChange()
            bumpVersion()
            Log.i(
                TAG,
                "نُقل $moved عنصراً من مجلد ${engine.name} القديم " +
                    "إلى ${base.absolutePath}"
            )
        }
        return moved
    }

    /**
     * هجرة كل المحرّكات — تُستدعى على خيط IO عند أول تشغيل.
     */
    fun migrateAll(
        context: Context,
        onProgress: (engine: Engine, done: Int, total: Int) -> Unit =
            { _, _, _ -> }
    ): Int {
        var moved = 0
        for (engine in Engine.entries) {
            // الهجرة الداخلية تُنفَّذ دائماً (خارج مفتاح الهجرة
            // الخارجية) لأنها إصلاح مسار لا يمنعها تفضيلٌ قديم.
            moved += migrateLegacyInternalDirs(context, engine)
            if (!needsMigration(context, engine)) continue
            moved += migrateExternalToInternal(context, engine) { d, t ->
                onProgress(engine, d, t)
            }
        }
        return moved
    }

    private fun markMigrated(context: Context, engine: Engine) {
        prefs(context)
            .edit()
            .putBoolean(MIGRATED_KEY + engine.name, true)
            .apply()
    }

    /** نقل احتياطي إن رفض `renameTo` (اختلاف نظام ملفات). */
    private fun copyRecursively(src: File, dst: File): Boolean {
        return try {
            if (src.isDirectory) {
                dst.mkdirs()
                val children = src.listFiles() ?: return true
                for (child in children) {
                    if (!copyRecursively(
                            child,
                            File(dst, child.name)
                        )
                    ) {
                        return false
                    }
                }
                true
            } else {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                true
            }
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "copyRecursively failed ${src.absolutePath}: " +
                    "${t.message}"
            )
            false
        }
    }
}
