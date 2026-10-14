package com.aymankhattab.nateq.core.audio.accessibility

/**
 * تركيب جملة «أين أنا» (ع٤١-ب3.2) بترتيب ثابت — منطقٌ نقيّ قابل للاختبار.
 * الترتيب: التطبيق ← الشاشة ← العنصر (دور + نص) ← الحالة.
 */
object WhereAmIComposer {

    private const val PASSWORD_TEXT = "حقل كلمة مرور"

    fun compose(
        appName: String,
        screenLabel: String?,
        node: NodeView?
    ): String {
        val parts = mutableListOf<String>()
        parts.add("أنت في $appName")
        screenLabel?.takeIf { it.isNotBlank() }?.let {
            parts.add("الشاشة $it")
        }
        if (node != null) {
            parts.add("العنصر ${describeElement(node)}")
            stateOf(node)?.let { parts.add(it) }
        }
        return parts.joinToString("، ")
    }

    /** تبسيط اسم النشاط: آخر مقطعٍ بعد النقطة، بإزالة لاحقة Activity. */
    fun screenLabel(activityName: String?): String? {
        val simple = activityName
            ?.substringAfterLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return simple.removeSuffix("Activity").ifBlank { simple }
    }

    private fun describeElement(node: NodeView): String {
        if (node.isPassword) return PASSWORD_TEXT
        val role = A11yRoleTable.roleFor(node.className)
        val text = node.readableText()
        return if (text.isNotBlank()) "$role: $text" else role
    }

    private fun stateOf(node: NodeView): String? = when {
        node.isCheckable -> if (node.isChecked) "محدد" else "غير محدد"
        node.isSelected -> "محدد"
        !node.isEnabled -> "غير مفعّل"
        else -> null
    }
}
