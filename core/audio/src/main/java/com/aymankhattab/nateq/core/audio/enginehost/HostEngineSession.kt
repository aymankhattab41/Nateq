package com.aymankhattab.nateq.core.audio.enginehost

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
 *
 * **الكتابة متزامنة:** شريحةُ الصوت تأتي من خيط ردّ المحرّك، والنبضةُ
 * والنهاية من خيط الحارس/الخدمة؛ بلا قفلٍ تتداخل الإطارات فيفسد
 * الأنبوب. والنهاية **أحادية التنفيذ** كي لا يُكتب إطارا نهاية
 * لطلبٍ واحد (العميل يتوقّف عند الأول فيرى نتيجةً غير الحقيقية).
 */
internal class PcmSink private constructor(
    private val out: DataOutputStream,
    private val pfdRef: ParcelFileDescriptor?
) : Closeable {

    /** أنبوبٌ حقيقي بين المضيف والعميل. */
    internal constructor(pfd: ParcelFileDescriptor) : this(
        DataOutputStream(
            BufferedOutputStream(FileOutputStream(pfd.fileDescriptor))
        ),
        pfd
    )

    /** تدفّقٌ في الذاكرة — للاختبارات فقط (بلا واصفٍ يُغلق). */
    @VisibleForTesting
    internal constructor(out: DataOutputStream) : this(out, null)

    private val closed = AtomicBoolean(false)
    private val endWritten = AtomicBoolean(false)

    /** زمن (uptime) آخر تقدّم — تقيس به سياسة الحارس الخمول. */
    private val lastProgressAt = AtomicLong(SystemClock.uptimeMillis())

    @Volatile
    private var headerWritten = false

    /** هل يجري الآن نداءٌ أصليّ متزامن (لا يبثّ إطاراتٍ في أثنائه)؟ */
    @Volatile
    private var inNativeCall = false
    private var nativeStartedAt = 0L
    private var nativeCapMs = 0L

    fun writeHeader(sampleRate: Int, channels: Int) {
        synchronized(this) {
            if (headerWritten) return
            HostProtocol.writeFormat(out, sampleRate, channels)
            headerWritten = true
        }
        noteProgress()
    }

    fun writePcm(bytes: ByteArray, length: Int) {
        synchronized(this) {
            if (!headerWritten) {
                throw IOException("PCM قبل إطار الصيغة")
            }
            HostProtocol.writePcm(out, bytes, length)
        }
        noteProgress()
    }

    /**
     * نبضةُ تقدّم أثناء عملٍ أصليّ متزامن. تُكتب إطاراً فارغاً في الأنبوب
     * فيراه العميل تقدّماً؛ وإلا عدّ صمت المحرك خمولاً فقطع نطقاً سليماً.
     * تُرسَل قبل الترويسة أيضاً: الطلب المنتظر في الطابور ليس خمولاً،
     * والعميل يعالج النبضة مستقلةً عن الصيغة.
     */
    fun ping() {
        synchronized(this) {
            HostProtocol.writePing(out)
        }
        noteProgress()
    }

    /** زمن (uptime) آخر ترويسة أو شريحة PCM كُتبت فعلاً. */
    fun lastProgressAtMs(): Long = lastProgressAt.get()

    /** إعادة ضبط عدّاد الخمول: انتظارُ الطابور ليس خمولاً للمحرك. */
    fun noteProgress() {
        lastProgressAt.set(SystemClock.uptimeMillis())
    }

    /**
     * فتح نافذة نداءٍ أصليّ متزامن مدّتها [capMs] كحدٍّ أقصى. تُرسَل
     * النبضات ما دامت النافذة مفتوحة، فإذا انقضى السقف توقّفت فانقضى
     * الحارس على النداء المتعلّق فعلاً.
     */
    fun enterNativeCall(capMs: Long) {
        nativeStartedAt = SystemClock.uptimeMillis()
        nativeCapMs = capMs.coerceAtLeast(1L)
        inNativeCall = true
        noteProgress()
    }

    fun exitNativeCall() {
        inNativeCall = false
        noteProgress()
    }

    /** هل النافذة الأصلية مفتوحة الآن (فيُحتاج إلى نبضة تقدّم)؟ */
    fun nativeWindowOpen(nowMs: Long = SystemClock.uptimeMillis()): Boolean =
        inNativeCall && nowMs - nativeStartedAt < nativeCapMs

    /**
     * كتابة نهاية الطلب مرة واحدة فقط. إن مات العميل (أنبوب مكسور)
     * تُبتلع الـexception هنا: النهاية رفاهية عنده، والـEOF نفسه يصله.
     */
    fun end(status: Int, code: String, message: String) {
        if (!endWritten.compareAndSet(false, true)) return
        synchronized(this) {
            runCatching {
                HostProtocol.writeEnd(out, status, code, message)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(this) {
            runCatching { out.flush() }
            runCatching { out.close() }
        }
        runCatching { pfdRef?.close() }
    }
}
