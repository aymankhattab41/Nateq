package com.aymankhattab.nateq.core.audio.accessibility

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * مصنّف دوّار العناصر (Rotor) لتصفح عناصر الشاشة حسب نوعها (ع٥٦-أمر 1).
 */
object ScreenRotor {

    const val DEFAULT_MAX_NODES = 2000
    const val MAX_TIME_BUDGET_MS = 400L

    enum class RotorCategory {
        BUTTONS,
        HEADINGS,
        LINKS,
        INPUT_FIELDS,
        IMAGES,
        LISTS,
        CONTROLS
    }

    data class RotorNodeItem(
        val category: RotorCategory,
        val text: String,
        val node: AccessibilityNodeInfo?
    )

    /**
     * مسح شجرة العقد وتصنيف العناصر بحد أقصى للعقد والميزانية الزمنية.
     */
    fun scan(
        root: AccessibilityNodeInfo?,
        maxNodes: Int = DEFAULT_MAX_NODES,
        maxTimeMs: Long = MAX_TIME_BUDGET_MS
    ): Map<RotorCategory, List<RotorNodeItem>> {
        if (root == null) return emptyMap()

        val results = mutableMapOf<RotorCategory, MutableList<RotorNodeItem>>()
        for (cat in RotorCategory.entries) {
            results[cat] = mutableListOf()
        }

        val startTime = SystemClock.uptimeMillis()
        var scannedCount = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (scannedCount >= maxNodes) return
            if (SystemClock.uptimeMillis() - startTime > maxTimeMs) return

            scannedCount++

            // استبعاد حقول كلمات المرور والعقد غير المرئية
            if (node.isPassword || !node.isVisibleToUser) {
                return
            }

            classify(node)?.let { item ->
                results[item.category]?.add(item)
            }

            for (i in 0 until node.childCount) {
                if (scannedCount >= maxNodes) break
                if (SystemClock.uptimeMillis() - startTime > maxTimeMs) break
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                }
                traverse(child)
            }
        }

        traverse(root)
        return results
    }

    /**
     * تصنيف عقدة واحدة.
     */
    fun classify(node: AccessibilityNodeInfo): RotorNodeItem? {
        val text = extractText(node)
        val className = node.className?.toString().orEmpty()

        val category = when {
            node.isCheckable ||
                className.contains("CheckBox", ignoreCase = true) ||
                className.contains("Switch", ignoreCase = true) ||
                className.contains("ToggleButton", ignoreCase = true) ||
                className.contains("RadioButton", ignoreCase = true) ->
                RotorCategory.CONTROLS

            node.isEditable ||
                className.contains("EditText", ignoreCase = true) ->
                RotorCategory.INPUT_FIELDS

            node.isHeading || isHeadingAction(node) ->
                RotorCategory.HEADINGS

            isLink(node, text) ->
                RotorCategory.LINKS

            className.contains("Button", ignoreCase = true) ||
                (node.isClickable && text.isNotBlank()) ->
                RotorCategory.BUTTONS

            className.contains("ImageView", ignoreCase = true) ||
                className.contains("Image", ignoreCase = true) ->
                RotorCategory.IMAGES

            className.contains("ListView", ignoreCase = true) ||
                className.contains("RecyclerView", ignoreCase = true) ||
                node.collectionInfo != null ||
                node.collectionItemInfo != null ->
                RotorCategory.LISTS

            else -> null
        } ?: return null

        val label = if (text.isNotBlank()) {
            text
        } else {
            category.name
        }
        return RotorNodeItem(category, label, node)
    }

    /**
     * نقل تركيز إمكانية الوصول فقط دون نقر.
     */
    fun focusItem(item: RotorNodeItem): Boolean {
        val target = item.node ?: return false
        return target.performAction(
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
        )
    }

    /**
     * تفعيل العنصر (نقر) بعد ضغطة مؤكدة من المستخدم.
     */
    fun activateItem(item: RotorNodeItem): Boolean {
        val target = item.node ?: return false
        if (target.isClickable) {
            return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var parent = target.parent
        var depth = 0
        while (parent != null && depth < 3) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
            depth++
        }
        return false
    }

    private fun isHeadingAction(node: AccessibilityNodeInfo): Boolean {
        return node.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT ||
                it.label?.contains("HEADING", ignoreCase = true) == true
        }
    }

    private fun isLink(node: AccessibilityNodeInfo, text: String): Boolean {
        if (text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true) ||
            text.startsWith("www.", ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    private fun extractText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        return when {
            text.isNotBlank() && desc.isNotBlank() && text != desc ->
                "$desc $text"
            text.isNotBlank() -> text
            desc.isNotBlank() -> desc
            else -> ""
        }
    }
}
