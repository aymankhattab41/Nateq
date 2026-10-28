package com.aymankhattab.nateq.core.audio.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.WindowManager
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * ستارة الشاشة السوداء (ع٥٦-أمر 19).
 * تضع طبقة حجب سوداء شفافة للمس عبر TYPE_ACCESSIBILITY_OVERLAY لحماية الخصوصية.
 */
object ScreenCurtain {

    private var overlayView: View? = null
    private var isCurtainActive = false
    private var isTemporarilyHidden = false

    fun isActive(): Boolean = isCurtainActive

    fun toggle(service: AccessibilityService): Boolean {
        return if (isCurtainActive) {
            disable(service)
            false
        } else {
            enable(service)
            true
        }
    }

    fun enable(service: AccessibilityService): Boolean {
        if (isCurtainActive) return true
        val wm = service.getSystemService(Context.WINDOW_SERVICE)
            as? WindowManager ?: return false

        try {
            val view = View(service).apply {
                setBackgroundColor(Color.BLACK)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            val overlayType =
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            wm.addView(view, params)
            overlayView = view
            isCurtainActive = true
            isTemporarilyHidden = false

            val msg = service.getString(R.string.curtain_enabled)
            A11ySpeechCoordinator.speakAnnouncement(service, msg)
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    fun disable(service: AccessibilityService): Boolean {
        if (!isCurtainActive) return false
        val wm = service.getSystemService(Context.WINDOW_SERVICE)
            as? WindowManager

        overlayView?.let { v ->
            runCatching { wm?.removeView(v) }
        }
        overlayView = null
        isCurtainActive = false
        isTemporarilyHidden = false

        val msg = service.getString(R.string.curtain_disabled)
        A11ySpeechCoordinator.speakAnnouncement(service, msg)
        return true
    }

    fun onScreenOff(service: AccessibilityService) {
        if (isCurtainActive) {
            hideTemporarily(service)
        }
    }

    fun onScreenOn(service: AccessibilityService) {
        if (isCurtainActive && isTemporarilyHidden) {
            restoreCurtain(service)
        }
    }

    private fun hideTemporarily(service: AccessibilityService) {
        overlayView?.visibility = View.GONE
        isTemporarilyHidden = true
    }

    private fun restoreCurtain(service: AccessibilityService) {
        overlayView?.visibility = View.VISIBLE
        isTemporarilyHidden = false
    }

    fun resetForTesting() {
        overlayView = null
        isCurtainActive = false
        isTemporarilyHidden = false
    }
}
