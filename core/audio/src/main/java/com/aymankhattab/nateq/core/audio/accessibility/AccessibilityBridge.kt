package com.aymankhattab.nateq.core.audio.accessibility

import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * جسر التواصل بين خدمة إمكانية الوصول ومحرك النطق في العملية :tts (ع٢٧-3).
 * - يوفّر: foregroundPackage, focusSnapshot, expandReadMore.
 * - لا يحتفظ بـ AccessibilityNodeInfo خارج المعالج لتفادي التسريب.
 */
object AccessibilityBridge {

    data class ResolvedPackageInfo(
        val packageName: String?,
        val source: String
    )

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

    @androidx.annotation.VisibleForTesting
    fun clearForegroundForTesting() {
        foregroundPackageRef.set(null)
        foregroundActivityRef.set(null)
    }

    /**
     * فحص ما إذا كانت الحزمة تنتمي لقارئ شاشة أو لوحة مفاتيح
     * أو تراكب نظام (الأمر 5).
     */
    fun isIgnoredPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        val lower = pkg.lowercase(Locale.ROOT)
        if (lower.contains("talkback") ||
            lower.contains("accessibility") ||
            lower.contains("screenreader") ||
            lower.contains("espeak") ||
            lower.contains("vocalizer") ||
            lower == "com.aymankhattab.nateq" ||
            lower == "com.android.systemui" ||
            lower == "android" ||
            lower.contains("inputmethod") ||
            lower.contains("latin") ||
            lower.contains("gboard") ||
            lower.contains("swiftkey") ||
            lower.contains("samsungime")
        ) {
            return true
        }
        return false
    }

    /**
     * حسم الحزمة الأمامية مع المصدر بدقة (عقدة مفوكسة، نافذة، احتياط)
     * — الجولة 48 (الأمر 5).
     */
    fun resolveForegroundPackageWithSource(): ResolvedPackageInfo {
        val service = activeServiceRef.get()
        if (service != null) {
            // 1) الأولوية لحزمة العقدة المفوكسة نفسها
            val rootPkg = runCatching {
                service.rootInActiveWindow?.packageName?.toString()
            }.getOrNull()
            if (!isIgnoredPackage(rootPkg)) {
                return ResolvedPackageInfo(rootPkg, "عقدة مفوكسة")
            }

            val snapshotPkg = runCatching {
                service.findCurrentAccessibilityFocusSnapshot()?.packageName
            }.getOrNull()
            if (!isIgnoredPackage(snapshotPkg)) {
                return ResolvedPackageInfo(snapshotPkg, "عقدة مفوكسة")
            }

            val lastFocusPkg = service.lastFocusedPackage
            if (!isIgnoredPackage(lastFocusPkg)) {
                return ResolvedPackageInfo(lastFocusPkg, "عقدة مفوكسة")
            }

            // 2) نافذة isFocused ثم isActive
            val wins = runCatching { service.windows }.getOrNull().orEmpty()
            val focusedWinPkg = wins.firstOrNull { win ->
                win.type == android.view.accessibility
                    .AccessibilityWindowInfo.TYPE_APPLICATION && win.isFocused
            }?.root?.packageName?.toString()
            if (!isIgnoredPackage(focusedWinPkg)) {
                return ResolvedPackageInfo(focusedWinPkg, "نافذة مفوكسة")
            }

            val activeWinPkg = wins.firstOrNull { win ->
                win.type == android.view.accessibility
                    .AccessibilityWindowInfo.TYPE_APPLICATION && win.isActive
            }?.root?.packageName?.toString()
            if (!isIgnoredPackage(activeWinPkg)) {
                return ResolvedPackageInfo(activeWinPkg, "نافذة نشطة")
            }
        }

        // 3) احتياط من foregroundPackage()
        val fg = foregroundPackage()
        if (!isIgnoredPackage(fg)) {
            return ResolvedPackageInfo(fg, "احتياط")
        }

        return ResolvedPackageInfo(null, "غير محدد")
    }

    /**
     * حساب الحزمة الأمامية بدقة من نوافذ التطبيق النشطة أو المفوكسة،
     * والرجوع إلى [foregroundPackage] كاحتياط (ع٤٥، الجولة 48 - الأمر 5).
     */
    fun resolveForegroundPackage(): String? =
        resolveForegroundPackageWithSource().packageName

    /**
     * تنفيذ نقرة إيماءة في إحداثيات محددة على الشاشة (الجولة 48 - الأمر 4).
     */
    fun dispatchClickGesture(x: Float, y: Float): Boolean {
        val service = activeServiceRef.get() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = android.graphics.Path().apply { moveTo(x, y) }
            val stroke = android.accessibilityservice
                .GestureDescription.StrokeDescription(path, 0L, 50L)
            val gesture = android.accessibilityservice
                .GestureDescription.Builder().addStroke(stroke).build()
            return service.dispatchGesture(gesture, null, null)
        }
        return false
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
        timeoutMs: Long = 200L
    ): String? {
        val service = activeServiceRef.get() ?: return null
        return withTimeoutOrNull(timeoutMs) {
            service.expandReadMoreInternal(prefix)
        }
    }

    /** هل الجسر متصل بخدمة إمكانية الوصول الحية؟ */
    fun isConnected(): Boolean = activeServiceRef.get() != null
}
