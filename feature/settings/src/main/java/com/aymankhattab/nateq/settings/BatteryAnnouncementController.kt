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
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.aymankhattab.nateq.core.data.SettingsRepository

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
    private lateinit var spinnerBatteryEngine: Spinner
    private lateinit var seekBatteryRate: SeekBar
    private lateinit var tvBatteryRateValue: TextView
    private lateinit var seekBatteryVolume: SeekBar
    private lateinit var tvBatteryVolumeValue: TextView
    private lateinit var spinnerBatteryCueMode: Spinner
    private lateinit var seekBatteryCueVolume: SeekBar
    private lateinit var tvBatteryCueVolumeValue: TextView
    private lateinit var switchChargingComplete: SwitchMaterial
    private lateinit var switchChargingDisconnect: SwitchMaterial
    private lateinit var switchPowerSaver: SwitchMaterial
    private lateinit var llPowerSaverThreshold: LinearLayout
    private lateinit var tvPowerSaverThresholdValue: TextView
    private lateinit var seekPowerSaverThreshold: SeekBar

    // **بند 6.3:** علمُ الربط البرمجي — يُسنَّع حول setProgress في setup حتى
    // لا يُفسَّر الإسنادُ البرمجي تعديلَ مستخدم. دون الحفظ في
    // onProgressChanged كان تعديل TalkBack (عبر أداء الوصول، لا يمر عبر
    // onStopTrackingTouch إطلاقاً) يفقد أي تعديل على أشرطة التمرير.
    private var bindingSlider = false

    fun setup(view: View) {
        switchBatteryAnnouncement =
            view.findViewById(R.id.switch_battery_announcement)
        llBatteryLevelsHeader =
            view.findViewById(R.id.ll_battery_levels_header)
        tvBatteryLevelsArrow = view.findViewById(R.id.tv_battery_levels_arrow)
        llBatteryLevels = view.findViewById(R.id.ll_battery_levels)
        spinnerBatteryVoice = view.findViewById(R.id.spinner_battery_voice)
        spinnerBatteryEngine = view.findViewById(R.id.spinner_battery_engine)
        seekBatteryRate = view.findViewById(R.id.seek_battery_rate)
        tvBatteryRateValue = view.findViewById(R.id.tv_battery_rate_value)
        seekBatteryVolume = view.findViewById(R.id.seek_battery_volume)
        tvBatteryVolumeValue = view.findViewById(R.id.tv_battery_volume_value)
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
        switchBatteryAnnouncement.isChecked =
            runCatching { settings.isBatteryAnnouncementEnabled() }
                .getOrDefault(false)
        switchBatteryAnnouncement.setOnCheckedChangeListener { _, checked ->
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
        llBatteryLevelsHeader.tag =
            fragment.getString(R.string.battery_announcement_levels_summary)
        llBatteryLevelsHeader.setOnClickListener {
            fragment.toggleCollapsible(
                llBatteryLevels,
                tvBatteryLevelsArrow,
                llBatteryLevelsHeader
            )
        }

        // مستويات البطارية: مسقط لكل مستوى (5%، 10%، ... 100%)
        val enabledLevels =
            runCatching { settings.getBatteryAnnouncementLevels() }
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
                minHeight =
                    (48 * fragment.resources.displayMetrics.density).toInt()
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
                    // تأكيد الحالة فوراً لقارئ الشاشة (بند 3-2): المربع يحمل
                    // contentDescription خاصة عبر delegate فيقرأ السِياق.
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
            llBatteryLevels.addView(cb)
        }

        // صوت نطق البطارية
        spinnerBatteryVoice.adapter =
            fragment.simpleAdapter(voices.map { it.displayName })
        val savedBatteryVoice =
            runCatching { settings.getBatteryAnnouncementVoiceId() }
                .getOrNull()
        if (savedBatteryVoice != null) {
            val idx = voices.indexOfFirst { it.name == savedBatteryVoice }
            if (idx >= 0) spinnerBatteryVoice.setSelection(idx)
        }
        spinnerBatteryVoice.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                runCatching {
                    settings.setBatteryAnnouncementVoiceId(
                        voices[position].name
                    )
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // محرك نطق البطارية
        val batteryEngineOptions = runCatching {
            EnginePicker.installedEngines(fragment.requireContext())
        }.getOrDefault(emptyList())
        val batteryEngineLabels = buildList {
            add(fragment.getString(R.string.first_run_engine_auto))
            addAll(batteryEngineOptions.map { it.label })
        }
        spinnerBatteryEngine.adapter =
            fragment.simpleAdapter(batteryEngineLabels)
        val savedBatteryEngine = runCatching {
            settings.getEngineForCategory(
                SettingsRepository.DEVICE_HEALTH_BATTERY
            )
        }.getOrNull()
        val batteryEngineIdx = batteryEngineOptions
            .indexOfFirst { it.packageName == savedBatteryEngine }
        spinnerBatteryEngine.setSelection(
            if (batteryEngineIdx >= 0) batteryEngineIdx + 1 else 0
        )
        spinnerBatteryEngine.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, v: View?,
                pos: Int, id: Long
            ) {
                val pkg = batteryEngineOptions
                    .getOrNull(pos - 1)?.packageName
                runCatching {
                    settings.setEngineForCategory(
                        SettingsRepository.DEVICE_HEALTH_BATTERY,
                        pkg
                    )
                }
                onStatusChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // سرعة النطق
        val batteryRate =
            runCatching { settings.getBatteryAnnouncementRate() }
                .getOrDefault(1.0f)
                .coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        tvBatteryRateValue.text = RateLabel.of(
            fragment.requireContext(), batteryRate
        )
        bindingSlider = true
        try {
            seekBatteryRate.progress =
                (batteryRate * 100).toInt().coerceIn(0, 200)
        } finally {
            bindingSlider = false
        }
        seekBatteryRate.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingSlider) return
                val value = progress.speedFactor()
                tvBatteryRateValue.text = RateLabel.of(
                    fragment.requireContext(),
                    value
                )
                seekBar.setSeekStateDescription(
                    tvBatteryRateValue.text
                )
                // **بند 6.3:** الحفظ عند كل تغيير — تعديل TalkBack لا تصل
                // نهايته إلى onStopTrackingTouch أبداً.
                runCatching { settings.setBatteryAnnouncementRate(value) }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekBar.snapSpeedMin()
                val value = seekBar.progress.speedFactor()
                runCatching {
                    settings.setBatteryAnnouncementRate(value)
                }
                seekBar.announceCompat(
                    RateLabel.of(fragment.requireContext(), value)
                )
            }
        })

        // مستوى الصوت
        val batteryVolume =
            runCatching { settings.getBatteryAnnouncementVolume() }
                .getOrDefault(1.0f)
        tvBatteryVolumeValue.text = "${(batteryVolume * 100).toInt()}%"
        bindingSlider = true
        try {
            seekBatteryVolume.progress =
                (batteryVolume * 100).toInt().coerceIn(0, 100)
        } finally {
            bindingSlider = false
        }
        seekBatteryVolume.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingSlider) return
                tvBatteryVolumeValue.text = "$progress%"
                seekBar.setSeekStateDescription(
                    tvBatteryVolumeValue.text
                )
                // **بند 6.3:** الحفظ عند كل تغيير — تعديل TalkBack لا تصل
                // نهايته إلى onStopTrackingTouch أبداً.
                runCatching {
                    settings.setBatteryAnnouncementVolume(progress / 100f)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching {
                    settings.setBatteryAnnouncementVolume(
                        seekBar.progress / 100f
                    )
                }
                seekBar.announceCompat("${seekBar.progress}%")
            }
        })

        // وضع مؤثر البطارية
        spinnerBatteryCueMode =
            view.findViewById(R.id.spinner_battery_cue_mode)
        val cueModeLabels = listOf(
            fragment.getString(R.string.battery_cue_mode_narration_cue),
            fragment.getString(R.string.battery_cue_mode_narration_only),
            fragment.getString(R.string.battery_cue_mode_cue_only)
        )
        spinnerBatteryCueMode.adapter =
            fragment.simpleAdapter(cueModeLabels)
        val savedCueMode =
            runCatching { settings.getBatterySoundCueMode() }
                .getOrDefault(0)
        spinnerBatteryCueMode.setSelection(
            savedCueMode.coerceIn(0, 2)
        )
        spinnerBatteryCueMode.onItemSelectedListener =
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

        // مستوى صوت مؤثر البطارية (مستقل عن صوت النطق بند 3-3): يُطبق
        // 0.1..1.0 على كامل مسار الشريط مثل رنة الساعة.
        val batteryCueVolume =
            runCatching { settings.getBatteryCueVolume() }
                .getOrDefault(0.8f)
        tvBatteryCueVolumeValue.text =
            "${(batteryCueVolume * 100).toInt()}%"
        bindingSlider = true
        try {
            seekBatteryCueVolume.progress =
                ((batteryCueVolume - 0.1f) / 0.9f * 100)
                    .toInt().coerceIn(0, 100)
        } finally {
            bindingSlider = false
        }
        seekBatteryCueVolume.setSeekStateDescription(
            tvBatteryCueVolumeValue.text
        )
        seekBatteryCueVolume.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingSlider) return
                val pct = ((0.1f + progress / 100f * 0.9f) * 100).toInt()
                tvBatteryCueVolumeValue.text = "$pct%"
                seekBar.setSeekStateDescription(
                    tvBatteryCueVolumeValue.text
                )
                // **بند 6.3:** الحفظ عند كل تغيير — تعديل TalkBack لا تصل
                // نهايته إلى onStopTrackingTouch أبداً.
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
        switchChargingComplete.isChecked =
            runCatching { settings.isChargingCompleteAnnouncementEnabled() }
                .getOrDefault(true)
        switchChargingComplete.setOnCheckedChangeListener { _, checked ->
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
        switchChargingDisconnect.isChecked =
            runCatching { settings.isChargingDisconnectAnnouncementEnabled() }
                .getOrDefault(true)
        switchChargingDisconnect.setOnCheckedChangeListener { _, checked ->
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
        switchPowerSaver.isChecked =
            runCatching { settings.isPowerSaverModeEnabled() }
                .getOrDefault(false)
        val powerThreshold =
            runCatching { settings.getPowerSaverBatteryThreshold() }
                .getOrDefault(20)
        tvPowerSaverThresholdValue.text = "$powerThreshold%"
        bindingSlider = true
        try {
            seekPowerSaverThreshold.progress = powerThreshold.coerceIn(0, 100)
        } finally {
            bindingSlider = false
        }
        llPowerSaverThreshold.visibility =
            if (switchPowerSaver.isChecked) View.VISIBLE else View.GONE
        seekPowerSaverThreshold.visibility =
            if (switchPowerSaver.isChecked) View.VISIBLE else View.GONE
        switchPowerSaver.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setPowerSaverModeEnabled(checked) }
            llPowerSaverThreshold.visibility =
                if (checked) View.VISIBLE else View.GONE
            seekPowerSaverThreshold.visibility =
                if (checked) View.VISIBLE else View.GONE
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }
        seekPowerSaverThreshold.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingSlider) return
                tvPowerSaverThresholdValue.text = "$progress%"
                seekBar.setSeekStateDescription(
                    tvPowerSaverThresholdValue.text
                )
                // **بند 6.3:** الحفظ عند كل تغيير — تعديل TalkBack لا تصل
                // نهايته إلى onStopTrackingTouch أبداً.
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
    }
}
