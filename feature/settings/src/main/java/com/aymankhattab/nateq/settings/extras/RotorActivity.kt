package com.aymankhattab.nateq.settings.extras

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aymankhattab.nateq.core.audio.accessibility.A11ySpeechCoordinator
import com.aymankhattab.nateq.core.audio.accessibility.AccessibilityBridge
import com.aymankhattab.nateq.core.audio.accessibility.ScreenRotor
import com.aymankhattab.nateq.core.audio.accessibility.ScreenRotor.RotorCategory
import com.aymankhattab.nateq.core.audio.accessibility.ScreenRotor.RotorNodeItem
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * نافذة دوّار العناصر الشفافة (ع٥٦-أمر 1).
 */
class RotorActivity : AppCompatActivity() {

    private lateinit var chipGroup: ChipGroup
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnActivate: MaterialButton
    private lateinit var btnClose: MaterialButton

    private val handler = Handler(Looper.getMainLooper())
    private val inactivityRunnable = Runnable { finish() }

    private var categorizedItems = mapOf<RotorCategory, List<RotorNodeItem>>()
    private var selectedItem: RotorNodeItem? = null
    private var currentCategory: RotorCategory = RotorCategory.BUTTONS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rotor)

        chipGroup = findViewById(R.id.cg_rotor_categories)
        recyclerView = findViewById(R.id.rv_rotor_items)
        btnActivate = findViewById(R.id.btn_rotor_activate)
        btnClose = findViewById(R.id.btn_rotor_close)

        recyclerView.layoutManager = LinearLayoutManager(this)

        btnClose.setOnClickListener { finish() }
        btnActivate.setOnClickListener {
            resetInactivityTimer()
            selectedItem?.let {
                ScreenRotor.activateItem(it)
                finish()
            }
        }

        scanAndPopulate()
        resetInactivityTimer()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetInactivityTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(inactivityRunnable)
        A11ySpeechCoordinator.speakAnnouncement(
            this,
            getString(R.string.rotor_done)
        )
    }

    private fun resetInactivityTimer() {
        handler.removeCallbacks(inactivityRunnable)
        handler.postDelayed(inactivityRunnable, 30_000L)
    }

    private fun scanAndPopulate() {
        val service = AccessibilityBridge.activeService()
        val root = service?.rootInActiveWindow
        categorizedItems = ScreenRotor.scan(root)

        val nonEmptyCategories = RotorCategory.entries.filter {
            (categorizedItems[it]?.size ?: 0) > 0
        }

        val speech = getString(
            R.string.rotor_categories_count,
            nonEmptyCategories.size
        )
        A11ySpeechCoordinator.speakAnnouncement(this, speech)

        chipGroup.removeAllViews()
        for (category in nonEmptyCategories) {
            val count = categorizedItems[category]?.size ?: 0
            val chip = Chip(this).apply {
                text = "${categoryLabel(category)} ($count)"
                isCheckable = true
                id = View.generateViewId()
                setOnClickListener {
                    currentCategory = category
                    displayCategoryItems(category)
                }
            }
            chipGroup.addView(chip)
        }

        val firstCat = nonEmptyCategories.firstOrNull() ?: RotorCategory.BUTTONS
        currentCategory = firstCat
        displayCategoryItems(firstCat)
        if (chipGroup.childCount > 0) {
            (chipGroup.getChildAt(0) as? Chip)?.isChecked = true
        }
    }

    private fun displayCategoryItems(category: RotorCategory) {
        val items = categorizedItems[category] ?: emptyList()
        selectedItem = null
        btnActivate.isEnabled = false

        recyclerView.adapter = RotorAdapter(items) { item ->
            resetInactivityTimer()
            selectedItem = item
            btnActivate.isEnabled = true
            ScreenRotor.focusItem(item)
        }
    }

    private fun categoryLabel(cat: RotorCategory): String = when (cat) {
        RotorCategory.BUTTONS -> getString(R.string.rotor_cat_buttons)
        RotorCategory.HEADINGS -> getString(R.string.rotor_cat_headings)
        RotorCategory.LINKS -> getString(R.string.rotor_cat_links)
        RotorCategory.INPUT_FIELDS -> getString(R.string.rotor_cat_inputs)
        RotorCategory.IMAGES -> getString(R.string.rotor_cat_images)
        RotorCategory.LISTS -> getString(R.string.rotor_cat_lists)
        RotorCategory.CONTROLS -> getString(R.string.rotor_cat_controls)
    }

    private class RotorAdapter(
        private val list: List<RotorNodeItem>,
        private val onItemClick: (RotorNodeItem) -> Unit
    ) : RecyclerView.Adapter<RotorViewHolder>() {

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): RotorViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(
                android.R.layout.simple_list_item_1,
                parent,
                false
            )
            return RotorViewHolder(view)
        }

        override fun onBindViewHolder(holder: RotorViewHolder, position: Int) {
            val item = list[position]
            holder.textView.text = item.text
            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = list.size
    }

    private class RotorViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(android.R.id.text1)
    }
}
