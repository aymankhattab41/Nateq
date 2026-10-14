package com.aymankhattab.nateq.core.audio.providers

import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.util.Locale

/**
 * فكّ تشفير ملفات بيانات Vocalizer Expressive 2 المشفَّرة بترميز
 * CodeFactory (CF) — مُشتقٌّ من إعادة هندسة مزوّد SmartVoice (V371).
 *
 * صيغة التشفير XOR بسيطة بمفتاح ثابت طوله 1024 بايت:
 *   plain[i] = cipher[i] XOR KEY[i % 1024]
 * حيث KEY[i] = حرف KEY_ASCII[i] لـ i في [0,1022]، و KEY[1023] = 0.
 *
 * ملفات الصوت `.dat` غير المشتركة مشفَّرة رأسه CF_MAGIC، والمفكوك
 * رأسه SV_MAGIC. الملفات المشتركة (clm/lid/msp/synth_med/sysdct) لا
 * تُفكّ لأنها تُخدَم من أصول التطبيق المنفصلة.
 */
object VocalizerCfDecryptor {

    private const val KEY_LEN = 1024

    /** مفتاح CF النصّي (1023 حرفاً) — مطابق لنصّ SmartVoice V371. */
    internal val KEY_ASCII: String =
        "JxGsBpJ8iP5CZUFKlRMMxhSyIRiRHGm0sUOMt04ehuTYGHR4eMkLUDMkKh6Cvp89" +
        "32Z0c1Fiw3UTrtNZh8J4gFCfaz4FEbGHaCnaF9QVHDA7kARm7tPcG2IKD4JnYxln" +
        "y5u5sMfZlMzoNS1sUjN09vq2fEqtHn07EJSjY5URFyw8LigVhhQ5HjXKpV6Lqgwp" +
        "7E8UeIF7sYa9JhWKyuU28hEJHJc7ihnl7fqesHxzSBmA6HSUV3EHO3eX0Wc67Rfm" +
        "iEozhpfXI4E2XGzv1m3LpHh8VCNMDnYsVZollIGqI7l89O1DaIgmyv5grunCWQTU" +
        "oY1sl5EgpMghlYMX2SnTUi915pFsfRFeAlY9Mab03cZqsXP4xmFNatAB1LxAmzJg" +
        "ck4kQJXswEjQP0kAnfa0ziMiVLET9BWKfBjQGncmTFDeLWQp6FzBFzLNCfsOeW66" +
        "D7C56c6RWneSfpvqLkPsCVTfahbX3rZF2MNCGhLEBnjK51Q2PG95zvsp1FlUumDA" +
        "n0Bk17v6Srysjlg4jYhLMrjkpDbxQbxw8hkVuVTVOLRDmICnHnsNX5vLtQ6Viag0" +
        "qt0WpnztZ0QYvCYagki10U1vgBPEkqrEWzGXMB1Q3oTiVCatbLSxiJ24blQcX2UW" +
        "6ZPcDoeRNJc23BtajxxfM477WpMlkhnxY1C6Hrgxo1j5TVjUVsEvfK5jQe084hIe" +
        "s9GJauOkeXu5qxlelIUfhqvzor4Zp6uWGeBsrCXMBTmvWB5kUhZNIXeAAiCb3US1" +
        "QE1DcH3y2cQpPMS5ayzxDz3T6ltI2yksSh191ZMtHZSOuSujGSvvyK2sT4Uf9lsT" +
        "QkBLDyw5vnxgm7CMA2B6iOVZ1eWcKRTT6ZSob1PomNnVxwbeYzeCOIzj45h8Loqi" +
        "0Tz2P7DJKOmoK72OLRIJ0p0H7YkXbpCXzJ59CHSxT2wQMKEPNl8VzIBfqED8Y0EW" +
        "UHZbq0zIINnwfBa8gYfNUWSMHOCBOaNQBD0U7zf6SqfenVx3hWz6gGvIof3Etcl"

    /** رأس الملف المشفَّر (CodeFactory): AD 4A A1 6C. */
    val CF_MAGIC: ByteArray = byteArrayOf(-83, 74, -95, 108)

    /** رأس الملف المفكوك (SmartVoice): E7 32 E6 1F. */
    val SV_MAGIC: ByteArray = byteArrayOf(-25, 50, -26, 31)

