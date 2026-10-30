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
        return allowedEngines.contains(engineId.trim())
    }

    fun filter(list: Collection<String>): List<String> {
        if (!enabled) return list.toList()
        return list.filter { isVisible(it) }
    }

    fun visibleSet(): Set<String> {
        return if (enabled) allowedEngines else emptySet()
    }

    /** إعادة التعيين للاختبارات */
    fun resetForTest() {
        enabled = false
        allowedEngines = emptySet()
    }
}
