package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineVoiceFingerprintTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("test_fp", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun storesAndLoadsFingerprint() {
        val names = setOf("Laila", "Ahmed", "Salah")
        EngineVoiceFingerprint.store(prefs, "com.vocalizer.tts", names)
        val loaded = EngineVoiceFingerprint.load(prefs, "com.vocalizer.tts")
        assertEquals(names, loaded)
    }

    @Test
    fun loadReturnsEmptyWhenNotStored() {
        val loaded = EngineVoiceFingerprint.load(prefs, "com.unknown")
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun storeTruncatesToMax200() {
        val big = (1..250).map { "v$it" }.toSet()
        EngineVoiceFingerprint.store(prefs, "pkg", big)
        val loaded = EngineVoiceFingerprint.load(prefs, "pkg")
        assertEquals(200, loaded.size)
    }

    @Test
    fun matchWhenIntersection() {
        val fp = setOf("Laila", "Ahmed")
        val actual = listOf("Ahmed", "New")
        val v = EngineVoiceFingerprint.isBoundToExpectedEngine("pkg", actual, fp)
        assertEquals(EngineVoiceFingerprint.Verdict.MATCH, v)
    }

    @Test
    fun mismatchWhenNoIntersection() {
        val fp = setOf("Laila", "Ahmed")
        val actual = listOf("GoogleTTSVoice1", "GoogleTTSVoice2")
        val v = EngineVoiceFingerprint.isBoundToExpectedEngine("pkg", actual, fp)
        assertEquals(EngineVoiceFingerprint.Verdict.MISMATCH, v)
    }

    @Test
    fun unknownWhenFingerprintEmpty() {
        val fp = emptySet<String>()
        val actual = listOf("VoiceA")
        val v = EngineVoiceFingerprint.isBoundToExpectedEngine("pkg", actual, fp)
        assertEquals(EngineVoiceFingerprint.Verdict.UNKNOWN, v)
    }

    @Test
    fun mismatchWhenGoogleTagsForNonGoogle() {
        val fp = setOf("Laila")
        val actual = listOf("com.google.android.tts:VoiceA")
        val v = EngineVoiceFingerprint.isBoundToExpectedEngine("com.other", actual, fp)
        assertEquals(EngineVoiceFingerprint.Verdict.MISMATCH, v)
    }

    @Test
    fun noMismatchForGoogleWithGoogleTags() {
        val fp = setOf("VoiceA")
        val actual = listOf("com.google.android.tts:VoiceA")
        val v = EngineVoiceFingerprint.isBoundToExpectedEngine("com.google.android.tts", actual, fp)
        assertEquals(EngineVoiceFingerprint.Verdict.MATCH, v)
    }
}

