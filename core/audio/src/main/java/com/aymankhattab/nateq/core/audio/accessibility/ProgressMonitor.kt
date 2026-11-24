package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * مراقب أشرطة التقدم والتحميل (ع٢٧-42، البند 26).
 * يعلن عن نسب الإنجاز بشكل دوري عند المعالم المهمة (25%، 50%، 75%، 100%).
 * يزيل الحالة عند الانتهاء ويدعم تراجع أو إعادة بدء العملية.
 */
object ProgressMonitor {

    private val lastAnnouncedPercent =
        ConcurrentHashMap<String, Int>()

    /**
     * معالجة تقدم جديد والإعلان عنه عند بلوغ عتبة رئيسية.
     */
    fun onProgressChanged(
        context: Context,
        trackerKey: String,
        current: Int,
        max: Int
    ) {
        if (max <= 0 || max > 1_000_000) return
        val clampedCurrent = current.coerceIn(0, max)
        val percent = ((clampedCurrent.toLong() * 100) / max).toInt()
        val milestone = (percent / 25) * 25
        val lastMilestone = lastAnnouncedPercent.getOrDefault(trackerKey, -1)

        // تراجع أو إعادة بدء العملية: إعادة ضبط الحالة لتمكين الإعلان مجدداً
        if (lastMilestone > 0 && percent < lastMilestone - 5) {
            lastAnnouncedPercent.remove(trackerKey)
            return
        }

        val shouldAnnounce = milestone > 0 && milestone > lastMilestone

        if (shouldAnnounce) {
            if (milestone >= 100) {
                lastAnnouncedPercent.remove(trackerKey)
            } else {
                lastAnnouncedPercent[trackerKey] = milestone
            }

            val text = if (milestone >= 100) {
                "اكتمل التحميل"
            } else {
                "التقدم $milestone بالمئة"
            }
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = text,
                isInterruptAllowed = false
            )
        }
    }

    fun clearTracker(trackerKey: String) {
        lastAnnouncedPercent.remove(trackerKey)
    }

    fun clearAll() {
        lastAnnouncedPercent.clear()
    }

    @androidx.annotation.VisibleForTesting
    fun getLastAnnounced(trackerKey: String): Int? =
        lastAnnouncedPercent[trackerKey]
}
