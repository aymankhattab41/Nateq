package com.aymankhattab.nateq.settings.extras

import androidx.annotation.StringRes
import com.aymankhattab.nateq.feature.settings.R

/**
 * مجموعات تصنيف الميزات الإضافية في Lord TTS (الجولة 56).
 */
enum class ExtraGroup(
    @StringRes val titleRes: Int
) {
    NAVIGATION(R.string.extra_group_navigation),
    PRODUCTIVITY(R.string.extra_group_productivity),
    PRIVACY(R.string.extra_group_privacy),
    SOCIAL(R.string.extra_group_social),
    DAILY_TOOLS(R.string.extra_group_daily_tools)
}
