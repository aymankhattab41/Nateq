package com.aymankhattab.nateq.core.audio.enginehost

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

/**
 * ع٢٩-5: بروتوكول العملية الأمامية ⇄ :engine_host.
 *
 * ثلاثة أشكال ترميز، كلها خالصة بلا أي إطار أندرويد فتُختبر في
 * اختبارات وحدة JVM مباشرة:
 *
 * 1. **نتائج الواجهة** (String عبر AIDL): `OK` أو `OK␟حمولة` أو
 *    `ERR␟رمز␟رسالة`.
 * 2. **قوائم الأصوات**: `VOICES` ثم سجلّات مفصولة بـ`␞`.
 * 3. **بثّ الصوت في الأنبوب**: إطارات `[نوع 1B][طول 4B][حمولة]`
 *    (صيغة، PCM، نهاية بحالة ورمز خطأ) — ترقيمٌ مضمون مع EOF
 *    فلا يشتبه PCM بعلامة نهاية ولا يتأخّر النهاية عن الإيقاف.
 *
 * الرموز (US/RS) مُستعملة عمداً بدل `\n`: لا يدخل في النص ولا في
 * أسماء الأصوات، فلا يُفسد الترميز تسميةٌ فيها سطر جديد.
 */
object HostProtocol {

    /** مفتاح فاصل داخل سطر النتيجة (ASCII Unit Separator). */
    private const val US = '\u001f'

    /** مفتاح فاصل بين السجلّات (ASCII Record Separator). */
    private const val RS = '\u001e'

    // محرّكات المضيف — أسماء ثابتة تمرّ عبر AIDL.
    const val ENGINE_VOCALIZER = "vocalizer"
    const val ENGINE_ACAPELA = "acapela"
    const val ENGINE_SHERPA = "sherpa"

    // أنواع الإطارات في الأنبوب.
    private const val FRAME_FORMAT: Byte = 1
    private const val FRAME_PCM: Byte = 2
    private const val FRAME_END: Byte = 3

    /** حالات نهاية الطلب (تُرمَّز في الإطار الأخير). */
    const val STATUS_OK = 0
    const val STATUS_ERROR = 1

    /** حدّ أقصى لحمولة إطار PCM (كتابة المزوّد محفوظة ضمنه). */
    const val MAX_PCM_FRAME = 1 shl 20

    /** حدّ أقصى لحمولة إطار نصّي (رمز/رسالة/صيغة). */
    private const val MAX_TEXT_FRAME = 1 shl 16

    // ----------------------------------------------------------------
    // نتائج الواجهة
    // ----------------------------------------------------------------

    /** نتيجة ناجحة بلا حمولة. */
    fun ok(): String = "OK"

    /** نتيجة ناجحة بحمولة نصّية واحدة (مثل معرّف الطلب). */
    fun ok(payload: String): String = "OK$US${sanitize(payload)}"

    /**
     * نتيجة ناجحة داخل المضيف قبل ترميزها للواجهة عبر [ok] —
     * جلسات المحرّكات تعيد [HostResult] والخدمة وحدها ترمّز.
     */
    fun success(payload: String? = null): HostResult =
        HostResult(ok = true, payload = payload)

    /** نتيجة فشل برمز معنون يصل سجلّ الأعطال بدل خطأ عام. */
    fun error(code: String, message: String?): String =
        listOf("ERR", sanitize(code), sanitize(message ?: ""))
            .joinToString(US.toString())

    /**
     * فكّ نتيجة نصّية. سلسلة فاسدة أو فارغة تعود خطأً برمز
     * `BAD_RESPONSE` بدل استثناء يُسقط النطق بصمتاً.
     */
    fun decodeResult(raw: String?): HostResult {
        if (raw.isNullOrBlank()) {
            return HostResult.failure("BAD_RESPONSE", "استجابة فارغة")
        }
        val parts = raw.split(US, limit = 3)
        return when (parts[0]) {
            "OK" -> HostResult(
                ok = true,
                payload = parts.getOrNull(1)
            )
            "ERR" -> {
                val code = parts.getOrNull(1).orEmpty()
                if (code.isBlank()) {
                    // ERR بلا رمز معنون سلسلةٌ فاسدة لا فشلٌ صالح.
                    HostResult.failure(
                        "BAD_RESPONSE",
                        "ERR بلا رمز معنون"
                    )
                } else {
                    HostResult(
                        ok = false,
                        code = code,
                        message = parts.getOrNull(2) ?: ""
                    )
                }
            }
            else -> HostResult.failure(
                "BAD_RESPONSE",
                "استجابة غير معروفة: ${parts[0].take(32)}"
            )
        }
    }

    /** فكّ معرّف الطلب من نتيجة ناجحة (أو -1 إن غاب). */
    fun decodeRequestId(raw: String?): Long =
        decodeResult(raw).payload?.toLongOrNull() ?: -1L

