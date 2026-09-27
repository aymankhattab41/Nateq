package com.aymankhattab.nateq.core.audio.announcement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** قرار التعليق النقي لمفاتيح «نطق الساعة في حالات خاصة» (منطق نقي). */
class TimeAnnouncementGateTest {

    private val normal = AudioSceneConditions(false, false, false)
    private val inCall = AudioSceneConditions(true, false, false)
    private val media = AudioSceneConditions(false, true, false)
    private val silent = AudioSceneConditions(false, false, true)
    private val all = AudioSceneConditions(true, true, true)

    @Test
    fun normalScene_neverSuppressed() {
        assertFalse(
            shouldSuppressTimeAnnouncement(
                normal, allowDuringCalls = false, allowDuringMedia = false,
                allowDuringSilent = false
            )
        )
    }

    @Test
    fun call_suppressedOnlyWhenFlagOff() {
        assertTrue(
            shouldSuppressTimeAnnouncement(
                inCall, allowDuringCalls = false, allowDuringMedia = true,
                allowDuringSilent = true
            )
        )
        assertFalse(
            shouldSuppressTimeAnnouncement(
                inCall, allowDuringCalls = true, allowDuringMedia = true,
                allowDuringSilent = true
            )
        )
    }

    @Test
    fun media_suppressedOnlyWhenFlagOff() {
        assertTrue(
            shouldSuppressTimeAnnouncement(
                media, allowDuringCalls = true, allowDuringMedia = false,
                allowDuringSilent = true
            )
        )
        assertFalse(
            shouldSuppressTimeAnnouncement(
                media, allowDuringCalls = true, allowDuringMedia = true,
                allowDuringSilent = true
            )
        )
    }

    @Test
    fun silent_suppressedOnlyWhenFlagOff() {
        assertTrue(
            shouldSuppressTimeAnnouncement(
                silent, allowDuringCalls = true, allowDuringMedia = true,
                allowDuringSilent = false
            )
        )
        assertFalse(
            shouldSuppressTimeAnnouncement(
                silent, allowDuringCalls = true, allowDuringMedia = true,
                allowDuringSilent = true
            )
        )
    }

    @Test
    fun anyScenarioWithAllFlagsOff_suppressed() {
        assertTrue(
            shouldSuppressTimeAnnouncement(
                all, allowDuringCalls = false, allowDuringMedia = false,
                allowDuringSilent = false
            )
        )
    }

    @Test
    fun scenariosIndependent_mediaFlagDoesNotSilenceCall() {
        assertTrue(
            shouldSuppressTimeAnnouncement(
                all, allowDuringCalls = false, allowDuringMedia = false,
                allowDuringSilent = true
            )
        )
        assertTrue(
            shouldSuppressTimeAnnouncement(
                all, allowDuringCalls = true, allowDuringMedia = true,
                allowDuringSilent = false
            )
        )
    }
}