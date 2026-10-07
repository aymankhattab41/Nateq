package com.aymankhattab.nateq.core.audio.accessibility

import android.graphics.Rect

/**
 * تمثيل مسطّح ومبسّط لعقدة الشاشة لمنع تسريب العقد
 * وتمكين الاختبارات الآلية المستقلة (ع٢٧-3، ع٢٧-8).
 */
data class NodeView(
    val text: CharSequence? = null,
    val contentDescription: CharSequence? = null,
    val className: String? = null,
    val viewIdResourceName: String? = null,
    val packageName: String? = null,
    val boundsInScreen: Rect = Rect(),
    val isVisibleToUser: Boolean = true,
    val isClickable: Boolean = false,
    val isFocusable: Boolean = false,
    val isAccessibilityFocused: Boolean = false,
    val isPassword: Boolean = false,
    val isEditable: Boolean = false,
    val actions: List<Int> = emptyList(),
    val customActionLabels: List<CharSequence> = emptyList(),
    val depth: Int = 0
) {
    /** النص المقروء الفعلي للعنصر */
    fun readableText(): String {
        val t = text?.toString()?.trim().orEmpty()
        val cd = contentDescription?.toString()?.trim().orEmpty()
        return when {
            t.isNotEmpty() && cd.isNotEmpty() && t != cd -> "$cd $t"
            t.isNotEmpty() -> t
            cd.isNotEmpty() -> cd
            else -> ""
        }
    }
}
