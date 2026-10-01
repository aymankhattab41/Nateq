package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حسمُ اكتمال كتابة ملف WAV بالطول المُعلَن في خانة البيانات: الدليل
 * القاطع الذي يمنع قطعَ نصٍّ في منتصف جملة حين يتأخّر إعلام onDone أو
 * يفقد. منطقٌ نقي بلا أجهزة.
 */
class WavWriteCompleteTest {

    /**
     * رأس WAV نموذجي: RIFF ثم fmt (16 بايت) ثم data. المصفوفة 48 بايت
     * لأن حجم خانة البيانات يُقرأ ثمانية بايتات كاملة.
     */
    private fun headerWithDataSize(declared: Long): ByteArray {
        val bytes = ByteArray(48)
        fun put(offset: Int, text: String) {
            for (i in text.indices) {
                bytes[offset + i] = text[i].code.toByte()
            }
        }
        fun putLeInt(offset: Int, value: Int) {
            for (i in 0..3) {
                bytes[offset + i] =
                    ((value shr (i * 8)) and 0xFF).toByte()
            }
        }
        fun putLeLong(offset: Int, value: Long) {
            for (i in 0..7) {
                bytes[offset + i] =
                    ((value shr (i * 8)) and 0xFF).toByte()
            }
        }
        put(0, "RIFF")
        putLeInt(4, 36 + declared.toInt())
        put(8, "WAVE")
        put(12, "fmt ")
        putLeInt(16, 16)
        putLeInt(20, 1)
        putLeInt(24, 1)
        putLeInt(28, 22050)
        put(36, "data")
        putLeLong(40, declared)
        return bytes
    }

    @Test
    fun declaredSizeReached_writeIsComplete() {
        val bytes = headerWithDataSize(8_000L)
        val meta = readWavStreamMeta(bytes, bytes.size)!!
        // الملف بلغ الحجم المعلن تماماً.
        assertTrue(isWavWriteComplete(meta, meta.dataStart + 8_000L))
        // تجاوزه قليلاً (حشو) يبقى مكتملاً.
        assertTrue(isWavWriteComplete(meta, meta.dataStart + 9_000L))
    }

    @Test
    fun declaredSizeNotReached_writeStillGoing() {
        val bytes = headerWithDataSize(8_000L)
        val meta = readWavStreamMeta(bytes, bytes.size)!!
        // الملف ما زال في منتصف الكتابة.
        assertFalse(isWavWriteComplete(meta, meta.dataStart + 7_999L))
        // ولا حتى قبل بداية البيانات.
        assertFalse(isWavWriteComplete(meta, meta.dataStart))
    }

    @Test
    fun noDeclaredSize_fallsBackToStallAndDone() {
        // حجم مؤقت (0xFFFFFFFF) لا يُعامَل إعلاناً قاطعاً.
        val bytes = headerWithDataSize(0xFFFF_FFFFL)
        val meta = readWavStreamMeta(bytes, bytes.size)!!
        assertFalse(
            "الحجم المؤقت ليس دليل اكتمال",
            isWavWriteComplete(meta, meta.dataStart + 5_000_000L)
        )
    }

    @Test
    fun nullMeta_isNeverComplete() {
        assertFalse(isWavWriteComplete(null, 1_000_000L))
    }
}