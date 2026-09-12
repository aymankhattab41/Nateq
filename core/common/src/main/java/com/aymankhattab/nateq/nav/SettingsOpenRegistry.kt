package com.aymankhattab.nateq.nav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * وجهة فتح التطبيق من إشعارات الخدمات — بنية [Context] المحايدة تحرر
 * core:audio من تسمية صف Feature بتسلسل نصي فيعكسه بنيةٌ قابلة للاختبار.
 */
fun interface SettingsOpenTarget {
    fun openIntent(context: Context): Intent
}

/**
 * سجلّ الوجهة الحية: يملؤه module آخر (الحزمة التي تعرف شاشة الإعدادات)
 * عند إقلاع التطبيق؛ ما لم يُسجَّل تُفتح شاشة الإقلاع الرئيسية (أو صفحة
 * تفاصيل التطبيق إن تعذرتا) — فلا يعتمد الإشعار على أي سلسلة صف.
 */
object SettingsOpenRegistry {

    @Volatile
    private var target: SettingsOpenTarget? = null

    /** تسجيل الوجهة (مرة واحدة عند بدء التطبيق). */
    fun register(newTarget: SettingsOpenTarget) {
        target = newTarget
    }

    /** إعادة تعيين الوجهة المسجَّلة — لاختبارات Robolectric التي تعيد
     *  بناء التطبيق في نفس الـ JVM. */
    fun resetForTests() {
        target = null
    }

    /** سحب الوجهة المسجَّلة أو الافتراضية المحايدة. */
    fun resolve(context: Context): Intent =
        target?.openIntent(context) ?: defaultIntent(context)

    /** الافتراضي: تشغيل شاشة الإقلاع، أو صفحة تفاصيل التطبيق كملاذ أخير. */
    private fun defaultIntent(context: Context): Intent {
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val info = context.packageManager
            .resolveActivity(launch, 0)
        if (info?.activityInfo != null) {
            return Intent().setClassName(
                info.activityInfo.packageName,
                info.activityInfo.name
            )
        }
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
    }
}