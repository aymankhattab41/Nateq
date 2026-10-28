package com.aymankhattab.nateq.settings.extras

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppManager
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * شاشة إعدادات الميزات الإضافية (ع٥٦).
 */
class ExtrasSettingsActivity : AppCompatActivity() {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var swMaster: SwitchMaterial
    private lateinit var btnReset: MaterialButton
    private lateinit var container: LinearLayout

    private val switchMap = mutableMapOf<String, SwitchMaterial>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extras_settings)

        settingsRepo = SettingsRepository.create(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_extras)
        toolbar.setNavigationOnClickListener { finish() }

        swMaster = findViewById(R.id.sw_master_extras)
        btnReset = findViewById(R.id.btn_reset_extras)
        container = findViewById(R.id.ll_extras_list)

        setupMasterControls()
        populateFeatures()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun setupMasterControls() {
        swMaster.setOnClickListener {
            val enable = swMaster.isChecked
            val allKeys = ExtrasRegistry.all.map { it.key }
            if (enable) {
                settingsRepo.enableAllExtras(allKeys)
                Toast.makeText(
                    this,
                    R.string.extras_all_enabled_toast,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                settingsRepo.resetAllExtras(allKeys)
                Toast.makeText(
                    this,
                    R.string.extras_all_reset_toast,
                    Toast.LENGTH_SHORT
                ).show()
            }
            refreshAll()
        }

        btnReset.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.extras_reset_all)
                .setMessage(R.string.extras_reset_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    settingsRepo.resetAllExtras(
                        ExtrasRegistry.all.map { it.key }
                    )
                    Toast.makeText(
                        this,
                        R.string.extras_all_reset_toast,
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshAll()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun populateFeatures() {
        container.removeAllViews()
        switchMap.clear()

        val isServiceConnected =
            ProtectedAppManager.isServiceConnected(this)
        val inflater = LayoutInflater.from(this)

        for (group in ExtraGroup.entries) {
            val groupFeatures = ExtrasRegistry.getByGroup(group)
            if (groupFeatures.isEmpty()) continue

            // عنوان المجموعة
            val header = TextView(this).apply {
                setText(group.titleRes)
                setTextAppearance(
                    com.google.android.material.R.style
                        .TextAppearance_MaterialComponents_Headline6
                )
                setPadding(8, 24, 8, 12)
                isAccessibilityHeading = true
            }
            container.addView(header)

            for (feature in groupFeatures) {
                val itemView = inflater.inflate(
                    R.layout.item_extra_feature,
                    container,
                    false
                )
                val sw = itemView.findViewById<SwitchMaterial>(
                    R.id.sw_extra_toggle
                )
                val tvDesc = itemView.findViewById<TextView>(
                    R.id.tv_extra_desc
                )
                val tvWarning = itemView.findViewById<TextView>(
                    R.id.tv_extra_warning
                )

                sw.setText(feature.titleRes)
                tvDesc.setText(feature.descRes)

                val showWarning =
                    feature.needsAccessibility && !isServiceConnected
                tvWarning.visibility =
                    if (showWarning) View.VISIBLE else View.GONE

                val enabled = settingsRepo.isExtraEnabled(feature.key)
                sw.isChecked = enabled
                updateAccessibilityDescription(sw, feature, enabled)

                sw.setOnCheckedChangeListener { _, isChecked ->
                    settingsRepo.setExtraEnabled(feature.key, isChecked)
                    updateAccessibilityDescription(sw, feature, isChecked)
                    val stateText = getString(
                        if (isChecked) {
                            R.string.extras_state_enabled
                        } else {
                            R.string.extras_state_disabled
                        }
                    )
                    sw.announceForAccessibility(
                        "${getString(feature.titleRes)}: $stateText"
                    )
                    updateMasterSwitchState()
                }

                switchMap[feature.key] = sw
                container.addView(itemView)
            }
        }
        updateMasterSwitchState()
    }

    private fun updateAccessibilityDescription(
        sw: SwitchMaterial,
        feature: ExtraFeature,
        enabled: Boolean
    ) {
        val title = getString(feature.titleRes)
        val state = getString(
            if (enabled) {
                R.string.extras_state_enabled
            } else {
                R.string.extras_state_disabled
            }
        )
        sw.contentDescription = "$title, $state"
    }

    private fun updateMasterSwitchState() {
        val allEnabled = ExtrasRegistry.all.all {
            settingsRepo.isExtraEnabled(it.key)
        }
        swMaster.setOnCheckedChangeListener(null)
        swMaster.isChecked = allEnabled
    }

    private fun refreshAll() {
        val isServiceConnected =
            ProtectedAppManager.isServiceConnected(this)
        for (feature in ExtrasRegistry.all) {
            val sw = switchMap[feature.key] ?: continue
            val enabled = settingsRepo.isExtraEnabled(feature.key)
            sw.isChecked = enabled
            updateAccessibilityDescription(sw, feature, enabled)
        }
        updateMasterSwitchState()
    }
}
