package com.aymankhattab.nateq.core.audio.accessibility

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * موسّع «قراءة المزيد» لتطبيقات المراسلة (واتساب/واتساب بزنس).
 * - البحث والنقر مقتصران على تحكّم توسعة صريح داخل حاوية الرسالة
 *   (ACTION_EXPAND، إجراء مسمّى، زر مؤكد قابل للنقر، أو ClickableSpan
 *   فعلي بنص توسعة صريح)، ولا ينقر على الطول/النقاط وحدها أو على حاوية
 *   عامة/رابط مجاور.
 * - يدعم تدفّقاً مُداراً بالهوية (canAttempt/onClickIssued/onAttemptFinished)
 *   وتوافقاً خلفياً مع [tryExpand] المحروس.
 * - يمنع التكرار/الحلقات ويتجاوز تبريد الفشل القديم؛ التشخيص بلا متن.
 */
object ReadMoreExpander {

    private const val TAG = "NATEQ_A11Y"
    private const val LOG_TAG = "[READMORE]"

    // تبريد لكل عقدة/رسالة، وحدّ معدل لكل تطبيق في الدقيقة.
    private const val NODE_COOLDOWN_MS = 4000L
    private const val APP_WINDOW_MS = 60_000L
    private const val MAX_CLICKS_PER_APP_MINUTE = 12

    // حجب عقدة/رسالة فاشلة مؤقتاً ثم تجاوز التبريد تلقائياً بعد انتهائه.
    private const val FAILED_NODE_TTL_MS = 60_000L

    // قاطع دائرة مؤقت بعد إخفاقات متتالية لنفس التطبيق.
    private const val CIRCUIT_FAIL_THRESHOLD = 3
    private const val CIRCUIT_COOLDOWN_MS = 60_000L

    private const val MAX_SEARCH_NODES = 48

    // nodeKey/identity -> آخر وقت نقرة ناجحة/مسجَّلة
    private val lastNodeClickTime = ConcurrentHashMap<String, Long>()
    // pkg -> طوابع نقرات ضمن نافذة الدقيقة
    private val appClickHistory = ConcurrentHashMap<String, MutableList<Long>>()
    // nodeKey/identity -> وقت تسجيل الفشل
    private val failedNodes = ConcurrentHashMap<String, Long>()
    // pkg -> عداد إخفاقات متتالية وقاطع دائرة
    private val appConsecutiveFailures = ConcurrentHashMap<String, Int>()
    private val appCircuitCooldownUntil = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastIssuedKey: String? = null
    @Volatile
    private var lastIssuedPkg: String = ""

    // ===== تدفّق مُدار بالهوية (NateqAccessibilityService) =====

    /** هل يُسمح بمحاولة توسعة لهذه الرسالة الآن؟ (بدون آثار جانبية) */
    fun canAttempt(
        pkg: String,
        container: AccessibilityNodeInfo,
        identity: String
    ): Boolean {
        if (AppPolicy.isAutomationActive()) return false
        val p = pkg.ifBlank { container.packageName?.toString().orEmpty() }
        if (!checkAppRateLimit(p)) return false
        val key = effectiveKey(identity, container, p)
        val now = SystemClock.uptimeMillis()
        val last = lastNodeClickTime[key]
        if (last != null && now - last < NODE_COOLDOWN_MS) return false
        val failedAt = failedNodes[key]
        if (failedAt != null) {
            if (now - failedAt < FAILED_NODE_TTL_MS) {
                return false
            }
            // تجاوز تبريد الفشل القديم تلقائياً
            failedNodes.remove(key)
        }
        return true
    }

    /** يسجّل إصدار نقرة توسعة لهوية رسالة (بعد نجاح التنفيذ). */
    fun onClickIssued(pkg: String, identity: String) {
        val key = identity.ifBlank { "$pkg|unknown" }
        val now = SystemClock.uptimeMillis()
        lastNodeClickTime[key] = now
        lastIssuedKey = key
        lastIssuedPkg = pkg
        recordAppClick(pkg, now)
    }

