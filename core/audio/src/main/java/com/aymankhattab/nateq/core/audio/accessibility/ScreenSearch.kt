package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * بحث نصي داخل الشاشة الحالية مع تطبيع النصوص والتنقل الدوري (ع٥٦-أمر 2).
 */
object ScreenSearch {

    private val searchHistory = mutableListOf<String>()
    private var lastQuery: String = ""
    private var currentResults = mutableListOf<AccessibilityNodeInfo>()
    private var currentIndex: Int = -1

    /**
     * تطبيع الاستعلام والنصوص: حذف التشكيل والتطويل وتوحيد الحروف.
     */
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text
            .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
            .replace(Regex("[أإآٱ]"), "ا")
            .replace("ى", "ي")
            .replace("ة", "ه")
            .lowercase(Locale.ROOT)
            .trim()
    }

    /**
     * تنفيذ البحث في شجرة العقد الحالية.
     */
    fun search(
        context: Context,
        root: AccessibilityNodeInfo?,
        query: String
    ): Int {
        val normQuery = normalize(query)
        lastQuery = query
        currentResults.clear()
        currentIndex = -1

        if (normQuery.isBlank() || root == null) {
            return 0
        }

        recordHistory(query)
        findMatchingNodes(root, normQuery, currentResults)

        if (currentResults.isEmpty()) {
            A11ySpeechCoordinator.speakAnnouncement(
                context,
                "لا توجد نتائج لـ $query"
            )
            return 0
        }

        navigateNext(context)
        return currentResults.size
    }

    /**
     * التنقل إلى النتيجة التالية دورياً.
     */
    fun navigateNext(context: Context): Boolean {
        if (currentResults.isEmpty()) return false
        currentIndex = (currentIndex + 1) % currentResults.size
        return announceAndFocus(context, currentIndex)
    }

    /**
     * التنقل إلى النتيجة السابقة دورياً.
     */
    fun navigatePrevious(context: Context): Boolean {
        if (currentResults.isEmpty()) return false
        currentIndex = if (currentIndex <= 0) {
            currentResults.size - 1
        } else {
            currentIndex - 1
        }
        return announceAndFocus(context, currentIndex)
    }

    private fun announceAndFocus(context: Context, index: Int): Boolean {
        val node = currentResults.getOrNull(index) ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

        val text = extractNodeText(node)
        val announcement = "النتيجة ${index + 1} من " +
            "${currentResults.size}: $text"
        A11ySpeechCoordinator.speakAnnouncement(context, announcement)
        return true
    }

    private fun findMatchingNodes(
        node: AccessibilityNodeInfo?,
        normQuery: String,
        outResults: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        if (node.isPassword || !node.isVisibleToUser) return

        val text = extractNodeText(node)
        if (text.isNotBlank() && normalize(text).contains(normQuery)) {
            outResults.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            }
            findMatchingNodes(child, normQuery, outResults)
        }
    }

    private fun extractNodeText(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString()?.trim().orEmpty()
        val cd = node.contentDescription?.toString()?.trim().orEmpty()
        return when {
            t.isNotBlank() && cd.isNotBlank() && t != cd -> "$cd $t"
            t.isNotBlank() -> t
            cd.isNotBlank() -> cd
            else -> ""
        }
    }

    private fun recordHistory(query: String) {
        searchHistory.remove(query)
        searchHistory.add(0, query)
        while (searchHistory.size > 5) {
            searchHistory.removeAt(searchHistory.size - 1)
        }
    }

    /** سجل آخر 5 استعلامات في الذاكرة. */
    fun getHistory(): List<String> = searchHistory.toList()

    /** مسح السجل والنتائج عند إطفاء الشاشة. */
    fun clearMemory() {
        searchHistory.clear()
        currentResults.clear()
        currentIndex = -1
        lastQuery = ""
    }

    fun hasResults(): Boolean = currentResults.isNotEmpty()

    fun getLastQuery(): String = lastQuery
}
