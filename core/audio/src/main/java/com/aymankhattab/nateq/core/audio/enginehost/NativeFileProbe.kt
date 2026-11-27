package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.os.FileObserver
import android.system.Os
import android.util.Log
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ع٣٤-1: مراقبة الملفات التي تلمسها المكتبة أثناء التهيئة (بلا root).
 *
 * 1. قبل أول initialize يتم إنشاء [FileObserver] (inotify) للمجلدات:
 *    - filesDir/VocalizerEx2
 *    - filesDir
 *    - cacheDir
 *    - codeCacheDir
 *    - dataDir (/data/user/0/<pkg>)
 *    - getExternalFilesDir(null)
 * 2. يرصد الأحداث: OPEN, ACCESS, CREATE, MODIFY, CLOSE_WRITE,
 *    CLOSE_NOWRITE, MOVED_TO, ATTRIB, DELETE.
 * 3. خيط مراقبة يلتقط كل ~1ms واصفات الملفات في /proc/self/fd عبر readlink.
 * 4. تسجيل File(".").canonicalPath للتحقق من مجلد العمل الحالي للعملية.
 */
object NativeFileProbe {

    private const val TAG = "NativeFileProbe"
    private const val PROBE_SECTION = "--- ملفات لمستها المكتبة ---"

    /** أحداث inotify المرصودة مجمعة. */
    const val OBSERVED_EVENTS = FileObserver.OPEN or
        FileObserver.ACCESS or
        FileObserver.CREATE or
        FileObserver.MODIFY or
        FileObserver.CLOSE_WRITE or
        FileObserver.CLOSE_NOWRITE or
        FileObserver.MOVED_TO or
        FileObserver.ATTRIB or
        FileObserver.DELETE

    /** تحويل قناع حدث inotify إلى اسم مقروء. */
    fun eventMaskToString(event: Int): String {
        val names = mutableListOf<String>()
        if (event and FileObserver.OPEN != 0) names.add("OPEN")
        if (event and FileObserver.ACCESS != 0) names.add("ACCESS")
        if (event and FileObserver.CREATE != 0) names.add("CREATE")
        if (event and FileObserver.MODIFY != 0) names.add("MODIFY")
        if (event and FileObserver.CLOSE_WRITE != 0) names.add("CLOSE_WRITE")
        if (event and FileObserver.CLOSE_NOWRITE != 0) {
            names.add("CLOSE_NOWRITE")
        }
        if (event and FileObserver.MOVED_TO != 0) names.add("MOVED_TO")
        if (event and FileObserver.ATTRIB != 0) names.add("ATTRIB")
        if (event and FileObserver.DELETE != 0) names.add("DELETE")
        return if (names.isEmpty()) "EVENT($event)" else names.joinToString("|")
    }

    /**
     * استخراج مسارات المجلدات المستهدفة للمراقبة دون تكرار.
     */
    fun getTargetDirectories(
        context: Context,
        extraDir: File? = null
    ): List<File> {
        val targets = mutableListOf<File>()

        fun addIfValid(f: File?) {
            if (f != null && !targets.contains(f)) {
                targets.add(f)
            }
        }

        extraDir?.let { addIfValid(it) }
        context.filesDir?.let { files ->
            addIfValid(File(files, "VocalizerEx2"))
            addIfValid(files)
        }
        addIfValid(context.cacheDir)
        addIfValid(context.codeCacheDir)
        context.applicationInfo?.dataDir?.let {
            addIfValid(File(it))
        }
        addIfValid(context.getExternalFilesDir(null))

        return targets
    }

    /**
     * قراءة الأهداف الحالية للواصفات في /proc/self/fd.
     */
    fun captureCurrentOpenFdTargets(): Set<String> {
        val fdDir = File("/proc/self/fd")
        val files = fdDir.listFiles() ?: return emptySet()
        val targets = mutableSetOf<String>()
        for (f in files) {
            val target = try {
                Os.readlink(f.absolutePath)
            } catch (_: Throwable) {
                runCatching { f.canonicalPath }.getOrNull()
            }
            if (!target.isNullOrBlank()) {
                targets.add(target)
            }
        }
        return targets
    }