    /** يسجّل نتيجة محاولة توسعة لهوية رسالة. */
    fun onAttemptFinished(
        pkg: String,
        identity: String,
        increased: Boolean,
        oldLength: Int,
        newLength: Int
    ) {
        val key = identity.ifBlank { lastIssuedKey.orEmpty() }
        val p = pkg.ifBlank { lastIssuedPkg }
        if (!increased) {
            if (key.isNotBlank()) {
                failedNodes[key] = SystemClock.uptimeMillis()
            }
            recordFailure(p, oldLength, newLength)
        } else {
            if (key.isNotBlank()) failedNodes.remove(key)
            appConsecutiveFailures[p] = 0
            Log.i(
                TAG,
                "$LOG_TAG نتيجة توسعة: نجح تحديث الرسالة " +
                    "(طول=$oldLength->$newLength)"
            )
        }
        lastIssuedKey = null
        lastIssuedPkg = ""
    }

    // ===== توافق خلفي (اختبارات/مسارات قديمة) =====

    /**
     * محاولة توسعة محروسة: تفحص معدّل التطبيق/قاطع الدائرة ثم تبحث عن
     * تحكّم توسعة صريح داخل الرسالة (ذاتها/أبناؤها/حاوية الأب المقيدة)
     * وتطبّق تبريد العقدة وحجب الفشل المؤقت، وتسجّل النقرة.
     */
    fun tryExpand(focusedNode: AccessibilityNodeInfo?): Boolean {
        if (AppPolicy.isAutomationActive()) return false
        if (focusedNode == null) return false
        val pkg = focusedNode.packageName?.toString().orEmpty()
        if (!checkAppRateLimit(pkg)) return false

        val r1 = tryExpandGuardedIn(focusedNode, focusedNode, pkg)
        if (r1 != null) return r1

        val parent = runCatching { focusedNode.parent }.getOrNull()
            ?: return false
        return try {
            tryExpandGuardedIn(parent, focusedNode, pkg) ?: false
        } finally {
            SafeNodeWalker.safeRecycle(parent)
        }
    }

    fun onExpandAttemptResult(
        pkg: String,
        increased: Boolean,
        oldLength: Int = 0,
        newLength: Int = 0
    ) {
        onAttemptFinished(
            pkg, lastIssuedKey.orEmpty(), increased, oldLength, newLength
        )
    }

    fun onExpandAttemptResult(increased: Boolean) {
        onExpandAttemptResult(lastIssuedPkg, increased)
    }

    @androidx.annotation.VisibleForTesting
    fun resetStateForTesting() {
        lastNodeClickTime.clear()
        appClickHistory.clear()
        failedNodes.clear()
        appConsecutiveFailures.clear()
        appCircuitCooldownUntil.clear()
        lastIssuedKey = null
        lastIssuedPkg = ""
    }

    // ===== تنفيذ النقر المحصور (بدون آثار حالة للتدفّق المُدار) =====

    /**
     * ينفّذ نقرة/إجراء توسعة صريح داخل حاوية الرسالة فقط.
     * لا يطبّق تبريد/معدّل/حجب فشل (يتولّاها المتصل المُدار)؛ يُعيد
     * ما إذا نُفّذ إجراء صريح فعلاً.
     */
    fun tryExpandConfined(
        container: AccessibilityNodeInfo,
        focused: AccessibilityNodeInfo
    ): Boolean {
        val windowId = runCatching { container.windowId }.getOrDefault(-1)
        val target = findBestTargetIn(container, focused, windowId)
            ?: return false
        val ok = performTarget(target)
        logAction(target.kind, ok)
        return ok
    }

    // ===== التنفيذ المحروس (للتوافق الخلفي) =====

