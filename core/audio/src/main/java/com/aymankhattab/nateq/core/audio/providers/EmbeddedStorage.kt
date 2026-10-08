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
 * يختلف سلوكه بين الأجهزة. ويبقى `getExternalFilesDir(null)/<EngineDir>`
 * في قائمة البحث فقط لمن نسخ ملفاته يدوياً، ويُهاجَر مرة واحدة إلى
 * الداخلي. وكل مسار `/sdcard` و`/storage/emulated/0` و`Environment.
 * getExternalStorageDirectory()` مُحذوف من المزوّدات (أندرويد 10+
 * لا يسمح بها بلا MANAGE_EXTERNAL_STORAGE).
 */
object EmbeddedStorage {

    private const val TAG = "EmbeddedStorage"

    /** تفضيل هجرة المحتوى الخارجي إلى الداخلي لكل محرّك. */
    private const val MIGRATED_KEY = "embedded_storage_migrated_"

    /** المحرّك المدمج ومجلده الأساسي وأسماء مجلداته القديمة. */
    enum class Engine(
        val dirName: String,
        val legacyExternalNames: List<String>
    ) {
        VOCALIZER(
            "VocalizerEx2",
            listOf("VocalizerEx2")
        ),
        ACAPELA(
            "AcapelaTTS",
            listOf("AcapelaTTS", "AcapelaGroup", "acapela")
        ),
        PIPER(
            "Piper",
            listOf("Piper", "piper")
        ),
        SHERPA(
            "SherpaTTS",
            listOf(
                "SherpaTTS",
                "sherpatts",
                "Piper",
                "piper",
                "models"
            )
        ),
    }

    /**
     * عدّاد يرتفع عند أي تغيير في محتوى المجلدات (ع٢٩-9): الكاش يُبطَل
     * برؤية رقمٍ أعلى بدل انتظار مهلته.
     */
    private val versionCounter = AtomicInteger(0)

    /** رقم الإصدار الحالي لمحتوى التخزين — يرتفع مع كل تغيير. */
    val version: Int
        get() = versionCounter.get()

    /** رفع رقم الإصدار — يُستدعى بعد كل تنزيل/حذف/استيراد/نقل. */
    fun bumpVersion() {
        versionCounter.incrementAndGet()
    }

    private val ioScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private val migrationStarted = AtomicBoolean(false)

    @Volatile
    private var migrationListener:
        ((engine: Engine, done: Int, total: Int) -> Unit)? = null

    /**
     * مستمع شريط تقدّم الهجرة (شاشة التخزين في ع٢٩-8): يستقبل
     * المحرّك وعدد المنقول والإجمالي لكل دفعة.
     */
    fun setMigrationListener(
        listener: ((engine: Engine, done: Int, total: Int) -> Unit)?
    ) {
        migrationListener = listener
    }

    /**
     * إنجاز هجرة المجلدات الخارجية إلى الداخلي مرة واحدة عند أول
     * تشغيل على خيط IO في الخلفية — لا يعطّل فتح أي شاشة.
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
                if (moved > 0) EnginePicker.invalidateAfterStorageChange()
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
     * المجلدات الخارجية القديمة الموجودة فعلاً (بحث فقط — تُهاجَر لاحقاً).
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

    /** هل يلزم إنجاز هجرة هذا المحرّك بعد؟ (فحص تفضيل بلا نقل). */
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

    /** هجرة كل المحرّكات — تُستدعى على خيط IO عند أول تشغيل. */
    fun migrateAll(
        context: Context,
        onProgress: (engine: Engine, done: Int, total: Int) -> Unit =
            { _, _, _ -> }
    ): Int {
        var moved = 0
        for (engine in Engine.entries) {
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
