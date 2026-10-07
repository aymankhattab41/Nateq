package com.aymankhattab.nateq.core.audio.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * موسّع «قراءة المزيد» الذكي لتطبيقات المراسلة (ع٢٧-20، ع٢٧-21).
 * يكتشف أزرار التوسيع وينقر عليها تلقائياً لقراءة الرسالة كاملة.
 */
object ReadMoreExpander {

    /**
     * البحث عن عقدة «قراءة المزيد» داخل العقدة المعطاة أو أبنائها
     */
    fun findReadMoreNode(
        root: AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        if (SelectorPacks.isReadMoreTrigger(root.text) ||
            SelectorPacks.isReadMoreTrigger(root.contentDescription)
        ) {
            return root
        }

        val count = root.childCount
        for (i in 0 until count) {
            val child = runCatching { root.getChild(i) }.getOrNull() ?: continue
            val found = findReadMoreNode(child)
            if (found != null) {
                return found
            }
            SafeNodeWalker.safeRecycle(child)
        }
        return null
    }

    /**
     * محاولة التوسيع الفوري بالنقر على زر قراءة المزيد
     */
    fun tryExpand(node: AccessibilityNodeInfo?): Boolean {
        val target = findReadMoreNode(node) ?: return false
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            if (target != node) {
                SafeNodeWalker.safeRecycle(target)
            }
        }
    }
}
