package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private class FakeSink : CueSink {
        var played: ShortArray? = null
        var volume: Float = 0f
        var onDone: ((Boolean) -> Unit)? = null
        var stopped = false
        var released = false
        var delayNotifyMs = 0L
        var playCalls = 0
        var stopCalls = 0

        override fun play(
            pcm: ShortArray,
            sampleRate: Int,
            volume: Float,
            onDone: (Boolean) -> Unit
        ) {
            playCalls++
            played = pcm
            this.volume = volume
            this.onDone = onDone
        }

        override fun stop() {
            stopped = true
            stopCalls++
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

    @Test
    fun `newest cue stops the previous and only the latest reports done`() {
        val sink = FakeSink()
        val player = AudioCuePlayer.forTesting(sink, CueSynth)

        var doneFirst = 0
        var doneSecond = 0
        player.play(AudioCue(CueType.BATTERY_CHARGING)) { doneFirst++ }
        val firstPcm = sink.played
        player.play(AudioCue(CueType.BATTERY_LOW)) { doneSecond++ }

        assertEquals(2, sink.playCalls)
        assertTrue("النغمة الأحدث توقف السابقة", sink.stopped)
        assertEquals(2, sink.stopCalls)
        assertNotNull(sink.played)
        assertNotSame("نغمة مختلفة فعلاً", firstPcm, sink.played)

        sink.notifyDone()
        ShadowLooper.idleMainLooper()
        assertEquals("السابقة أُجهضت بلا إنهاء", 0, doneFirst)
        assertEquals("الأحدث تنتهي مرة واحدة", 1, doneSecond)
    }

    @Test
    fun `volume is clamped into the playable range`() {
        val sink = FakeSink()
        val player = AudioCuePlayer.forTesting(sink, CueSynth)

        player.play(AudioCue(CueType.BATTERY_FULL, volume = 1.5f)) {}
        assertEquals(1.0f, sink.volume, 0.0f)

        player.play(AudioCue(CueType.BATTERY_LOW, volume = -0.3f)) {}
        assertEquals(0.0f, sink.volume, 0.0f)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `release cancels timeout releases the sink without onDone`() {
        val sink = FakeSink()
        val player = AudioCuePlayer.forTesting(sink, CueSynth)

        var doneCalls = 0
        player.play(AudioCue(CueType.TIME_HOURLY, "classic_bell")) {
            doneCalls++
        }
        player.release()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(0, doneCalls)
        assertTrue(sink.stopped)
        assertTrue(sink.released)
    }

    @Test
    fun `cue audio attributes target accessibility sonification`() {
        // سمات المؤثر تُوجّه لمسار الإتاحة (يتلاءم مع نغمة TalkBack) —
        // نفس البناء من المشغّلَين (AudioTrack وSoundPool).
        assertEquals(
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
            CueAudioAttributes.forCues.usage
        )
        assertEquals(
            AudioAttributes.CONTENT_TYPE_SONIFICATION,
            CueAudioAttributes.forCues.contentType
        )
    }

    @Test
    fun `sound pool sink writes a well-formed wav file`() {
        val sink = SoundPoolCueSink(context, Handler(Looper.getMainLooper()))
        val pcm = shortArrayOf(1, -1, 2, -2, 3, 0, -3, 1)
        val file = sink.writeWav(pcm, 44_100, "unit_test")
        val bytes = Files.readAllBytes(file.toPath())
        val dataSize = pcm.size * 2

        assertEquals(44 + dataSize, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(36 + dataSize, leInt(bytes, 4))
        assertEquals("fmt ", String(bytes, 12, 4))
        assertEquals(16, leInt(bytes, 16))
        assertEquals(1, leShort(bytes, 20).toInt()) // PCM
        assertEquals(1, leShort(bytes, 22).toInt()) // أحادية
        assertEquals(44_100, leInt(bytes, 24))
        assertEquals(44_100 * 2, leInt(bytes, 28))
        assertEquals(2, leShort(bytes, 32).toInt())
        assertEquals(16, leShort(bytes, 34).toInt())
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(dataSize, leInt(bytes, 40))
        for (i in pcm.indices) {
            assertEquals(pcm[i], leShort(bytes, 44 + i * 2))
        }
        sink.release()
    }

    @Test
    fun `sound pool sink reports failure on stop and releases cleanly`() {
        val sink = SoundPoolCueSink(context, Handler(Looper.getMainLooper()))
        var result: Boolean? = null

        sink.play(shortArrayOf(0, 1, 2, 0, 1), 44_100, 0.4f) { result = it }
        // تحميل SoundPool لا يكتمل تلقائياً في الظل — الإيقاف يغلق الدورة
        assertEquals(null, result)
        sink.stop()
        ShadowLooper.idleMainLooper()
        assertEquals(false, result)
        sink.release()
        assertTrue(true)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun leShort(bytes: ByteArray, offset: Int): Short {
        return ((bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)).toShort()
    }
}