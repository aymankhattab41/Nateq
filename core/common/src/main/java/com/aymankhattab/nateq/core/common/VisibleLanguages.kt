package com.aymankhattab.nateq.core.common

import java.util.Locale

/**
 * المنطق المركزي لتصفية اللغات المرئية في التطبيق (الأمر 2).
 */
object VisibleLanguages {

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var allowedLanguages: Set<String> = emptySet()

    fun update(filterEnabled: Boolean, languages: Set<String>?) {
        enabled = filterEnabled
        allowedLanguages = languages
            ?.map { normalize(it) }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
    }

    fun setFilter(filterEnabled: Boolean, languages: Set<String>?) {
        update(filterEnabled, languages)
    }

    fun isFilterEnabled(): Boolean = enabled

    fun normalize(tag: String?): String {
        if (tag.isNullOrBlank()) return ""
        return tag.substringBefore('-').substringBefore('_')
            .trim().lowercase(Locale.ROOT)
    }

    fun isVisible(tag: String?): Boolean {
        if (!enabled) return true
        if (tag.isNullOrBlank()) return true
        val base = normalize(tag)
        if (base.isBlank()) return true
        return allowedLanguages.contains(base)
    }

    fun filter(list: Collection<String>): List<String> {
        if (!enabled) return list.toList()
        return list.filter { isVisible(it) }
    }

    fun visibleSet(): Set<String> {
        return if (enabled) allowedLanguages else emptySet()
    }

    /** إعادة التعيين للاختبارات */
    fun resetForTest() {
        enabled = false
        allowedLanguages = emptySet()
    }
}
