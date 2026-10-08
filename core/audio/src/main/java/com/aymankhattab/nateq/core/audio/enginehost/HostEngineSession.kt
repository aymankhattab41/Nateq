package com.aymankhattab.nateq.core.audio.enginehost

import android.os.ParcelFileDescriptor
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** طلب نطق واحد يمرّ من العملية الأمامية إلى :engine_host. */
internal data class SpeakRequest(
    val voiceId: String,
    val text: String,
    val rate: Float,
    val pitch: Float,
    val volume: Float
)

/**
 * ع٢٩-5: عقد جلسة محرك داخل المضيف.
 *
 * [init] و[speak] يلمسان المكتبة الأصلية حصراً داخل `:engine_host`؛
 * [speak] كوروتين فتُشغَّل عبر `runBlocking` على خيط المزوّد (لا على
 * خيط المُستدعي).
 */
internal interface HostEngineSession {

    /** تهيئة الجلسة ببياناتها؛ فشلها سببٌ معنون لا استثناء خام. */
    fun init(dataDir: String): HostResult

    /** توليد نصّ كامل إلى [sink] (صيغة ثم شرائح ثم رجوع بالنتيجة). */
    suspend fun speak(
        req: SpeakRequest,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ): HostResult

    /**
     * إيقاف التوليد الجاري عند أول فرصة — استجابةً لطلب
     * [EngineHostClient.cancel].
     */
    fun stop()

    /** تحرير كل الموارد الأصلية للجلسة. */
    fun shutdown()
}

/**
 * ع٢٩-5: محول الأنبوب في المضيف — صيغة واحدة ثم شرائح PCM ثم
 * نهاية بحالة. غياب الصيغة قبل أول شريحة خرقٌ للبروتوكول يرفضه
 * [writePcm] فلا يصل PCM مشوّهاً إلى العميل.
 */
internal class PcmSink(
    pfd: ParcelFileDescriptor
) : Closeable {

    private val pfdRef = pfd
    private val out = DataOutputStream(
        BufferedOutputStream(FileOutputStream(pfd.fileDescriptor))
    )
    private val closed = AtomicBoolean(false)

    @Volatile
    private var headerWritten = false

    fun writeHeader(sampleRate: Int, channels: Int) {
        if (headerWritten) return
        HostProtocol.writeFormat(out, sampleRate, channels)
        headerWritten = true
    }

    fun writePcm(bytes: ByteArray, length: Int) {
        if (!headerWritten) {
            throw IOException("PCM قبل إطار الصيغة")
        }
        HostProtocol.writePcm(out, bytes, length)
    }

    /**
     * كتابة نهاية الطلب. إن مات العميل (أنبوب مكسور) تُبتلع
     * ال exception هنا: النهاية رفاهية عنده، والـEOF نفسه يصله.
     */
    fun end(status: Int, code: String, message: String) {
        runCatching {
            HostProtocol.writeEnd(out, status, code, message)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { out.flush() }
        runCatching { out.close() }
        runCatching { pfdRef.close() }
    }
}
