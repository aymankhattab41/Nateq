package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * مشغّل المؤثرات الصوتية (Audio Cues) — مثيل مشترك لكل عملية.
 *
 * يُشغّل نغمة واحدة في أي وقت؛ النغمة الأحدث توقف سابقتها.
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
    private val sink: CueSink?,
    private val synth: CueSynth,
    private val handler: Handler
) {

    companion object {
        private const val TAG = "NATEQ_CUE"
        private const val CUE_SAFETY_MARGIN_MS = 600

        @Volatile
        private var shared: AudioCuePlayer? = null

        @JvmStatic
        fun getInstance(context: Context): AudioCuePlayer {
            shared?.let { return it }
            return synchronized(this) {
                shared?.let { return it }
                runCatching {
                    AudioCuePlayer(
                        sink = SoundPoolCueSink(
                            context.applicationContext,
                            Handler(Looper.getMainLooper())
                        ),
                        synth = CueSynth,
                        handler = Handler(Looper.getMainLooper())
                    )
                }.getOrElse { t ->
                    // فشل بناء سمع الخام (SoundPool) — صوتٌ صامت يحفظ الدورة:
                    // كل استدعاءٍ كان يبني مثيلاً جديداً بلا تعيين `shared`
                    // (كسر Singleton) فتُستنزف مسارات الصوت حتى
                    // `AudioTrack::createTrack() failed`.
                    Log.w(TAG, "SoundPool cue player failed", t)
                    AudioCuePlayer(
                        null,
                        CueSynth,
                        Handler(Looper.getMainLooper())
                    )
                }.also { shared = it }
            }
        }

        /** إنشاء مثيل قابل للاختبار بسلك وهمي. */
        internal fun forTesting(
            sink: CueSink,
            synth: CueSynth = CueSynth,
            handler: Handler = Handler(Looper.getMainLooper())
        ): AudioCuePlayer = AudioCuePlayer(sink, synth, handler)

        /** استبدال المثيل المشترك بنسخة اختبار (سلك وهمي) بين دورات
         *  الاختبار — لا يُستخدم في الإنتاج إطلاقاً. */
        internal fun replaceSharedForTesting(player: AudioCuePlayer?) {
            shared = player
        }
    }

    private var timeoutRunnable: Runnable? = null

    /**
     * تشغيل مؤثر صوتي.
     * @param onDone يُستدعى مرة واحدة فقط عند الانتهاء أو الفشل
     *   أو انتهاء المهلة.
     */
    fun play(cue: AudioCue, onDone: (Boolean) -> Unit) {
        if (sink == null) {
            runCatching { onDone(false) }
            return
        }
        stopInternal()

        val guard = AtomicBoolean(false)
        val finishOnce: (Boolean) -> Unit = { ok ->
            if (guard.compareAndSet(false, true)) {
                onDone(ok)
            }
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

    /** إيقاف أي مؤثر جارٍ. لا يُستدعى onDone. */
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        sink?.stop()
    }

    /** تحرير موارد الـ Sink عند إغلاق العملية. */
    fun release() {
        stopInternal()
        sink?.release()
    }
}
