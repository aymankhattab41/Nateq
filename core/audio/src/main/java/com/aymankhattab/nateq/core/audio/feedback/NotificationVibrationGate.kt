package com.aymankhattab.nateq.core.audio.feedback

/**
 * بوابة مركزية تحظر أي اهتزاز متعلق بالإشعارات أو التنبيهات أو المكالمات.
 * الاهتزاز الوحيد المسموح به في التطبيق (إذا فعّله المستخدم يدوياً)
 * هو اهتزاز اللمس للوحة المفاتيح ولوحة الاتصال فقط.
 */
object NotificationVibrationGate {
    fun allowed(): Boolean = false
}
