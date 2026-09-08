package com.aymankhattab.nateq.settings

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.announceCompat
import java.util.Locale
import com.aymankhattab.nateq.core.data.SettingsRepository

/** ضابط قسم «نطق الأرقام + مفتاح لغة النطق». */
internal class NumberReadingController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val onStatusChanged: () -> Unit
) {

    private lateinit var spinnerNumberReadingMode: Spinner
    private lateinit var btnSpeechLanguage: com.google.android.material.button.MaterialButton

    fun setup(view: View) {
        spinnerNumberReadingMode = view.findViewById(R.id.spinner_number_reading_mode)
        btnSpeechLanguage = view.findViewById(R.id.btn_speech_language)

        // خيارات طريقة نطق الأرقام (1..8) ثنائية اللغة
        val modeLabels = arrayOf(
            fragment.getString(R.string.number_mode_single),
            fragment.getString(R.string.number_mode_pairs),
            fragment.getString(R.string.number_mode_triples),
            fragment.getString(R.string.number_mode_quadruples),
            fragment.getString(R.string.number_mode_quintuples),
            fragment.getString(R.string.number_mode_sextuples),
            fragment.getString(R.string.number_mode_septuples),
            fragment.getString(R.string.number_mode_octuples)
        )
        val savedMode = runCatching { settings.getNumberReadingMode() }.getOrDefault(1).coerceIn(1, 8)
        spinnerNumberReadingMode.adapter = ArrayAdapter(
            fragment.requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            modeLabels
        )
        spinnerNumberReadingMode.setSelection(savedMode - 1)
        spinnerNumberReadingMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                runCatching { settings.setNumberReadingMode(position + 1) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // مفتاح لغة نطق الإعلانات (EN/AR) — يعرض الإجراء نحو اللغة المعاكسة للحالية
        val current = runCatching { settings.getAnnouncementSpeechLanguage() }.getOrNull()
        val isArabic = if (current == null) {
            LanguageCode.isArabic(Locale.getDefault().language)
        } else {
            LanguageCode.isArabic(current)
        }
        btnSpeechLanguage.text = if (isArabic) {
            fragment.getString(R.string.speech_language_to_en)
        } else {
            fragment.getString(R.string.speech_language_to_ar)
        }
        btnSpeechLanguage.setOnClickListener {
            // يُقرأ الوضع الحالي في كل ضغطة (لا قيمة مأسورة) ثم يُقلب نحو المعاكس
            val lang = runCatching { settings.getAnnouncementSpeechLanguage() }.getOrNull()
            val isArabicNow = if (lang == null) {
                LanguageCode.isArabic(Locale.getDefault().language)
            } else {
                LanguageCode.isArabic(lang)
            }
            val next = if (isArabicNow) LanguageCode.EN.tag else LanguageCode.AR.tag
            runCatching { settings.setAnnouncementSpeechLanguage(next) }
            // النص يعرض الإجراء نحو المعاكس للحالة الجديدة
            btnSpeechLanguage.text = if (next == LanguageCode.AR.tag) {
                fragment.getString(R.string.speech_language_to_en)
            } else {
                fragment.getString(R.string.speech_language_to_ar)
            }
            // إعلان مسموع للبدّل حتى يعرف المستمع أن اللغة تبدّلت (TalkBack/قراءة الشاشة)
            btnSpeechLanguage.announceCompat(
                fragment.getString(R.string.speech_language_switch) + " — " + btnSpeechLanguage.text
            )
            onStatusChanged()
        }
    }
}
