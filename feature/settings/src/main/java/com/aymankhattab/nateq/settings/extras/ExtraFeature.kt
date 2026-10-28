package com.aymankhattab.nateq.settings.extras

import androidx.annotation.StringRes

/**
 * تمثيل ميزة إضافية في قائمة الإضافات (ع٥٦).
 */
data class ExtraFeature(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val group: ExtraGroup,
    val needsAccessibility: Boolean = true
)
