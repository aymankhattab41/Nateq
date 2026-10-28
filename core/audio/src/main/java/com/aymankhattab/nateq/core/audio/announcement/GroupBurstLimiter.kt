package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.audio.accessibility.A11ySpeechCoordinator
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * محدد ازدحام المجموعات (ع٥٦-أمر 23).
 * يمنع تتابع الإشعارات الصاخبة من المجموعات النشطة ويلخصها بعد هدوء مؤقت.
 */
object GroupBurstLimiter {

    const val DEFAULT_WINDOW_MS = 20000L
    const val DEFAULT_THRESHOLD = 4
    const val DEFAULT_QUIET_PERIOD_MS = 6000L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    data class GroupBurstState(
        val timestamps: MutableList<Long> = mutableListOf(),
        var suppressedCount: Int = 0,
        var quietRunnable: Runnable? = null
    )

    private val groupStates = ConcurrentHashMap<String, GroupBurstState>()

    fun shouldSuppressSpeech(
        context: Context,
        groupKey: String?,
        groupTitle: String?,
        messageText: String?,
        windowMs: Long = DEFAULT_WINDOW_MS,
        threshold: Int = DEFAULT_THRESHOLD,
        quietPeriodMs: Long = DEFAULT_QUIET_PERIOD_MS
    ): Boolean {
        if (groupKey.isNullOrBlank()) return false
        val repo = SettingsRepository.create(context)
        if (!repo.isExtraEnabled("extra_group_limiter")) return false

        // استثناء الإشارات المباشرة
        if (messageText != null && messageText.contains("@")) {
            return false
        }

        val now = SystemClock.uptimeMillis()
        val state = groupStates.getOrPut(groupKey) { GroupBurstState() }

        synchronized(state) {
            state.timestamps.removeAll { now - it > windowMs }
            state.timestamps.add(now)

            if (state.timestamps.size > threshold) {
                state.suppressedCount++

                state.quietRunnable?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    val count = state.suppressedCount
                    state.suppressedCount = 0
                    if (count > 0) {
                        val title = groupTitle.orEmpty()
                        val summary = context.getString(
                            R.string.group_burst_summary,
                            title,
                            count
                        )
                        A11ySpeechCoordinator.speakAnnouncement(
                            context,
                            summary
                        )
                    }
                }
                state.quietRunnable = runnable
                mainHandler.postDelayed(runnable, quietPeriodMs)

                return true
            }
            return false
        }
    }

    fun resetForTesting() {
        groupStates.values.forEach {
            it.quietRunnable?.let { r -> mainHandler.removeCallbacks(r) }
        }
        groupStates.clear()
    }
}
