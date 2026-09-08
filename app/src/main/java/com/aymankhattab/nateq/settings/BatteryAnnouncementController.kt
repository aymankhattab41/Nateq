package com.aymankhattab.nateq.settings

import android.view.View
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import com.aymankhattab.nateq.util.setSeekStateDescription
import android.widget.Spinner
import android.widget.TextView
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.engine.AnnouncementSchedulerService
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

/** ضابط قسم «إعلان مستوى البطارية»: المستويات/الصوت/السرعة/مستوى صوت الشحن. */
internal class BatteryAnnouncementController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val voices: List<NateqVoice>,
    private val onStatusChanged: () -> Unit
) {

    private lateinit var switchBatteryAnnouncement: SwitchMaterial
    private lateinit var llBatteryLevelsHeader: LinearLayout
    private lateinit var tvBatteryLevelsArrow: TextView
    private lateinit var llBatteryLevels: LinearLayout
    private lateinit var spinnerBatteryVoice: Spinner
    private lateinit var seekBatteryRate: SeekBar
    private lateinit var tvBatteryRateValue: TextView
    private lateinit var seekBatteryVolume: SeekBar
    private lateinit var tvBatteryVolumeValue: TextView
    private lateinit var switchChargingComplete: SwitchMaterial
    private lateinit var switchChargingDisconnect: SwitchMaterial
    private lateinit var switchPowerSaver: SwitchMaterial
    private lateinit var llPowerSaverThreshold: LinearLayout
    private lateinit var tvPowerSaverThresholdValue: TextView
    private lateinit var seekPowerSaverThreshold: SeekBar

    fun setup(view: View) {
        switchBatteryAnnouncement = view.findViewById(R.id.switch_battery_announcement)
        llBatteryLevelsHeader = view.findViewById(R.id.ll_battery_levels_header)
        tvBatteryLevelsArrow = view.findViewById(R.id.tv_battery_levels_arrow)
        llBatteryLevels = view.findViewById(R.id.ll_battery_levels)
        spinnerBatteryVoice = view.findViewById(R.id.spinner_battery_voice)
        seekBatteryRate = view.findViewById(R.id.seek_battery_rate)
        tvBatteryRateValue = view.findViewById(R.id.tv_battery_rate_value)
        seekBatteryVolume = view.findViewById(R.id.seek_battery_volume)
        tvBatteryVolumeValue = view.findViewById(R.id.tv_battery_volume_value)
        switchChargingComplete = view.findViewById(R.id.switch_charging_complete_announcement)
        switchChargingDisconnect = view.findViewById(R.id.switch_charging_disconnect_announcement)
        switchPowerSaver = view.findViewById(R.id.switch_power_saver_mode)
        llPowerSaverThreshold = view.findViewById(R.id.ll_power_saver_threshold)
        tvPowerSaverThresholdValue = view.findViewById(R.id.tv_power_saver_threshold_value)
        seekPowerSaverThreshold = view.findViewById(R.id.seek_power_saver_threshold)

        // المفتاح الرئيسي
        switchBatteryAnnouncement.isChecked =
            runCatching { settings.isBatteryAnnouncementEnabled() }.getOrDefault(false)
        switchBatteryAnnouncement.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setBatteryAnnouncementEnabled(checked) }
            if (checked) AnnouncementSchedulerService.requestStart(fragment.requireContext())
            else AnnouncementSchedulerService.syncIfRunning(fragment.requireContext())
            onStatusChanged()
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on else R.string.announcement_turned_off
                )
            )
        }

        // عنوان المستويات قابل للتوسيع/الطي
        llBatteryLevelsHeader.tag = fragment.getString(R.string.battery_announcement_levels_summary)
        llBatteryLevelsHeader.setOnClickListener {
            fragment.toggleCollapsible(llBatteryLevels, tvBatteryLevelsArrow, llBatteryLevelsHeader)
        }

        // مستويات البطارية: مسقط لكل مستوى (5%، 10%، ... 100%)
        val enabledLevels = runCatching { settings.getBatteryAnnouncementLevels() }
            .getOrDefault(setOf("20", "15"))
        llBatteryLevels.removeAllViews()
        val allLevels = (1..20).map { it * 5 } // 5, 10, ... 100
        for (level in allLevels) {
            val levelStr = level.toString()
            val cb = CheckBox(fragment.requireContext()).apply {
                text = "$level%"
                isChecked = levelStr in enabledLevels
                textSize = 16f
                setPadding(0, 4, 0, 4)
                // هدف لمس لا يقل عن 48dp
                minHeight = (48 * fragment.resources.displayMetrics.density).toInt()
                accessibilityDelegate = object : android.view.View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: android.view.View,
                        info: android.view.accessibility.AccessibilityNodeInfo
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.contentDescription =
                            fragment.getString(R.string.battery_level_contentdesc, level)
                    }
                }
                setOnCheckedChangeListener { _, isChecked ->
                    val current = settings.getBatteryAnnouncementLevels().toMutableSet()
                    if (isChecked) current.add(levelStr) else current.remove(levelStr)
                    settings.setBatteryAnnouncementLevels(current)
                }
            }
            llBatteryLevels.addView(cb)
        }

        // صوت نطق البطارية
        spinnerBatteryVoice.adapter = fragment.simpleAdapter(voices.map { it.displayName })
        val savedBatteryVoice = runCatching { settings.getBatteryAnnouncementVoiceId() }.getOrNull()
        if (savedBatteryVoice != null) {
            val idx = voices.indexOfFirst { it.name == savedBatteryVoice }
            if (idx >= 0) spinnerBatteryVoice.setSelection(idx)
        }
        spinnerBatteryVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                runCatching { settings.setBatteryAnnouncementVoiceId(voices[position].name) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // سرعة النطق
        val batteryRate = runCatching { settings.getBatteryAnnouncementRate() }.getOrDefault(1.0f)
        tvBatteryRateValue.text = String.format(Locale.US, "%.1fx", batteryRate)
        seekBatteryRate.progress = (batteryRate * 100).toInt().coerceIn(0, 200)
        seekBatteryRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                tvBatteryRateValue.text = String.format(Locale.US, "%.1fx", value)
                seekBar.setSeekStateDescription(tvBatteryRateValue.text)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching { settings.setBatteryAnnouncementRate(seekBar.progress / 100f) }
                seekBar.announceCompat(String.format(Locale.US, "%.1fx", seekBar.progress / 100f))
            }
        })

        // مستوى الصوت
        val batteryVolume = runCatching { settings.getBatteryAnnouncementVolume() }.getOrDefault(1.0f)
        tvBatteryVolumeValue.text = "${(batteryVolume * 100).toInt()}%"
        seekBatteryVolume.progress = (batteryVolume * 100).toInt().coerceIn(0, 100)
        seekBatteryVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvBatteryVolumeValue.text = "$progress%"
                seekBar.setSeekStateDescription(tvBatteryVolumeValue.text)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching { settings.setBatteryAnnouncementVolume(seekBar.progress / 100f) }
                seekBar.announceCompat("${seekBar.progress}%")
            }
        })

        // إعلان اكتمال الشحن (100%)
        switchChargingComplete.isChecked =
            runCatching { settings.isChargingCompleteAnnouncementEnabled() }.getOrDefault(true)
        switchChargingComplete.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setChargingCompleteAnnouncementEnabled(checked) }
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on else R.string.announcement_turned_off
                )
            )
        }

        // إعلان فصل الشاحن
        switchChargingDisconnect.isChecked =
            runCatching { settings.isChargingDisconnectAnnouncementEnabled() }.getOrDefault(true)
        switchChargingDisconnect.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setChargingDisconnectAnnouncementEnabled(checked) }
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on else R.string.announcement_turned_off
                )
            )
        }

        // وضع توفير الطاقة + عتبته
        switchPowerSaver.isChecked =
            runCatching { settings.isPowerSaverModeEnabled() }.getOrDefault(false)
        val powerThreshold =
            runCatching { settings.getPowerSaverBatteryThreshold() }.getOrDefault(20)
        tvPowerSaverThresholdValue.text = "$powerThreshold%"
        seekPowerSaverThreshold.progress = powerThreshold.coerceIn(0, 100)
        llPowerSaverThreshold.visibility = if (switchPowerSaver.isChecked) View.VISIBLE else View.GONE
        seekPowerSaverThreshold.visibility = if (switchPowerSaver.isChecked) View.VISIBLE else View.GONE
        switchPowerSaver.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setPowerSaverModeEnabled(checked) }
            llPowerSaverThreshold.visibility = if (checked) View.VISIBLE else View.GONE
            seekPowerSaverThreshold.visibility = if (checked) View.VISIBLE else View.GONE
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on else R.string.announcement_turned_off
                )
            )
        }
        seekPowerSaverThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvPowerSaverThresholdValue.text = "$progress%"
                seekBar.setSeekStateDescription(tvPowerSaverThresholdValue.text)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching { settings.setPowerSaverBatteryThreshold(seekBar.progress) }
                seekBar.announceCompat("${seekBar.progress}%")
            }
        })
    }
}