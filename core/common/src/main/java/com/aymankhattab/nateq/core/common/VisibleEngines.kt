package com.aymankhattab.nateq.core.common

/**
 * المنطق المركزي لتصفية المحركات المرئية في التطبيق (الأمر 3).
 */
object VisibleEngines {

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var allowedEngines: Set<String> = emptySet()

    fun update(filterEnabled: Boolean, engines: Set<String>?) {
        enabled = filterEnabled
        allowedEngines = engines
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
    }

    fun setFilter(filterEnabled: Boolean, engines: Set<String>?) {
        update(filterEnabled, engines)
    }

    fun isFilterEnabled(): Boolean = enabled

    fun isVisible(engineId: String?): Boolean {
        if (!enabled) return true
        if (engineId.isNullOrBlank()) return true
        // [بناء 10728] فلترٌ مفعَّل بلا أي محرك محدد لا يُخفي شيئاً:
        // حماية من إسكات التطبيق كله بقائمة فارغة.
        if (allowedEngines.isEmpty()) return true
        val trimmed = engineId.trim()
        if (allowedEngines.contains(trimmed)) return true
        // المحركات المدمجة تُخزَّن في القائمة باسم المزوّد المدمج، وبعض
        // المواضع تناديها بالاسم القصير؛ نقبل الشكلين كي لا يختفي محرك
        // اختاره المستخدم بسبب اختلاف التسمية.
        val alias = when (trimmed) {
            "vocalizer" -> "vocalizer_embedded"
            "vocalizer_embedded" -> "vocalizer"
            "microsoft" -> "microsoft_embedded"
            "microsoft_embedded" -> "microsoft"
            "acapela" -> "acapela_embedded"
            "acapela_embedded" -> "acapela"
            else -> null
        }
        return alias != null && allowedEngines.contains(alias)
    }

    fun filter(list: Collection<String>): List<String> {
        if (!enabled) return list.toList()
        return list.filter { isVisible(it) }
    }

    /** المحركات المسموح بها صراحةً (بلا إضافة المحركات المدمجة قهراً). */
    fun visibleSet(): Set<String> {
        return if (enabled) allowedEngines else emptySet()
    }

    /** إعادة التعيين للاختبارات */
    fun resetForTest() {
        enabled = false
        allowedEngines = emptySet()
    }
}
