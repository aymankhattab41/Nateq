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
 *
 * تعدد العمليات: يعمل في عمليتين —
 *  1) العملية الرئيسية (الواجهة + الخدمة الأمامية للإعلانات الدورية) و
 *  2) عملية المحرك `:tts` (NateqTtsService فقط).
 * كائن SettingsRepository يُنشأ في كل عملية على حدة، لذا يُستدعى
 * SettingsRepository.reload() في الخدمات بعد أي كتابة من العملية الأخرى
 * حتى لا تُقرأ قيم قديمة مخزنة مؤقتاً.
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