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

    // مراجع العرض قابلة للتصفير في cleanup() عند تدمير عرض الفصيل
    // (بند 4.1) حتى لا تبقى شجرة العرض القديمة محتجزة في الخلفية.
    private var spinnerPunctuationLevel: Spinner? = null
    private var switchSmartSpelling: SwitchMaterial? = null
    private var switchTashkeelPreserved: SwitchMaterial? = null
    private var switchFollowReaderRate: SwitchMaterial? = null

    fun setup(view: View) {
        spinnerPunctuationLevel =
            view.findViewById(R.id.spinner_punctuation_level)
        switchSmartSpelling = view.findViewById(R.id.switch_smart_spelling)
        switchTashkeelPreserved =
            view.findViewById(R.id.switch_tashkeel_preserved)
        switchFollowReaderRate =
            view.findViewById(R.id.switch_follow_reader_rate)

        // خيارات مستوى نطق الترقيم (0..2) ثنائية اللغة
        val levelLabels = arrayOf(
            fragment.getString(R.string.punctuation_level_none),
            fragment.getString(R.string.punctuation_level_some),
            fragment.getString(R.string.punctuation_level_all)
        )
        val savedLevel = runCatching { settings.getPunctuationLevel() }
            .getOrDefault(PunctuationLevels.SOME)
            .coerceIn(PunctuationLevels.MIN, PunctuationLevels.MAX)
        spinnerPunctuationLevel?.adapter = ArrayAdapter(
            fragment.requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            levelLabels
        )
        spinnerPunctuationLevel?.setSelection(savedLevel)
        spinnerPunctuationLevel?.onItemSelectedListener =
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

        switchSmartSpelling?.isChecked =
            runCatching { settings.isSmartSpellingEnabled() }
                .getOrDefault(false)
        switchSmartSpelling?.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setSmartSpellingEnabled(checked) }
            onStatusChanged()
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        switchTashkeelPreserved?.isChecked =
            runCatching { settings.isTashkeelPreserved() }
                .getOrDefault(false)
        switchTashkeelPreserved?.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTashkeelPreserved(checked) }
            onStatusChanged()
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        switchFollowReaderRate?.isChecked =
            runCatching { settings.isFollowReaderRateEnabled() }
                .getOrDefault(true)
        switchFollowReaderRate?.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setFollowReaderRateEnabled(checked) }
            onStatusChanged()
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }
    }

    /** يصفّر مراجع العرض (بند 4.1) — يُستدعى من onDestroyView. */
    fun cleanup() {
        spinnerPunctuationLevel = null
        switchSmartSpelling = null
        switchTashkeelPreserved = null
        switchFollowReaderRate = null
    }
}