package com.aymankhattab.nateq.core.audio.accessibility

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * مجتاز العقد الآمن (ع٢٧-8):
 * - أقصى عدد عقد: 400
 * - أقصى عمق: 25
 * - مهلة زمنية: 12ms للمرور الواحد
 * - يتخطى العقد غير المرئية وعقد كلمات المرور
 * - لا يستدعي recycle() على API 33+
 */
object SafeNodeWalker {

    const val MAX_NODES = 400
    const val MAX_DEPTH = 25
    const val MAX_TIMEOUT_MS = 12L

    /**
     * يجتاز شجرة العقد من [root] ويعيد قائمة مسطحة من [NodeView].
     */
    fun walk(
        root: AccessibilityNodeInfo?,
        filter: ((AccessibilityNodeInfo) -> Boolean)? = null
    ): List<NodeView> {
        if (root == null) return emptyList()
        val result = mutableListOf<NodeView>()
        val startTime = SystemClock.uptimeMillis()

        traverse(
            node = root,
            depth = 0,
            result = result,
            filter = filter,
            startTime = startTime
        )
        return result
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        depth: Int,
        result: MutableList<NodeView>,
        filter: ((AccessibilityNodeInfo) -> Boolean)?,
        startTime: Long
    ) {
        if (result.size >= MAX_NODES || depth > MAX_DEPTH) return
        if (SystemClock.uptimeMillis() - startTime > MAX_TIMEOUT_MS) return

        // تخطي العقد غير المرئية وكلمات المرور
        if (!node.isVisibleToUser || node.isPassword) return

        if (filter == null || filter(node)) {
            result.add(extractNodeView(node, depth))
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (result.size >= MAX_NODES) break
            if (SystemClock.uptimeMillis() - startTime > MAX_TIMEOUT_MS) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                traverse(child, depth + 1, result, filter, startTime)
            } finally {
                safeRecycle(child)
            }
        }
    }

    /**
     * استخراج بيانات العقدة ككائن [NodeView] مسطّح.
     */
    fun extractNodeView(
        node: AccessibilityNodeInfo,
        depth: Int = 0
    ): NodeView {
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }

        val customLabels = mutableListOf<CharSequence>()
        val actionsList = mutableListOf<Int>()
        node.actionList?.forEach { action ->
            actionsList.add(action.id)
            action.label?.let { customLabels.add(it) }
        }

        return NodeView(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            viewIdResourceName = runCatching {
                node.viewIdResourceName
            }.getOrNull(),
            packageName = node.packageName?.toString(),
            boundsInScreen = rect,
            isVisibleToUser = node.isVisibleToUser,
            isClickable = node.isClickable,
            isFocusable = node.isFocusable,
            isAccessibilityFocused = node.isAccessibilityFocused,
            isPassword = node.isPassword,
            isEditable = node.isEditable,
            isEnabled = node.isEnabled,
            isCheckable = node.isCheckable,
            isChecked = isCheckedOf(node),
            isSelected = node.isSelected,
            actions = actionsList,
            customActionLabels = customLabels,
            depth = depth
        )
    }

    /**
     * `isChecked` مُهمَل في SDK 37 لعدم توفّر بديلٍ عام على كل المستويات،
     * فالاعتماد عليه لازمٌ لحالة العناصر المحددة.
     */
    @Suppress("DEPRECATION")
    private fun isCheckedOf(node: AccessibilityNodeInfo): Boolean =
        node.isChecked

    /**
     * إعادة تدوير العقدة بأمان (قبل API 33 فقط).
     */
    @Suppress("DEPRECATION")
    fun safeRecycle(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runCatching { node.recycle() }
        }
    }

    /**
     * استخراج النص العميق من العقدة أو أبنائها المباشرين (ع٤٣-1).
     * يفيد عندما تكون العقدة المفوكسة حاوية (مثل ViewGroup/LinearLayout)
     * ونص الرسالة الفعلي موجود داخل عقدة فرعية (TextView).
     */
    fun extractSubtreeText(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val direct = root.text?.toString()?.trim()
        if (!direct.isNullOrBlank()) return direct
        val desc = root.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && !SelectorPacks.isReadMoreTrigger(desc)) {
            return desc
        }
        return findTextInDescendants(root, depth = 0)
    }

    private fun findTextInDescendants(
        node: AccessibilityNodeInfo,
        depth: Int
    ): String {
        if (depth > 4) return ""
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching {
                node.getChild(i)
            }.getOrNull() ?: continue
            try {
                val t = child.text?.toString()?.trim()
                if (!t.isNullOrBlank()) return t
                val desc = child.contentDescription?.toString()?.trim()
                if (!desc.isNullOrBlank() &&
                    !SelectorPacks.isReadMoreTrigger(desc)
                ) {
                    return desc
                }
                val deeper = findTextInDescendants(child, depth + 1)
                if (deeper.isNotBlank()) return deeper
            } finally {
                safeRecycle(child)
            }
        }
        return ""
    }
}
