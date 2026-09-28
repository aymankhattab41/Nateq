package com.aymankhattab.nateq.settings

import android.view.View
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import com.aymankhattab.nateq.util.setSeekStateDescription
import android.widget.Spinner
import android.widget.TextView
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSchedulerService
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.aymankhattab.nateq.core.data.SettingsRepository

/** ضابط قسم «إعلان مستوى البطارية»: المستويات/المؤثرات/وضع توفير الطاقة. */
internal class BatteryAnnouncementController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val onStatusChanged: () -> Unit
) {

    // مراجع العرض قابلة للتصفير في cleanup() عند تدمير عرض الفصيل
    // (بند 4.1) حتى لا تبقى شجرة العرض القديمة محتجزة في الخلفية.
    private var switchBatteryAnnouncement: SwitchMaterial? = null
    private var llBatteryLevelsHeader: LinearLayout? = null
    private var tvBatteryLevelsArrow: TextView? = null
    private var llBatteryLevels: LinearLayout? = null
    private var spinnerBatteryCueMode: Spinner? = null
    private var seekBatteryCueVolume: SeekBar? = null
    private var tvBatteryCueVolumeValue: TextView? = null
    private var switchChargingComplete: SwitchMaterial? = null
    private var switchChargingDisconnect: SwitchMaterial? = null
    private var switchPowerSaver: SwitchMaterial? = null
    private var llPowerSaverThreshold: LinearLayout? = null
    private var tvPowerSaverThresholdValue: TextView? = null
    private var seekPowerSaverThreshold: SeekBar? = null

    // علمُ الربط البرمجي — يُسنَّع حول setProgress في setup حتى
    // لا يُفسَّر الإسنادُ البرمجي تعديلَ مستخدم.
    private var bindingSlider = false

    fun setup(view: View) {
        switchBatteryAnnouncement =
            view.findViewById(R.id.switch_battery_announcement)
        llBatteryLevelsHeader =
            view.findViewById(R.id.ll_battery_levels_header)
        tvBatteryLevelsArrow = view.findViewById(R.id.tv_battery_levels_arrow)
        llBatteryLevels = view.findViewById(R.id.ll_battery_levels)
        spinnerBatteryCueMode =
            view.findViewById(R.id.spinner_battery_cue_mode)
        seekBatteryCueVolume = view.findViewById(R.id.seek_battery_cue_volume)
        tvBatteryCueVolumeValue =
            view.findViewById(R.id.tv_battery_cue_volume_value)
        switchChargingComplete =
            view.findViewById(R.id.switch_charging_complete_announcement)
        switchChargingDisconnect =
            view.findViewById(R.id.switch_charging_disconnect_announcement)
        switchPowerSaver = view.findViewById(R.id.switch_power_saver_mode)
        llPowerSaverThreshold =
            view.findViewById(R.id.ll_power_saver_threshold)
        tvPowerSaverThresholdValue =
            view.findViewById(R.id.tv_power_saver_threshold_value)
        seekPowerSaverThreshold =
            view.findViewById(R.id.seek_power_saver_threshold)

        // المفتاح الرئيسي
        switchBatteryAnnouncement?.isChecked =
            runCatching { settings.isBatteryAnnouncementEnabled() }
                .getOrDefault(false)
        switchBatteryAnnouncement?.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setBatteryAnnouncementEnabled(checked) }
            if (checked) {
                AnnouncementSchedulerService.requestStart(
                    fragment.requireContext()
                )
            } else {
                AnnouncementSchedulerService.syncIfRunning(
                    fragment.requireContext()
                )
            }
            onStatusChanged()
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        // عنوان المستويات قابل للتوسيع/الطي
        llBatteryLevelsHeader?.tag =
            fragment.getString(R.string.battery_announcement_levels_summary)
        llBatteryLevelsHeader?.setOnClickListener {
            val content = llBatteryLevels
                ?: return@setOnClickListener
            val arrow = tvBatteryLevelsArrow
                ?: return@setOnClickListener
            val header = llBatteryLevelsHeader
                ?: return@setOnClickListener
            fragment.toggleCollapsible(content, arrow, header)
        }

        // مستويات البطارية: مسقط لكل مستوى (5%، 10%، ... 100%)
        val enabledLevels =
            runCatching { settings.getBatteryAnnouncementLevels() }
                .getOrDefault(setOf("20", "15"))
        llBatteryLevels?.removeAllViews()
        val allLevels = (1..20).map { it * 5 } // 5, 10, ... 100
        val density = fragment.resources.displayMetrics.density
        for (level in allLevels) {
            val levelStr = level.toString()
            val cb = CheckBox(fragment.requireContext()).apply {
                text = "$level%"
                isChecked = levelStr in enabledLevels
                textSize = 16f
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                minHeight = (48 * density).toInt()
                accessibilityDelegate =
                    object : android.view.View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: android.view.View,
                        info: android.view.accessibility.AccessibilityNodeInfo
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.contentDescription =
                            fragment.getString(
                                R.string.battery_level_contentdesc,
                                level
                            )
                    }
                }
                setOnCheckedChangeListener { _, isChecked ->
                    val current =
                        settings.getBatteryAnnouncementLevels().toMutableSet()
                    if (isChecked) {
                        current.add(levelStr)
                    } else {
                        current.remove(levelStr)
                    }
                    settings.setBatteryAnnouncementLevels(current)
                    onStatusChanged()
                    fragment.view?.announceCompat(
                        fragment.getString(
                            if (isChecked) {
                                R.string.battery_level_checked
                            } else {
                                R.string.battery_level_unchecked
                            },
                            level
                        )
                    )
                }
            }
            llBatteryLevels?.addView(cb)
        }

        // وضع مؤثر البطارية
        val cueModeLabels = listOf(
            fragment.getString(R.string.battery_cue_mode_narration_cue),
            fragment.getString(R.string.battery_cue_mode_narration_only),
            fragment.getString(R.string.battery_cue_mode_cue_only)
        )
        spinnerBatteryCueMode?.adapter =
            fragment.simpleAdapter(cueModeLabels)
        val savedCueMode =
            runCatching { settings.getBatterySoundCueMode() }
                .getOrDefault(0)
        spinnerBatteryCueMode?.setSelection(
            savedCueMode.coerceIn(0, 2)
        )
        spinnerBatteryCueMode?.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                runCatching {
                    settings.setBatterySoundCueMode(position)
                }
            }

            override fun onNothingSelected(
                parent: AdapterView<*>?
            ) {}
        }

        // مستوى صوت مؤثر البطارية (مستقل عن صوت النطق بند 3-3)
        val batteryCueVolume =
            runCatching { settings.getBatteryCueVolume() }
                .getOrDefault(0.8f)
        tvBatteryCueVolumeValue?.text =
            "${(batteryCueVolume * 100).toInt()}%"
        bindingSlider = true
        try {
            seekBatteryCueVolume?.progress =
                ((batteryCueVolume - 0.1f) / 0.9f * 100)
                    .toInt().coerceIn(0, 100)
        } finally {
            bindingSlider = false
        }
        seekBatteryCueVolume?.setSeekStateDescription(
            tvBatteryCueVolumeValue?.text ?: ""
        )
        seekBatteryCueVolume?.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                if (bindingSlider) return
                val pct = ((0.1f + progress / 100f * 0.9f) * 100).toInt()
                tvBatteryCueVolumeValue?.text = "$pct%"
                seekBar.setSeekStateDescription(
                    tvBatteryCueVolumeValue?.text ?: ""
                )
                runCatching {
                    settings.setBatteryCueVolume(0.1f + progress / 100f * 0.9f)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val vol = 0.1f + seekBar.progress / 100f * 0.9f
                runCatching { settings.setBatteryCueVolume(vol) }
                seekBar.announceCompat("${(vol * 100).toInt()}%")
            }
        })

        // إعلان اكتمال الشحن (100%)
        switchChargingComplete?.isChecked =
            runCatching { settings.isChargingCompleteAnnouncementEnabled() }
                .getOrDefault(true)
        switchChargingComplete?.setOnCheckedChangeListener { _, checked ->
            runCatching {
                settings.setChargingCompleteAnnouncementEnabled(checked)
            }
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        // إعلان فصل الشاحن
        switchChargingDisconnect?.isChecked =
            runCatching { settings.isChargingDisconnectAnnouncementEnabled() }
                .getOrDefault(true)
        switchChargingDisconnect?.setOnCheckedChangeListener { _, checked ->
            runCatching {
                settings.setChargingDisconnectAnnouncementEnabled(checked)
            }
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        // وضع توفير الطاقة + عتبته
        switchPowerSaver?.isChecked =
            runCatching { settings.isPowerSaverModeEnabled() }
                .getOrDefault(false)
        val powerThreshold =
            runCatching { settings.getPowerSaverBatteryThreshold() }
                .getOrDefault(20)
        tvPowerSaverThresholdValue?.text = "$powerThreshold%"
        bindingSlider = true
        try {
            seekPowerSaverThreshold?.progress =
                powerThreshold.coerceIn(0, 100)
        } finally {
            bindingSlider = false
        }
        llPowerSaverThreshold?.visibility =
            if (switchPowerSaver?.isChecked == true) {
                View.VISIBLE
            } else {
                View.GONE
            }
        seekPowerSaverThreshold?.visibility =
            if (switchPowerSaver?.isChecked == true) {
                View.VISIBLE
            } else {
                View.GONE
            }
        switchPowerSaver?.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setPowerSaverModeEnabled(checked) }
            llPowerSaverThreshold?.visibility =
                if (checked) View.VISIBLE else View.GONE
            seekPowerSaverThreshold?.visibility =
                if (checked) View.VISIBLE else View.GONE
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }
        seekPowerSaverThreshold?.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                if (bindingSlider) return
                tvPowerSaverThresholdValue?.text = "$progress%"
                seekBar.setSeekStateDescription(
                    tvPowerSaverThresholdValue?.text ?: ""
                )
                runCatching {
                    settings.setPowerSaverBatteryThreshold(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching {
                    settings.setPowerSaverBatteryThreshold(seekBar.progress)
                }
                seekBar.announceCompat("${seekBar.progress}%")
            }
        })

        // معاينة إعلان البطارية بالقيم المحفوظة في فئة البطارية الموحَّدة
        view.findViewById<View>(R.id.btnPreviewBattery)
            ?.setOnClickListener { previewBattery() }
    }

    /** معاينة «البطارية عشرون بالمئة» بالصوت والأشرطة الموحّدة للبطارية. */
    private fun previewBattery() {
        val category = SettingsRepository.VOICE_CATEGORY_BATTERY
        val voiceId = runCatching {
            settings.getPreferredVoiceIdForCategory(category)
        }.getOrNull()
        val engine = runCatching {
            settings.getEngineForCategory(category)
        }.getOrNull()
        val rate = runCatching {
            settings.getSpeechRateForCategory(category)
        }.getOrDefault(1.0f)
        val pitch = runCatching {
            settings.getPitchForCategory(category)
        }.getOrDefault(1.0f)
        val volume = runCatching {
            settings.getVolumeForCategory(category)
        }.getOrDefault(1.0f)
        val sample = fragment.getString(R.string.sample_text_battery_preview)
        val lang = runCatching {
            settings.getLanguageForCategory(category)
        }.getOrNull() ?: "ar"
        fragment.previewSpeech(
            buildPreviewParamsFrom(
                voiceName = voiceId.orEmpty(),
                languageTag = lang,
                enginePkg = engine,
                rateProgress = (rate * 100).toInt().coerceIn(0, 200),
                pitchProgress = (pitch * 100).toInt().coerceIn(0, 200),
                volumePercent = (volume * 100).toInt().coerceIn(0, 100),
                sampleText = sample
            )
        )
    }

    /** يصفّر مراجع العرض (بند 4.1) — يُستدعى من onDestroyView. */
    fun cleanup() {
        switchBatteryAnnouncement = null
        llBatteryLevelsHeader = null
        tvBatteryLevelsArrow = null
        llBatteryLevels = null
        spinnerBatteryCueMode = null
        seekBatteryCueVolume = null
        tvBatteryCueVolumeValue = null
        switchChargingComplete = null
        switchChargingDisconnect = null
        switchPowerSaver = null
        llPowerSaverThreshold = null
        tvPowerSaverThresholdValue = null
        seekPowerSaverThreshold = null
    }
}
