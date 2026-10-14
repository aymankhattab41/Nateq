package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousPageTrackerTest {

    @Test
    fun `first page is returned in full and its tail remembered`() {
        val tracker = ContinuousPageTracker(tailSize = 2)
        val page = listOf("س1", "س2", "س3")
        assertEquals(page, tracker.firstPage(page))
    }

    @Test
    fun `unchanged tail means end of content`() {
        val tracker = ContinuousPageTracker(tailSize = 2)
        tracker.firstPage(listOf("س1", "س2", "س3"))
        val result = tracker.nextPage(listOf("س1", "س2", "س3"))
        assertTrue(result.isEnd)
        assertTrue(result.freshTexts.isEmpty())
    }

    @Test
    fun `empty page means end of content`() {
        val tracker = ContinuousPageTracker(tailSize = 2)
        tracker.firstPage(listOf("س1", "س2"))
        assertTrue(tracker.nextPage(emptyList()).isEnd)
    }

    @Test
    fun `overlapping head is skipped and only fresh texts returned`() {
        val tracker = ContinuousPageTracker(tailSize = 2)
        tracker.firstPage(listOf("س1", "س2", "س3"))
        val result = tracker.nextPage(listOf("س3", "س4", "س5"))
        assertFalse(result.isEnd)
        assertEquals(listOf("س4", "س5"), result.freshTexts)
    }

    @Test
    fun `no overlap returns whole new page as fresh`() {
        val tracker = ContinuousPageTracker(tailSize = 2)
        tracker.firstPage(listOf("س1", "س2"))
        val result = tracker.nextPage(listOf("ص1", "ص2"))
        assertFalse(result.isEnd)
        assertEquals(listOf("ص1", "ص2"), result.freshTexts)
    }
}