    private val DROP_PREFIXES = arrayOf(
        "clm", "lid", "msp", "synth_med", "sysdct"
    )

    private val KEY: ByteArray by lazy { buildKey() }

    private fun buildKey(): ByteArray {
        val key = ByteArray(KEY_LEN)
        val n = minOf(KEY_ASCII.length, KEY_LEN - 1)
        for (i in 0 until n) {
            key[i] = KEY_ASCII[i].code.toByte()
        }
        key[KEY_LEN - 1] = 0
        return key
    }

    /** هل رأس الملف المشفَّر يطابق CF_MAGIC؟ (`len` = البايتات المتاحة). */
    fun isEncrypted(bytes: ByteArray, len: Int): Boolean =
        sameMagic(bytes, len, CF_MAGIC)

    /** هل رأس الملف المفكوك يطابق SV_MAGIC؟ */
    fun isPlain(bytes: ByteArray, len: Int): Boolean =
        sameMagic(bytes, len, SV_MAGIC)

    private fun sameMagic(
        bytes: ByteArray,
        len: Int,
        magic: ByteArray
    ): Boolean {
        if (len < magic.size) return false
        for (i in magic.indices) {
            if (bytes[i] != magic[i]) return false
        }
        return true
    }

    /**
     * هل الملف "مشترك" (يُخدَم من أصول التطبيق ولا يُفكّ)؟
     * المطابقة على البادئة تليها '.' أو '_' مثل clm.dat أو msp_…
     */
    fun isSharedBlob(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        for (p in DROP_PREFIXES) {
            if (lower.startsWith("$p.") || lower.startsWith("${p}_")) {
                return true
            }
        }
        return false
    }

    /**
     * هل هذا اسم مدخل يجب اعتباره لفكّ التشفير؟
     * ملف `.dat` لمحرك Vocalizer وغير مشترك.
     */
    fun isDecryptableDat(engineType: String, entryName: String): Boolean {
        val base = entryName.substringAfterLast('/')
        return engineType == VOICE_ENGINE_VOCALIZER &&
            base.lowercase(Locale.ROOT).endsWith(".dat") &&
            !isSharedBlob(base)
    }

    /**
     * يفكّ تشفير `len` بايت في مكانها بدءاً من موضع المدخل `startIndex`
     * داخل الملف (لضبط إزاحة المفتاح عند التدبيق المسترسل).
     */
    fun xorInPlace(buf: ByteArray, startIndex: Int, len: Int) {
        val key = KEY
        for (i in 0 until len) {
            val k = key[(startIndex + i) % KEY_LEN].toInt()
            buf[i] = (buf[i].toInt() xor k).toByte()
        }
    }

    /**
     * ينسخ `src` إلى `out`، وإن كان مشفَّراً (رأسه CF_MAGIC) فكّ
     * تشفيره أثناء النسخ التدبيقي. يُبلّغ عن كل كتلة بمقدارها عبر
     * `onBytes(count)` لحساب التقدّم وحدّ الاستخراج.
     *
     * @return true إن فُكّ التشفير فعلاً، و false إن نُسخ كما هو.
     */
    fun copyDecryptingIfNeeded(
        src: InputStream,
        out: OutputStream,
        onBytes: (Int) -> Unit
    ): Boolean {
        val pip = PushbackInputStream(src, CF_MAGIC.size)
        val head = ByteArray(CF_MAGIC.size)
        var got = 0
        while (got < head.size) {
            val r = pip.read(head, got, head.size - got)
            if (r < 0) break
            got += r
        }
        val encrypted = got == head.size && isEncrypted(head, got)
        var index = 0
        if (encrypted) {
            xorInPlace(head, 0, got)
            onBytes(got)
            out.write(head, 0, got)
            index = got
        } else if (got > 0) {
            pip.unread(head, 0, got)
        }

        val buffer = ByteArray(32768)
        while (true) {
            val n = pip.read(buffer)
            if (n <= 0) break
            if (encrypted) {
                xorInPlace(buffer, index, n)
                index += n
            }
            onBytes(n)
            out.write(buffer, 0, n)
        }
        return encrypted
    }

    /** معرّف محرك Vocalizer المستخدم في قرار الفكّ. */
    private const val VOICE_ENGINE_VOCALIZER = "vocalizer"
}
