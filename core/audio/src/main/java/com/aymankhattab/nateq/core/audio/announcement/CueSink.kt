package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * واجهة تشغيل موجة PCM محلية — يحقن في الاختبارات بنسخة وهمية.
 */
internal interface CueSink {

    /**
     * تشغيل نغمة.
     * @param pcm موجة 16-bit جاهزة
     * @param sampleRate معدّل العيّنات
     * @param volume معامل الصوت 0.0..1.0
     * @param cueKey هوية النغمة الدلالية (نوعها واسم صوتها) — يُضمن تفرّد
     *   كاش التخزين حتى لا تتصادم موجتان متماثلتا الطول لنغمتين مختلفتين
     *   (كانت «digital_chime» و«BATTERY_FULL» تتشاركان مفتاح
     *   «44100|28665» فتُشغَّل نغمة الساعة عند اكتمال الشحن!)
     * @param onDone نداء عند انتهاء التشغيل أو فشله
     */
    fun play(
        pcm: ShortArray,
        sampleRate: Int,
        volume: Float,
        cueKey: String,
        onDone: (Boolean) -> Unit
    )

    /** إيقاف أي تشغيل جارٍ فوراً. */
    fun stop()

    /** تحرير الموارد (يُستدعى عند إغلاق العملية). */
    fun release()
}

/**
 * سمات الصوت الموحّدة للمؤثرات (Audio Cues): مُوجّهة لمسار الإتاحة
 * (أدوات إمكانية الوصول، مثل TalkBack) ونوع نغمة إعلامية — يُستخدم
 * من مُنفّذي [CueSink] كلَيهما. كائن داخلي ليُفحص في الاختبارات.
 */
internal object CueAudioAttributes {
    val forCues: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}

/**
 * مُنفّذ عبر AudioTrack في الوضع الثابت (MODE_STATIC):
 * يكتب PCM كاملاً ثم يُشغّل ويُعلم بالاكتمال.
 *
 * selectedItem(s) وهمية: يُنشئ AudioTrack واحداً ويعيد استخدامه
 * (نغمة واحدة في أي وقت). يلبي تصميم AudioCuePlayer.
 */
internal class AudioTrackCueSink : CueSink {

    companion object {
        private const val TAG = "NATEQ_CUE"
    }

    private var track: AudioTrack? = null
    private var handler: Handler? = null
    private val completed = AtomicBoolean(false)

    override fun play(
        pcm: ShortArray,
        sampleRate: Int,
        volume: Float,
        cueKey: String,
        onDone: (Boolean) -> Unit
    ) {
        stop()
        completed.set(false)
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, pcm.size * 2)
            val attrs = CueAudioAttributes.forCues
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track = t
            t.setVolume(volume)
            t.write(pcm, 0, pcm.size)
            t.setNotificationMarkerPosition(pcm.size)
            t.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(at: AudioTrack?) {
                        if (completed.compareAndSet(false, true)) {
                            handler?.post { onDone(true) }
                        }
                    }
                    override fun onPeriodicNotification(at: AudioTrack?) {}
                }
            )
            t.play()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack play failed", t)
            if (completed.compareAndSet(false, true)) {
                onDone(false)
            }
        }
    }

    override fun stop() {
        try {
            track?.stop()
        } catch (_: IllegalStateException) {
        }
        track?.release()
        track = null
    }

    override fun release() {
        stop()
    }

    fun setHandler(handler: Handler) {
        this.handler = handler
    }
}

/**
 * مُولّد ملفات WAV مؤقتة + SoundPool:
 * يكتب PCM في ملف مؤقت في cacheDir ثم يحمّله عبر SoundPool.
 *
 * هذا الأسلوب يُحقق شرط "maxStreams 2" بدقة. الاكتمال يُستنتج بمؤقّت
 * يطابق مدة الموجة (SoundPool لا يوفّر معاودة اكتمال للدفق).
 */
