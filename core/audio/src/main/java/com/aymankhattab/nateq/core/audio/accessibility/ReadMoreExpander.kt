package com.aymankhattab.nateq.core.audio.accessibility

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * موسّع «قراءة المزيد» الذكي لتطبيقات المراسلة.
 * محصور في شجرة العقدة المفوكسة دون مسح النافذة،
 * مع ضوابط حاسمة ضد النقر العشوائي:
 * 1) لا نقر لمجرد اقتطاع النص.
 * 2) حصر النقر بالعقدة المفوكسة وأبنائها وإخوتها في نفس الحاوية.
 * 3) شرط مطابقة نص الزر أو وصفه أو إجراء ACTION_EXPAND الصريح.
 * 4) حظر النقر على الحاويات الأبوية أو عناصر القوائم.
 * 5) تحديد معدل النقر: نقرة واحدة لكل عقدة كل 5 ثوانٍ، و3 في الدقيقة للتطبيق.
 * 6) منع إعادة النقر على العقدة إن لم يزد النص بعد نقرها.
 */
object ReadMoreExpander {

    private const val TAG = "NATEQ_A11Y"
    private const val LOG_TAG = "[READMORE]"

    private const val NODE_COOLDOWN_MS = 5000L
    private const val APP_WINDOW_MS = 60_000L
    private const val MAX_CLICKS_PER_APP_MINUTE = 3

    // سجل أوقات النقر لكل عقدة: nodeKey -> timestamp
    private val lastNodeClickTime = ConcurrentHashMap<String, Long>()

    // سجل أوقات النقر لكل تطبيق: pkg -> list of timestamps
    private val appClickHistory = ConcurrentHashMap<String, MutableList<Long>>()

    // العقد التي نُقرت ولم يزد النص بعدها
    private val failedNodes = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastClickedNodeKey: String? = null

    /**
     * محاولة التوسيع الفوري للرسالة المفوكسة عبر استراتيجيات محصورة.
     * @param focusedNode العقدة المفوكسة حالياً
     * @return هل تم تنفيذ نقرة/إجراء توسيع بنجاح؟
     */
    fun tryExpand(focusedNode: AccessibilityNodeInfo?): Boolean {
        if (AppPolicy.isAutomationActive()) return false
        if (focusedNode == null) {
            Log.w(TAG, "$LOG_TAG tryExpand: focusedNode is null")
            return false
        }

        val pkg = focusedNode.packageName?.toString().orEmpty()
        if (!checkAppRateLimit(pkg)) {
            Log.w(
                TAG,
                "$LOG_TAG App rate limit reached for $pkg (max 3/min)"
            )
            return false
        }

        // الاستراتيجية 1: فحص العقدة المفوكسة نفسها (إجراءات أو زر صريح)
        if (tryExpandSelf(focusedNode, pkg)) {
            Log.i(TAG, "$LOG_TAG Strategy 1 (SELF) executed")
            return true
        }

        // الاستراتيجية 2: البحث داخل أبناء العقدة المفوكسة
        if (tryExpandChildren(focusedNode, pkg)) {
            Log.i(TAG, "$LOG_TAG Strategy 2 (CHILDREN) executed")
            return true
        }

        // الاستراتيجية 3: البحث بين إخوة العقدة المفوكسة في نفس الحاوية
        if (tryExpandSiblings(focusedNode, pkg)) {
            Log.i(TAG, "$LOG_TAG Strategy 3 (SIBLINGS) executed")
            return true
        }

        // الاستراتيجية 4: فحص إجراءات الحاوية الأبوية المباشرة فقط (دون نقرها)
        if (tryExpandParentActions(focusedNode, pkg)) {
            Log.i(TAG, "$LOG_TAG Strategy 4 (PARENT_ACTIONS) executed")
            return true
        }

        return false
    }

    /**
     * إبلاغ بنتيجة محاولة التوسيع: هل زاد النص فعلاً؟
     */
    fun onExpandAttemptResult(increased: Boolean) {
        val key = lastClickedNodeKey ?: return
        if (!increased) {
            failedNodes[key] = SystemClock.uptimeMillis()
            Log.w(
                TAG,
                "$LOG_TAG Text did not increase, marking node failed: $key"
            )
        } else {
            failedNodes.remove(key)
        }
        lastClickedNodeKey = null
    }

