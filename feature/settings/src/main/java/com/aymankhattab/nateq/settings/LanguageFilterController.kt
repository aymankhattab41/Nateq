package com.aymankhattab.nateq.settings

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.common.VisibleLanguages
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import java.util.Locale

/**
 * ضابط تصفية اللغات (الأمر 2).
 */
internal class LanguageFilterController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val catalog: EngineVoicesCatalog,
    private val onFilterChanged: (() -> Unit)? = null
) {

    private var switchLanguages: SwitchCompat? = null
    private var btnChooseLanguages: View? = null
    private var tvWarning: TextView? = null

    fun setup(view: View) {
        switchLanguages = view.findViewById(R.id.switch_filter_languages)
        btnChooseLanguages = view.findViewById(R.id.btn_choose_languages)
        tvWarning = view.findViewById(R.id.tv_filter_languages_warning)

        val enabled = settings.isFilterLanguagesEnabled()
        switchLanguages?.isChecked = enabled
        btnChooseLanguages?.isEnabled = true

        updateWarning()

        switchLanguages?.setOnCheckedChangeListener { _, isChecked ->
            settings.setFilterLanguagesEnabled(isChecked)
            VisibleLanguages.setFilter(
                isChecked,
                settings.getFilterLanguages()
            )
            updateWarning()
            onFilterChanged?.invoke()
        }

        btnChooseLanguages?.setOnClickListener {
            showLanguageSelectionDialog()
        }
    }

    private fun updateWarning() {
        if (!settings.isFilterLanguagesEnabled()) {
            tvWarning?.visibility = View.GONE
            return
        }
        val set = settings.getFilterLanguages()
        val primary = (settings.getAppLanguage()
            ?: Locale.getDefault().language)
            .takeWhile { it.isLetter() }.lowercase()
        val secondary = settings.getSecondaryLanguage()
            .takeWhile { it.isLetter() }.lowercase()

        val primaryHidden = primary.isNotEmpty() && !set.contains(primary)
        val secondaryHidden = secondary.isNotEmpty() && !set.contains(secondary)

        if (primaryHidden || secondaryHidden) {
            tvWarning?.visibility = View.VISIBLE
        } else {
            tvWarning?.visibility = View.GONE
        }
    }

    private fun showLanguageSelectionDialog() {
        val currentAppLang = context.resources.configuration.locales[0]
            .language.lowercase()

        // جمع اللغات الأساسية من Locale + المحركات المثبتة + ar و en
        val langCodes = mutableSetOf("ar", "en")
        for (loc in Locale.getAvailableLocales()) {
            val code = loc.language.lowercase()
            if (code.length in 2..3) {
                langCodes.add(code)
            }
        }

        // إضافة لغات أصوات المحركات المتاحة
        for (lang in catalog.languages()) {
            val code = lang.takeWhile { it.isLetter() }.lowercase()
            if (code.length in 2..3) {
                langCodes.add(code)
            }
        }

        val displayLocale = Locale.getDefault()
        val items = langCodes.map { code ->
            val display = Locale(code).getDisplayLanguage(displayLocale)
                .replaceFirstChar { it.uppercase() }
            val hasDistinctDisplay = display.isNotBlank() &&
                !display.equals(code, true)
            val label = if (hasDistinctDisplay) {
                "$display ($code)"
            } else {
                code
            }
            MultiSelectItem(
                id = code,
                label = label,
                isMandatory = (code == currentAppLang)
            )
        }.sortedBy { it.label }

        val currentSelected = settings.getFilterLanguages()
        val initialSelected = if (currentSelected.isNotEmpty()) {
            currentSelected
        } else {
            setOf("ar", "en", currentAppLang)
        }

        MultiSelectFilterDialog.show(
            context = context,
            title = context.getString(R.string.filter_languages_title),
            items = items,
            initialSelected = initialSelected,
            emptyErrorMessage = context.getString(
                R.string.filter_min_selection_error
            ),
            onSelected = { selected ->
                settings.setFilterLanguages(selected)
                VisibleLanguages.setFilter(
                    settings.isFilterLanguagesEnabled(),
                    selected
                )
                updateWarning()
                onFilterChanged?.invoke()
            }
        )
    }
}
