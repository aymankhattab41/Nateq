package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * مراقب أشرطة التقدم والتحميل (ع٢٧-42).
 * يعلن عن نسب الإنجاز بشكل دوري عند المعالم المهمة (10%، 25%، 50%، 75%، 100%).
 */
object ProgressMonitor {

    private val lastAnnouncedPercent =
        ConcurrentHashMap<String, Int>()

    /**
     * معالجة تقدم جديد والإعلان عنه عند بلوغ عتبة رئيسية
     */
    fun onProgressChanged(
        context: Context,
        trackerKey: String,
        current: Int,
        max: Int
    ) {
        if (max <= 0) return
        val percent = (current * 100) / max
        val last = lastAnnouncedPercent.getOrDefault(trackerKey, -1)

        // إعلان كل 25% أو عند الاكتمال
        val shouldAnnounce = when {
            percent == 100 && last != 100 -> true
            percent >= 75 && last < 75 -> true
            percent >= 50 && last < 50 -> true
            percent >= 25 && last < 25 -> true
            else -> false
        }

        if (shouldAnnounce) {
            lastAnnouncedPercent[trackerKey] = percent
            val text = if (percent == 100) {
                "اكتمل التحميل"
            } else {
                "التقدم $percent بالمئة"
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
}