    /**
     * يبحث عن أفضل هدف صريح داخل [container]، فإن وُجد طبّق تبريد
     * العقدة/حجب الفشل ونفّذ وسجّل؛ يعيد null إن لم يوجد هدف.
     */
    private fun tryExpandGuardedIn(
        container: AccessibilityNodeInfo,
        focused: AccessibilityNodeInfo,
        pkg: String
    ): Boolean? {
        val windowId = runCatching { container.windowId }.getOrDefault(-1)
        val target = findBestTargetIn(container, focused, windowId)
            ?: return null
        val key = nodeKey(target.node, pkg)
        val now = SystemClock.uptimeMillis()
        if (!checkNodeRateLimit(key, now)) return false
        val ok = performTarget(target)
        // التسجيل غير مشروط (حتى عند فشل التنفيذ) لمنع الطرق المتكرر.
        recordClick(pkg, key, now)
        logAction(target.kind, ok)
        return ok
    }

    // ===== العثور على الهدف الصريح =====

    private enum class TargetKind {
        EXPAND_ACTION, NAMED_ACTION, CONFIRMED_BUTTON, CLICKABLE_SPAN
    }

    private data class FoundTarget(
        val node: AccessibilityNodeInfo,
        val kind: TargetKind,
        val actionId: Int = AccessibilityNodeInfo.ACTION_CLICK,
        val spanText: String? = null,
        val score: Int = 0,
        val owned: Boolean = false
    )

    private fun findBestTargetIn(
        container: AccessibilityNodeInfo,
        focused: AccessibilityNodeInfo,
        windowId: Int
    ): FoundTarget? {
        var best: FoundTarget? = null
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(container to false)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SEARCH_NODES) {
            val (node, owned) = queue.removeFirst()
            visited++
            if (!isInSameWindow(node, windowId)) {
                if (owned) SafeNodeWalker.safeRecycle(node)
                continue
            }
            val candidate = classifyNode(node, owned)
            var keepNode = false
            if (candidate != null &&
                (best == null || candidate.score > best!!.score)
            ) {
                // استبدال المرشح السابق مع تحرير نسخه المملوكة إن وجدت.
                val prev = best
                if (prev != null && prev.owned && prev.node !== node) {
                    SafeNodeWalker.safeRecycle(prev.node)
                }
                best = candidate
                keepNode = candidate.owned
                if (best!!.score >= 100) {
                    if (owned && !keepNode) {
                        SafeNodeWalker.safeRecycle(node)
                    }
                    drainOwned(queue)
                    // نفّذ على نسخة غير مملوكة بمسار البحث (لا نُحررها هنا)
                    return best
                }
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                if (visited + queue.size >= MAX_SEARCH_NODES) break
                val child = runCatching { node.getChild(i) }.getOrNull()
                    ?: continue
                val vis = runCatching { child.isVisibleToUser }
                    .getOrDefault(true)
                if (windowId > 0 && !vis) {
                    SafeNodeWalker.safeRecycle(child)
                    continue
                }
                queue.add(child to true)
            }
            if (owned && !keepNode) SafeNodeWalker.safeRecycle(node)
        }
        drainOwned(queue)

