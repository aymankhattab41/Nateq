package com.aymankhattab.nateq.core.audio.accessibility

import android.media.AudioDeviceInfo

/**
 * ع٤١-ب9: أسماء مسارات الصوت القابلة للإعلان (منطق نقي). تُتجاهل أنواع
 * البلوتوث لأنّ إعلانها يأتي من مستقبل ACL ([BluetoothAnnouncer]).
 */
object AudioRouteLabel {

    /** @return الاسم العربي للنوع، أو null إن كان النوع غير قابل للإعلان. */
    fun nameOf(type: Int): String? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "مكبّر الصوت"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "سماعة سلكية"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "سماعة رأس سلكية"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "سماعة USB"
        AudioDeviceInfo.TYPE_HEARING_AID -> "معين سمعي"
        else -> null
    }

    /**
     * هل يُعلَن فصل هذا النوع؟ يُكتم فصل المكبّر الداخلي لأنه مجرّد
     * تراجعٍ افتراضي عند وصل سماعة (وإعلانُ الوصل يكفي).
     */
    fun shouldAnnounceRemoval(type: Int): Boolean =
        type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && nameOf(type) != null
}