    // ----------------------------------------------------------------
    // قوائم الأصوات
    // ----------------------------------------------------------------

    /** ترميز قائمة أصوات لتمرّر عبر AIDL كسلسلة واحدة. */
    fun encodeVoices(voices: List<HostVoice>): String {
        if (voices.isEmpty()) return "VOICES"
        val body = voices.joinToString(RS.toString()) { v ->
            // تطهير الحقول نفسها: اسمٌ يحمل فاصلاً لا يكسر
            // بقية السجلّات ولا يُسقَط بصمتاً عند الفكّ.
            listOf(
                sanitize(v.id),
                sanitize(v.name),
                sanitize(v.langTag)
            ).joinToString(US.toString())
        }
        return "VOICES$US$body"
    }

    /** فكّ قائمة أصوات؛ سلسلة بلا بادئة `VOICES` تعود فارغة. */
    fun decodeVoices(raw: String?): List<HostVoice> {
        if (raw.isNullOrBlank()) return emptyList()
        val prefix = "VOICES$US"
        if (!raw.startsWith(prefix)) return emptyList()
        val body = raw.removePrefix(prefix)
        if (body.isEmpty()) return emptyList()
        return body.split(RS).mapNotNull { record ->
            val parts = record.split(US, limit = 3)
            if (parts.size < 3) null else HostVoice(
                id = parts[0],
                name = parts[1],
                langTag = parts[2]
            )
        }
    }

    /**
     * فكّ نتيجة `listVoices`: قائمة عند ترميزها الصحيح، أو فشلٌ
     * مُعلن برمز معنون (`ERR...`) أو استجابةٌ فاسدة — فيسجّل
     * المُستدعي السبب بدل عرض قائمةٍ صامتة كأنها كاملة.
     */
    fun decodeVoicesResponse(raw: String?): HostVoicesResult {
        if (raw.isNullOrBlank()) {
            return HostVoicesResult(
                failure = HostResult.failure(
                    "BAD_RESPONSE",
                    "استجابة أصوات فارغة"
                )
            )
        }
        if (raw.startsWith("VOICES$US") || raw == "VOICES") {
            return HostVoicesResult(voices = decodeVoices(raw))
        }
        val decoded = decodeResult(raw)
        return if (decoded.ok) {
            HostVoicesResult(failure = null)
        } else {
            HostVoicesResult(failure = decoded)
        }
    }

    // ----------------------------------------------------------------
    // إطارات الأنبوب
    // ----------------------------------------------------------------

    /** إطار واحد في أنبوب الـPCM: صيغة، صوت، أو نهاية بحالة. */
    sealed class HostFrame {
        data class Format(
            val sampleRate: Int,
            val channels: Int
        ) : HostFrame()

        class Pcm(val bytes: ByteArray) : HostFrame()

        data class End(
            val status: Int,
            val code: String,
            val message: String
        ) : HostFrame()
    }

    /** كتابة إطار صيغة (يسبق كل PCM دائماً). */
    fun writeFormat(
        out: DataOutputStream,
        sampleRate: Int,
        channels: Int
    ) {
        val payload = java.nio.ByteBuffer.allocate(8)
            .putInt(sampleRate)
            .putInt(channels)
            .array()
        writeFrame(out, FRAME_FORMAT, payload)
    }

    /** كتابة شريحة PCM خام (16-bit little-endian كما هي). */
    fun writePcm(out: DataOutputStream, bytes: ByteArray, length: Int) {
        val payload = if (length == bytes.size) {
            bytes
        } else {
            bytes.copyOf(length)
        }
        if (payload.isEmpty()) return
        if (payload.size > MAX_PCM_FRAME) {
            throw IOException(
                "إطار PCM أكبر من الحد: ${payload.size} > $MAX_PCM_FRAME"
            )
        }
        writeFrame(out, FRAME_PCM, payload)
    }

    /** كتابة إطار النهاية: حالة + رمز خطأ معنون + رسالة. */
    fun writeEnd(
        out: DataOutputStream,
        status: Int,
        code: String = "",
        message: String = ""
    ) {
        val codeBytes = sanitize(code).toByteArray(Charsets.UTF_8)
        val msgBytes = sanitize(message).toByteArray(Charsets.UTF_8)
        val payload = java.nio.ByteBuffer.allocate(
            4 + 4 + codeBytes.size + 4 + msgBytes.size
        )
            .putInt(status)
            .putInt(codeBytes.size)
            .put(codeBytes)
            .putInt(msgBytes.size)
            .put(msgBytes)
            .array()
        writeFrame(out, FRAME_END, payload)
        out.flush()
    }

