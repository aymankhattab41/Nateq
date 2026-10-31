package com.aymankhattab.nateq.core.audio.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * ستارة الشاشة السوداء (ع٥٦-أمر 19 / ع٥٧-أمر 3).
 * تضع طبقة حجب سوداء معتمة شفافة للمس عبر TYPE_ACCESSIBILITY_OVERLAY
 * لحماية الخصوصية وتخفي أشرطة النظام دون تغيير السطوع.
 */
object ScreenCurtain {

    private const val TAG = "ScreenCurtain"

    const val ACTION_CURTAIN_RESULT =
        "com.aymankhattab.nateq.action.CURTAIN_RESULT"
    const val EXTRA_SUCCESS = "curtain_success"
    const val EXTRA_ERROR = "curtain_error"

    private var overlayView: View? = null
    private var isCurtainActive = false
    private var isTemporarilyHidden = false

    @Volatile
    var lastError: String? = null
        internal set

    fun isActive(context: Context? = null): Boolean {
        if (context != null) {
            val repo = SettingsRepository.create(context)
            return repo.isScreenCurtainActive()
        }
        return isCurtainActive
    }

    fun toggle(service: AccessibilityService): Boolean {
        return if (isCurtainActive) {
            disable(service)
            false
        } else {
            val success = enable(service)
            if (!success) {
                val failMsg = service.getString(R.string.curtain_failed)
                A11ySpeechCoordinator.speakAnnouncement(service, failMsg)
            }
            success
        }
    }

    fun enable(service: AccessibilityService): Boolean {
        if (isCurtainActive) return true
        val wm = service.getSystemService(Context.WINDOW_SERVICE)
            as? WindowManager
        if (wm == null) {
            lastError = "WindowManager unavailable"
            return false
        }

        try {
            val view = object : View(service) {
                override fun onAttachedToWindow() {
                    super.onAttachedToWindow()
                    applySystemBarInsets(this)
                }
            }.apply {
                setBackgroundColor(Color.BLACK)
                importantForAccessibility =
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            val overlayType =
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

            val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                flags,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

            wm.addView(view, params)
            overlayView = view
            isCurtainActive = true
            isTemporarilyHidden = false
            lastError = null

            val repo = SettingsRepository.create(service)
            repo.setScreenCurtainActive(true)

            val msg = service.getString(R.string.curtain_enabled)
            A11ySpeechCoordinator.speakAnnouncement(service, msg)
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to enable screen curtain: ${t.message}", t)
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            isCurtainActive = false
            val repo = SettingsRepository.create(service)
            repo.setScreenCurtainActive(false)
            return false
        }
    }

    private fun applySystemBarInsets(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.windowInsetsController?.let { controller ->
                controller.hide(
                    WindowInsets.Type.statusBars() or
                        WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    WindowInsetsController
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            view.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
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

        val repo = SettingsRepository.create(service)
        repo.setScreenCurtainActive(false)

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
        lastError = null
    }
}
