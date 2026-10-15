package com.aymankhattab.nateq.core.audio.accessibility

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * موسّع «قراءة المزيد» الذكي لتطبيقات المراسلة (ع٢٧-20، ع٢٧-21، ع٤٣-1).
 * يعتمد 4 استراتيجيات مرتبة محصورة في الشجرة المفوكسة وحاويتها دون مسح النافذة:
 * 1) فحص العقدة المفوكسة نفسها (ACTION_EXPAND / إجراء مخصص / نقر مباشر).
 * 2) البحث داخل أبناء العقدة المفوكسة (أزرار أو نصوص فرعية).
 * 3) فحص إخوة العقدة المفوكسة داخل نفس الحاوية (الأب).
 * 4) فحص الحاوية الأبوية المباشرة (فقاعة الرسالة / صف المحادثة).
 */
object ReadMoreExpander {

    private const val TAG = "NATEQ_A11Y"
    private const val LOG_TAG = "[READMORE]"

    /**
     * محاولة التوسيع الفوري للرسالة المفوكسة عبر 4 استراتيجيات مرتبة (ع٤٣-1).
     * @param focusedNode العقدة المفوكسة حالياً (Accessibility focus)
     * @return هل نجح تنفيذ إجراء التوسيع؟
     */
    fun tryExpand(focusedNode: AccessibilityNodeInfo?): Boolean {
        if (focusedNode == null) {
            Log.w(TAG, "$LOG_TAG tryExpand: focusedNode is null")
            return false
        }

        // الاستراتيجية 1: فحص العقدة المفوكسة نفسها
        if (tryExpandSelf(focusedNode)) {
            Log.w(TAG, "$LOG_TAG Strategy 1 (SELF) succeeded")
            return true
        }

        // الاستراتيجية 2: البحث داخل أبناء العقدة المفوكسة
        if (tryExpandChildren(focusedNode)) {
            Log.w(TAG, "$LOG_TAG Strategy 2 (CHILDREN) succeeded")
            return true
        }

        // الاستراتيجية 3: البحث بين إخوة العقدة المفوكسة داخل نفس الحاوية
        if (tryExpandSiblings(focusedNode)) {
            Log.w(TAG, "$LOG_TAG Strategy 3 (SIBLINGS) succeeded")
            return true
        }

        // الاستراتيجية 4: فحص الحاوية الأبوية المباشرة (فقاعة الرسالة)
        if (tryExpandParentContainer(focusedNode)) {
            Log.w(TAG, "$LOG_TAG Strategy 4 (PARENT_CONTAINER) succeeded")
            return true
        }

        Log.w(TAG, "$LOG_TAG No expand trigger found in focused subtree")
        return false
    }

    /**
     * الاستراتيجية 1: فحص العقدة المفوكسة نفسها للإجراءات المخصصة والنقر.
     */
    private fun tryExpandSelf(node: AccessibilityNodeInfo): Boolean {
        // 1. فحص قائمة الإجراءات لـ ACTION_EXPAND أو إجراء معنون بقراءة المزيد
        val actions = runCatching { node.actionList }.getOrNull().orEmpty()
        for (action in actions) {
            if (action.id == AccessibilityNodeInfo.ACTION_EXPAND) {
                val ok = runCatching {
                    node.performAction(AccessibilityNodeInfo.ACTION_EXPAND)
                }.getOrDefault(false)
                if (ok) return true
            }
            val label = action.label?.toString()
            if (!label.isNullOrBlank() &&
                SelectorPacks.isReadMoreTrigger(label)
            ) {
                val ok = runCatching {
                    node.performAction(action.id)
                }.getOrDefault(false)
                if (ok) return true
            }
        }

        // 2. إذا كانت العقدة نفسها زر قراءة المزيد وهي قابلة للنقر
        if (isTriggerNodeText(node) && node.isClickable) {
            return runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
        }

        // 3. إذا كانت العقدة نص رسالة مقتطعة قابلة للنقر مباشرة
        val text = node.text?.toString()
        if (node.isClickable &&
            !text.isNullOrBlank() &&
            SelectorPacks.looksTruncated(text)
        ) {
            return runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
        }

        return false
    }

    /**
     * الاستراتيجية 2: البحث في أبناء العقدة المفوكسة (حتى عمق 3).
     */
    private fun tryExpandChildren(
        parent: AccessibilityNodeInfo,
        depth: Int = 0
    ): Boolean {
        if (depth > 5) return false
        val count = runCatching { parent.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { parent.getChild(i) }.getOrNull()
                ?: continue
            try {
                if (isTriggerNode(child)) {
                    if (performClickOrAction(child)) return true
                }
                if (tryExpandChildren(child, depth + 1)) return true
            } finally {
                SafeNodeWalker.safeRecycle(child)
            }
        }
        return false
    }