    private fun tryExpandSelf(
        node: AccessibilityNodeInfo,
        pkg: String
    ): Boolean {
        // فحص إجراءات العقدة لـ ACTION_EXPAND أو إجراء معنون صراحةً
        val actions = runCatching { node.actionList }.getOrNull().orEmpty()
        val hasExpand =
            ((node.actions and AccessibilityNodeInfo.ACTION_EXPAND) != 0) ||
                actions.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND }
        if (hasExpand) {
            if (performActionOnNode(
                    node,
                    AccessibilityNodeInfo.ACTION_EXPAND,
                    pkg,
                    "SELF_EXPAND_ACTION"
                )
            ) {
                return true
            }
        }
        for (action in actions) {
            val label = action.label?.toString()
            if (!label.isNullOrBlank() &&
                SelectorPacks.isReadMoreActionLabel(label)
            ) {
                if (performActionOnNode(
                        node,
                        action.id,
                        pkg,
                        "SELF_NAMED_ACTION"
                    )
                ) {
                    return true
                }
            }
        }

        // إذا كانت العقدة نفسها زر قراءة المزيد الصريح
        if (isStrictReadMoreButton(node) && node.isClickable) {
            if (performClickOnNode(node, pkg, "SELF_CLICK")) {
                return true
            }
        }

