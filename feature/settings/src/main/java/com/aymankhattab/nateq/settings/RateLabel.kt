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
    fun of(context: Context, value: Float): String = String.format(
        Locale.getDefault(),
        context.getString(R.string.rate_value_format),
        value
    )
}