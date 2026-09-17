package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبار [ChunkCrossfader] — الدمج السلس بين شريحتَي البثّ المختلط
 * (مرحلة 7). يثبت أن الحدّ بين شريحتين لا يقفز قفزةً حادة (كليك)،
 * ولا يُكرر الذيلُ ولا يُضاع الصوتُ (overlap-add بلا ازدواج).
 */
class ChunkCrossfaderTest {

    /** يبني شريحة مونو 16-بت (little endian) من قائمة عيّنات Short. */
    private fun pcm(vararg samples: Short): ByteArray {
        val out = ByteArray(samples.size * 2)
        for ((i, s) in samples.withIndex()) {
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s.toInt() ushr 8) and 0xFF).toByte()
        }
        return out
    }

    /** يقرأ عيّنة 16-بت (little endian) في موضع [index]. */
    private fun sampleAt(pcm: ByteArray, index: Int): Int {
        val lo = pcm[index * 2].toInt() and 0xFF
        val hi = (pcm[index * 2 + 1].toInt() shl 8) and 0xFFFF
        return (lo or hi).toShort().toInt()
    }

    @Test
    fun firstChunk_emitsAllButTail_holdsTailForNext() {
        // نافذة 4 عيّنات: أول شريحةٌ من 8 عيّنات ثابتة 1000.
        val fader = ChunkCrossfader(4)
        val chunk = pcm(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000)
        val emitLen = fader.process(chunk, chunk.size)
        // الناتجُ الجاهز = 8 عيّنات - 4 محجوزة = 4 عيّنات (8 بايت).
        assertEquals(8, emitLen)
        assertEquals(4, fader.pendingFrames)
        // الذيلُ المحجوز يبدأ من عيّنة 4 (الفهرس): قيم 1000.
        val tail = ByteArray(8)
        fader.copyPending(tail, 0)
        assertEquals(1000, sampleAt(tail, 0))
        assertEquals(1000, sampleAt(tail, 3))
    }

    @Test
    fun secondChunk_blendsBoundaryInsteadOfJumping() {
        // شريحة أولى كلها صامتة (0) — يعتبرها المتلقي صمتاً؛
        // والثانية انفجار عالٍ (16000). الحدّ الصحيح لا يقفز من 0 إلى
        // 16000 دفعةً واحدة بل ينساب عبر نافذة الدمج التدريجي.
        val fader = ChunkCrossfader(4)
        val first = pcm(0, 0, 0, 0, 0, 0)
        fader.process(first, first.size)
        val second = pcm(16000, 16000, 16000, 16000, 16000, 16000)
        fader.process(second, second.size)
        // أول عيّنة بعد الدمج بين القيمتين (وليست 16000 خالصة ولا 0):
        // بانحدارٍ خطي t=(0+1)/(4+1)=0.2 → 16000*0.2=3200 تقريباً.
        val blended = second
        val firstBlended = sampleAt(blended, 0)
        assertTrue(
            "أول عيّنة مدمجة يجب أن تكون كسباً تدريجياً: $firstBlended",
            firstBlended > 0 && firstBlended < 16000
        )
        // الذيلُ المحجوز للشريحة الأولى (0) استُهلك فلا تكرار:
        // عيّنةٌ لاحقة داخل حدود الدمج تتقدم نحو 16000.
        assertTrue(sampleAt(blended, 3) > firstBlended)
    }

    @Test
    fun pendingTail_isEmittedOnce_afterLastChunk() {
        // شريحة واحدة فقط: كل شيء بينها وبين القادم محجوز؛
        // الذيلُ يُنسخ ويُبثّ عند النهاية فلا يُقتطع آخر الصوت.
        val fader = ChunkCrossfader(3)
        val chunk = pcm(5000, 5000, 5000, 5000)
        val emitLen = fader.process(chunk, chunk.size)
        // أول 2 عيّنة مبثوثة (4 عيّنات - 3 محجوزة ≈ عيّنة واحدة فقط!)…
        // حجمُ الدمج 3 فمحجوز 3، والناتج 8-6=2 بايت (عيّنة واحدة).
        assertEquals(2, emitLen)
        assertEquals(3, fader.pendingFrames)
        val tail = ByteArray(6)
        fader.copyPending(tail, 0)
        assertEquals(5000, sampleAt(tail, 0))
        assertEquals(5000, sampleAt(tail, 2))
    }

    @Test
    fun reset_clearsHold() {
        val fader = ChunkCrossfader(4)
        val chunk = pcm(1000, 1000, 1000, 1000)
        fader.process(chunk, chunk.size)
        assertEquals(4, fader.pendingFrames)
        fader.reset()
        assertEquals(0, fader.pendingFrames)
        // بعد إعادة التعيين تشبهُ الشريحةُ الأولى تماماً (لا دمج قديم).
        val fresh = pcm(777, 777, 777, 777)
        val emit = fader.process(fresh, fresh.size)
        assertEquals(0, emit)
        assertTrue(fader.pendingFrames > 0)
    }
}