    /**
     * جلسة تتبع حية تمسك بـ FileObservers وخيط مسح /proc/self/fd.
     */
    class Session(
        private val engineId: String,
        private val directories: List<File>
    ) {
        private val observers = mutableListOf<FileObserver>()
        private val inotifyEvents =
            Collections.synchronizedList(mutableListOf<String>())
        private val capturedFdTargets =
            Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        private val isRunning = AtomicBoolean(true)
        private var pollingThread: Thread? = null

        fun start() {
            for (dir in directories) {
                if (!dir.exists()) {
                    runCatching { dir.mkdirs() }
                }
                if (dir.exists()) {
                    // المُنشئ (File, Int) متاح من API 29 فقط وminSdk=24،
                    // لذا يُستخدم (String, Int) الملغى عمداً هنا.
                    @Suppress("DEPRECATION")
                    val obs = object : FileObserver(
                        dir.path,
                        OBSERVED_EVENTS
                    ) {
                        override fun onEvent(event: Int, path: String?) {
                            val eventName = eventMaskToString(event)
                            val entry = "[inotify] ${dir.name}/" +
                                "${path ?: ""}: $eventName"
                            inotifyEvents.add(entry)
                        }
                    }
                    try {
                        obs.startWatching()
                        observers.add(obs)
                    } catch (t: Throwable) {
                        Log.w(
                            TAG,
                            "استثناء بدء مراقبة inotify: " +
                                "${t.javaClass.simpleName}: ${t.message}"
                        )
                    }
                }
            }

            // تشغيل خيط فحص /proc/self/fd كل ~1ms
            val t = Thread({
                while (isRunning.get()) {
                    val current = captureCurrentOpenFdTargets()
                    capturedFdTargets.addAll(current)
                    try {
                        Thread.sleep(1)
                    } catch (e: InterruptedException) {
                        Log.d(
                            TAG,
                            "مقاطعة نوم خيط فحص fd: " +
                                "${e.javaClass.simpleName}: ${e.message}"
                        )
                        break
                    }
                }
            }, "NativeFileProbe-FD")
            t.isDaemon = true
            t.start()
            pollingThread = t
        }

        fun stopAndLog(): String {
            isRunning.set(false)
            try {
                pollingThread?.interrupt()
                pollingThread?.join(200)
            } catch (e: Throwable) {
                if (e is java.util.concurrent.CancellationException) throw e
                Log.w(
                    TAG,
                    "فشل إيقاف خيط فحص الملفات: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }
            for (obs in observers) {
                try {
                    obs.stopWatching()
                } catch (e: Throwable) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    Log.w(
                        TAG,
                        "فشل إيقاف مراقب الملفات: " +
                            "${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
            observers.clear()

            // تسجيل مجلد العمل الحالي
            val cwd = runCatching {
                File(".").canonicalPath
            }.getOrDefault(File(".").absolutePath)

            val lines = mutableListOf<String>()
            lines.add("مجلد العمل الحالي للعملية (CWD): $cwd")
            lines.add("المجلدات المراقبة (${directories.size}): " +
                directories.joinToString(", ") { it.path })

            lines.add("أحداث inotify المرصودة (${inotifyEvents.size}):")
            if (inotifyEvents.isEmpty()) {
                lines.add("  (لا أحداث inotify على المجلدات المراقبة)")
            } else {
                for (ev in inotifyEvents.take(100)) {
                    lines.add("  $ev")
                }
                if (inotifyEvents.size > 100) {
                    lines.add(
                        "  ... و${inotifyEvents.size - 100} حدثاً إضافياً"
                    )
                }
            }

            // تصفية أهداف FD لإبراز ما يخص مكتبتنا أو التطبيق
            val relevantFd = capturedFdTargets.filter { target ->
                target.contains("vocalizer", ignoreCase = true) ||
                    target.contains(
                        "com.aymankhattab.nateq",
                        ignoreCase = true
                    ) ||
                    target.endsWith(".dat") ||
                    target.endsWith(".hdr") ||
                    target.endsWith(".xml") ||
                    target.endsWith(".xmf") ||
                    target.endsWith(".ini") ||
                    target.endsWith(".inf") ||
                    target.endsWith(".dca") ||
                    target.endsWith(".bvcu")
            }
            lines.add("واصفات /proc/self/fd ذات الصلة (${relevantFd.size}):")
            if (relevantFd.isEmpty()) {
                lines.add("  (لا واصفات مطابقة لكلمات المحرك)")
            } else {
                for (fd in relevantFd.take(50)) {
                    lines.add("  $fd")
                }
            }

            val body = lines.joinToString("\n")
            HostLog.i(engineId, "$PROBE_SECTION\n$body")
            return body
        }
    }

    /**
     * بدء جلسة مراقبة لعملية التهيئة.
     *
     * @param onlyExtraDir عند `true` يُراقب [extraDir] وحده (المجلد
     *   المُمرَّر لـ`nInit`) بدل كل مجلدات التطبيق، فلا تُنشأ مجلدات
     *   جانبية ولا تُرصد أحداث لا تخصّ التجربة.
     */
    fun startSession(
        context: Context,
        engineId: String,
        extraDir: File? = null,
        onlyExtraDir: Boolean = false
    ): Session {
        val dirs = if (onlyExtraDir && extraDir != null) {
            listOf(extraDir)
        } else {
            getTargetDirectories(context, extraDir)
        }
        val session = Session(engineId, dirs)
        session.start()
        return session
    }
}
