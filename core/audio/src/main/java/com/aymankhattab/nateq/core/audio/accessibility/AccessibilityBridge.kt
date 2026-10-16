package com.aymankhattab.nateq.core.audio.accessibility

import kotlinx.coroutines.CompletableDeferred
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
     * نصوص الشاشة الحالية مرتّبة بصرياً (ع٤١-ب6، ب7).
     * يعود إلى آخر نصوص محفوظة إن لم تكن الخدمة متصلة.
     */
    fun readScreenTexts(): List<String> {
        val service = activeServiceRef.get() ?: return lastScreenTexts()
        val texts = runCatching { service.readScreenTexts() }
            .getOrDefault(emptyList())
        return if (texts.isEmpty()) lastScreenTexts() else texts
    }

    /** يمرّر النافذة النشطة للأمام (ع٤١-ب6). @return هل نجح التمرير؟ */
    fun scrollForward(): Boolean {
        val service = activeServiceRef.get() ?: return false
        return runCatching { service.scrollForwardActiveWindow() }
            .getOrDefault(false)
    }

    private val contentChangeSignal =
        AtomicReference<CompletableDeferred<Unit>?>()

    /**
     * إشعار الجسر بحدوث تغيّر في محتوى النافذة لإيقاظ الانتظار
     * اللاتزامني (ع٤٣-1).
     */
    fun notifyContentChanged() {
        contentChangeSignal.getAndSet(null)?.complete(Unit)
    }

    /**
     * انتظار لاتزامني غير حاجز حتى وصول إشعار تغيّر المحتوى أو انتهاء المهلة.
     */
    suspend fun waitForContentChange(timeoutMs: Long): Boolean {
        val deferred = CompletableDeferred<Unit>()
        contentChangeSignal.set(deferred)
        return try {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
                true
            } ?: false
        } finally {
            contentChangeSignal.compareAndSet(deferred, null)
        }
    }

    /**
     * توسيع الرسالة المقتطعة تلقائياً بمهلة زمنية كافية (ع٢٧-20، ع٤٣-1)
     */
    suspend fun expandReadMore(
        prefix: String,
        timeoutMs: Long = 2000L
    ): String? {
        val service = activeServiceRef.get() ?: return null
        return withTimeoutOrNull(timeoutMs) {
            service.expandReadMoreInternal(prefix)
        }
    }

    /** هل الجسر متصل بخدمة إمكانية الوصول الحية؟ */
    fun isConnected(): Boolean = activeServiceRef.get() != null
}