    /**
     * قراءة إطار واحد؛ `null` عند نهاية الـEOF قبل إطار كامل.
     * أي إطار فاسد أو حمولة تتجاوز الحدّ يرمي [IOException]
     * (لا استهلاك ذاكرة بلا حدّ عبر Binder).
     */
    fun readFrame(input: DataInputStream): HostFrame? {
        val type = try {
            input.readByte()
        } catch (_: EOFException) {
            return null
        }
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            throw IOException("إطار مبتور بعد نوعه")
        }
        if (length < 0 || length > MAX_PCM_FRAME) {
            throw IOException("طول إطار غير صالح: $length")
        }
        val payload = ByteArray(length)
        try {
            input.readFully(payload)
        } catch (_: EOFException) {
            throw IOException("إطار مبتور قبل حمولته")
        }
        return when (type) {
            FRAME_FORMAT -> {
                if (payload.size != 8) {
                    throw IOException("إطار صيغة غير صالح")
                }
                val bb = java.nio.ByteBuffer.wrap(payload)
                HostFrame.Format(bb.int, bb.int)
            }
            FRAME_PCM -> HostFrame.Pcm(payload)
            FRAME_END -> decodeEndPayload(payload)
            else -> throw IOException("نوع إطار غير معروف: $type")
        }
    }

    private fun decodeEndPayload(payload: ByteArray): HostFrame.End {
        if (payload.size < 8) throw IOException("إطار نهاية قصير")
        val bb = java.nio.ByteBuffer.wrap(payload)
        val status = bb.int
        val codeLen = bb.int
        if (codeLen < 0 || codeLen > MAX_TEXT_FRAME ||
            codeLen > bb.remaining()
        ) {
            throw IOException("طول رمز خطأ غير صالح: $codeLen")
        }
        val codeBytes = ByteArray(codeLen)
        bb.get(codeBytes)
        if (bb.remaining() < 4) throw IOException("إطار نهاية قصير")
        val msgLen = bb.int
        if (msgLen < 0 || msgLen > MAX_TEXT_FRAME ||
            msgLen > bb.remaining()
        ) {
            throw IOException("طول رسالة نهاية غير صالح: $msgLen")
        }
        val msgBytes = ByteArray(msgLen)
        bb.get(msgBytes)
        return HostFrame.End(
            status = status,
            code = String(codeBytes, Charsets.UTF_8),
            message = String(msgBytes, Charsets.UTF_8)
        )
    }

    private fun writeFrame(
        out: DataOutputStream,
        type: Byte,
        payload: ByteArray
    ) {
        out.writeByte(type.toInt())
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    // ----------------------------------------------------------------
    // علامة الانهيار
    // ----------------------------------------------------------------

    /**
     * ملف علامة الانهيار لمحرك: يُكتب قبل أي تهيئة أصلية ويُمحى
     * بعد رجوعها، فإن ماتت العملية أثناء التهيئة تبقى العلامة
     * فتقرؤها العملية التالية وتعيد `CRASHED_LAST_RUN`.
     */
    fun markerFile(dir: File, engineId: String): File =
        File(dir, "crash_marker_$engineId")

    /** كتابة علامة انهيار مسبوقة بمسار أصلٍ للتشخيص. */
    fun writeMarker(dir: File, engineId: String, detail: String) {
        runCatching {
            markerFile(dir, engineId)
                .writeText("$engineId$US${sanitize(detail)}")
        }
    }

    /** حذف علامة الانهيار (رجوع التهيئة — نجاحاً أو فشلاً). */
    fun clearMarker(dir: File, engineId: String) {
        runCatching { markerFile(dir, engineId).delete() }
    }

    /** هل ماتت العملية أثناء آخر تهيئة لهذا المحرك؟ */
    fun hasMarker(dir: File, engineId: String): Boolean =
        markerFile(dir, engineId).exists()

    /** محتوى العلامة (للتشخيص في السجلّ)، أو سلسلة فارغة. */
    fun readMarker(dir: File, engineId: String): String = runCatching {
        markerFile(dir, engineId).readText()
    }.getOrDefault("")

    private fun sanitize(value: String): String =
        value.replace(US, ' ').replace(RS, ' ')
}

/** نتيجة نصّية من المضيف. */
data class HostResult(
    val ok: Boolean,
    val payload: String? = null,
    val code: String? = null,
    val message: String? = null
) {
    companion object {
        fun failure(code: String, message: String): HostResult =
            HostResult(ok = false, code = code, message = message)
    }
}

/** سجلّ صوت واحد مرمَّز عبر [HostProtocol]. */
data class HostVoice(
    val id: String,
    val name: String,
    val langTag: String
)

/** نتيجة فكّ `listVoices`: قائمة، أو فشلٌ برمز معنون. */
data class HostVoicesResult(
    val voices: List<HostVoice> = emptyList(),
    val failure: HostResult? = null
)
