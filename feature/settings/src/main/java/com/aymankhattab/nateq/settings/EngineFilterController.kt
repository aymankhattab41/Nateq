package com.aymankhattab.nateq.settings

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * ضابط تصفية المحركات (الأمر 3).
 */
internal class EngineFilterController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val onFilterChanged: (() -> Unit)? = null
) {

    private var switchEngines: SwitchCompat? = null
    private var btnChooseEngines: View? = null
    private var tvWarning: TextView? = null

    fun setup(view: View) {
        switchEngines = view.findViewById(R.id.switch_filter_engines)
        btnChooseEngines = view.findViewById(R.id.btn_choose_engines)
        tvWarning = view.findViewById(R.id.tv_filter_engines_warning)

        val enabled = settings.isFilterEnginesEnabled()
        switchEngines?.isChecked = enabled
        btnChooseEngines?.isEnabled = true

        updateWarning()

        switchEngines?.setOnCheckedChangeListener { _, isChecked ->
            settings.setFilterEnginesEnabled(isChecked)
            VisibleEngines.setFilter(
                isChecked,
                settings.getFilterEngines()
            )
            updateWarning()
            onFilterChanged?.invoke()
        }

        btnChooseEngines?.setOnClickListener {
            showEngineSelectionDialog()
        }
    }

    private fun updateWarning() {
        if (!settings.isFilterEnginesEnabled()) {
            tvWarning?.visibility = View.GONE
            return
        }
        val set = settings.getFilterEngines()
        val configuredEngines = mutableSetOf<String>()
        settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        )?.let { configuredEngines.add(it) }
        settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )?.let { configuredEngines.add(it) }
        settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_EMOJI
        )?.let { configuredEngines.add(it) }

        val hiddenConfigured = configuredEngines.firstOrNull {
            !set.contains(it)
        }

        if (hiddenConfigured != null) {
            val allEngines = EnginePicker.allInstalledEngines(context)
            val label = allEngines.firstOrNull {
                it.packageName == hiddenConfigured
            }?.label ?: hiddenConfigured
            tvWarning?.text = context.getString(
                R.string.filter_engines_warning,
                label
            )
            tvWarning?.visibility = View.VISIBLE
        } else {
            tvWarning?.visibility = View.GONE
        }
    }

    private fun showEngineSelectionDialog() {
        // كل المحركات المثبتة: محركات النظام + المحركات المدمجة
        val allEngines = EnginePicker.allInstalledEngines(context)
        val defaultSystemPkg = EnginePicker.defaultSystemEngine(context)

        val items = allEngines.map { eng ->
            MultiSelectItem(
                id = eng.packageName,
                label = eng.label,
                isMandatory = false
            )
        }

        val currentSelected = settings.getFilterEngines()
        val initialSelected = if (currentSelected.isNotEmpty()) {
            currentSelected
        } else {
            allEngines.map { it.packageName }.toSet()
        }

        MultiSelectFilterDialog.show(
            context = context,
            title = context.getString(R.string.filter_engines_title),
            items = items,
            initialSelected = initialSelected,
            emptyErrorMessage = context.getString(
                R.string.filter_min_engine_selection_error
            ),
            onWarningCheck = { selected, onConfirm ->
                if (defaultSystemPkg != null &&
                    !selected.contains(defaultSystemPkg)
                ) {
                    MaterialAlertDialogBuilder(context)
                        .setMessage(
                            R.string.filter_engine_default_warning
                        )
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            onConfirm()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    onConfirm()
                }
            },
            onSelected = { selected ->
                settings.setFilterEngines(selected)
                VisibleEngines.setFilter(
                    settings.isFilterEnginesEnabled(),
                    selected
                )
                updateWarning()
                onFilterChanged?.invoke()
            }
        )
    }
}
