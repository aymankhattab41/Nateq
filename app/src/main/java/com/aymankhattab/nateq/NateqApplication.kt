package com.aymankhattab.nateq

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.aymankhattab.nateq.settings.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

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
@HiltAndroidApp
class NateqApplication : Application() {

    /** مصدر الإعدادات الوحيد المحقون — ينشئه Hilt مرة واحدة في كل عملية. */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** نطاق عام يعيش مع التطبيق — بديل GlobalScope للمستقبلات اللاحقة.
     *  يُرفق معالج أخطاء عام يمنع إسقاط العملية عند أي استثناء لا يُلتقط
     *  داخل كوروتينات المستقبلات، ويسجّله في اللوج بدلاً من ذلك. */
    private val appCoroutineExceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.e("NATEQ_APP", "استثناء غير مُلتقط في النطاق العام", t)
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + appCoroutineExceptionHandler)

    override fun onCreate() {
        super.onCreate()

        // تطبيق لغة الواجهة المختارة يدوياً؛ في حال لم تُحدَّد تتبع الواجهة لغة النظام تلقائياً.
        // الحقل محقون من Hilt لكن نُبقي الحماية: أي فشل تهيئة (Keystore قديم مثلاً)
        // لا يجوز أن يُسقط العملية قبل أي شاشة — التراجع الصامت إلى لغة النظام آمن.
        val appLang = runCatching { settingsRepository.getAppLanguage() }.getOrNull()
        if (appLang != null) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(appLang))
        }
    }
}