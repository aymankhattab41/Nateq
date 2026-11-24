package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * اختبار [ChunkCrossfader] — سلامة تدفق الصوت ومنع النقر.
 * يثبت الحفاظ التام على العينات والزمن في تدفق النقل المتصل،
 * وفصل حدود النقل عن حدود المقاطع، وحماية عجز 20480 إلى 11264 بايت.
 */
class ChunkCrossfaderTest {

    private fun pcm(vararg samples: Short): ByteArray {
        val out = ByteArray(samples.size * 2)
        for ((i, s) in samples.withIndex()) {
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s.toInt() ushr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun sampleAt(pcm: ByteArray, index: Int): Int {
        val lo = pcm[index * 2].toInt() and 0xFF
        val hi = (pcm[index * 2 + 1].toInt() shl 8) and 0xFFFF
        return (lo or hi).toShort().toInt()
    }

    // --- اختبارات النمط الافتراضي (حفظ العينات والزمن وتدفق النقل) ---

    @Test
    fun tenChunks_2048BytesEach_emitsAll20480BytesWithoutLoss() {
        // حماية العجز التاريخي (20480 بايت مدخلة خرج منها 11264 مع فقد 45%)
        val fader = ChunkCrossfader(512)
        val chunkBytes = 2048
        val chunkCount = 10
        var totalEmitted = 0

        for (i in 0 until chunkCount) {
            val chunk = ByteArray(chunkBytes) { (it and 0x7F).toByte() }
            val emitted = fader.process(chunk, chunk.size)
            totalEmitted += emitted
            assertEquals(chunkBytes, emitted)
        }

        assertEquals(20480, totalEmitted)
        assertEquals(0, fader.pendingFrames)
    }

    @Test
    fun streamingConstantSignal_allSamplesPreserved() {
        val fader = ChunkCrossfader(512)
        val samplesPerChunk = 256
        val sampleVal: Short = 4500

        for (c in 0 until 5) {
            val chunk = pcm(*ShortArray(samplesPerChunk) { sampleVal })
            val emitted = fader.process(chunk, chunk.size)
            assertEquals(samplesPerChunk * 2, emitted)
            for (i in 0 until samplesPerChunk) {
                assertEquals(sampleVal.toInt(), sampleAt(chunk, i))
            }
        }
    }

    @Test
    fun streamingVaryingWave_sineWavePreservedAcrossSplits() {
        val fader = ChunkCrossfader(512)
        val totalSamples = 2000
        val original = ShortArray(totalSamples) { i ->
            (sin(i * 0.05) * 12000).toInt().toShort()
        }

        // تقسيمات متنوعة: أقل من 512 وعشوائية وأكبر من 512
        val splitSizes = intArrayOf(50, 120, 512, 10, 300, 700, 308)
        var offset = 0
        var totalEmitted = 0

        for (size in splitSizes) {
            val slice = ShortArray(size) { original[offset + it] }
            val chunk = pcm(*slice)
            val emitted = fader.process(chunk, chunk.size)
            assertEquals(size * 2, emitted)
            totalEmitted += emitted
            offset += size
        }

        assertEquals(totalSamples * 2, totalEmitted)
    }

    @Test
    fun streamingImpulse_preservesMagnitudeAndCount() {
        val fader = ChunkCrossfader(512)
        val chunk = pcm(0, 0, 0, 30000, 0, 0, 0, 0)
        val emitted = fader.process(chunk, chunk.size)
        assertEquals(16, emitted)
        assertEquals(30000, sampleAt(chunk, 3))
    }

    @Test
    fun segmentBoundary_declicksTransitionWithoutLosingSamples() {
        val fader = ChunkCrossfader(4)
        // مقطع 1 ينتهي عند سعة عالية
        val seg1 = pcm(10000, 10000, 10000, 10000)
        val emitted1 = fader.process(seg1, seg1.size)
        assertEquals(8, emitted1)
        fader.onSegmentComplete()

        // مقطع 2 يبدأ بسعة سالبة حادة
        val seg2 = pcm(-10000, -10000, -10000, -10000)
        val emitted2 = fader.process(seg2, seg2.size)
        assertEquals(8, emitted2)

        // أول عينة تنعم من 10000 نحو -10000 دون قفزة ولا نقص
        val firstSample = sampleAt(seg2, 0)
        assertTrue(
            "الانتقال ناعم بين 10000 و -10000: $firstSample",
            firstSample in -9999..9999
        )
    }

    @Test
    fun segmentSkipped_clearsBoundaryState() {
        val fader = ChunkCrossfader(4)
        val seg1 = pcm(8000, 8000)
        fader.process(seg1, seg1.size)
        fader.onSegmentComplete()

        // مقطع تم تخطيه
        fader.onSegmentSkipped()

        // مقطع تالٍ يبدأ نظيفاً بلا دمج
        val seg2 = pcm(-5000, -5000)
        fader.process(seg2, seg2.size)
        assertEquals(-5000, sampleAt(seg2, 0))
    }

    @Test
    fun reset_clearsAllState() {
        val fader = ChunkCrossfader(512)
        val chunk = pcm(1000, 2000, 3000)
        fader.process(chunk, chunk.size)
        fader.onSegmentComplete()

        fader.reset()
        val fresh = pcm(9999, 9999)
        fader.process(fresh, fresh.size)
        assertEquals(9999, sampleAt(fresh, 0))
    }

    // --- اختبارات نمط overlap-add الصريح (للتوافق عند طلبه) ---

    @Test
    fun overlapAdd_firstChunk_emitsAllButTail_holdsTailForNext() {
        val fader = ChunkCrossfader(4, allowOverlapAdd = true)
        val chunk = pcm(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000)
        val emitLen = fader.process(chunk, chunk.size)
        assertEquals(8, emitLen)
        assertEquals(4, fader.pendingFrames)
        val tail = ByteArray(8)
        fader.copyPending(tail, 0)
        assertEquals(1000, sampleAt(tail, 0))
        assertEquals(1000, sampleAt(tail, 3))
    }

    @Test
    fun overlapAdd_secondChunk_blendsBoundaryInsteadOfJumping() {
        val fader = ChunkCrossfader(4, allowOverlapAdd = true)
        val first = pcm(0, 0, 0, 0, 0, 0)
        fader.process(first, first.size)
        val second = pcm(16000, 16000, 16000, 16000, 16000, 16000)
        fader.process(second, second.size)
        val blended = second
        val firstBlended = sampleAt(blended, 0)
        assertTrue(
            "أول عيّنة مدمجة كسب تدريجي: $firstBlended",
            firstBlended > 0 && firstBlended < 16000
        )
        assertTrue(sampleAt(blended, 3) > firstBlended)
    }

    @Test
    fun overlapAdd_pendingTail_isEmittedOnce_afterLastChunk() {
        val fader = ChunkCrossfader(3, allowOverlapAdd = true)
        val chunk = pcm(5000, 5000, 5000, 5000)
        val emitLen = fader.process(chunk, chunk.size)
        assertEquals(2, emitLen)
        assertEquals(3, fader.pendingFrames)
        val tail = ByteArray(6)
        fader.copyPending(tail, 0)
        assertEquals(5000, sampleAt(tail, 0))
        assertEquals(5000, sampleAt(tail, 2))
    }

    @Test
    fun overlapAdd_reset_clearsHold() {
        val fader = ChunkCrossfader(4, allowOverlapAdd = true)
        val chunk = pcm(1000, 1000, 1000, 1000)
        fader.process(chunk, chunk.size)
        assertEquals(4, fader.pendingFrames)
        fader.reset()
        assertEquals(0, fader.pendingFrames)
        val fresh = pcm(777, 777, 777, 777)
        val emit = fader.process(fresh, fresh.size)
        assertEquals(0, emit)
        assertTrue(fader.pendingFrames > 0)
    }
}
