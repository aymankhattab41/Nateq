package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContinuousReaderGenerationTest {

    private class TestSource : ContinuousReader.Source {
        val announcements = mutableListOf<String>()
        var screenTexts = listOf(
            "الصفحة 1 - سطر 1",
            "الصفحة 1 - سطر 2"
        )
        var scrollCount = AtomicInteger(0)
        var speakingFlag = AtomicBoolean(false)

        override fun readTexts(): List<String> = screenTexts

        override fun scrollForward(): Boolean {
            scrollCount.incrementAndGet()
            screenTexts = listOf(
                "الصفحة 2 - سطر 1",
                "الصفحة 2 - سطر 2"
            )
            return true
        }

        override fun currentPackage(): String? = "com.test.app"

        override fun announce(text: String) {
            announcements.add(text)
        }

        override fun isSpeaking(): Boolean = speakingFlag.get()
    }

    @Before
    fun setUp() {
        ContinuousReader.stopSilently()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun contentChangedDuringWait_readsNextAtomicallyWithoutDuplicates() {
        val source = TestSource()
        ContinuousReader.toggle(source)

        // Read initial page
        Thread.sleep(100)
        assertEquals(1, source.announcements.size)
        assertTrue(source.announcements[0].contains("الصفحة 1"))

        // Multiple rapid onContentChanged calls simulate duplicate a11y events
        ContinuousReader.onContentChanged()
        ContinuousReader.onContentChanged()
        ContinuousReader.onContentChanged()

        Thread.sleep(200)

        // Only one transition should have occurred for page 2
        assertTrue(
            "يجب ألا تتكرر إعلانات الصفحة 2",
            source.announcements.count { it.contains("الصفحة 2") } <= 1
        )
        ContinuousReader.stopSilently()
    }

    @Test
    fun stopSilently_invalidatesSessionAndStopsFutureExecution() {
        val source = TestSource()
        ContinuousReader.toggle(source)
        assertTrue(ContinuousReader.isReading())

        ContinuousReader.stopSilently()
        assertFalse(ContinuousReader.isReading())

        // Call onContentChanged after stop
        ContinuousReader.onContentChanged()
        Thread.sleep(100)
        assertFalse(ContinuousReader.isReading())
    }

    @Test
    fun speechThrottling_delaysScrollWhileSpeaking() {
        val source = TestSource()
        source.speakingFlag.set(true)
        ContinuousReader.toggle(source)

        Thread.sleep(100)
        val initialScrolls = source.scrollCount.get()

        // Speech is still active
        Thread.sleep(300)
        assertEquals(
            "يجب ألا يمرر لصفحة جديدة أثناء النطق",
            initialScrolls,
            source.scrollCount.get()
        )

        ContinuousReader.stopSilently()
    }
}
