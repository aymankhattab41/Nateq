package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioEnvironmentPolicyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AudioEnvironmentPolicy.testInCall = null
        AudioEnvironmentPolicy.testRecordingVoiceNote = null
        ScrollCuePlayer.resetLastPlayTimeForTesting()
    }

    @After
    fun tearDown() {
        AudioEnvironmentPolicy.testInCall = null
        AudioEnvironmentPolicy.testRecordingVoiceNote = null
        ScrollCuePlayer.resetLastPlayTimeForTesting()
    }

    @Test
    fun isInCall_reflectsTestOverride() {
        AudioEnvironmentPolicy.testInCall = true
        assertTrue(AudioEnvironmentPolicy.isInCall(context))

        AudioEnvironmentPolicy.testInCall = false
        assertFalse(AudioEnvironmentPolicy.isInCall(context))
    }

    @Test
    fun isRecordingVoiceNote_reflectsTestOverride() {
        AudioEnvironmentPolicy.testRecordingVoiceNote = true
        assertTrue(AudioEnvironmentPolicy.isRecordingVoiceNote(context))

        AudioEnvironmentPolicy.testRecordingVoiceNote = false
        assertFalse(AudioEnvironmentPolicy.isRecordingVoiceNote(context))
    }

    @Test
    fun shouldMuteCues_trueDuringCallOrRecording() {
        AudioEnvironmentPolicy.testInCall = true
        AudioEnvironmentPolicy.testRecordingVoiceNote = false
        assertTrue(AudioEnvironmentPolicy.shouldMuteCues(context))

        AudioEnvironmentPolicy.testInCall = false
        AudioEnvironmentPolicy.testRecordingVoiceNote = true
        assertTrue(AudioEnvironmentPolicy.shouldMuteCues(context))

        AudioEnvironmentPolicy.testInCall = false
        AudioEnvironmentPolicy.testRecordingVoiceNote = false
        assertFalse(AudioEnvironmentPolicy.shouldMuteCues(context))
    }

    @Test
    fun scrollCuePlayer_mutesDuringRestrictedEnvironment() {
        AudioEnvironmentPolicy.testInCall = true
        val playedInCall = ScrollCuePlayer.onScroll(
            ratio = 0.5f,
            isSpeaking = false,
            context = context
        )
        assertFalse("يجب حظر نغمة التمرير أثناء المكالمة", playedInCall)

        AudioEnvironmentPolicy.testInCall = false
        AudioEnvironmentPolicy.testRecordingVoiceNote = true
        val playedInRec = ScrollCuePlayer.onScroll(
            ratio = 0.5f,
            isSpeaking = false,
            context = context
        )
        assertFalse("يجب حظر نغمة التمرير أثناء التسجيل الصوتي", playedInRec)

        AudioEnvironmentPolicy.testInCall = false
        AudioEnvironmentPolicy.testRecordingVoiceNote = false
        ScrollCuePlayer.resetLastPlayTimeForTesting()
        val playedNormal = ScrollCuePlayer.onScroll(
            ratio = 0.5f,
            isSpeaking = false,
            context = context,
            now = 1000L
        )
        assertTrue("يجب السماح بنغمة التمرير في الوضع العادي", playedNormal)
    }

    @Test
    fun progressMonitor_suppressedDuringRestrictedEnvironment() {
        AudioEnvironmentPolicy.testInCall = true
        // لا يعلن ولا يحدث استثناء
        ProgressMonitor.onProgressChanged(context, "dl_1", 50, 100)

        AudioEnvironmentPolicy.testInCall = false
        AudioEnvironmentPolicy.testRecordingVoiceNote = true
        ProgressMonitor.onProgressChanged(context, "dl_1", 75, 100)
    }

    @Test
    fun a11ySpeechCoordinator_canSpeakFalse_duringVoiceNoteRecording() {
        AudioEnvironmentPolicy.testRecordingVoiceNote = true
        assertFalse(
            "يجب حظر الإعلانات الصوتية أثناء تسجيل الرسائل الصوتية",
            A11ySpeechCoordinator.canSpeak(context, isInterruptAllowed = true)
        )

        AudioEnvironmentPolicy.testRecordingVoiceNote = false
        assertTrue(
            A11ySpeechCoordinator.canSpeak(context, isInterruptAllowed = true)
        )
    }
}
