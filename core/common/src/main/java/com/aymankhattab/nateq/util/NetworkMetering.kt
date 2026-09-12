package com.aymankhattab.nateq.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * كشف ما إذا كانت الشبكة الحالية مدفوعة (بيانات الهاتف) أم لا — يخدم
 * قرار «تنزيل التحديث عبر Wi-Fi افتراضياً» فيعرف التطبيق متى يستعلم
 * المستخدم صراحةً عن استهلاك بياناته.
 */
object NetworkMetering {

    /** هل الشبكة النشطة حالياً مدفوعة (مقيَّدة البيانات)؟ */
    fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        return try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                ?: return false
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (t: Throwable) {
            false
        }
    }
}