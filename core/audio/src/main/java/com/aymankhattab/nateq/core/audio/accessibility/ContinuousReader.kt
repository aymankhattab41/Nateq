package com.aymankhattab.nateq.core.audio.accessibility

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * القراءة المستمرة عبر تمرير المحتوى (ع٤١-ب6).
 *
 * - تعمل على خيط خلفي مستقلّ فلا تُثقل الخيط الرئيسي أثناء التمرير.
 * - تقرأ الصفحة الأولى كاملةً ثم تمرّر وتقارن (عبر [ContinuousPageTracker])
 *   لكشف نهاية المحتوى وقراءة الجديد فقط.
 * - تتوقف عند: تغيّر التطبيق، إطفاء الشاشة، مكالمة/إيقاف مؤقت، أمرٍ
 *   بإيقافها، أو بلوغ الحدّ الأقصى ([MAX_PAGES]).
 *
 * ولا تعتمد على أندرويد في منطقها الأساسي إلا في جدولة الخيط؛ مصدر
 * القراءة والتمرير والإعلان يُمرَّر عبر [Source] فيبقى الاختبار ممكناً.
 */
object ContinuousReader {

    /** مصدر القراءة والتمرير والإعلان — يُنفّذه موجّه الاختصارات. */
    interface Source {
        fun readTexts(): List<String>
        fun scrollForward(): Boolean
        fun currentPackage(): String?
        fun announce(text: String)
    }

    private const val TAG = "NATEQ_A11Y"
    private const val PAGE_WAIT_MS = 1500L
    private const val MAX_PAGES = 50

    private val waitToken = Any()
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var source: Source? = null
    private var tracker = ContinuousPageTracker()
    private var startPackage: String? = null
    private var pages = 0

    @Volatile
    private var running = false

    fun isReading(): Boolean = running

    /**
     * يبدأ القراءة إن لم تكن جارية، ويوقفها إن كانت جارية.
     * @return هل بدأت جلسةٌ جديدة؟
     */
    fun toggle(src: Source): Boolean {
        if (running) {
            stop(ANNOUNCE_STOPPED)
            return false
        }
        start(src)
        return true
    }

    private fun start(src: Source) {
        source = src
        tracker = ContinuousPageTracker()
        startPackage = src.currentPackage()
        pages = 0
        running = true
        ensureThread()
        val post = handler?.post { readInitial() }
        if (post == null) stop(null)
    }

    private fun ensureThread() {
        if (handlerThread != null) return
        val thread = HandlerThread(THREAD_NAME)
        thread.start()
        handlerThread = thread
        handler = Handler(thread.looper)
    }

    private fun readInitial() {
        if (!running) return
        val src = source ?: return finish(null)
        val texts = src.readTexts()
        if (texts.isEmpty()) return finish(ANNOUNCE_NO_TEXT)
        src.announce(texts.joinToString(SEPARATOR))
        tracker.firstPage(texts)
        pages = 1
        scrollNext()
    }

    /** يُنادى من الخدمة عند تغيّر نافذةٍ قد يعني تبديل التطبيق. */
    fun onWindowChanged(packageName: String?) {
        if (!running) return
        if (packageName != null && startPackage != null &&
            packageName != startPackage
        ) {
            stop(ANNOUNCE_APP_CHANGED)
            return
        }
        onContentChanged()
    }

    /** يُنادى من الخدمة عند تمرير/تغيّر محتوى أثناء انتظار الصفحة. */
    fun onContentChanged() {
        if (!running) return
        handler?.removeCallbacksAndMessages(waitToken)
        handler?.post { readNext() }
    }

    private fun scrollNext() {
        if (!running) return
        val src = source ?: return finish(null)
        if (pages >= MAX_PAGES) return finish(ANNOUNCE_MAX_PAGES)
        if (!src.scrollForward()) return finish(ANNOUNCE_END)
        handler?.postAtTime(
            { readNext() },
            waitToken,
            SystemClock.uptimeMillis() + PAGE_WAIT_MS
        )
    }

    private fun readNext() {
        if (!running) return
        val src = source ?: return finish(null)
        val result = tracker.nextPage(src.readTexts())
        if (result.isEnd) return finish(ANNOUNCE_END)
        if (result.freshTexts.isNotEmpty()) {
            src.announce(result.freshTexts.joinToString(SEPARATOR))
        }
        pages++
        scrollNext()
    }

    /** إيقاف فوري بلا إعلان (إطفاء الشاشة، مكالمة، تغيّر حالة مؤقتة). */
    fun stopSilently() = stop(null)

    /** يوقف الجلسة ويعيد المورد؛ وإن مُرّرت رسالةٌ أُعلنت. */
    fun stop(message: String?) {
        if (!running) return
        running = false
        handler?.removeCallbacksAndMessages(null)
        val src = source
        source = null
        releaseThread()
        if (message != null) {
            runCatching { src?.announce(message) }
                .onFailure { Log.w(TAG, "continuous stop announce failed", it) }
        }
    }

    private fun finish(message: String?) = stop(message)

    private fun releaseThread() {
        val thread = handlerThread ?: return
        handlerThread = null
        handler = null
        runCatching { thread.quitSafely() }
    }

    private const val THREAD_NAME = "NATEQ_CONTINUOUS"
    private const val SEPARATOR = ". "
    private const val ANNOUNCE_NO_TEXT = "لا يوجد نصّ لقراءته"
    private const val ANNOUNCE_END = "انتهى المحتوى"
    private const val ANNOUNCE_MAX_PAGES = "انتهت القراءة، بلغت الحدّ الأقصى"
    private const val ANNOUNCE_APP_CHANGED = "توقفت القراءة، تغيّر التطبيق"
    private const val ANNOUNCE_STOPPED = "توقفت القراءة المستمرة"
}
