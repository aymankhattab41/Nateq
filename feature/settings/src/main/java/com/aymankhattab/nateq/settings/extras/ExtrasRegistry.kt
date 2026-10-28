package com.aymankhattab.nateq.settings.extras

import com.aymankhattab.nateq.feature.settings.R

/**
 * السجل الثابت لجميع الإضافات المتوفرة في Lord TTS (الجولة 56).
 */
object ExtrasRegistry {

    val all: List<ExtraFeature> = listOf(
        // المجموعة 1: التنقل في الشاشة (7 ميزات)
        ExtraFeature(
            key = "extra_screen_rotor",
            titleRes = R.string.extra_screen_rotor_title,
            descRes = R.string.extra_screen_rotor_desc,
            group = ExtraGroup.NAVIGATION,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_screen_search",
            titleRes = R.string.extra_screen_search_title,
            descRes = R.string.extra_screen_search_desc,
            group = ExtraGroup.NAVIGATION,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_scroll_cue",
            titleRes = R.string.extra_scroll_cue_title,
            descRes = R.string.extra_scroll_cue_desc,
            group = ExtraGroup.NAVIGATION,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_focus_restore",
            titleRes = R.string.extra_focus_restore_title,
            descRes = R.string.extra_focus_restore_desc,
            group = ExtraGroup.NAVIGATION,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_screen_grid",
            titleRes = R.string.extra_screen_grid_title,
            descRes = R.string.extra_screen_grid_desc,
            group = ExtraGroup.NAVIGATION,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_loading_detector",
            titleRes = R.string.extra_loading_detector_title,
            descRes = R.string.extra_loading_detector_desc,
            group = ExtraGroup.NAVIGATION,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_article_reader",
            titleRes = R.string.extra_article_reader_title,
            descRes = R.string.extra_article_reader_desc,
            group = ExtraGroup.NAVIGATION,
            needsAccessibility = true
        ),

        // المجموعة 2: الإنتاجية والكتابة (7 ميزات)
        ExtraFeature(
            key = "extra_clipboard_stack",
            titleRes = R.string.extra_clipboard_stack_title,
            descRes = R.string.extra_clipboard_stack_desc,
            group = ExtraGroup.PRODUCTIVITY,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_otp_copier",
            titleRes = R.string.extra_otp_copier_title,
            descRes = R.string.extra_otp_copier_desc,
            group = ExtraGroup.PRODUCTIVITY,
            needsAccessibility = false
        ),
        ExtraFeature(
            key = "extra_keyboard_guard",
            titleRes = R.string.extra_keyboard_guard_title,
            descRes = R.string.extra_keyboard_guard_desc,
            group = ExtraGroup.PRODUCTIVITY,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_word_eraser",
            titleRes = R.string.extra_word_eraser_title,
            descRes = R.string.extra_word_eraser_desc,
            group = ExtraGroup.PRODUCTIVITY,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_typo_whisper",
            titleRes = R.string.extra_typo_whisper_title,
            descRes = R.string.extra_typo_whisper_desc,
            group = ExtraGroup.PRODUCTIVITY,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_currency_math",
            titleRes = R.string.extra_currency_math_title,
            descRes = R.string.extra_currency_math_desc,
            group = ExtraGroup.PRODUCTIVITY,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_screen_translator",
            titleRes = R.string.extra_screen_translator_title,
            descRes = R.string.extra_screen_translator_desc,
            group = ExtraGroup.PRODUCTIVITY,
            needsAccessibility = true
        ),

        // المجموعة 3: الخصوصية والأمان (4 ميزات)
        ExtraFeature(
            key = "extra_password_shield",
            titleRes = R.string.extra_password_shield_title,
            descRes = R.string.extra_password_shield_desc,
            group = ExtraGroup.PRIVACY,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_camera_mic_alert",
            titleRes = R.string.extra_camera_mic_alert_title,
            descRes = R.string.extra_camera_mic_alert_desc,
            group = ExtraGroup.PRIVACY,
            needsAccessibility = false
        ),
        ExtraFeature(
            key = "extra_link_inspector",
            titleRes = R.string.extra_link_inspector_title,
            descRes = R.string.extra_link_inspector_desc,
            group = ExtraGroup.PRIVACY,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_screen_curtain",
            titleRes = R.string.extra_screen_curtain_title,
            descRes = R.string.extra_screen_curtain_desc,
            group = ExtraGroup.PRIVACY,
            needsAccessibility = true
        ),

        // المجموعة 4: الدردشة والتواصل (6 ميزات)
        ExtraFeature(
            key = "extra_skip_ads",
            titleRes = R.string.extra_skip_ads_title,
            descRes = R.string.extra_skip_ads_desc,
            group = ExtraGroup.SOCIAL,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_voice_note_inspector",
            titleRes = R.string.extra_voice_note_inspector_title,
            descRes = R.string.extra_voice_note_inspector_desc,
            group = ExtraGroup.SOCIAL,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_message_change_alert",
            titleRes = R.string.extra_message_change_alert_title,
            descRes = R.string.extra_message_change_alert_desc,
            group = ExtraGroup.SOCIAL,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_group_limiter",
            titleRes = R.string.extra_group_limiter_title,
            descRes = R.string.extra_group_limiter_desc,
            group = ExtraGroup.SOCIAL,
            needsAccessibility = false
        ),
        ExtraFeature(
            key = "extra_contact_status",
            titleRes = R.string.extra_contact_status_title,
            descRes = R.string.extra_contact_status_desc,
            group = ExtraGroup.SOCIAL,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_alt_text",
            titleRes = R.string.extra_alt_text_title,
            descRes = R.string.extra_alt_text_desc,
            group = ExtraGroup.SOCIAL,
            needsAccessibility = true
        ),

        // المجموعة 5: أدوات يومية وتخصيص النطق (5 ميزات)
        ExtraFeature(
            key = "extra_battery_thresholds",
            titleRes = R.string.extra_battery_thresholds_title,
            descRes = R.string.extra_battery_thresholds_desc,
            group = ExtraGroup.DAILY_TOOLS,
            needsAccessibility = false
        ),
        ExtraFeature(
            key = "extra_role_earcons",
            titleRes = R.string.extra_role_earcons_title,
            descRes = R.string.extra_role_earcons_desc,
            group = ExtraGroup.DAILY_TOOLS,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_quick_dictionary",
            titleRes = R.string.extra_quick_dictionary_title,
            descRes = R.string.extra_quick_dictionary_desc,
            group = ExtraGroup.DAILY_TOOLS,
            needsAccessibility = true
        ),
        ExtraFeature(
            key = "extra_dynamic_rate",
            titleRes = R.string.extra_dynamic_rate_title,
            descRes = R.string.extra_dynamic_rate_desc,
            group = ExtraGroup.DAILY_TOOLS,
            needsAccessibility = false
        ),
        ExtraFeature(
            key = "extra_table_reader",
            titleRes = R.string.extra_table_reader_title,
            descRes = R.string.extra_table_reader_desc,
            group = ExtraGroup.DAILY_TOOLS,
            needsAccessibility = true
        )
    )

    private val byKeyMap: Map<String, ExtraFeature> by lazy {
        all.associateBy { it.key }
    }

    /** استرجاع الميزة بمفتاحها. */
    fun findByKey(key: String): ExtraFeature? = byKeyMap[key]

    /** استرجاع جميع ميزات مجموعة معينة. */
    fun getByGroup(group: ExtraGroup): List<ExtraFeature> =
        all.filter { it.group == group }
}
