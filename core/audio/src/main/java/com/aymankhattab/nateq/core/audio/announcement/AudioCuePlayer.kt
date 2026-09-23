package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
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
    private val handler: Handler
) {

    companion object {
        private const val TAG = "NATEQ_CUE"
        private const val CUE_SAFETY_MARGIN_MS = 600
        private const val CUSTOM_CHIME_MAX_TIMEOUT_MS = 15000L

        @Volatile
        private var shared: AudioCuePlayer? = null

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
                        handler = Handler(Looper.getMainLooper())
                    )
                }.getOrElse { t ->
                    Log.w(TAG, "SoundPool cue player failed", t)
                    AudioCuePlayer(
                        context = appContext,
                        sink = null,
                        synth = CueSynth,
                        handler = Handler(Looper.getMainLooper())
                    )
                }.also { shared = it }
            }
        }

        /** إنشاء مثيل قابل للاختبار بسلك وهمي. */
        internal fun forTesting(
            sink: CueSink,
            synth: CueSynth = CueSynth,
            handler: Handler = Handler(Looper.getMainLooper()),
            context: Context? = null
        ): AudioCuePlayer = AudioCuePlayer(context, sink, synth, handler)

        /** استبدال المثيل المشترك بنسخة اختبار (سلك وهمي) بين دورات
         *  الاختبار — لا يُستخدم في الإنتاج إطلاقاً. */
        internal fun replaceSharedForTesting(player: AudioCuePlayer?) {
            shared = player
        }
    }

    private var timeoutRunnable: Runnable? = null
    private var activeMediaPlayer: MediaPlayer? = null

    /**
     * تشغيل مؤثر صوتي.
     * @param onDone يُستدعى مرة واحدة فقط عند الانتهاء أو الفشل
     *   أو انتهاء المهلة.
     */
    fun play(cue: AudioCue, onDone: (Boolean) -> Unit) {
        stopInternal()

        val guard = AtomicBoolean(false)
        val finishOnce: (Boolean) -> Unit = { ok ->
            if (guard.compareAndSet(false, true)) {
                onDone(ok)
            }
        }

        // مسار النغمة المخصصة عبر MediaPlayer (بند 3)
        val customUriStr = cue.customUri
        val ctx = context
        if (!customUriStr.isNullOrBlank() && ctx != null) {
            val uri = Uri.parse(customUriStr)
            val canRead = runCatching {
                ctx.contentResolver.openAssetFileDescriptor(
                    uri, "r"
                )?.use { true } ?: false
            }.getOrDefault(false)

            if (canRead) {
                val played = playCustomMediaUri(
                    uri = uri,
                    volume = cue.volume,
                    finishOnce = finishOnce
                )
                if (played) return
            }

            // فشلت القراءة أو سُحبت الصلاحية — إعلان التنبيه والرجوع للافتراضي
            notifyCustomChimeUnavailable(ctx)
        }

        if (sink == null) {
            finishOnce(false)
            return
        }

        val pcm = try {
            synth.synthesize(cue)
        } catch (t: Throwable) {
            Log.w(TAG, "synth failed", t)
            finishOnce(false)
            return
        }

        val durationMs = synth.durationMs(cue)
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
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                timeoutRunnable = null
                finishOnce(success)
            }
        }
    }

    private fun playCustomMediaUri(
        uri: Uri,
        volume: Float,
        finishOnce: (Boolean) -> Unit
    ): Boolean {
        return try {
            val ctx = context ?: return false
            val mp = MediaPlayer()
            activeMediaPlayer = mp
            mp.setAudioAttributes(CueAudioAttributes.forCue)
            mp.setDataSource(ctx, uri)
            val clampedVol = volume.coerceIn(0f, 1f)
            mp.setVolume(clampedVol, clampedVol)

            val timeout = Runnable {
                stopCustomMedia()
                finishOnce(false)
            }
            timeoutRunnable = timeout

            mp.setOnCompletionListener {
                handler.post {
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    timeoutRunnable = null
                    stopCustomMedia()
                    finishOnce(true)
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                handler.post {
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    timeoutRunnable = null
                    stopCustomMedia()
                    finishOnce(false)
                }
                true
            }
            mp.prepare()
            val dur = mp.duration
            val maxTimeoutMs = if (dur > 0) {
                dur.toLong() + CUE_SAFETY_MARGIN_MS
            } else {
                CUSTOM_CHIME_MAX_TIMEOUT_MS
            }
            handler.postDelayed(timeout, maxTimeoutMs)
            mp.start()
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
            runCatching {
                val am = context.getSystemService(
                    Context.ACCESSIBILITY_SERVICE
                ) as? AccessibilityManager
                if (am?.isEnabled == true) {
                    val event = AccessibilityEvent.obtain(
                        AccessibilityEvent.TYPE_ANNOUNCEMENT
                    )
                    event.text.add(msg)
                    event.className = javaClass.name
                    event.packageName = context.packageName
                    am.sendAccessibilityEvent(event)
                }
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
