package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.aymankhattab.nateq.core.audio.R

/**
 * كشف التحميل بنغمة هادئة وإعلان الانتهاء (ع٥٦-أمر 6).
 */
object LoadingDetector {

    const val THROTTLE_MS = 500L
    const val TONE_INTERVAL_MS = 1200L
    const val MAX_LOADING_DURATION_MS = 60_000L
    const val MIN_LOADING_FOR_ANNOUNCE_MS = 1500L

    private var lastProcessTime = 0L
    private var loadingStartTime = 0L
    private var isLoadingActive = false
    private var currentWindowPackage: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var toneRunnable: Runnable? = null

    /**
     * معالجة حدث تغير المحتوى لكشف مؤشرات التحميل.
     */
    fun onContentChanged(
        context: Context,
        packageName: String?,
        root: AccessibilityNodeInfo?,
        now: Long = SystemClock.uptimeMillis()
    ) {
        if (packageName.isNullOrBlank() || root == null) return
        if (AppPolicy.isSensitiveApp(packageName)) {
            stopLoading(context, announced = false)
            return
        }

        if (now - lastProcessTime < THROTTLE_MS) return
        lastProcessTime = now

        currentWindowPackage = packageName
        val foundIndicator = hasLoadingIndicator(root)

        if (foundIndicator) {
            if (!isLoadingActive) {
                isLoadingActive = true
                loadingStartTime = now
                scheduleTone(context)
            } else {
                // تجاوز الحد الأقصى 60 ثانية
                if (now - loadingStartTime > MAX_LOADING_DURATION_MS) {
                    stopLoading(context, announced = false)
                }
            }
        } else {
            if (isLoadingActive) {
                val duration = now - loadingStartTime
                val shouldAnnounce = duration >= MIN_LOADING_FOR_ANNOUNCE_MS
                stopLoading(context, announced = shouldAnnounce)
            }
        }
    }

    private fun scheduleTone(context: Context) {
        toneRunnable?.let { handler.removeCallbacks(it) }

        val runnable = object : Runnable {
            override fun run() {
                if (!isLoadingActive) return
                val now = SystemClock.uptimeMillis()
                if (now - loadingStartTime > MAX_LOADING_DURATION_MS) {
                    stopLoading(context, announced = false)
                    return
                }
                // نغمة هادئة بتردد 520Hz
                ScrollCuePlayer.onScroll(
                    ratio = 0.5f,
                    isSpeaking = false,
                    volume = 0.5f,
                    now = now
                )
                handler.postDelayed(this, TONE_INTERVAL_MS)
            }
        }
        toneRunnable = runnable
        handler.post(runnable)
    }

    /**
     * إيقاف مراقبة التحميل عند مغادرة النافذة أو اكتمال التحميل.
     */
    fun stopLoading(context: Context, announced: Boolean) {
        isLoadingActive = false
        loadingStartTime = 0L
        toneRunnable?.let {
            handler.removeCallbacks(it)
            toneRunnable = null
        }
        if (announced) {
            val msg = context.getString(R.string.loading_complete)
            A11ySpeechCoordinator.speakAnnouncement(context, msg)
        }
    }

    /**
     * فحص شجرة العقد للعثور على ProgressBar أو RangeInfo غير محدد.
     */
    fun hasLoadingIndicator(root: AccessibilityNodeInfo): Boolean {
        if (root.isPassword || !root.isVisibleToUser) return false

        val className = root.className?.toString().orEmpty()
        if (className.contains("ProgressBar", ignoreCase = true)) {
            val range = root.rangeInfo
            if (range == null || range.type == 0 || range.max <= 0f) {
                return true
            }
        }

        for (i in 0 until root.childCount) {
            val child = try {
                root.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue

            if (hasLoadingIndicator(child)) {
                return true
            }
        }
        return false
    }

    @androidx.annotation.VisibleForTesting
    fun resetForTesting() {
        isLoadingActive = false
        loadingStartTime = 0L
        lastProcessTime = 0L
        toneRunnable?.let { handler.removeCallbacks(it) }
        toneRunnable = null
    }

    @androidx.annotation.VisibleForTesting
    fun isLoading(): Boolean = isLoadingActive
}
