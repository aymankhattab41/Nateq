package com.aymankhattab.nateq.core.audio.announcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

/** اختبارات مشغّل المؤثرات بسلك وهمي — لا صوت فعلي في الاختبار. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AudioCuePlayerTest {

    private class FakeSink : CueSink {
        var played: ShortArray? = null
        var volume: Float = 0f
        var onDone: ((Boolean) -> Unit)? = null
        var stopped = false
        var released = false
        var delayNotifyMs = 0L

        override fun play(
            pcm: ShortArray,
            sampleRate: Int,
            volume: Float,
            onDone: (Boolean) -> Unit
        ) {
            played = pcm
            this.volume = volume
            this.onDone = onDone
        }

        override fun stop() {
            stopped = true
        }

        override fun release() {
            released = true
        }

        fun notifyDone(ok: Boolean = true) {
            onDone?.invoke(ok)
            onDone = null
        }
    }

    @Test
    fun `play synthesizes and forwards to sink`() {
        val sink = FakeSink()
        val player = AudioCuePlayer.forTesting(sink, CueSynth)

        var doneCalls = 0
        val cue = AudioCue(CueType.BATTERY_CHARGING, volume = 0.7f)
        player.play(cue) { doneCalls++ }

        val pcm = sink.played
        assertNotNull(pcm)
        assertEquals(CueSynth.durationMs(cue) * CueSynth.SAMPLE_RATE / 1000,
            pcm!!.size)
        assertEquals(0.7f, sink.volume, 0.0f)
        sink.notifyDone()

        // إكمال التشغيل ثم المهلة — لا استدعاء ثانٍ
        ShadowLooper.idleMainLooper()
        assertEquals(1, doneCalls)
    }

    @Test
    fun `completion clears the timeout`() {
        val sink = FakeSink()
        val player = AudioCuePlayer.forTesting(sink, CueSynth)

        var doneCalls = 0
        player.play(AudioCue(CueType.TIME_HOURLY, "digital_chime")) {
            doneCalls++
        }
        sink.notifyDone()

        // مرّر زمناً أطول من مهلة الأمان: لا استدعاء مكرر
        ShadowLooper.idleMainLooper()
        assertEquals(1, doneCalls)
    }

    @Test
    fun `timeout fires onDone once when sink never reports`() {
        val sink = FakeSink()
        val player = AudioCuePlayer.forTesting(sink, CueSynth)

        var doneCalls = 0
        player.play(AudioCue(CueType.BATTERY_LOW)) { doneCalls++ }

        // مهلة الأمان = المدة + 600ms (+ هامش): تشغيل المهام المؤجلة يمرّرها
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(1, doneCalls)
        assertTrue(sink.stopped)
    }

    @Test
    fun `stop cancels timeout and stops sink`() {
        val sink = FakeSink()
        val player = AudioCuePlayer.forTesting(sink, CueSynth)

        var doneCalls = 0
        player.play(AudioCue(CueType.BATTERY_FULL)) { doneCalls++ }

        player.stop()
        ShadowLooper.idleMainLooper()
        assertEquals(0, doneCalls)
        assertTrue(sink.stopped)
    }
}