package com.aymankhattab.nateq.engine

import com.aymankhattab.nateq.core.audio.engine.PcmResampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات معيد أخذ العينات وخافض القنوات (بند 17.1) — منطق نقي بلا Android. */
class PcmResamplerTest {

    private fun encode(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            val clamped = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (clamped and 0xFF).toByte()
            out[i * 2 + 1] = (clamped shr 8).toByte()
        }
        return out
    }

    private fun decode(pcm: ByteArray): IntArray {
        val out = IntArray(pcm.size / 2)
        for (i in out.indices) {
            out[i] = (pcm[i * 2].toInt() and 0xFF or (pcm[i * 2 + 1].toInt() shl 8))
                .toShort().toInt()
        }
        return out
    }

    @Test
    fun emptyInput_passesThrough() {
        val empty = ByteArray(0)
        val result = PcmResampler.convert(empty, 22050, 1, 44100)
        assertEqualsPcm(ByteArray(0), result)
    }

    @Test
    fun sameRateMono_returnsSameInstance() {
        val pcm = encode(100, -200, 300, -400)
        val result = PcmResampler.convert(pcm, 22050, 1, 22050)
        assertSame("معدلان متساويان مونو: نفس المرجع (بلا نسخ)", pcm, result)
    }

    @Test
    fun constantSignal_downsampleByHalf_preservesValue() {
        val pcm = encode(*IntArray(200) { 1200 })
        val result = PcmResampler.convert(pcm, 44100, 1, 22050)
        assertEquals("الطول نصف الفريمات", 100, result.size / 2)
        assertTrue("قيم ثابتة محفوظة", decode(result).all { it == 1200 })
    }

    @Test
    fun constantSignal_upsampleByDouble_preservesValue() {
        // فريم واحد ثابت: الاستيفاء يبقيه ثابتاً في كل العينات المولّدة.
        val pcm = encode(900)
        val result = PcmResampler.convert(pcm, 22050, 1, 44100)
        assertEquals("الطول مضاعف الفريمات", 2, result.size / 2)
        assertEquals(".أول عينة مطابقة للمدخل", 900, decode(result)[0])
        assertArrayEquals(intArrayOf(900, 900), decode(result))
    }

    @Test
    fun ramp_downsample_interpolates() {
        // رامب [0,1000,2000,3000] بمعدل 44100 → نصف العينات (22050):
        // فريم 0 ← عينة 0 (0)، فريم 1 ← عينة 2 (2000).
        val pcm = encode(0, 1000, 2000, 3000)
        val result = PcmResampler.convert(pcm, 44100, 1, 22050)
        assertEquals(2, result.size / 2)
        val decoded = decode(result)
        assertEquals(0, decoded[0])
        assertEquals(2000, decoded[1])
    }

    @Test
    fun fractionalInterpolation_approximatesMidpoint() {
        // رامب [0,1000] بمعدل 22050 → رفعه للضعف: مواضع 0.0/0.5/1.0/1.5
        // تنتج [0, 500, 1000, 1000] — الاستيفاء عند المنتصف بالضبط.
        val pcm = encode(0, 1000)
        val result = PcmResampler.convert(pcm, 22050, 1, 44100)
        assertEquals(4, result.size / 2)
        val decoded = decode(result)
        assertEquals(0, decoded[0])
        assertEquals("المنتصف يُستوفى خطياً", 500, decoded[1])
        assertEquals(1000, decoded[2])
        assertEquals(1000, decoded[3])
    }

    @Test
    fun stereo_downmix_averagesChannelsThenPasses() {
        // فريمان: (1000,2000) ثم (4000,-2000) → مونو [1500, 1000]
        val pcm = encode(1000, 2000, 4000, -2000)
        val result = PcmResampler.convert(pcm, 22050, 2, 22050)
        assertEquals(2, result.size / 2)
        assertArrayEquals(intArrayOf(1500, 1000), decode(result))
    }

    @Test
    fun invalidRates_passThrough() {
        val pcm = encode(1, 2)
        val zero = PcmResampler.convert(pcm, 0, 1, 22050)
        val negative = PcmResampler.convert(pcm, 22050, 1, -1)
        assertEqualsPcm(pcm, zero)
        assertEqualsPcm(pcm, negative)
    }

    private fun assertEqualsPcm(expected: ByteArray, actual: ByteArray) {
        assertArrayEquals("مطابقة PCM", expected, actual)
    }
}