internal class SoundPoolCueSink(
    private val context: Context,
    private val handler: Handler
) : CueSink {

    companion object {
        private const val TAG = "NATEQ_CUE_SP"
        private const val DONE_MARGIN_MS = 50L
    }

    private val soundPool: android.media.SoundPool
    private val cacheDir: File = context.cacheDir
    private val soundIds = HashMap<String, Int>()
    private val pendingLoad = HashMap<Int, () -> Unit>()
    private var activeStreamId = 0
    private var activeOnDone: ((Boolean) -> Unit)? = null
    private var completionRunnable: Runnable? = null
    private val activeGuard = AtomicBoolean(false)
    private val loaded = HashSet<String>()

    init {
        soundPool = android.media.SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(CueAudioAttributes.forCues)
            .build()
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            val pending = pendingLoad.remove(sampleId)
            if (status == 0 && pending != null) {
                pending()
            } else if (status != 0) {
                Log.w(TAG, "SoundPool load failed: status=$status")
            }
        }
    }

    override fun play(
        pcm: ShortArray,
        sampleRate: Int,
        volume: Float,
        cueKey: String,
        onDone: (Boolean) -> Unit
    ) {
        stop()
        val key = "$cueKey|$sampleRate|${pcm.size}"
        val durationMs = (pcm.size * 1000L / sampleRate).toInt()
            .coerceAtLeast(1)
        activeGuard.set(true)
        activeOnDone = onDone
        val soundId = soundIds[key]
        if (soundId != null && loaded.contains(key)) {
            startStream(soundId, volume, durationMs)
            return
        }
        try {
            val wavFile = writeWav(pcm, sampleRate, key)
            @Suppress("DEPRECATION")
            val sid = soundPool.load(wavFile.absolutePath, 1)
            if (sid == 0) {
                finish(false); return
            }
            soundIds[key] = sid
            pendingLoad[sid] = {
                loaded.add(key)
                startStream(sid, volume, durationMs)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "load failed", t)
            finish(false)
        }
    }

    override fun stop() {
        completionRunnable?.let { handler.removeCallbacks(it) }
        completionRunnable = null
        if (activeStreamId != 0) {
            try { soundPool.stop(activeStreamId) } catch (_: Throwable) {}
            activeStreamId = 0
        }
        if (activeGuard.compareAndSet(true, false)) {
            val callback = activeOnDone
            activeOnDone = null
            handler.post { callback?.invoke(false) }
        }
    }

    override fun release() {
        stop()
        soundPool.release()
    }

    private fun startStream(soundId: Int, volume: Float, durationMs: Int) {
        val streamId = soundPool.play(
            soundId, volume, volume, 1, 0, 1f
        )
        if (streamId == 0) {
            finish(false)
        } else {
            activeStreamId = streamId
            scheduleDone(durationMs)
        }
    }

    private fun scheduleDone(durationMs: Int) {
        val runnable = Runnable { finish(true) }
        completionRunnable = runnable
        handler.postDelayed(runnable, durationMs.toLong() + DONE_MARGIN_MS)
    }

    private fun finish(ok: Boolean) {
        completionRunnable?.let { handler.removeCallbacks(it) }
        completionRunnable = null
        if (activeGuard.compareAndSet(true, false)) {
            val callback = activeOnDone
            activeOnDone = null
            handler.post { callback?.invoke(ok) }
        }
    }

    /** يكتب موجّة PCM في ملف WAV مؤقت داخل cacheDir — داخلي لفحصه في
     *  الاختبارات (بنية الرأس/الحجم تضمن توافق SoundPool). */
    internal fun writeWav(
        pcm: ShortArray,
        sampleRate: Int,
        tag: String
    ): File {
        val file = File(cacheDir, "cue_${tag.hashCode()}.wav")
        FileOutputStream(file).use { fos ->
            val dataSize = pcm.size * 2
            val buf = ByteBuffer.allocate(44 + dataSize)
                .order(ByteOrder.LITTLE_ENDIAN)
            // RIFF header
            buf.put("RIFF".toByteArray())
            buf.putInt(36 + dataSize)
            buf.put("WAVE".toByteArray())
            // fmt chunk
            buf.put("fmt ".toByteArray())
            buf.putInt(16) // PCM header size
            buf.putShort(1) // PCM format
            buf.putShort(1) // mono
            buf.putInt(sampleRate)
            buf.putInt(sampleRate * 2) // byte rate
            buf.putShort(2) // block align
            buf.putShort(16) // bits per sample
            // data chunk
            buf.put("data".toByteArray())
            buf.putInt(dataSize)
            for (s in pcm) {
                buf.putShort(s)
            }
            fos.write(buf.array())
        }
        return file
    }
}
