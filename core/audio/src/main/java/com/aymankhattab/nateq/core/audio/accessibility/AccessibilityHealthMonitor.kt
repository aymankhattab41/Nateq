package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.util.AccessibilityUtils
import java.util.concurrent.atomic.AtomicBoolean

/**
 * مراقب صحة خدمة إمكانية الوصول (ع٢٧-5).
 * يكشف إذا كانت الخدمة مفعّلة في النظام لكنها غير متصلة (قتلها النظام).
 */
object AccessibilityHealthMonitor {

    enum class HealthStatus {
        ENABLED_AND_CONNECTED,
        ENABLED_BUT_DISCONNECTED,
        DISABLED_IN_SYSTEM
    }

    private val hasWarnedThisSession = AtomicBoolean(false)

    fun checkHealth(context: Context): HealthStatus {
        val isSystemEnabled =
            AccessibilityUtils.isNateqAccessibilityServiceEnabled(context)
        val store = A11yStateStore.getInstance(context)
        val isConnected = store?.isConnected() == true

        return when {
            isSystemEnabled && isConnected ->
                HealthStatus.ENABLED_AND_CONNECTED
            isSystemEnabled && !isConnected ->
                HealthStatus.ENABLED_BUT_DISCONNECTED
            else ->
                HealthStatus.DISABLED_IN_SYSTEM
        }
    }

    /**
     * هل ينبغي تحذير المستخدم من فصل الخدمة (مرة واحدة لكل جلسة)؟
     */
    fun shouldWarnDisconnected(context: Context): Boolean {
        if (checkHealth(context) == HealthStatus.ENABLED_BUT_DISCONNECTED) {
            return hasWarnedThisSession.compareAndSet(false, true)
        }
        return false
    }

    fun resetSessionWarning() {
        hasWarnedThisSession.set(false)
    }
}
