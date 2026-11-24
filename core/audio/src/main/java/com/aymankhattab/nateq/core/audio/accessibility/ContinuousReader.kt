package com.aymankhattab.nateq.core.audio.accessibility

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * القراءة المستمرة عبر تمرير المحتوى (ع٤١-ب6).
 *
 * - تعمل على خيط خلفي مستقلّ فلا تُثقل الخيط الرئيسي أثناء التمرير.
 * - تنسّق حالة انتظار التمرير برقم جيل موحّد يلغي الجدولة السابقة قطعا،
 *   وتجعل قراءة الصفحة ذرية لكل انتقال (SCROLLED أو CONTENT_CHANGED فقط).
 * - تربط كل نداء برقم جلسة القراءة الحالية لمنع تنفيذ أي رد نداء قديم
 *   بعد التوقف أو التناوب السريع.
 * - تضبط سرعة التمرير بمعدل تقدم النطق ولا تبدأ تمرير الصفحة التالية
 *   والصوت السابق لا يزال في الطابور.
 */
object ContinuousReader {

    /** مصدر القراءة والتمرير والإعلان — يُنفّذه موجّه الاختصارات. */
    interface Source {
        fun readTexts(): List<String>
        fun scrollForward(): Boolean
        fun currentPackage(): String?
        fun announce(text: String)
        fun isSpeaking(): Boolean = false
    }

    private const val TAG = "NATEQ_A11Y"
    private const val PAGE_WAIT_MS = 1500L
    private const val SPEECH_POLL_MS = 250L
    private const val MAX_SPEECH_WAIT_MS = 15_000L
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

    private var currentSessionId = 0L
    private var scrollGeneration = 0L
    private var processedGeneration = 0L

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
        scrollGeneration = 0L
        processedGeneration = 0L
        val sessionId = ++currentSessionId
        running = true
        ensureThread()
        val post = handler?.post { readInitial(sessionId) }
        if (post == null) stop(null)
    }

    private fun ensureThread() {
        if (handlerThread != null) return
        val thread = HandlerThread(THREAD_NAME)
        thread.start()
        handlerThread = thread
        handler = Handler(thread.looper)
    }

    private fun readInitial(sessionId: Long) {
        if (sessionId != currentSessionId || !running) return
        val src = source ?: return finish(null)
        val texts = src.readTexts()
        if (texts.isEmpty()) return finish(ANNOUNCE_NO_TEXT)
        src.announce(texts.joinToString(SEPARATOR))
        tracker.firstPage(texts)
        pages = 1
        scheduleNextScrollWhenQuiet(sessionId, SystemClock.uptimeMillis())
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
        val sessionId = currentSessionId
        val gen = scrollGeneration
        if (gen > processedGeneration) {
            handler?.removeCallbacksAndMessages(waitToken)
            handler?.postAtTime(
                { readNextForGeneration(sessionId, gen) },
                waitToken,
                SystemClock.uptimeMillis()
            )
        }
    }

    private fun scheduleNextScrollWhenQuiet(
        sessionId: Long,
        waitStart: Long
    ) {
        if (sessionId != currentSessionId || !running) return
        val src = source ?: return finish(null)
        val elapsed = SystemClock.uptimeMillis() - waitStart
        if (src.isSpeaking() && elapsed < MAX_SPEECH_WAIT_MS) {
            handler?.postDelayed({
                scheduleNextScrollWhenQuiet(sessionId, waitStart)
            }, SPEECH_POLL_MS)
            return
        }
        scrollNext(sessionId)
    }

    private fun scrollNext(sessionId: Long) {
        if (sessionId != currentSessionId || !running) return
        val src = source ?: return finish(null)
        if (pages >= MAX_PAGES) return finish(ANNOUNCE_MAX_PAGES)
        if (!src.scrollForward()) return finish(ANNOUNCE_END)
        val gen = ++scrollGeneration
        handler?.postAtTime(
            { readNextForGeneration(sessionId, gen) },
            waitToken,
            SystemClock.uptimeMillis() + PAGE_WAIT_MS
        )
    }

    private fun readNextForGeneration(sessionId: Long, gen: Long) {
        if (sessionId != currentSessionId || !running) return
        if (gen != scrollGeneration || gen == processedGeneration) return
        processedGeneration = gen
        handler?.removeCallbacksAndMessages(waitToken)

        val src = source ?: return finish(null)
        val result = tracker.nextPage(src.readTexts())
        if (result.isEnd) return finish(ANNOUNCE_END)
        if (result.freshTexts.isNotEmpty()) {
            src.announce(result.freshTexts.joinToString(SEPARATOR))
        }
        pages++
        scheduleNextScrollWhenQuiet(sessionId, SystemClock.uptimeMillis())
    }

    /** إيقاف فوري بلا إعلان (إطفاء الشاشة، مكالمة، تغيّر حالة مؤقتة). */
    fun stopSilently() = stop(null)

    /** يوقف الجلسة ويعيد المورد؛ وإن مُرّرت رسالةٌ أُعلنت. */
    fun stop(message: String?) {
        if (!running) return
        running = false
        currentSessionId++
        handler?.removeCallbacksAndMessages(null)
        val src = source
        source = null
        releaseThread()
        if (message != null) {
            runCatching { src?.announce(message) }
                .onFailure {
                    Log.w(TAG, "continuous stop announce failed", it)
                }
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
    private const val ANNOUNCE_MAX_PAGES =
        "انتهت القراءة، بلغت الحدّ الأقصى"
    private const val ANNOUNCE_APP_CHANGED =
        "توقفت القراءة، تغيّر التطبيق"
    private const val ANNOUNCE_STOPPED = "توقفت القراءة المستمرة"
}
