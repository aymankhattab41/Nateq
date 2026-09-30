package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.aymankhattab.nateq.core.audio.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * مشغّل المؤثرات الصوتية (Audio Cues) — مثيل مشترك لكل عملية.
 *
 * يُشغّل نغمة واحدة في أي وقت؛ النغمة الأحدث توقف سابقتها.
 * يدعم رنات CueSynth المولّدة، ورنات الملفات المخصصة عبر MediaPlayer.
 * يحقن [CueSink] في الاختبارات ((fake sink)).
 *
 * يُستخدم من:
 * - [AnnouncementSpeaker] عبر `speak(..., cue)` — المؤثر يسبق النطق.
 * - [BatteryAnnouncementReceiver] في الوضع "مؤثر فقط" (mode=2).
 *
 * التسلسل: المؤثر يُشغَّل داخل دورة التركيز المملوكة للمتحدث
 * فلا يطلب تركيزاً منفصلاً.
 */
class AudioCuePlayer private constructor(
    private val context: Context?,
    private val sink: CueSink?,
    private val synth: CueSynth,
    private val handler: Handler,
    private val background: java.util.concurrent.Executor
) {

    companion object {
        private const val TAG = "NATEQ_CUE"
        private const val CUE_SAFETY_MARGIN_MS = 600
        private const val CUSTOM_CHIME_MAX_TIMEOUT_MS = 15000L

        @Volatile
        private var shared: AudioCuePlayer? = null

        /** منفّذ خلفي واحد للعملية كلها: التخليق وكتابة الملف وmp.prepare()
         *  خارج خيط الواجهة، ويتسلسل إيقاع نغمةٍ واحدة في كل لحظة.
         *  خيوطه وصّاية (daemon) فلا تحجز خروجَ العملية. */
        private fun bgExecutor(): java.util.concurrent.Executor =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "nateq-cue").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            }

        @JvmStatic
        fun getInstance(context: Context): AudioCuePlayer {
            shared?.let { return it }
            return synchronized(this) {
                shared?.let { return it }
                val appContext = context.applicationContext
                runCatching {
                    AudioCuePlayer(
                        context = appContext,
                        sink = SoundPoolCueSink(
                            appContext,
                            Handler(Looper.getMainLooper())
                        ),
                        synth = CueSynth,
                        handler = Handler(Looper.getMainLooper()),
                        background = bgExecutor()
                    )
                }.getOrElse { t ->
                    Log.w(TAG, "SoundPool cue player failed", t)
                    AudioCuePlayer(
                        context = appContext,
                        sink = null,
                        synth = CueSynth,
                        handler = Handler(Looper.getMainLooper()),
                        background = bgExecutor()
                    )
                }.also { shared = it }
            }
        }

        /** إنشاء مثيل قابل للاختبار بسلك وهمي — منفّذ فوري (inline) إلا إن
         *  حُقن آخر، فيبقى سلوك النطق التزامنياً كما تعتمده الاختبارات. */
        internal fun forTesting(
            sink: CueSink,
            synth: CueSynth = CueSynth,
            handler: Handler = Handler(Looper.getMainLooper()),
            context: Context? = null,
            background: java.util.concurrent.Executor =
                java.util.concurrent.Executor { it.run() }
        ): AudioCuePlayer =
            AudioCuePlayer(context, sink, synth, handler, background)

        /** استبدال المثيل المشترك بنسخة اختبار (سلك وهمي) بين دورات
         *  الاختبار — لا يُستخدم في الإنتاج إطلاقاً. */
        internal fun replaceSharedForTesting(player: AudioCuePlayer?) {
            shared = player
        }
    }

    private val playEpoch = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile
    private var timeoutRunnable: Runnable? = null

    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null

    /**
     * تشغيل مؤثر صوتي.
     * @param onDone يُستدعى مرة واحدة فقط عند الانتهاء أو الفشل
     *   أو انتهاء المهلة.
     */
    fun play(cue: AudioCue, onDone: (Boolean) -> Unit) {
        // حقبة الإيقاف: أي play() يُبطل نغمةً أقدم قيد التخليق على
        // المنفّذ الخلفي (stop()/play أحدث) — لا نغمةٌ بعد صمتٍ ولا
        // استدعاءُ onDone لدورةٍ أُجهضت.
        val epoch = playEpoch.incrementAndGet()
        stopInternal()

        val guard = AtomicBoolean(false)
        val finishOnce: (Boolean) -> Unit = { ok ->
            if (epoch == playEpoch.get() && guard.compareAndSet(false, true)) {
                onDone(ok)
            }
        }
        val cancelled: () -> Boolean = { epoch != playEpoch.get() }

        // **بند الأداء:** التخليق (CueSynth) وكتابة ملف WAV (SoundPoolCueSink)
        // وmp.prepare() لكلُّه عبءٌ ثقيل كان يجري على خيط الواجهة في تعليقٍ
        // ظاهرٍ عند كل إشارة — ننقله كله إلى المنفّذ الخلفي، والنتائج تُعاد
        // إلى [handler] (الرئيسي) كما كانت. في الاختبارات (منفّذ فوري) يبقى
        // السلوك التزامنياً نفسه بالضبط.
        background.execute {
            if (cancelled()) return@execute

            // مسار النغمة المخصصة عبر MediaPlayer (بند 3)
            val customUriStr = cue.customUri
            val ctx = context
            if (!customUriStr.isNullOrBlank() && ctx != null) {
                val uri = Uri.parse(customUriStr)
                val canRead = runCatching {
                    ctx.contentResolver.openAssetFileDescriptor(uri, "r")
                        ?.use { true } ?: false
                }.getOrDefault(false)
                if (canRead && playCustomMediaUri(
                        uri, cue.volume, epoch, finishOnce
                    )
                ) {
                    return@execute
                }
                if (cancelled()) return@execute
                // فشلت القراءة أو سُحبت الصلاحية أو تعثر التحضير —
                // إعلان التنبيه والرجوع للافتراضي.
                notifyCustomChimeUnavailable(ctx)
            }

            if (sink == null) {
                handler.post { finishOnce(false) }
                return@execute
            }

            val pcm = try {
                synth.synthesize(cue)
            } catch (t: Throwable) {
                Log.w(TAG, "synth failed", t)
                handler.post { finishOnce(false) }
                return@execute
            }

            val durationMs = synth.durationMs(cue)
            if (cancelled()) return@execute
            val timeout = Runnable {
                sink.stop()
                finishOnce(false)
            }
            timeoutRunnable = timeout
            handler.postDelayed(
                timeout,
                durationMs.toLong() + CUE_SAFETY_MARGIN_MS
            )

            val cueKey = "${cue.type}|${cue.soundName ?: ""}"
            sink.play(
                pcm,
                CueSynth.SAMPLE_RATE,
                cue.volume.coerceIn(0f, 1f),
                cueKey
            ) { success ->
                handler.post {
                    if (epoch != playEpoch.get()) return@post
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    timeoutRunnable = null
                    finishOnce(success)
                }
            }
        }
    }

    private fun playCustomMediaUri(
        uri: Uri,
        volume: Float,
        epoch: Long,
        finishOnce: (Boolean) -> Unit
    ): Boolean {
        return try {
            val ctx = context ?: return false
            val mp = MediaPlayer()
            activeMediaPlayer = mp
            mp.setAudioAttributes(
                CueAudioAttributes.attributesFor()
            )
            mp.setDataSource(ctx, uri)
            val clampedVol = volume.coerceIn(0f, 1f)
            mp.setVolume(clampedVol, clampedVol)

            val timeout = Runnable {
                if (epoch == playEpoch.get()) {
                    stopCustomMedia()
                    finishOnce(false)
                }
            }
            timeoutRunnable = timeout

            mp.setOnCompletionListener {
                handler.post {
                    if (epoch != playEpoch.get()) return@post
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    timeoutRunnable = null
                    stopCustomMedia()
                    finishOnce(true)
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                handler.post {
                    if (epoch != playEpoch.get()) return@post
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    timeoutRunnable = null
                    stopCustomMedia()
                    finishOnce(false)
                }
                true
            }
            mp.setOnPreparedListener { player ->
                handler.post {
                    if (epoch != playEpoch.get()) return@post
                    val dur = runCatching { player.duration }
                        .getOrDefault(0)
                    val maxTimeoutMs = if (dur > 0) {
                        dur.toLong() + CUE_SAFETY_MARGIN_MS
                    } else {
                        CUSTOM_CHIME_MAX_TIMEOUT_MS
                    }
                    handler.postDelayed(timeout, maxTimeoutMs)
                    runCatching { player.start() }
                }
            }
            mp.prepareAsync()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "playCustomMediaUri failed", t)
            stopCustomMedia()
            false
        }
    }

    private fun notifyCustomChimeUnavailable(context: Context) {
        val msg = try {
            context.getString(R.string.custom_chime_unavailable_fallback)
        } catch (_: Throwable) {
            "تعذّر تشغيل ملف النغمة المخصّص، تم الرجوع للرنة الافتراضية"
        }
        handler.post {
            runCatching {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopCustomMedia() {
        val mp = activeMediaPlayer
        activeMediaPlayer = null
        if (mp != null) {
            runCatching {
                if (mp.isPlaying) mp.stop()
            }
            runCatching { mp.release() }
        }
    }

    /** إيقاف أي مؤثر جارٍ. لا يُستدعى onDone. */
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        stopCustomMedia()
        sink?.stop()
    }

    /** تحرير موارد الـ Sink عند إغلاق العملية. */
    fun release() {
        stopInternal()
        sink?.release()
    }
}
