package com.aymankhattab.nateq.settings

import android.content.Context
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal data class MultiSelectItem(
    val id: String,
    val label: String,
    val isMandatory: Boolean = false
)

/**
 * إطار حوار مشترك متعدد الاختيار لتصفية اللغات والمحركات (الأمران 2 و3).
 */
internal object MultiSelectFilterDialog {

    fun show(
        context: Context,
        title: String,
        items: List<MultiSelectItem>,
        initialSelected: Set<String>,
        emptyErrorMessage: String,
        onWarningCheck: ((Set<String>, onConfirm: () -> Unit) -> Unit)? = null,
        onSelected: (Set<String>) -> Unit
    ) {
        val selectedSet = initialSelected.toMutableSet()
        // ضمان بقاء العناصر الإلزامية محددة
        items.filter { it.isMandatory }.forEach { selectedSet.add(it.id) }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // صف أزرار تحديد الكل / إلغاء الكل
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnSelectAll = MaterialButton(
            context,
            null,
            android.R.attr.borderlessButtonStyle
        ).apply {
            text = context.getString(R.string.filter_select_all)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val btnDeselectAll = MaterialButton(
            context,
            null,
            android.R.attr.borderlessButtonStyle
        ).apply {
            text = context.getString(R.string.filter_deselect_all)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        btnRow.addView(btnSelectAll)
        btnRow.addView(btnDeselectAll)
        root.addView(btnRow)

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (360 * resources.displayMetrics.density).toInt()
            )
        }

        val itemsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val checkBoxes = mutableListOf<Pair<MultiSelectItem, CheckBox>>()

        for (item in items) {
            val cb = CheckBox(context).apply {
                text = item.label
                isChecked = selectedSet.contains(item.id)
                if (item.isMandatory) {
                    isEnabled = false
                }
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedSet.add(item.id)
                    } else if (!item.isMandatory) {
                        selectedSet.remove(item.id)
                    }
                }
            }
            checkBoxes.add(item to cb)
            itemsLayout.addView(cb)
        }

        scrollView.addView(itemsLayout)
        root.addView(scrollView)

        btnSelectAll.setOnClickListener {
            for ((item, cb) in checkBoxes) {
                cb.isChecked = true
                selectedSet.add(item.id)
            }
        }

        btnDeselectAll.setOnClickListener {
            for ((item, cb) in checkBoxes) {
                if (item.isMandatory) {
                    cb.isChecked = true
                    selectedSet.add(item.id)
                } else {
                    cb.isChecked = false
                    selectedSet.remove(item.id)
                }
            }
        }

        lateinit var dialog: AlertDialog

        fun tryConfirm() {
            if (selectedSet.isEmpty()) {
                Toast.makeText(context, emptyErrorMessage, Toast.LENGTH_SHORT)
                    .show()
                return
            }

            val confirmAction = {
                onSelected(selectedSet)
                dialog.dismiss()
            }

            if (onWarningCheck != null) {
                onWarningCheck(selectedSet, confirmAction)
            } else {
                confirmAction()
            }
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                tryConfirm()
            }
        }

        dialog.show()
    }
}
