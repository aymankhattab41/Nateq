package com.aymankhattab.nateq.core.common

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

// بند 4.2: قراءةٌ موحّدة للبث اللاصق للبطارية (ACTION_BATTERY_CHANGED)
// عبر التسجيل العابر (receiver = null) الذي لا يستقبل شيئاً بل يقرأ
// الحالة المضمّنة في آخر بث. عتبةُ الأعلام هنا واحدةٌ والسلوك واحدٌ في
// شاشة صحة الجهاز (DeviceHealthController) ومجدول إعلانات الوقت
// (TimeAnnouncementManager) — RECEIVER_NOT_EXPORTED أصحُّ للبث النظامي
// وأقل تعريضاً من EXPORTED. سابقاً كانت العتبة متباينة (Tiramisu في
// مجدول الوقت مقابل UpsideDownCake في شاشة الصحة) فيتحيّر السلوك بين
// الرومات المعدّلة.
fun readStickyBattery(context: Context): Intent? {
    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(
            null, filter, Context.RECEIVER_NOT_EXPORTED
        )
    } else {
        @Suppress("DEPRECATION", "UnspecifiedRegisterReceiverFlag")
        // قبل 33 لا إلزام بعلَم — النسخة الثنائية فحسب وبلا تعريض.
        context.registerReceiver(null, filter)
    }
}