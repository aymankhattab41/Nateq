package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * سمات الصوت الموحّدة للمؤثرات (Audio Cues): مُوجّهة لمسار الوسائط
 * (USAGE_MEDIA) لمنع واجهات الأجهزة (مثل سامسونج) من خفض صوت الوسائط
 * الأخرى (Audio Ducking) تلقائياً، ونوع نغمة إعلامية. ثابتة على MEDIA
 * لكل الأحداث (نفس قناة البطارية) بلا شرط بمفتاحٍ أو بحالة الموسيقى.
 */
internal object CueAudioAttributes {
    val forCue: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun attributesFor(): AudioAttributes {
        return forCue
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
        private const val DONE_MARGIN_MS = 0L
    }

    private val soundPool: android.media.SoundPool
    private val cacheDir: File = context.cacheDir
    private val soundIds =
        java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val pendingLoad =
        java.util.concurrent.ConcurrentHashMap<Int, () -> Unit>()
    private val pendingFiles =
        java.util.concurrent.ConcurrentHashMap<Int, File>()
    private var activeStreamId = 0
    private var activeOnDone: ((Boolean) -> Unit)? = null
    private var completionRunnable: Runnable? = null
    private val activeGuard = AtomicBoolean(false)
    private val loaded =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        soundPool = android.media.SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(CueAudioAttributes.attributesFor())
            .build()
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            val wavFile = pendingFiles.remove(sampleId)
            wavFile?.let { runCatching { it.delete() } }
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
                runCatching { wavFile.delete() }
                finish(false); return
            }
            pendingFiles[sid] = wavFile
            soundIds[key] = sid
            // إن صدر stop() (أو play() أحدث ألغاه) قبل اكتمال تحميل
            // الدفعة نتخلى عن التشغيل: البوابة تُسقط الرجلَ المتأخر
            // (سباق بين خيط التحميل وخيط الإيقاف) فلا نغمةٌ بعد الصمت.
            pendingLoad[sid] = {
                if (activeGuard.get()) {
                    loaded.add(key)
                    startStream(sid, volume, durationMs)
                }
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
        // نداءات التحميل المعلَّقة كانت تُنفَّذ مهما حدث بعد stop/play أحدث:
        // فتُشغَّل نغمةً قديمة ميتة فوق الإيقاف (بند 2.10) أو تصطدم بقيمة
        // غيرها في soundPool.play أو تنتظر id من دفقٍ مُطلَق. نسكبها كلها
        // فلا تنطلق نغمةً لن تُسمع؛ النداء الذي انفصل لحظة الإلغاء تحرسه
        // بوابة [activeGuard] في [play] فلا يشغّل بعد الإيقاف.
        pendingLoad.clear()
        pendingFiles.values.forEach { file ->
            runCatching { file.delete() }
        }
        pendingFiles.clear()
        if (activeGuard.compareAndSet(true, false)) {
            val callback = activeOnDone
            activeOnDone = null
            handler.post { callback?.invoke(false) }
        }
    }

    override fun release() {
        stop()
        pendingFiles.values.forEach { file ->
            runCatching { file.delete() }
        }
        pendingFiles.clear()
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
        if (activeStreamId != 0) {
            try { soundPool.stop(activeStreamId) } catch (_: Throwable) {}
            activeStreamId = 0
        }
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
