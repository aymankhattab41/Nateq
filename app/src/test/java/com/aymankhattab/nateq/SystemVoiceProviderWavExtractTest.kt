package com.aymankhattab.nateq

import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.providers.SystemVoiceProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * يغطّي استخراج PCM من ملفات WAV في [SystemVoiceProvider] عبر Reflection (الطريقة
 * والنتيجة خاصّان). التركيز على الملفات التالفة التي كانت توقع المؤشر في حلقة لا
 * نهائية عند قراءة حجم خانة سالب (البت 31 مضبوطاً) — يجب أن ينهي الاستخراج فوراً
 * وبلا تعليق وعائداً لجسم PCM فارغ.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemVoiceProviderWavExtractTest {

    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        tempFiles.forEach { runCatching { it.delete() } }
        tempFiles.clear()
    }

    private fun writeWav(name: String, bytes: ByteArray): File {
        val file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, name)
        file.writeBytes(bytes)
        tempFiles += file
        return file
    }

    private fun extractPcm(file: File): Any {
        val provider = SystemVoiceProvider(ApplicationProvider.getApplicationContext())
        val method = SystemVoiceProvider::class.java.getDeclaredMethod("extractPcm", File::class.java)
        method.isAccessible = true
        return method.invoke(provider, file) ?: error("extractPcm returned null")
    }

    private fun fieldOf(result: Any, name: String): Any? {
        val field = result.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(result)
    }

    private fun intField(result: Any, name: String): Int = (fieldOf(result, name) as? Int) ?: error("missing Int field $name")

    private fun byteField(result: Any, name: String): ByteArray = (fieldOf(result, name) as? ByteArray) ?: error("missing byte[] field $name")

    private fun leShort(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte()
    )

    private fun leInt(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    private fun chunk(id: String, body: ByteArray): ByteArray {
        require(id.length == 4)
        return id.toByteArray(Charsets.US_ASCII) + leInt(body.size) + body
    }

    private fun riffWith(chunks: ByteArray): ByteArray =
        "RIFF".toByteArray(Charsets.US_ASCII) + leInt(4 + chunks.size) + "WAVE".toByteArray(Charsets.US_ASCII) + chunks

    /** WAV سليم: fmt PCM معدّل 44100 + خانة data بأربع عيّنات. */
    private fun validWav(): ByteArray {
        val fmt = leShort(1) +            // audioFormat = PCM
            leShort(1) +                  // numChannels
            leInt(44100) +                // sampleRate
            leInt(88200) +                // byteRate
            leShort(2) +                  // blockAlign
            leShort(16)                   // bitsPerSample
        return riffWith(chunk("fmt ", fmt) + chunk("data", byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun validWav_parsesPcmDataAndSampleRate() {
        val result = extractPcm(writeWav("valid.wav", validWav()))
        assertNotNull(result)
        assertEquals(4, intField(result, "validLength"))
        assertEquals(44100, intField(result, "sampleRateInHz"))
        assertEquals(byteArrayOf(1, 2, 3, 4)[0], byteField(result, "pcm")[0])
    }

    /** خانة volume بحجم 0xFFFFFFF8 (−8) كانت تبقّي المؤشر على نفس الموضع إلى الأبد. */
    @Test
    fun corruptLoopingSize_endsImmediately_withEmptyPcm() {
        // RIFF(8) + LOOP/hugeNeg(12..23) + حشو حتى 32 بايتاً.
        val bytes = "RIFF".toByteArray(Charsets.US_ASCII) +
            leInt(4) +
            "WAVE".toByteArray(Charsets.US_ASCII) +
            "LOOP".toByteArray(Charsets.US_ASCII) + leInt(-8) +
            ByteArray(12)
        assertEquals(32, bytes.size)
        val result = extractPcm(writeWav("loop.wav", bytes))
        assertEquals(0, intField(result, "validLength"))
        assertEquals(0, byteField(result, "pcm").size)
    }

    /** 0x80000000 (البت 31 مضبوطاً) كان يقفز بالمؤشر إلى موضع سالب ويُرمى على الأرجح. */
    @Test
    fun corruptSignBitSize_endsImmediately_withEmptyPcm() {
        val bytes = "RIFF".toByteArray(Charsets.US_ASCII) +
            leInt(4) +
            "WAVE".toByteArray(Charsets.US_ASCII) +
            "LOOP".toByteArray(Charsets.US_ASCII) + leInt(Int.MIN_VALUE) +
            ByteArray(12)
        val result = extractPcm(writeWav("signbit.wav", bytes))
        assertEquals(0, intField(result, "validLength"))
    }

    /** 0xFFFFFFFF (−1) — مع بُنى تالفة كان يتراكم نزولاً ويعيد تكرار مواضع سابقة. */
    @Test
    fun corruptAllOnesSize_endsImmediately_withEmptyPcm() {
        val bytes = "RIFF".toByteArray(Charsets.US_ASCII) +
            leInt(4) +
            "WAVE".toByteArray(Charsets.US_ASCII) +
            "LOOP".toByteArray(Charsets.US_ASCII) + leInt(-1) +
            ByteArray(12)
        val result = extractPcm(writeWav("allones.wav", bytes))
        assertEquals(0, intField(result, "validLength"))
    }

    /** خانة data تعلن عن حجم أضخم مما تبقّى — تُبتَر ولا يتكرر حلولٌ عشوائي. */
    @Test
    fun dataSizeExceedsFile_returnsRemainderAsPcm() {
        val contents = "RIFF".toByteArray(Charsets.US_ASCII) +
            leInt(4) +
            "WAVE".toByteArray(Charsets.US_ASCII) +
            "data".toByteArray(Charsets.US_ASCII) + leInt(Int.MAX_VALUE) +
            byteArrayOf(5, 6)
        val result = extractPcm(writeWav("shortdata.wav", contents))
        assertNotNull(result)
        assertEquals(2, intField(result, "validLength"))
        assertEquals(5, byteField(result, "pcm")[0].toInt() and 0xFF)
        assertEquals(6, byteField(result, "pcm")[1].toInt() and 0xFF)
    }

    /** ملف لا يبدأ برأس RIFF — يُمرَّر كما هو (لا يُهضم) بطولٍ مساوٍ لحجمه. */
    @Test
    fun nonWavFile_passesThroughAllBytes() {
        val garbage = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val result = extractPcm(writeWav("notwav.bin", garbage))
        assertEquals(12, intField(result, "validLength"))
        assertEquals(garbage[11], byteField(result, "pcm")[11])
    }
}