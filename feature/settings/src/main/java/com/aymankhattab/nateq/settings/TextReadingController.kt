package com.aymankhattab.nateq.settings

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.aymankhattab.nateq.core.audio.engine.LatinLanguageDetector
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.engine.PunctuationLevels
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

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
    private var btnSecondaryLanguage: MaterialButton? = null
    private var btnNumberReadingLanguage: MaterialButton? = null

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

        // لغة النطق الاحتياطية (بند اللغة الثانية): الكلمات الأجنبية القصيرة
        // غير المتحسَّمة الحُكم ضمن النص العربي تُنطق باللغة المختارة هاهنا
        // عوضاً عن الإنجليزية الافتراضية — حوارُ اختيارٍ من اللغات التي
        // يكشفها الكاشف اللاتيني.
        btnSecondaryLanguage = view.findViewById(R.id.btn_secondary_language)
        val supportedLanguages = LatinLanguageDetector.SUPPORTED_LANGUAGES
        updateSecondaryLanguageLabel(supportedLanguages)
        btnSecondaryLanguage?.setOnClickListener {
            val current = runCatching { settings.getSecondaryLanguage() }
                .getOrDefault(LanguageCode.EN.tag)
            val currentIndex = supportedLanguages.indexOf(current)
                .coerceAtLeast(0)
            val labels = supportedLanguages.map { languageDisplayName(it) }
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.secondary_language_dialog_title)
                .setSingleChoiceItems(labels.toTypedArray(), currentIndex) {
                    dialog, which ->
                    val chosen = supportedLanguages[which]
                    runCatching { settings.setSecondaryLanguage(chosen) }
                    updateSecondaryLanguageLabel(supportedLanguages)
                    onStatusChanged()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // لغة نطق الأرقام: عربي / إنجليزي فقط
        btnNumberReadingLanguage =
            view.findViewById(R.id.btn_number_reading_language)
        updateNumberReadingLanguageLabel()
        btnNumberReadingLanguage?.setOnClickListener {
            val current = runCatching { settings.getNumberReadingLanguage() }
                .getOrDefault("ar")
            val currentIndex = if (current == "en") 1 else 0
            val options = arrayOf(
                fragment.getString(R.string.number_reading_language_arabic),
                fragment.getString(R.string.number_reading_language_english)
            )
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.number_reading_language_title)
                .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                    val chosen = if (which == 1) "en" else "ar"
                    runCatching { settings.setNumberReadingLanguage(chosen) }
                    updateNumberReadingLanguageLabel()
                    onStatusChanged()
                    val announcement = fragment.getString(
                        R.string.number_reading_language_btn,
                        options[which]
                    )
                    fragment.view?.announceCompat(announcement)
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** تحديث تسمية زر لغة نطق الأرقام. */
    private fun updateNumberReadingLanguageLabel() {
        val current = runCatching { settings.getNumberReadingLanguage() }
            .getOrDefault("ar")
        val label = if (current == "en") {
            fragment.getString(R.string.number_reading_language_english)
        } else {
            fragment.getString(R.string.number_reading_language_arabic)
        }
        btnNumberReadingLanguage?.text =
            fragment.getString(R.string.number_reading_language_btn, label)
    }

    /** يعرض اسم اللغة الحالية الفعلية على زر اللغة الاحتياطية (بند اللغة
     *  الثانية) — خلفيةٌ آمنة على قيمةٍ غير مدعومة من حدثٍ قديم. */
    private fun updateSecondaryLanguageLabel(
        supportedLanguages: List<String>
    ) {
        val stored = runCatching { settings.getSecondaryLanguage() }
            .getOrDefault(LanguageCode.EN.tag)
        val language = supportedLanguages.firstOrNull { it == stored }
            ?: LanguageCode.EN.tag
        btnSecondaryLanguage?.text =
            fragment.getString(R.string.secondary_language_title) +
                "، " + languageDisplayName(language)
    }

    /** الاسم المقروء للغة من الموارد — سقوطٌ على الوسام إن لم يُعرَف. */
    private fun languageDisplayName(language: String): String =
        when (language) {
            "en" -> fragment.getString(R.string.language_english)
            "fr" -> fragment.getString(R.string.language_french)
            "de" -> fragment.getString(R.string.language_german)
            "es" -> fragment.getString(R.string.language_spanish)
            else -> language
        }

    /** يصفّر مراجع العرض (بند 4.1) — يُستدعى من onDestroyView. */
    fun cleanup() {
        spinnerPunctuationLevel = null
        switchSmartSpelling = null
        switchTashkeelPreserved = null
        switchFollowReaderRate = null
        btnSecondaryLanguage = null
        btnNumberReadingLanguage = null
    }
}