package com.aymankhattab.nateq.core.audio.accessibility

import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * جسر التواصل بين خدمة إمكانية الوصول ومحرك النطق في العملية :tts (ع٢٧-3).
 * - يوفّر: foregroundPackage, focusSnapshot, expandReadMore.
 * - لا يحتفظ بـ AccessibilityNodeInfo خارج المعالج لتفادي التسريب.
 */
object AccessibilityBridge {

    private val activeServiceRef =
        AtomicReference<NateqAccessibilityService?>()

    private val foregroundPackageRef = AtomicReference<String?>()
    private val foregroundActivityRef = AtomicReference<String?>()

    private val lastScreenTextsBuffer = CopyOnWriteArrayList<String>()

    fun registerService(service: NateqAccessibilityService) {
        activeServiceRef.set(service)
    }

    fun unregisterService(service: NateqAccessibilityService) {
        activeServiceRef.compareAndSet(service, null)
    }

    fun updateForeground(packageName: String?, activityName: String?) {
        foregroundPackageRef.set(packageName)
        foregroundActivityRef.set(activityName)
    }

    /** التطبيق الأمامي الحالي النشط */
    fun foregroundPackage(): String? = foregroundPackageRef.get()

    /** النشاط الأمامي الحالي النشط */
    fun foregroundActivity(): String? = foregroundActivityRef.get()

    /** لقطة سريعة للعنصر المفوكس حالياً (عبر NodeView مسطّح) */
    fun focusSnapshot(): NodeView? {
        val service = activeServiceRef.get() ?: return null
        return service.findCurrentAccessibilityFocusSnapshot()
    }

    /** آخر نصوص شاشة تم رصدها */
    fun lastScreenTexts(): List<String> =
        lastScreenTextsBuffer.toList()

    fun updateLastScreenTexts(texts: List<String>) {
        lastScreenTextsBuffer.clear()
        lastScreenTextsBuffer.addAll(texts)
    }

    /**
     * توسيع الرسالة المقتطعة تلقائياً بمهلة زمنية (ع٢٧-20، ع٢٧-21)
     */
    suspend fun expandReadMore(
        prefix: String,
        timeoutMs: Long = 350L
    ): String? {
        val service = activeServiceRef.get() ?: return null
        return withTimeoutOrNull(timeoutMs) {
            service.expandReadMoreInternal(prefix)
        }
    }

    /** هل الجسر متصل بخدمة إمكانية الوصول الحية؟ */
    fun isConnected(): Boolean = activeServiceRef.get() != null
}
