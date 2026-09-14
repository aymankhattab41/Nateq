package com.aymankhattab.nateq.settings

import android.content.Context
import com.aymankhattab.nateq.feature.settings.R
import java.util.Locale

/**
 * تسمية قيمة مقياس السرعة/النبرة: الرقم يُنسَّق على لغة الواجهة (أرقام
 * وفاصل عشري موضعيان) مع لاحقة من الموارد — بدل فرض `Locale.US` الذي كان
 * يجعل TalkBack العربي يقرأ القيمة حرفياً («1.5x» بلا نطق سليم).
 */
internal object RateLabel {

    /** يبني «1.5x» (إنجليزية) أو «١٫٥×» (عربية) وفق [context]. */
    fun of(context: Context, value: Float): String {
        // **بند 2.3:** لغة الواجهة من إعدادات التطبيق (configuration.locales)
        // لا من Locale.getDefault() — كان الأخير يجلب لغة النظام لا لغة
        // التطبيق، فتبقى التسمية بالعربية على جهازٍ إنجليزي (أو العكس)
        // حتى بعد تغيير لغة الإعدادات. يقضي كسر "تحبس نفسها" في كلا
        // الاتجاهين.
        val appLocale = context.resources.configuration.locales.let {
            if (it.isEmpty) Locale.getDefault() else it.get(0)
        }
        return String.format(
            appLocale,
            context.getString(R.string.rate_value_format),
            value
        )
    }
}