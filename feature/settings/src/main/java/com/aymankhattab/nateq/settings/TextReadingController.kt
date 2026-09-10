package com.aymankhattab.nateq.settings

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.aymankhattab.nateq.core.engine.PunctuationLevels
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.aymankhattab.nateq.core.data.SettingsRepository

/** ضابط قسم «قراءة النصوص»: مستوى نطق علامات الترقيم + مفتاح التهجئة الذكية. */
internal class TextReadingController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val onStatusChanged: () -> Unit
) {

    private lateinit var spinnerPunctuationLevel: Spinner
    private lateinit var switchSmartSpelling: SwitchMaterial

    fun setup(view: View) {
        spinnerPunctuationLevel =
            view.findViewById(R.id.spinner_punctuation_level)
        switchSmartSpelling = view.findViewById(R.id.switch_smart_spelling)

        // خيارات مستوى نطق الترقيم (0..2) ثنائية اللغة
        val levelLabels = arrayOf(
            fragment.getString(R.string.punctuation_level_none),
            fragment.getString(R.string.punctuation_level_some),
            fragment.getString(R.string.punctuation_level_all)
        )
        val savedLevel = runCatching { settings.getPunctuationLevel() }
            .getOrDefault(PunctuationLevels.SOME)
            .coerceIn(PunctuationLevels.MIN, PunctuationLevels.MAX)
        spinnerPunctuationLevel.adapter = ArrayAdapter(
            fragment.requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            levelLabels
        )
        spinnerPunctuationLevel.setSelection(savedLevel)
        spinnerPunctuationLevel.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                runCatching {
                    settings.setPunctuationLevel(position)
                }
                onStatusChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        switchSmartSpelling.isChecked =
            runCatching { settings.isSmartSpellingEnabled() }
                .getOrDefault(false)
        switchSmartSpelling.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setSmartSpellingEnabled(checked) }
            onStatusChanged()
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }
    }
}