        return false
    }

    private fun tryExpandChildren(
        parent: AccessibilityNodeInfo,
        pkg: String
    ): Boolean {
        val count = runCatching { parent.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { parent.getChild(i) }.getOrNull()
                ?: continue
            try {
                if (!isNodeValidForClick(child, parent.windowId)) continue

                // فحص إجراءات الابن
                val actions = runCatching {
                    child.actionList
                }.getOrNull().orEmpty()
                val hasExpand = ((child.actions and
                    AccessibilityNodeInfo.ACTION_EXPAND) != 0) ||
                    actions.any {
                        it.id == AccessibilityNodeInfo.ACTION_EXPAND
                    }
                if (hasExpand) {
                    if (performActionOnNode(
                            child,
                            AccessibilityNodeInfo.ACTION_EXPAND,
                            pkg,
                            "CHILD_EXPAND_ACTION"
                        )
                    ) {
                        return true
                    }
                }
                for (action in actions) {
                    val label = action.label?.toString()
                    if (!label.isNullOrBlank() &&
                        SelectorPacks.isReadMoreActionLabel(label)
                    ) {
                        if (performActionOnNode(
                                child,
                                action.id,
                                pkg,
                                "CHILD_NAMED_ACTION"
                            )
                        ) {
                            return true
                        }
                    }
                }

                // فحص نص أو وصف الابن
                if (isStrictReadMoreButton(child) && child.isClickable) {
                    if (performClickOnNode(child, pkg, "CHILD_CLICK")) {
                        return true
                    }
                }
            } finally {
                SafeNodeWalker.safeRecycle(child)
            }
        }
        return false
    }

    private fun tryExpandSiblings(
        node: AccessibilityNodeInfo,
        pkg: String
    ): Boolean {
        val parent = runCatching { node.parent }.getOrNull() ?: return false
        try {
            val count = runCatching { parent.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                val sibling = runCatching { parent.getChild(i) }.getOrNull()
                    ?: continue
                try {
                    if (sibling == node) continue
                    if (!isNodeValidForClick(sibling, node.windowId)) continue

                    val actions = runCatching {
                        sibling.actionList
                    }.getOrNull().orEmpty()
                    val hasExpand = ((sibling.actions and
                        AccessibilityNodeInfo.ACTION_EXPAND) != 0) ||
                        actions.any {
                            it.id == AccessibilityNodeInfo.ACTION_EXPAND
                        }
                    if (hasExpand) {
                        if (performActionOnNode(
                                sibling,
                                AccessibilityNodeInfo.ACTION_EXPAND,
                                pkg,
                                "SIBLING_EXPAND_ACTION"
                            )
                        ) {
                            return true
                        }
                    }
                    for (action in actions) {
                        val label = action.label?.toString()
                        if (!label.isNullOrBlank() &&
                            SelectorPacks.isReadMoreActionLabel(label)
                        ) {
                            if (performActionOnNode(
                                    sibling,
                                    action.id,
                                    pkg,
                                    "SIBLING_NAMED_ACTION"
                                )
                            ) {
                                return true
                            }
                        }
                    }

                    if (
                        isStrictReadMoreButton(sibling) &&
                        sibling.isClickable
                    ) {
                        if (performClickOnNode(
                                sibling,
                                pkg,
                                "SIBLING_CLICK"
                            )
                        ) {
                            return true
                        }
                    }
                } finally {
                    SafeNodeWalker.safeRecycle(sibling)
                }
            }
        } finally {
            SafeNodeWalker.safeRecycle(parent)
        }
        return false
    }

    private fun tryExpandParentActions(
        node: AccessibilityNodeInfo,
        pkg: String
    ): Boolean {
        val parent = runCatching { node.parent }.getOrNull() ?: return false
        try {
            if (parent.windowId > 0 &&
                node.windowId > 0 &&
                parent.windowId != node.windowId
            ) {
                return false
            }
            val actions = runCatching {
                parent.actionList
            }.getOrNull().orEmpty()
            val hasExpand = ((parent.actions and
                AccessibilityNodeInfo.ACTION_EXPAND) != 0) ||
                actions.any {
                    it.id == AccessibilityNodeInfo.ACTION_EXPAND
                }
            if (hasExpand) {
                if (performActionOnNode(
                        parent,
                        AccessibilityNodeInfo.ACTION_EXPAND,
                        pkg,
                        "PARENT_EXPAND_ACTION"
                    )
                ) {
                    return true
                }
            }
            for (action in actions) {
                val label = action.label?.toString()
                if (!label.isNullOrBlank() &&
                    SelectorPacks.isReadMoreActionLabel(label)
                ) {
                    if (performActionOnNode(
                            parent,
                            action.id,
                            pkg,
                            "PARENT_NAMED_ACTION"
                        )
                    ) {
                        return true
                    }
                }
            }
        } finally {
            SafeNodeWalker.safeRecycle(parent)
        }
        return false
    }

    private fun isNodeValidForClick(
        node: AccessibilityNodeInfo,
        expectedWindowId: Int
    ): Boolean {
        if (expectedWindowId > 0 && node.windowId != expectedWindowId) {
            return false
        }
        if (node.windowId > 0 && !node.isVisibleToUser) {
            return false
        }
        return true
    }

    private fun isStrictReadMoreButton(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && SelectorPacks.isReadMoreText(text)) {
            return true
        }
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && SelectorPacks.isReadMoreText(desc)) {
            return true
        }
        return false
    }

    private fun performClickOnNode(
        node: AccessibilityNodeInfo,
        pkg: String,
        strategy: String
    ): Boolean {
        val key = getNodeKey(node, pkg)
        if (!checkNodeRateLimit(key)) return false

        val ok = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)

        recordClick(pkg, key)
        logClick(node, pkg, strategy, "ACTION_CLICK", ok)
        val hasClick = node.isClickable ||
            ((node.actions and AccessibilityNodeInfo.ACTION_CLICK) != 0) ||
            node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_CLICK
            }
        return ok || hasClick
    }

    private fun performActionOnNode(
        node: AccessibilityNodeInfo,
        actionId: Int,
        pkg: String,
        strategy: String
    ): Boolean {
        val key = getNodeKey(node, pkg)
        if (!checkNodeRateLimit(key)) return false

        val ok = runCatching {
            node.performAction(actionId)
        }.getOrDefault(false)

        recordClick(pkg, key)
        logClick(node, pkg, strategy, "ACTION_$actionId", ok)
        val hasAction = ((node.actions and actionId) != 0) ||
            node.actionList.any { it.id == actionId }
        return ok || hasAction
    }

    private fun checkAppRateLimit(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        val now = SystemClock.uptimeMillis()
        val list = appClickHistory.getOrPut(pkg) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > APP_WINDOW_MS }
            return list.size < MAX_CLICKS_PER_APP_MINUTE
        }
    }

    private fun checkNodeRateLimit(nodeKey: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (failedNodes.containsKey(nodeKey)) {
            Log.w(
                TAG,
                "$LOG_TAG Node previously failed to expand: $nodeKey"
            )
            return false
        }
        val lastTime = lastNodeClickTime[nodeKey]
        if (lastTime != null && now - lastTime < NODE_COOLDOWN_MS) {
            Log.w(
                TAG,
                "$LOG_TAG Node click cooldown active: $nodeKey"
            )
            return false
        }
        return true
    }

    private fun recordClick(pkg: String, nodeKey: String) {
        val now = SystemClock.uptimeMillis()
        lastNodeClickTime[nodeKey] = now
        lastClickedNodeKey = nodeKey
        if (pkg.isNotBlank()) {
            val list = appClickHistory.getOrPut(pkg) { mutableListOf() }
            synchronized(list) {
                list.add(now)
            }
        }
    }

    private fun getNodeKey(node: AccessibilityNodeInfo, pkg: String): String {
        val cls = node.className?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty()
        val txt = node.text?.toString().orEmpty().take(30)
        return "$pkg|$cls|$id|$txt"
    }

    private fun logClick(
        node: AccessibilityNodeInfo,
        pkg: String,
        strategy: String,
        action: String,
        success: Boolean
    ) {
        val cls = node.className?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty()
        val textSnippet = node.text?.toString().orEmpty().take(40)
        Log.i(
            TAG,
            "[READMORE_CLICK] pkg=$pkg, cls=$cls, id=$id, " +
                "text='$textSnippet', strat=$strategy, action=$action, " +
                "res=$success"
        )
    }

    @androidx.annotation.VisibleForTesting
    fun resetStateForTesting() {
        lastNodeClickTime.clear()
        appClickHistory.clear()
        failedNodes.clear()
        lastClickedNodeKey = null
    }
}
