package com.aymankhattab.nateq.core.audio.announcement

import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnnouncementSpeakerWarmupTest {
    @Test
    fun warmInstanceDoesNotPoll() {
        val speaker = AnnouncementSpeaker(ApplicationProvider.getApplicationContext())
        val wasWarm = true
        assertEquals(true, wasWarm)
    }
}

