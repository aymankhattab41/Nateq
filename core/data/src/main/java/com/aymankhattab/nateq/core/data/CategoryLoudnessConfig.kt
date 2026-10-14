package com.aymankhattab.nateq.core.data

/**
 * ع٤٢-2: أوضاع مسار الصوت للإعلان (StreamMode).
 */
enum class StreamMode(val id: String) {
    AUTO("auto"),
    ACCESSIBILITY("accessibility"),
    ALARM("alarm"),
    NOTIFICATION("notification"),
    MEDIA("media");

    companion object {
        fun fromId(id: String?): StreamMode =
            entries.firstOrNull {
                it.id.equals(id, ignoreCase = true)
            } ?: AUTO
    }
}

/**
 * ع٤٢-2: سياسة جهارة الصوت لفئة حدث معينة (إعلان المتصل، الوقت، البطارية، إلخ).
 *
 * @param streamMode وضع تيار الصوت: AUTO أو أحد التيارات الصريحة.
 * @param forceMax رفع القناة للحد الأقصى أثناء نطق الحدث.
 * @param bypassSilent تجاوز وضع الصامت والاهتزاز.
 * @param headsetCapPercent سقف الأمان لسماعات الرأس والبلوتوث (افتراضي 85%).
 */
data class CategoryLoudnessConfig(
    val streamMode: StreamMode = StreamMode.AUTO,
    val forceMax: Boolean = true,
    val bypassSilent: Boolean = true,
    val headsetCapPercent: Int = DEFAULT_HEADSET_CAP_PERCENT
) {
    companion object {
        const val DEFAULT_HEADSET_CAP_PERCENT = 85

        /**
         * الإعداد الافتراضي بحسب فئة الحدث (بند 2).
         * المتصل والوقت والبطارية: forceMax=true, bypassSilent=true.
         * الرسائل والإشعارات: forceMax=false, bypassSilent=false.
         */
        fun defaultForCategory(category: String?): CategoryLoudnessConfig {
            val isHighPriority = when (category) {
                SettingsRepository.VOICE_CATEGORY_BATTERY,
                SettingsRepository.VOICE_CATEGORY_TIME,
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER,
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR,
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN -> true
                else -> false
            }
            return CategoryLoudnessConfig(
                streamMode = StreamMode.AUTO,
                forceMax = isHighPriority,
                bypassSilent = isHighPriority,
                headsetCapPercent = DEFAULT_HEADSET_CAP_PERCENT
            )
        }
    }
}
