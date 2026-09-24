package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبار تصريف ذيل البثّ عند استقرار الكتابة دون onDone (إعلام سامسونج
 *  المتأخر/الغائب): يُصرف المتبقي الصغير فقط إذا صدر صوتٌ فعلاً، ولم
 *  يُعلِم المحرك بالاكتمال، واستقر حجم الملف جولاتٍ كافية. المنطق نقي
 *  (بارامترات صريحة) — نفس نمط [StreamDeadlineExtendTest].
 */
class StreamTailFlushTest {

    private val gracePolls = 5
    private val maxTailBytes = 16 * 1024L

    @Test
    fun stableFile_tinyTail_emitted_flushes() {
        assertTrue(
            shouldFlushTailAfterStall(
                emittedAny = true,
                finished = false,
                stallPolls = gracePolls,
                remainingBytes = 4 * 1024L,
                gracePolls = gracePolls,
                maxTailBytes = maxTailBytes
            )
        )
    }

    @Test
    fun stableFile_zeroRemainder_emitted_flushes() {
        // كل ما كُتب صُدر، وonDone متأخر — يُعلَّم الذيل منجزاً لينجح.
        assertTrue(
            shouldFlushTailAfterStall(
                emittedAny = true,
                finished = false,
                stallPolls = gracePolls + 2,
                remainingBytes = 0L,
                gracePolls = gracePolls,
                maxTailBytes = maxTailBytes
            )
        )
    }

    @Test
    fun emittedNothing_neverFlushes() {
        assertFalse(
            shouldFlushTailAfterStall(
                emittedAny = false,
                finished = false,
                stallPolls = gracePolls + 1,
                remainingBytes = 1_000L,
                gracePolls = gracePolls,
                maxTailBytes = maxTailBytes
            )
        )
    }

    @Test
    fun onDoneArrived_neverFlushes() {
        assertFalse(
            shouldFlushTailAfterStall(
                emittedAny = true,
                finished = true,
                stallPolls = gracePolls,
                remainingBytes = 1_000L,
                gracePolls = gracePolls,
                maxTailBytes = maxTailBytes
            )
        )
    }

    @Test
    fun largeRemainder_neverFlushes() {
        // ذيل كبير = كتابة متوقفة في النقلة، لا اكتمال — يبقى للdeadline.
        assertFalse(
            shouldFlushTailAfterStall(
                emittedAny = true,
                finished = false,
                stallPolls = gracePolls,
                remainingBytes = maxTailBytes,
                gracePolls = gracePolls,
                maxTailBytes = maxTailBytes
            )
        )
    }

    @Test
    fun unstableStallCount_neverFlushes() {
        // استقر عدد جولاتٍ دون النصاب — قد يكتب المحرك بأشلاءٍ متباعدة.
        assertFalse(
            shouldFlushTailAfterStall(
                emittedAny = true,
                finished = false,
                stallPolls = gracePolls - 1,
                remainingBytes = 1_000L,
                gracePolls = gracePolls,
                maxTailBytes = maxTailBytes
            )
        )
    }
}