        // تفضيل أولوية أعلى في العنصر المفوكس/الحاوية (غير مملوكين هنا).
        if (isInSameWindow(focused, windowId)) {
            val f = classifyNode(focused, false)
            if (f != null && (best == null || f.score > best!!.score)) {
                if (best != null && best!!.owned) {
                    SafeNodeWalker.safeRecycle(best!!.node)
                }
                best = f
            }
        }
        if (isInSameWindow(container, windowId) && container !== focused) {
            val c = classifyNode(container, false)
            if (c != null && (best == null || c.score > best!!.score)) {
                if (best != null && best!!.owned) {
                    SafeNodeWalker.safeRecycle(best!!.node)
                }
                best = c
            }
        }
        return best
    }

    private fun drainOwned(
        queue: ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>
    ) {
        while (queue.isNotEmpty()) {
            val (n, owned) = queue.removeFirst()
            if (owned) SafeNodeWalker.safeRecycle(n)
        }
    }

    private fun classifyNode(
        node: AccessibilityNodeInfo,
        owned: Boolean
    ): FoundTarget? {
        val actions = runCatching { node.actionList }.getOrNull().orEmpty()
        val hasExpand =
            (node.actions and AccessibilityNodeInfo.ACTION_EXPAND) != 0 ||
                actions.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND }
        if (hasExpand) {
            return FoundTarget(
                node, TargetKind.EXPAND_ACTION,
                AccessibilityNodeInfo.ACTION_EXPAND, score = 100,
                owned = owned
            )
        }

        val named = actions.firstOrNull { a ->
            val l = a.label?.toString().orEmpty()
            l.isNotBlank() && SelectorPacks.isReadMoreActionLabel(l)
        }
        if (named != null) {
            return FoundTarget(
                node, TargetKind.NAMED_ACTION, named.id, score = 90,
                owned = owned
            )
        }

        val clickable = runCatching { node.isClickable }.getOrDefault(false) ||
            runCatching { node.isLongClickable }.getOrDefault(false)
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val controlLabel = isControlLabel(text) || isControlLabel(desc)
        if (clickable && controlLabel) {
            return FoundTarget(
                node, TargetKind.CONFIRMED_BUTTON, score = 80,
                owned = owned
            )
        }

        val spanText = explicitClickableSpanText(node)
        if (spanText != null) {
            return FoundTarget(
                node, TargetKind.CLICKABLE_SPAN,
                spanText = spanText, score = 70,
                owned = owned
            )
        }
        return null
    }

    /** هل النص تسمية تحكّم توسعة صريحة (قصيرة) لا محتوى رسالة؟ */
    private fun isControlLabel(text: String): Boolean {
        if (text.isBlank()) return false
        if (!SelectorPacks.isExplicitExpandText(text)) return false
        val clean = SelectorPacks.cleanBidi(text).trim()
        return clean.length in 1..60
    }

    private fun explicitClickableSpanText(
        node: AccessibilityNodeInfo
    ): String? {
        val sp = node.text as? android.text.Spanned ?: return null
        val spans = runCatching {
            sp.getSpans(
                0, sp.length, android.text.style.ClickableSpan::class.java
            )
        }.getOrNull().orEmpty()
        if (spans.isEmpty()) return null
        return spans.firstNotNullOfOrNull { span ->
            val start = sp.getSpanStart(span).coerceAtLeast(0)
            val end = sp.getSpanEnd(span).coerceAtMost(sp.length)
            if (end <= start) return@firstNotNullOfOrNull null
            val s = sp.subSequence(start, end).toString().trim()
            if (SelectorPacks.isExplicitExpandText(s)) s else null
        }
    }

    private fun performTarget(target: FoundTarget): Boolean {
        return try {
            when (target.kind) {
                TargetKind.EXPAND_ACTION -> performAction(
                    target.node, AccessibilityNodeInfo.ACTION_EXPAND
                )
                TargetKind.NAMED_ACTION -> performAction(
                    target.node, target.actionId
                )
                TargetKind.CONFIRMED_BUTTON -> performClick(target.node)
                TargetKind.CLICKABLE_SPAN -> {
                    // نقر العقدة الحاوية لنص الامتداد الصريح (لا نستهدف
                    // روابط مجاورة لأن امتداد التوسعة نفسه تحقّق صراحة).
                    performClick(target.node)
                }
            }
        } finally {
            if (target.owned) SafeNodeWalker.safeRecycle(target.node)
        }
    }

    private fun performAction(
        node: AccessibilityNodeInfo, actionId: Int
    ): Boolean =
        runCatching { node.performAction(actionId) }.getOrDefault(false)

    private fun performClick(node: AccessibilityNodeInfo): Boolean =
        runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)

    private fun isInSameWindow(
        node: AccessibilityNodeInfo, windowId: Int
    ): Boolean {
        if (windowId <= 0) return true
        val wid = runCatching { node.windowId }.getOrDefault(-1)
        return wid <= 0 || wid == windowId
    }

    private fun nodeKey(node: AccessibilityNodeInfo, pkg: String): String {
        val rect = android.graphics.Rect()
        runCatching { node.getBoundsInScreen(rect) }
        val id = runCatching { node.viewIdResourceName }.getOrNull().orEmpty()
        val cls = node.className?.toString().orEmpty()
        val text = node.text?.toString().orEmpty().take(40)
        val desc = node.contentDescription?.toString().orEmpty().take(40)
        val wid = runCatching { node.windowId }.getOrDefault(-1)
        return "$pkg|$wid|$id|$cls|${rect.flattenToString()}|$text|$desc"
    }

    private fun effectiveKey(
        identity: String,
        container: AccessibilityNodeInfo,
        pkg: String
    ): String = identity.ifBlank { nodeKey(container, pkg) }

    // ===== حراسة المعدّل والتبريد =====

    private fun checkNodeRateLimit(key: String, now: Long): Boolean {
        val last = lastNodeClickTime[key]
        if (last != null && now - last < NODE_COOLDOWN_MS) return false
        val failedAt = failedNodes[key]
        if (failedAt != null) {
            if (now - failedAt < FAILED_NODE_TTL_MS) return false
            failedNodes.remove(key)
        }
        return true
    }

    private fun checkAppRateLimit(pkg: String): Boolean {
        if (pkg.isBlank()) {
            // بلا حزمة معروفة لا نطبّق قاطع دائرة، لكن لا نُسقط.
            return true
        }
        val now = SystemClock.uptimeMillis()
        val cooldown = appCircuitCooldownUntil[pkg]
        if (cooldown != null) {
            if (now < cooldown) return false
            appCircuitCooldownUntil.remove(pkg)
        }
        val list = appClickHistory.getOrPut(pkg) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > APP_WINDOW_MS }
            return list.size < MAX_CLICKS_PER_APP_MINUTE
        }
    }

    private fun recordClick(pkg: String, key: String, now: Long) {
        lastNodeClickTime[key] = now
        lastIssuedKey = key
        lastIssuedPkg = pkg
        recordAppClick(pkg, now)
    }

    private fun recordAppClick(pkg: String, now: Long) {
        if (pkg.isBlank()) return
        val list = appClickHistory.getOrPut(pkg) { mutableListOf() }
        synchronized(list) { list.add(now) }
    }

    private fun recordFailure(pkg: String, oldLength: Int, newLength: Int) {
        if (pkg.isBlank()) {
            Log.w(
                TAG,
                "$LOG_TAG نتيجة توسعة: فشل تحديث الرسالة " +
                    "(طول=$oldLength->$newLength)"
            )
            return
        }
        val fails = (appConsecutiveFailures[pkg] ?: 0) + 1
        appConsecutiveFailures[pkg] = fails
        Log.w(
            TAG,
            "$LOG_TAG نتيجة توسعة: فشل تحديث الرسالة " +
                "(محاولات متتالية=$fails، طول=$oldLength->$newLength)"
        )
        if (fails >= CIRCUIT_FAIL_THRESHOLD) {
            appCircuitCooldownUntil[pkg] =
                SystemClock.uptimeMillis() + CIRCUIT_COOLDOWN_MS
            appConsecutiveFailures[pkg] = 0
            Log.w(TAG, "$LOG_TAG تفعيل قاطع تكرار مؤقت للتطبيق")
        }
    }

    private fun logAction(kind: TargetKind, ok: Boolean) {
        Log.i(TAG, "$LOG_TAG تنفيذ إجراء توسعة صريح ($kind): $ok")
    }
}
