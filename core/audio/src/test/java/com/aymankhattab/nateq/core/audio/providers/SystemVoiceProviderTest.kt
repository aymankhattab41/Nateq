package com.aymankhattab.nateq.core.audio.providers

import com.aymankhattab.nateq.core.audio.engine.BytePool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** اختبار استخراج PCM من ملف WAV مباشرة من القرص إلى مسبح [BytePool] —
 *  منطق [extractPcmFromFile] النقي (بلا Android). */
class SystemVoiceProviderTest {

    private val pool = BytePool(minRetainedSize = 1, maxCapacity = 2)

    private fun tempFile(bytes: ByteArray): File {
        val file = File.createTempFile("nateq_test_wav", ".wav")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    /** يبني WAV كاملاً (رأس RIFF 44 بايت): fmt بعينات [sampleRate] أحادية
     *  قناة 16-بت، وdata ببيانات [dataBytes]. */
    private fun buildWav(
        sampleRate: Int,
        dataBytes: ByteArray
    ): ByteArray {
        val fmt = ByteArray(16)
        writeLeShort(fmt, 0, 1) // audioFormat = PCM
        writeLeShort(fmt, 2, 1) // channels
        writeLeInt(fmt, 4, sampleRate)
        writeLeInt(fmt, 8, sampleRate * 2) // byteRate
        writeLeShort(fmt, 12, 2) // blockAlign
        writeLeShort(fmt, 14, 16) // bitsPerSample
        val body = 8 + fmt.size + 8 + dataBytes.size
        val out = ByteArray(12 + body)
        putAscii(out, 0, "RIFF")
        writeLeInt(out, 4, body)
        putAscii(out, 8, "WAVE")
        putAscii(out, 12, "fmt ")
        writeLeInt(out, 16, fmt.size)
        System.arraycopy(fmt, 0, out, 20, fmt.size)
        putAscii(out, 36, "data")
        writeLeInt(out, 40, dataBytes.size)
        System.arraycopy(dataBytes, 0, out, 44, dataBytes.size)
        return out
    }

    private fun putAscii(out: ByteArray, offset: Int, text: String) {
        for ((i, c) in text.withIndex()) {
            out[offset + i] = c.code.toByte()
        }
    }

    private fun writeLeShort(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeLeInt(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    @Test
    fun `extractsDataChunkAndSampleRate`() {
        val data = ByteArray(32) { (it * 3 + 1).toByte() }
        val wav = buildWav(44100, data)
        val result = extractPcmFromFile(tempFile(wav), pool)
        assertEquals(44100, result.sampleRateInHz)
        assertEquals(data.size, result.validLength)
        assertArrayEquals(data, result.pcm.copyOf(result.validLength))
    }

    @Test
    fun `unsupportedSampleRateFallsBackTo22050`() {
        val result = extractPcmFromFile(
            tempFile(buildWav(8000, ByteArray(16) { 5 })),
            pool
        )
        assertEquals(
            SystemVoiceProvider.FALLBACK_SAMPLE_RATE,
            result.sampleRateInHz
        )
        assertEquals(16, result.validLength)
    }

    @Test
    fun `missingDataChunkFallsBackTo44ByteOffset`() {
        // fmt + خانات جانبية بلا data: نهاية 44 بايت احتياطية.
        val fmt = ByteArray(16)
        writeLeShort(fmt, 0, 1)
        writeLeShort(fmt, 2, 1)
        writeLeInt(fmt, 4, 22050)
        writeLeInt(fmt, 8, 44100)
        writeLeShort(fmt, 12, 2)
        writeLeShort(fmt, 14, 16)
        val junk = ByteArray(8) { (it + 10).toByte() }
        val body = 8 + fmt.size + 8 + junk.size
        val out = ByteArray(12 + body)
        putAscii(out, 0, "RIFF")
        writeLeInt(out, 4, body)
        putAscii(out, 8, "WAVE")
        putAscii(out, 12, "fmt ")
        writeLeInt(out, 16, fmt.size)
        System.arraycopy(fmt, 0, out, 20, fmt.size)
        putAscii(out, 36, "LIST")
        writeLeInt(out, 40, junk.size)
        System.arraycopy(junk, 0, out, 44, junk.size)
        val result = extractPcmFromFile(tempFile(out), pool)
        assertEquals(junk.size, result.validLength)
        assertArrayEquals(junk, result.pcm.copyOf(result.validLength))
    }

    @Test
    fun `truncatedDataChunkClampsToFileEnd`() {
        val data = ByteArray(20) { it.toByte() }
        val wav = buildWav(22050, data)
        // بتر آخر 5 بايت من البيانات: البيانات الفعلية 15 فقط.
        val truncated = wav.copyOf(wav.size - 5)
        val result = extractPcmFromFile(tempFile(truncated), pool)
        assertEquals(15, result.validLength)
        assertArrayEquals(
            data.copyOf(15), result.pcm.copyOf(result.validLength)
        )
    }

    @Test
    fun `nonWavReturnsAllBytesWithFallbackRate`() {
        val raw = ByteArray(64) { (it + 1).toByte() }
        val result = extractPcmFromFile(tempFile(raw), pool)
        assertEquals(
            SystemVoiceProvider.FALLBACK_SAMPLE_RATE,
            result.sampleRateInHz
        )
        assertEquals(raw.size, result.validLength)
        assertArrayEquals(raw, result.pcm.copyOf(result.validLength))
    }

    @Test
    fun `tinyFileReturnsEmpty`() {
        val result = extractPcmFromFile(
            tempFile(ByteArray(6) { 7 }),
            pool
        )
        assertEquals(0, result.validLength)
    }
}