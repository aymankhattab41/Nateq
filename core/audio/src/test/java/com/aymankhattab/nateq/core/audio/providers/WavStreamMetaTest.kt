package com.aymankhattab.nateq.core.audio.providers

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** اختبار [readWavStreamMeta] لرؤوس WAV النامية (بند ب.txt 3.2) — JVM نقي. */
class WavStreamMetaTest {

    private fun writeLe(buf: ByteBuffer, value: Long) {
        buf.putInt((value and 0xFFFFFFFFL).toInt())
    }

    private fun chunk(
        buf: ByteBuffer,
        name: String,
        payload: ByteArray
    ) {
        buf.put(name.toByteArray(Charsets.US_ASCII))
        writeLe(buf, payload.size.toLong())
        buf.put(payload)
    }

    private fun putPcmHeader(
        buf: ByteBuffer,
        sampleRate: Int
    ) {
        val fmt = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        fmt.putInt(1)                       // PCM
        fmt.putInt(1)                       // mono
        fmt.putInt(sampleRate)
        fmt.putInt(sampleRate * 2)          // byte rate
        fmt.putShort(2)                     // block align
        fmt.putShort(16)                    // bits per sample
        // الحمولة الرسمية لـ fmt هي 16 بايتاً (4 ints + shortان).
        chunk(buf, "fmt ", fmt.array().copyOf(16))
    }

    /** كتلة قبل data (لا fmt) من 4 بايتات int — كحشوٍ تجاري محاكى. */
    private fun padChunk(size: Int): ByteArray = ByteArray(size * 4)

    /** يبني بايتات WAV: رأس RIFF ثم (fmt أو الحشو) ثم خانة data. */
    private fun wavBytes(
        sampleRate: Int,
        dataLength: Int = 16,
        pads: Int = 0,
        padWords: Int = 4,
        includeFmt: Boolean = true
    ): ByteArray {
        val padBytes = pads * (8 + padWords * 4)
        val total = 12 + padBytes + (8 + 16) + 8 + dataLength
        val buf = ByteBuffer.wrap(ByteArray(total))
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        writeLe(buf, (total - 8).toLong())
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        if (includeFmt) {
            putPcmHeader(buf, sampleRate)
        } else {
            repeat(pads) { chunk(buf, "JUNK", padChunk(padWords)) }
        }
        buf.put("data".toByteArray(Charsets.US_ASCII))
        writeLe(buf, dataLength.toLong())
        // الحمولة المعلنة بايتاتٍ صامتة — أربعة بايتات لكل int.
        for (i in 0 until dataLength / 4) writeLe(buf, 0)
        return buf.array()
    }

    @Test
    fun `canonical header yields data offset and rate`() {
        val bytes = wavBytes(sampleRate = 25_050, dataLength = 64)
        val meta = readWavStreamMeta(bytes, bytes.size)
        assertEquals(25_050, meta?.sampleRateInHz)
        assertEquals(44L, meta?.dataStart)
    }

    @Test
    fun `growing header returns null until data chunk appears`() {
        // اثنا عشر بايتاً: رأس RIFF بلا خانة data بعد — تُعاد الفحص لاحقاً.
        val header = ByteArray(12)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        writeLe(buf, 4)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        assertNull(readWavStreamMeta(header, header.size))
        // أقل من 12 بايتاً: بلا حتى تأكيد WAVE.
        assertNull(readWavStreamMeta(header, 8))
    }

    @Test
    fun `garbage without RIFF magic returns null`() {
        val bytes = ByteArray(64) { 'X'.code.toByte() }
        assertNull(readWavStreamMeta(bytes, bytes.size))
    }

    @Test
    fun `data after junk chunks keeps correct offset and fallback rate`() {
        // كتلتا حشو قبل data (بلا fmt): موضع البيانات يُحسب حقيقةً،
        // والمعدل يبقى الاحتياطَ حتى تأتي خانة fmt.
        val bytes = wavBytes(
            sampleRate = 44_100,
            dataLength = 32,
            pads = 2,
            includeFmt = false
        )
        val meta = readWavStreamMeta(bytes, bytes.size)
        assertEquals(
            SystemVoiceProvider.FALLBACK_SAMPLE_RATE,
            meta?.sampleRateInHz
        )
        val expectedDataStart = 12 + 2 * (8 + 16) + 8
        assertEquals(expectedDataStart.toLong(), meta?.dataStart)
    }
}