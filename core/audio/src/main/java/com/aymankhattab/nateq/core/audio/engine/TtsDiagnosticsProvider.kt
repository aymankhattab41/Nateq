package com.aymankhattab.nateq.core.audio.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.aymankhattab.nateq.core.audio.accessibility.A11yStateStore
import com.aymankhattab.nateq.core.audio.accessibility.AccessibilityBridge
import com.aymankhattab.nateq.core.audio.accessibility.BatteryDiagnosticsTracker
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppTimelineTracker
import com.aymankhattab.nateq.core.audio.accessibility.VoiceResolutionTracker

/**
 * مزود تشخيص يعمل في عملية :tts لقراءة بيانات الخدمة الحقيقية
 * بدقة عبر العمليات (الجولة 53 - الأمر 2).
 */
class TtsDiagnosticsProvider : ContentProvider() {

    companion object {
        const val METHOD_SNAPSHOT = "snapshot"
        const val KEY_FOREGROUND_PKG = "foreground_pkg"
        const val KEY_A11Y_CONNECTED = "a11y_connected"
        const val KEY_A11Y_PAUSED = "a11y_paused"
        const val KEY_PAUSE_LEVEL = "pause_level"
        const val KEY_PAUSED_UNTIL = "paused_until"
        const val KEY_VOICE_RESOLUTIONS = "voice_resolutions"
        const val KEY_SPEED_REPORT = "speed_report"
        const val KEY_A11Y_EVENTS_PER_MIN = "a11y_events_per_min"
        const val KEY_USAGE_STATS_POLLS = "usage_stats_polls"
        const val KEY_TIMELINE_REPORT = "timeline_report"
    }

    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle? {
        val ctx = context ?: return null
        if (method == METHOD_SNAPSHOT) {
            val store = A11yStateStore.getInstance(ctx)
            val fg = AccessibilityBridge.foregroundPackage()
            val isConnected = store?.isConnected() ?: false
            val isPaused = store?.isPaused() ?: false
            val pauseLevel = store?.getPauseLevel() ?: 1
            val untilEpoch = store?.getPausedUntilEpoch() ?: 0L

            val resolutions = ArrayList(
                VoiceResolutionTracker.getRecent().map { it.format() }
            )
            val speedReport = SpeechLatencyTracker.formatSpeedReport()
            val a11yEventsPerMin =
                BatteryDiagnosticsTracker.getA11yEventsPerMinuteByType()
                    .values.sum()
            val usageStatsPolls =
                BatteryDiagnosticsTracker.getUsageStatsPollCount()
            val timelineReport =
                ProtectedAppTimelineTracker.getTimelineReport()

            return Bundle().apply {
                putString(KEY_FOREGROUND_PKG, fg)
                putBoolean(KEY_A11Y_CONNECTED, isConnected)
                putBoolean(KEY_A11Y_PAUSED, isPaused)
                putInt(KEY_PAUSE_LEVEL, pauseLevel)
                putLong(KEY_PAUSED_UNTIL, untilEpoch)
                putStringArrayList(KEY_VOICE_RESOLUTIONS, resolutions)
                putString(KEY_SPEED_REPORT, speedReport)
                putInt(KEY_A11Y_EVENTS_PER_MIN, a11yEventsPerMin)
                putInt(KEY_USAGE_STATS_POLLS, usageStatsPolls)
                putString(KEY_TIMELINE_REPORT, timelineReport)
            }
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
