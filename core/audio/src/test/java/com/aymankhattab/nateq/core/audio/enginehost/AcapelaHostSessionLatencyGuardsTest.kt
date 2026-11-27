package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AcapelaHostSessionLatencyGuardsTest {

    private fun newSession(): AcapelaHostSession {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AcapelaHostSession(context)
    }

    private fun setPrivateField(target: Any, name: String, value: Any) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        val current = field.get(target)
        when (current) {
            is AtomicBoolean -> current.set(value as Boolean)
            is AtomicLong -> current.set(value as Long)
            else -> field.set(target, value)
        }
    }

    private fun <T> withAdvancingClock(block: () -> T): T {
        val done = AtomicBoolean(false)
        val advancer = Thread {
            while (!done.get()) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(15))
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        return try {
            block()
        } finally {
            done.set(true)
            advancer.interrupt()
        }
    }

    @Test
    fun hasPronounceableText_skipsSilentChunks() {
        assertFalse(AcapelaHostSession.hasPronounceableText("..."))
        assertFalse(AcapelaHostSession.hasPronounceableText("!!! ???"))
        assertFalse(AcapelaHostSession.hasPronounceableText("   \n\t "))
        assertFalse(AcapelaHostSession.hasPronounceableText("🙂🙂"))
        assertFalse(AcapelaHostSession.hasPronounceableText("،؛:!"))
    }

    @Test
    fun hasPronounceableText_keepsRealText() {
        assertTrue(AcapelaHostSession.hasPronounceableText("مرحبا"))
        assertTrue(AcapelaHostSession.hasPronounceableText("Hello"))
        assertTrue(AcapelaHostSession.hasPronounceableText("123"))
        assertTrue(AcapelaHostSession.hasPronounceableText("... ok"))
    }

    @Test
    fun awaitChunkEnd_returnsWithoutExtraDelayWhenAudioAlreadyEnded() =
        withAdvancingClock {
            runBlocking {
                val session = newSession()
                setPrivateField(session, "audioReceived", true)
                setPrivateField(
                    session,
                    "lastAudioTime",
                    SystemClock.uptimeMillis() - 500L
                )
                val sink = PcmSink(DataOutputStream(ByteArrayOutputStream()))

                val started = SystemClock.uptimeMillis()
                session.awaitChunkEnd(
                    inst = null,
                    chars = 20,
                    sink = sink,
                    cancelled = AtomicBoolean(false),
                    speakingPoll = { 0 }
                )
                val elapsed = SystemClock.uptimeMillis() - started

                assertTrue(
                    "يجب أن ينتهي فوراً بعد صمت الذيل دون تأخير إضافي ($elapsed ms)",
                    elapsed < 250L
                )
            }
        }

    @Test
    fun awaitChunkEnd_capsStuckSpeakingAfterAudioStopped() =
        withAdvancingClock {
            runBlocking {
                val session = newSession()
                setPrivateField(session, "audioReceived", true)
                setPrivateField(
                    session,
                    "lastAudioTime",
                    SystemClock.uptimeMillis() - 500L
                )
                val sink = PcmSink(DataOutputStream(ByteArrayOutputStream()))

                val started = SystemClock.uptimeMillis()
                session.awaitChunkEnd(
                    inst = null,
                    chars = 200,
                    sink = sink,
                    cancelled = AtomicBoolean(false),
                    speakingPoll = { 1 } // عالق: يدّعي النطق دائماً
                )
                val elapsed = SystemClock.uptimeMillis() - started

                assertTrue(
                    "سقف النطق العالق يجب أن يمنع انتظار ميزانية القطعة ($elapsed ms)",
                    elapsed < 1_500L
                )
                assertTrue(
                    "يجب ألا يعود فوراً قبل سقف العالق/الذيل ($elapsed ms)",
                    elapsed >= 100L
                )
            }
        }
}
