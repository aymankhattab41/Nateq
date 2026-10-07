package com.aymankhattab.nateq.settings

import android.content.Context
import android.view.View
import android.widget.CompoundButton
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * ضابط قسم «معالجة النصوص»: مفاتيح تفعيل/تعطيل مراحل التحويل المختلفة
 * (الأوقات، العملات، وحدات القياس، الرموز، الهواتف، وتطبيع الحروف العربية).
 */
class TextProcessingController(
    private val settings: SettingsRepository,
    private val context: Context,
    private val onStatusChanged: (() -> Unit)? = null
) {
    fun bind(view: View) {
        view.findViewWithTag<CompoundButton>(
            "switch_time_conversion"
        )?.apply {
            isChecked = settings.isTimeConversionEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                settings.setTimeConversionEnabled(isChecked)
                onStatusChanged?.invoke()
            }
        }

        view.findViewWithTag<CompoundButton>(
            "switch_currency_conversion"
        )?.apply {
            isChecked = settings.isCurrencyConversionEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                settings.setCurrencyConversionEnabled(isChecked)
                onStatusChanged?.invoke()
            }
        }

        view.findViewWithTag<CompoundButton>(
            "switch_unit_conversion"
        )?.apply {
            isChecked = settings.isUnitConversionEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                settings.setUnitConversionEnabled(isChecked)
                onStatusChanged?.invoke()
            }
        }

        view.findViewWithTag<CompoundButton>(
            "switch_symbol_conversion"
        )?.apply {
            isChecked = settings.isSymbolConversionEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                settings.setSymbolConversionEnabled(isChecked)
                onStatusChanged?.invoke()
            }
        }

        view.findViewWithTag<CompoundButton>(
            "switch_phone_conversion"
        )?.apply {
            isChecked = settings.isPhoneConversionEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                settings.setPhoneConversionEnabled(isChecked)
                onStatusChanged?.invoke()
            }
        }

        view.findViewWithTag<CompoundButton>(
            "switch_arabic_normalization"
        )?.apply {
            isChecked = settings.isArabicNormalizationEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                settings.setArabicNormalizationEnabled(isChecked)
                onStatusChanged?.invoke()
            }
        }
    }
}
