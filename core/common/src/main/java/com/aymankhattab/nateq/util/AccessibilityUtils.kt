package com.aymankhattab.nateq.util

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * كشف حالة قارئ الشاشة (TalkBack وأقرانه) ليتعايش النطق المستقل معه.
 *
 * الإعلانات الصوتية (المتصل/الرسائل/البطارية) تُنطق عبر مسار الإتاحة
 * (USAGE_ASSISTANCE_ACCESSIBILITY) — وهو نفس المسار الذي يتحدث عليه
 * قارئ الشاشة: إذا كان القارئ نشطاً يلتقي الصوتان في قناة واحدة فيتصادمان.
 * عند النشاط يتحول النطق إلى مسار الوسائط (USAGE_MEDIA)، والمجموعات
 * الصوتية على أندرويد تخفض تلقائياً وسائط الإعلان لصالح كلام القارئ
 * فتفوز القراءة دون ضياع الإعلان كلياً.
 */
object AccessibilityUtils {

    /** حزم قارئات الشاشة المعروفة (تُفحص في قائمة خدمات الإتاحة المفعّلة). */
    private val KNOWN_SCREEN_READERS = listOf(
        "com.google.android.marvin.talkback",
        "com.android.talkback",
        "com.samsung.android.app.talkback"
    )

    /**
     * هل قارئ الشاشة نشط؟
     *
     * الادلة: استكشاف اللمس (isTouchExplorationEnabled) فالفحص المحدد
     * للوضع القائم، ثم قائمة خدمات الإتاحة المفعلة بحثاً عن حزم قارئات
     * معروفة (يغطي القارئات التي لا تفعّل استكشاف اللمس). القراءة آمنة
     * من عمليات الخلفية ومن العملية المنفصلة لمحرك النطق.
     */
    fun isScreenReaderEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager
        if (am != null && am.isTouchExplorationEnabled) return true
        return runCatching {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(':').any { flat ->
                val pkg = flat.substringBefore('/')
                KNOWN_SCREEN_READERS.contains(pkg)
            }
        }.getOrDefault(false)
    }

    /**
     * هل استكشاف اللمس (Touch Exploration) مفعّل في قارئ الشاشة؟
     * عندما يكون مفعّلاً، تتحول اللمسات المفردة إلى أحداث تحويم (Hover)،
     * بينما اللمسات المتعددة (إصبعين فأكثر) تكون للتمرير والتنقل في النظام.
     */
    fun isTouchExplorationEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager
        return am?.isTouchExplorationEnabled == true
    }

    /**
     * هل خدمة إمكانية الوصول الخاصة بنا مفعّلة في إعدادات النظام؟ (ع٢٧-4)
     */
    fun isNateqAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager
        if (am != null) {
            val enabledServices = runCatching {
                am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo
                        .FEEDBACK_ALL_MASK
                )
            }.getOrNull()
            if (enabledServices != null && enabledServices.any {
                it.resolveInfo?.serviceInfo?.packageName == context.packageName
            }) {
                return true
            }
        }
        return runCatching {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(':').any {
                it.startsWith("${context.packageName}/") &&
                    it.contains("NateqAccessibilityService")
            }
        }.getOrDefault(false)
    }
}