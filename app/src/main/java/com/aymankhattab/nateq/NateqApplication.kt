package com.aymankhattab.nateq

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.aymankhattab.nateq.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * نقطة الدخول العامة للتطبيق.
 * يطبّق هنا لغة التطبيق المختارة يدوياً (إن حددها المستخدم) قبل أي Activity،
 * حتى تنعكس اللغة على الواجهة فوراً عند أول عُرض.
 */
class NateqApplication : Application() {

    /** نطاق عام يعيش مع التطبيق — بديل GlobalScope للمستقبلات اللاحقة */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // تطبيق لغة الواجهة المختارة يدوياً؛ في حال لم تُحدَّد تتبع الواجهة لغة النظام تلقائياً.
        val appLang = runCatching { SettingsRepository(this).getAppLanguage() }.getOrNull()
        if (appLang != null) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(appLang))
        }
    }
}