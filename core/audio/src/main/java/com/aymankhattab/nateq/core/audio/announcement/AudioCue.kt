package com.aymankhattab.nateq.core.audio.announcement

/**
 * أنواع المؤثرات الصوتية التي يُمكن تشغيلها قبل أو بدلاً من نطق النص.
 */
enum class CueType {
    /** رنة جرس الساعة الهادئة عند رأس الساعة. */
    TIME_HOURLY,
    /** نغمة صاعدة عند توصيل الشاحن. */
    BATTERY_CHARGING,
    /** نغمة هابطة عند فصل الشاحن. */
    BATTERY_DISCONNECTED,
    /** نغمة مرح (آربيجيو) عند اكتمال الشحن 100%. */
    BATTERY_FULL,
    /** نغمة تحذيرة عند انخفاض البطارية (10%/5%). */
    BATTERY_LOW
}

/**
 * مؤثر صوتي محدد: نوع + اسم الرنة المفضّلة (للساعة فقط) + مستوى الصوت.
 *
 * @property type نوع المؤثر
 * @property soundName اسم الرنة داخل النوع (يُستخدم فقط مع TIME_HOURLY):
 *   "classic_bell" | "digital_chime" | "soft_ding"؛ أي قيمة أخرى أو null تُعامل
 *   كـ "classic_bell".
 * @property volume مستوى الصوت 0.0..1.0
 */
data class AudioCue(
    val type: CueType,
    val soundName: String? = null,
    val volume: Float = 0.5f
) {
    /** مفتاح تخزين مؤقت فريد (لمساواة Cache). */
    val key: String
        get() = "${type.name}|${soundName ?: ""}"
}
