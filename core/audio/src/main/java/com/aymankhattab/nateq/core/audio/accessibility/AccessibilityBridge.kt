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

    fun activeService(): NateqAccessibilityService? = activeServiceRef.get()

    fun isServiceConnected(): Boolean = activeServiceRef.get() != null

    fun updateForeground(packageName: String?, activityName: String?) {
        if (packageName.isNullOrBlank()) return
        if (packageName.contains("talkback", ignoreCase = true)) return
        foregroundPackageRef.set(packageName)
        foregroundActivityRef.set(activityName)
    }

    /** التطبيق الأمامي الحالي النشط */
    fun foregroundPackage(): String? = foregroundPackageRef.get()

    /**
     * حساب الحزمة الأمامية بدقة من نوافذ التطبيق النشطة أو المفوكسة،
     * والرجوع إلى [foregroundPackage] كاحتياط (ع٤٥ - الأمر 2).
     */
    fun resolveForegroundPackage(): String? {
        val service = activeServiceRef.get()
        if (service != null) {
            val appPkg = runCatching {
                service.windows?.firstOrNull { win ->
                    win.type == android.view.accessibility
                        .AccessibilityWindowInfo.TYPE_APPLICATION &&
                        (win.isActive || win.isFocused)
                }?.root?.packageName?.toString()
            }.getOrNull()
            if (!appPkg.isNullOrBlank() &&
                !appPkg.contains("talkback", ignoreCase = true)
            ) {
                return appPkg
            }
        }
        return foregroundPackage()
    }

    /**
     * استخراج تلميح اسم المرسل من العقدة المفوكسة أو رأس المحادثة (ع٤٥).
     */
    fun currentSenderHint(): String? {
        val service = activeServiceRef.get() ?: return null
        return service.extractCurrentSenderHint()
    }

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
     * ع٤٣-3: تجهيز إشارة انتظار تغيّر المحتوى مسبقاً قبل تنفيذ النقر
     * لمنع ضياع الحدث اللاتزامني المتزامن مع الإجراء (Point 2).
     */
    fun prepareContentChangeSignal(): CompletableDeferred<Unit> {
        val deferred = CompletableDeferred<Unit>()
        contentChangeSignal.set(deferred)
        return deferred
    }

    /**
     * انتظار إشارة تغيّر المحتوى المجهزة مسبقاً بمهلة محددة.
     */
    suspend fun awaitContentChange(
        deferred: CompletableDeferred<Unit>,
        timeoutMs: Long
    ): Boolean {
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
     * إلغاء إشارة تغيّر المحتوى في حال عدم تنفيذ أي إجراء توسيع.
     */
    fun cancelContentChangeSignal(deferred: CompletableDeferred<Unit>) {
        contentChangeSignal.compareAndSet(deferred, null)
    }

    /**
     * انتظار لاتزامني غير حاجز حتى وصول إشعار تغيّر المحتوى أو انتهاء المهلة.
     */
    suspend fun waitForContentChange(timeoutMs: Long): Boolean {
        val deferred = prepareContentChangeSignal()
        return awaitContentChange(deferred, timeoutMs)
    }

    /**
     * توسيع الرسالة المقتطعة تلقائياً بمهلة سريعة (ع٢٧-20، ع٤٣-1، ع٤٣-3)
     */
    suspend fun expandReadMore(
        prefix: String,
        timeoutMs: Long = 500L
    ): String? {
        val service = activeServiceRef.get() ?: return null
        return withTimeoutOrNull(timeoutMs) {
            service.expandReadMoreInternal(prefix)
        }
    }

    /** هل الجسر متصل بخدمة إمكانية الوصول الحية؟ */
    fun isConnected(): Boolean = activeServiceRef.get() != null
}
