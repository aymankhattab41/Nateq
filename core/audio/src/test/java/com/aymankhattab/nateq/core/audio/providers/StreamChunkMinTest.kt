package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * اختبار اختيار حدّ شريحة البثّ (بند ب: تفجير أول صوتٍ لعناصر قارئ
 *  الشاشة القصيرة قبل onDone): قبل أول بثّ تُعتمد العتبة الصغيرة
 *  (1KB) فيُصدر الصوتُ فور توفّر بياناته، وبعده تعود الشرائحُ لحدّها
 *  المعتاد (16KB) فلا قراءات رقاقة خلف رقاقة على ملفٍ ينمو. المنطق
 *  نقي (بارامترات صريحة) — نفس نمط [StreamTailFlushTest].
 */
class StreamChunkMinTest {

    private val firstChunk = 1 * 1024
    private val regularChunk = 16 * 1024

    @Test
    fun beforeFirstEmit_usesSmallThreshold() {
        assertEquals(
            firstChunk,
            streamChunkMinBytes(
                emittedAny = false,
                firstChunkMinBytes = firstChunk,
                regularChunkBytes = regularChunk
            )
        )
    }

    @Test
    fun afterFirstEmit_usesRegularThreshold() {
        assertEquals(
            regularChunk,
            streamChunkMinBytes(
                emittedAny = true,
                firstChunkMinBytes = firstChunk,
                regularChunkBytes = regularChunk
            )
        )
    }

    @Test
    fun regularNeverBelowFirst() {
        // العتبة المعتادة لا تُخفض قط بعد البثّ مهما كانت الصغيرة.
        assertEquals(
            regularChunk,
            streamChunkMinBytes(
                emittedAny = true,
                firstChunkMinBytes = firstChunk,
                regularChunkBytes = regularChunk
            )
        )
    }
}