    /**
     * الاستراتيجية 3: البحث بين إخوة العقدة المفوكسة في نفس الحاوية (الأب).
     */
    private fun tryExpandSiblings(focusedNode: AccessibilityNodeInfo): Boolean {
        val parent = runCatching { focusedNode.parent }.getOrNull()
            ?: return false
        try {
            val count = runCatching { parent.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                val sibling = runCatching { parent.getChild(i) }.getOrNull()
                    ?: continue
                try {
                    if (sibling == focusedNode) continue
                    if (isTriggerNode(sibling)) {
                        if (performClickOrAction(sibling)) return true
                    }
                    if (tryExpandChildren(sibling, depth = 1)) return true
                } finally {
                    SafeNodeWalker.safeRecycle(sibling)
                }
            }
        } finally {
            SafeNodeWalker.safeRecycle(parent)
        }
        return false
    }

    /**
     * الاستراتيجية 4: فحص الحاوية الأبوية المباشرة أو الجد (فقاعة الرسالة).
     */
    private fun tryExpandParentContainer(
        focusedNode: AccessibilityNodeInfo
    ): Boolean {
        var current = runCatching { focusedNode.parent }.getOrNull()
            ?: return false
        var levels = 0
        try {
            while (levels < 2) {
                // فحص إجراءات الحاوية
                val actions = runCatching { current.actionList }.getOrNull()
                    .orEmpty()
                for (action in actions) {
                    if (action.id == AccessibilityNodeInfo.ACTION_EXPAND) {
                        val ok = runCatching {
                            current.performAction(
                                AccessibilityNodeInfo.ACTION_EXPAND
                            )
                        }.getOrDefault(false)
                        if (ok) return true
                    }
                    val label = action.label?.toString()
                    if (!label.isNullOrBlank() &&
                        SelectorPacks.isReadMoreTrigger(label)
                    ) {
                        val ok = runCatching {
                            current.performAction(action.id)
                        }.getOrDefault(false)
                        if (ok) return true
                    }
                }

                // فحص إخوة الحاوية إن كان هناك زر في نفس الصف
                val grandParent = runCatching { current.parent }.getOrNull()
                    ?: break
                try {
                    val count = runCatching {
                        grandParent.childCount
                    }.getOrDefault(0)
                    for (i in 0 until count) {
                        val uncle = runCatching {
                            grandParent.getChild(i)
                        }.getOrNull() ?: continue
                        try {
                            if (uncle == current) continue
                            if (isTriggerNode(uncle)) {
                                if (performClickOrAction(uncle)) return true
                            }
                        } finally {
                            SafeNodeWalker.safeRecycle(uncle)
                        }
                    }
                } finally {
                    SafeNodeWalker.safeRecycle(current)
                    current = grandParent
                }
                levels++
            }
        } finally {
            SafeNodeWalker.safeRecycle(current)
        }
        return false
    }

    private fun isTriggerNode(node: AccessibilityNodeInfo): Boolean {
        if (isTriggerNodeText(node)) return true
        val actions = runCatching { node.actionList }.getOrNull().orEmpty()
        return actions.any { action ->
            action.id == AccessibilityNodeInfo.ACTION_EXPAND ||
                (!action.label.isNullOrBlank() &&
                    SelectorPacks.isReadMoreTrigger(action.label.toString()))
        }
    }

    private fun isTriggerNodeText(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && SelectorPacks.isReadMoreTrigger(text)) {
            return true
        }
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && SelectorPacks.isReadMoreTrigger(desc)) {
            return true
        }
        return false
    }

    private fun performClickOrAction(node: AccessibilityNodeInfo): Boolean {
        val actions = runCatching { node.actionList }.getOrNull().orEmpty()
        val expandAction = actions.firstOrNull {
            it.id == AccessibilityNodeInfo.ACTION_EXPAND ||
                (!it.label.isNullOrBlank() &&
                    SelectorPacks.isReadMoreTrigger(it.label.toString()))
        }
        if (expandAction != null) {
            val ok = runCatching {
                node.performAction(expandAction.id)
            }.getOrDefault(false)
            if (ok) return true
        }

        if (node.isClickable) {
            return runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
        }

        // محاولة النقر على الوالد إن كان هو القابل للنقر
        var parent = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (parent != null && depth < 2) {
            try {
                if (parent.isClickable) {
                    val ok = runCatching {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrDefault(false)
                    if (ok) return true
                }
                val next = runCatching { parent.parent }.getOrNull()
                SafeNodeWalker.safeRecycle(parent)
                parent = next
                depth++
            } catch (_: Throwable) {
                SafeNodeWalker.safeRecycle(parent)
                break
            }
        }
        return false
    }
}
