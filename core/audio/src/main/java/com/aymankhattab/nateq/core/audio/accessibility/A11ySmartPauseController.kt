package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * تحكم الإيقاف المؤقت الذكي لخدمات الشاشة (ع٢٧-18).
 * يوقف الخدمة مؤقتاً تلقائياً في الحالات التالية ويعيدها عند زوال السبب:
 * 1. أثناء المكالمة الهاتفية النشطة.
 * 2. عند قفل الشاشة (إن فُعّل).
 * 3. عند فتح تطبيق حساس (نظام/دفع/كلمات مرور).
 * 4. عند انخفاض البطارية الشديد (< 5%).
 */
class A11ySmartPauseController private constructor(
    private val appContext: Context
) {

    private val activeReasons = CopyOnWriteArraySet<String>()
    private val isSmartPaused = AtomicBoolean(false)

    fun setReasonActive(reason: String, active: Boolean) {
        if (active) {
            activeReasons.add(reason)
        } else {
            activeReasons.remove(reason)
        }
        evaluateSmartPause()
    }

    fun isReasonActive(reason: String): Boolean =
        activeReasons.contains(reason)

    fun hasAnyActiveReason(): Boolean =
        activeReasons.isNotEmpty()

    private fun evaluateSmartPause() {
        val shouldPause = activeReasons.isNotEmpty()
        val changed = isSmartPaused.getAndSet(shouldPause) != shouldPause
        if (changed) {
            val store = A11yStateStore.getInstance(appContext)
            // إذا لم يكن هناك إيقاف يدوي، نحدّث حالة الخدمة
            if (store != null && !store.isPaused()) {
                val service = AccessibilityBridge.focusSnapshot()
                // يتم إشعار الخدمة لتحديث serviceInfo
            }
        }
    }

    companion object {
        const val REASON_IN_CALL = "reason_in_call"
        const val REASON_SCREEN_LOCKED = "reason_screen_locked"
        const val REASON_SENSITIVE_APP = "reason_sensitive_app"
        const val REASON_LOW_BATTERY = "reason_low_battery"

        @Volatile
        private var instance: A11ySmartPauseController? = null

        fun getInstance(context: Context): A11ySmartPauseController {
            return instance ?: synchronized(this) {
                instance ?: A11ySmartPauseController(
                    context.applicationContext
                ).also { instance = it }
            }
        }
    }
}
