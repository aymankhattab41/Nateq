package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ذاكرة استعادة آخر تركيز عند العودة إلى التطبيق (ع٥٦-أمر 4).
 */
object FocusMemory {

    const val DEFAULT_RETENTION_MS = 10 * 60 * 1000L // 10 دقائق
    private const val MAX_PACKAGES = 30
    private const val DEBOUNCE_FOCUS_MS = 300L
    private const val WINDOW_SETTLE_MS = 500L

    data class FocusFingerprint(
        val packageName: String,
        val viewId: String?,
        val textHash: Int,
        val text: String,
        val path: List<Int>,
        val timestamp: Long
    )

    private val memory = mutableMapOf<String, FocusFingerprint>()
    private val handler = Handler(Looper.getMainLooper())

    private var pendingPackage: String? = null
    private var pendingNode: AccessibilityNodeInfo? = null
    private var debounceRunnable: Runnable? = null

    /**
     * تسجيل تركيز جديد بعد تأخير 300ms لتجميع الأحداث.
     */
    fun onFocusEvent(
        packageName: String?,
        node: AccessibilityNodeInfo?,
        now: Long = SystemClock.uptimeMillis()
    ) {
        if (packageName.isNullOrBlank() || node == null) return
        if (node.isPassword) return
        if (AppPolicy.isSensitiveApp(packageName)) return

        debounceRunnable?.let { handler.removeCallbacks(it) }

        val runnable = Runnable {
            saveFingerprint(packageName, node, now)
        }
        debounceRunnable = runnable
        handler.postDelayed(runnable, DEBOUNCE_FOCUS_MS)
    }

    private fun saveFingerprint(
        packageName: String,
        node: AccessibilityNodeInfo,
        now: Long
    ) {
        val viewId = node.viewIdResourceName
        val text = extractText(node)
        val textHash = text.hashCode()
        val path = calculatePathFromRoot(node)

        val fp = FocusFingerprint(
            packageName = packageName,
            viewId = viewId,
            textHash = textHash,
            text = text,
            path = path,
            timestamp = now
        )

        synchronized(memory) {
            memory[packageName] = fp
            if (memory.size > MAX_PACKAGES) {
                val oldestKey = memory.minByOrNull { it.value.timestamp }?.key
                oldestKey?.let { memory.remove(it) }
            }
        }
    }

    /**
     * معالجة العودة للتطبيق بعد استقرار النافذة (500ms).
     */
    fun onWindowStateChanged(
        context: Context,
        packageName: String?,
        root: AccessibilityNodeInfo?,
        retentionMs: Long = DEFAULT_RETENTION_MS,
        now: Long = SystemClock.uptimeMillis()
    ) {
        if (packageName.isNullOrBlank() || root == null) return
        if (AppPolicy.isSensitiveApp(packageName)) return

        val fp = synchronized(memory) { memory[packageName] } ?: return
        if (now - fp.timestamp > retentionMs) {
            synchronized(memory) { memory.remove(packageName) }
            return
        }

        handler.postDelayed({
            restoreFocus(context, root, fp)
        }, WINDOW_SETTLE_MS)
    }

    private fun restoreFocus(
        context: Context,
        root: AccessibilityNodeInfo,
        fp: FocusFingerprint
    ): Boolean {
        // البحث بالمعرف أولاً
        var target: AccessibilityNodeInfo? = null
        if (!fp.viewId.isNullOrBlank()) {
            val byId = root.findAccessibilityNodeInfosByViewId(fp.viewId)
            target = byId.firstOrNull { !it.isPassword && it.isVisibleToUser }
        }

        // الاحتياط بالنص
        if (target == null && fp.text.isNotBlank()) {
            val byText = root.findAccessibilityNodeInfosByText(fp.text)
            target = byText.firstOrNull {
                !it.isPassword &&
                    it.isVisibleToUser &&
                    extractText(it).hashCode() == fp.textHash
            }
        }

        // الاحتياط بمسار الشجرة
        if (target == null && fp.path.isNotEmpty()) {
            target = findByPath(root, fp.path)
        }

        if (target != null && !target.isPassword && target.isVisibleToUser) {
            val success = target.performAction(
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
            )
            if (success && fp.text.isNotBlank()) {
                A11ySpeechCoordinator.speakAnnouncement(context, fp.text)
            }
            return success
        }
        return false
    }

    private fun findByPath(
        root: AccessibilityNodeInfo,
        path: List<Int>
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = root
        for (index in path) {
            if (current == null) return null
            if (index in 0 until current.childCount) {
                current = try {
                    current.getChild(index)
                } catch (_: Exception) {
                    null
                }
            } else {
                return null
            }
        }
        return current
    }

    private fun calculatePathFromRoot(
        node: AccessibilityNodeInfo
    ): List<Int> {
        val path = mutableListOf<Int>()
        var current = node
        var parent = current.parent
        var depth = 0

        while (parent != null && depth < 20) {
            var childIndex = -1
            for (i in 0 until parent.childCount) {
                val child = try {
                    parent.getChild(i)
                } catch (_: Exception) {
                    null
                }
                if (child == current) {
                    childIndex = i
                    break
                }
            }
            if (childIndex != -1) {
                path.add(0, childIndex)
            } else {
                break
            }
            current = parent
            parent = current.parent
            depth++
        }
        return path
    }

    private fun extractText(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString()?.trim().orEmpty()
        val cd = node.contentDescription?.toString()?.trim().orEmpty()
        return when {
            t.isNotBlank() && cd.isNotBlank() && t != cd -> "$cd $t"
            t.isNotBlank() -> t
            cd.isNotBlank() -> cd
            else -> ""
        }
    }

    @androidx.annotation.VisibleForTesting
    fun clearForTesting() {
        synchronized(memory) { memory.clear() }
    }

    @androidx.annotation.VisibleForTesting
    fun getFingerprintForTesting(packageName: String): FocusFingerprint? =
        synchronized(memory) { memory[packageName] }
}
