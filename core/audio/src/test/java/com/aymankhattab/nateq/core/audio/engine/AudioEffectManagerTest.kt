package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.core.engine.AudioExpansionLevels
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AudioEffectManagerTest {

    @Test
    fun testAttachAndDetachLifecycle() {
        val manager = AudioEffectManager()
        val sessionId = 42

        manager.attach(sessionId, AudioExpansionLevels.LIGHT)
        // In JVM test environment, native Virtualizer init gracefully returns
        // null or is handled without crashing.
        // Detach should safely execute and not throw.
        manager.detach(sessionId)
        assertFalse(manager.isAttached(sessionId))
    }

    @Test
    fun testAttachWithOffDetaches() {
        val manager = AudioEffectManager()
        val sessionId = 100

        manager.attach(sessionId, AudioExpansionLevels.OFF)
        assertFalse(manager.isAttached(sessionId))
    }

    @Test
    fun testAttachWithInvalidSessionIdIsNoOp() {
        val manager = AudioEffectManager()

        manager.attach(0, AudioExpansionLevels.MEDIUM)
        assertFalse(manager.isAttached(0))

        manager.attach(-1, AudioExpansionLevels.MEDIUM)
        assertFalse(manager.isAttached(-1))
    }

    @Test
    fun testReleaseAllClearsAllSessions() {
        val manager = AudioEffectManager()
        manager.attach(101, AudioExpansionLevels.LIGHT)
        manager.attach(102, AudioExpansionLevels.MEDIUM)

        manager.releaseAll()
        assertFalse(manager.isAttached(101))
        assertFalse(manager.isAttached(102))
    }

    @Test
    fun testAttachBenchmarkLatency() {
        val manager = AudioEffectManager()
        val start = System.nanoTime()

        for (i in 1..20) {
            manager.attach(i, AudioExpansionLevels.LIGHT)
            manager.detach(i)
        }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        // 20 attach/detach cycles should execute in under 20ms
        assertTrue(
            "Attach/detach latency too high: ${elapsedMs}ms",
            elapsedMs < 20.0
        )
    }
}
