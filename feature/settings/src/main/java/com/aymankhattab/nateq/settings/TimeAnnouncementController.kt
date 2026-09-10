package com.aymankhattab.nateq.settings

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSchedulerService
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Calendar
import com.aymankhattab.nateq.core.data.SettingsRepository

/** ضابط قسم «إعلان الوقت»: الفاصل الزمني/الصيغة/
 *  ساعات الهدوء/التاريخ الهجري. */
internal class TimeAnnouncementController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val onStatusChanged: () -> Unit
) {

    private lateinit var switchTimeAnnouncement: SwitchMaterial
    private lateinit var spinnerTimeInterval: Spinner
    private lateinit var llQuietSchedule: LinearLayout
    private lateinit var spinnerTimeFormat: Spinner
    private lateinit var switchTime24h: SwitchMaterial
    private lateinit var switchHijriDate: SwitchMaterial
    private lateinit var switchClockWidget: SwitchMaterial
    private lateinit var switchTimeChime: SwitchMaterial
    private lateinit var spinnerTimeChimeSound: Spinner
    private lateinit var seekTimeChimeVolume: SeekBar

    fun setup(view: View) {
        switchTimeAnnouncement =
            view.findViewById(R.id.switch_time_announcement)
        spinnerTimeInterval = view.findViewById(R.id.spinner_time_interval)
        llQuietSchedule = view.findViewById(R.id.ll_quiet_schedule)
        spinnerTimeFormat = view.findViewById(R.id.spinner_time_format)
        switchTime24h = view.findViewById(R.id.switch_time_display_24h)
        switchHijriDate = view.findViewById(R.id.switch_hijri_date)
        switchClockWidget = view.findViewById(R.id.switch_clock_widget)

        val intervals = listOf(
            fragment.getString(R.string.time_interval_15),
            fragment.getString(R.string.time_interval_30),
            fragment.getString(R.string.time_interval_45),
            fragment.getString(R.string.time_interval_60)
        )
        spinnerTimeInterval.adapter = fragment.simpleAdapter(intervals)

        val formats = listOf(
            fragment.getString(R.string.time_format_natural),
            fragment.getString(R.string.time_format_digital)
        )
        spinnerTimeFormat.adapter = fragment.simpleAdapter(formats)

        val intervalPref =
            runCatching { settings.getTimeAnnouncementInterval() }
                .getOrDefault(30)
        spinnerTimeInterval.setSelection(intervalIndex(intervalPref))
        val formatPref =
            runCatching { settings.getTimeAnnouncementFormat() }
                .getOrDefault("arabic_natural")
        spinnerTimeFormat.setSelection(if (formatPref == "digital") 1 else 0)
        switchTimeAnnouncement.isChecked =
            runCatching { settings.isTimeAnnouncementEnabled() }
                .getOrDefault(true)
        setupQuietScheduleRows(view)

        switchClockWidget.isChecked =
            runCatching { settings.isClockWidgetEnabled() }.getOrDefault(true)
        setupExactAlarmPermissionRow(view)

        switchTimeAnnouncement.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTimeAnnouncementEnabled(checked) }
            if (checked) {
                AnnouncementSchedulerService.requestStart(
                    fragment.requireContext()
                )
            }
            onStatusChanged()
            fragment.view?.announceCompat(
                fragment.getString(
                        if (checked) {
                            R.string.announcement_turned_on
                        } else {
                            R.string.announcement_turned_off
                        }
                    )
            )
        }
        switchTime24h.isChecked =
            runCatching { settings.isTime24Hour() }
                .getOrDefault(false)
        switchTime24h.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTime24Hour(checked) }
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.toggle_on else R.string.toggle_off
                )
            )
        }
        switchHijriDate.isChecked =
            runCatching { settings.isHijriDateEnabled() }
                .getOrDefault(false)
        switchHijriDate.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setHijriDateEnabled(checked) }
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.toggle_on else R.string.toggle_off
                )
            )
        }
        switchClockWidget.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setClockWidgetEnabled(checked) }
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.toggle_on else R.string.toggle_off
                )
            )
        }

        // ─── رنة رأس الساعة ───
        switchTimeChime = view.findViewById(R.id.switch_time_chime)
        spinnerTimeChimeSound =
            view.findViewById(R.id.spinner_time_chime_sound)
        seekTimeChimeVolume =
            view.findViewById(R.id.seekbar_time_chime_volume)
        val chimeSounds = listOf(
            fragment.getString(R.string.time_chime_sound_classic_bell),
            fragment.getString(R.string.time_chime_sound_digital_chime),
            fragment.getString(R.string.time_chime_sound_soft_ding)
        )
        spinnerTimeChimeSound.adapter =
            fragment.simpleAdapter(chimeSounds)
        switchTimeChime.isChecked =
            runCatching { settings.isTimeChimeEnabled() }
                .getOrDefault(true)
        val savedChimeSound = runCatching {
            settings.getTimeChimeSound()
        }.getOrDefault("classic_bell")
        val chimeSoundIndex = when (savedChimeSound) {
            "digital_chime" -> 1
            "soft_ding" -> 2
            else -> 0
        }
        spinnerTimeChimeSound.setSelection(chimeSoundIndex)
        val savedChimeVol = runCatching {
            settings.getTimeChimeVolume()
        }.getOrDefault(0.5f)
        val seekProgress = ((savedChimeVol - 0.1f) / 0.9f * 100)
            .toInt().coerceIn(0, 100)
        seekTimeChimeVolume.max = 100
        seekTimeChimeVolume.progress = seekProgress

        switchTimeChime.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTimeChimeEnabled(checked) }
            spinnerTimeChimeSound.isEnabled = checked
            seekTimeChimeVolume.isEnabled = checked
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) {
                        R.string.announcement_turned_on
                    } else {
                        R.string.announcement_turned_off
                    }
                )
            )
        }
        spinnerTimeChimeSound.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                v: View?,
                pos: Int,
                id: Long
            ) {
                val sound = when (pos) {
                    1 -> "digital_chime"
                    2 -> "soft_ding"
                    else -> "classic_bell"
                }
                runCatching {
                    settings.setTimeChimeSound(sound)
                }
            }

            override fun onNothingSelected(
                parent: AdapterView<*>?
            ) {}
        }
        seekTimeChimeVolume.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                sb: SeekBar, progress: Int, fromUser: Boolean
            ) {}

            override fun onStartTrackingTouch(sb: SeekBar) {}

            override fun onStopTrackingTouch(sb: SeekBar) {
                val vol = 0.1f + sb.progress / 100f * 0.9f
                runCatching {
                    settings.setTimeChimeVolume(vol)
                }
                val pct = (vol * 100).toInt()
                sb.announceCompat("$pct%")
            }
        })

        spinnerTimeInterval.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val value = when (position) {
                    1 -> 30
                    2 -> 45
                    3 -> 60
                    else -> 15
                }
                runCatching { settings.setTimeAnnouncementInterval(value) }
                onStatusChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun intervalIndex(interval: Int): Int = when (interval) {
        30 -> 1
        45 -> 2
        60 -> 3
        else -> 0
    }

    /**
     * بند [13.3]: صف «منح إذن المنبهات الدقيقة» — يظهر فقط على أندرويد 12+
     * حين لا يمتلك التطبيق إمكانية جدولة المنبهات الدقيقة، ويفتح شاشة
     * النظام المخصصة لمنح الإذن. بدونه يظل الإعلان يعمل بمنبّه مرن يقترب من
     * اللحظة المستهدفة (setAndAllowWhileIdle) دون أيقونة منبه دائمة في
     * شريط الحالة.
     */
    private fun setupExactAlarmPermissionRow(view: View) {
        val row = view.findViewById<View>(
            R.id.ll_exact_alarm_permission
        ) ?: return
        val alarmManager = fragment.requireContext().getSystemService(
            Context.ALARM_SERVICE
        ) as? AlarmManager
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (alarmManager == null || !alarmManager.canScheduleExactAlarms())
        row.visibility = if (needsPermission) View.VISIBLE else View.GONE
        val onClick = View.OnClickListener {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                ).apply {
                    data = Uri.parse(
                        "package:${fragment.requireContext().packageName}"
                    )
                }
                fragment.startActivity(intent)
            }.onFailure {
                android.util.Log.w(
                    "NATEQ_TTS",
                    "exact alarm settings not opened",
                    it
                )
            }
        }
        row.setOnClickListener(onClick)
        view.findViewById<View>(
            R.id.btn_exact_alarm_permission
        )?.setOnClickListener(onClick)
    }

    /**
     * يبني صفوف ساعات الهدوء السبعة: لكل يوم مفتاح تفعيل (سويتش) + سبنرا
     * بداية/نهاية 0..23، يُحفظان فور تعديلهما ويُفعَّلان/يُعطَّلان مع المفتاح
     * (Calendar.DAY_OF_WEEK: 1=الأحد…7=السبت).
     */
    private fun setupQuietScheduleRows(view: View) {
        llQuietSchedule.removeAllViews()
        val days = listOf(
            R.string.day_sunday to Calendar.SUNDAY,
            R.string.day_monday to Calendar.MONDAY,
            R.string.day_tuesday to Calendar.TUESDAY,
            R.string.day_wednesday to Calendar.WEDNESDAY,
            R.string.day_thursday to Calendar.THURSDAY,
            R.string.day_friday to Calendar.FRIDAY,
            R.string.day_saturday to Calendar.SATURDAY
        )
        val density = fragment.resources.displayMetrics.density
        val hoursLabels = (0..23).map { it.toString().padStart(2, '0') }
        val fromLabel = fragment.getString(R.string.time_quiet_start_hint)
        val toLabel = fragment.getString(R.string.time_quiet_end_hint)

        for ((labelRes, day) in days) {
            val dayName = fragment.getString(labelRes)
            val dayEnabled = runCatching {
                settings.isDayQuietEnabled(day)
            }.getOrDefault(true)
            val start = runCatching {
                settings.getQuietStartForDay(day)
            }.getOrDefault(23)
            val end = runCatching {
                settings.getQuietEndForDay(day)
            }.getOrDefault(7)

            val startSpinner = Spinner(fragment.requireContext()).apply {
                adapter = fragment.simpleAdapter(hoursLabels)
                setSelection(start)
                isEnabled = dayEnabled
                minimumHeight = (48 * density).toInt()
                contentDescription = fragment.getString(
                    R.string.time_quiet_start_for_day, dayName
                )
                onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        selected: View?,
                        position: Int,
                        id: Long
                    ) {
                        if (position in 0..23) {
                            runCatching {
                                settings.setQuietStartForDay(day, position)
                            }
                            onStatusChanged()
                        }
                    }

                    override fun onNothingSelected(
                        parent: AdapterView<*>?
                    ) {}
                }
            }
            val endSpinner = Spinner(fragment.requireContext()).apply {
                adapter = fragment.simpleAdapter(hoursLabels)
                setSelection(end)
                isEnabled = dayEnabled
                minimumHeight = (48 * density).toInt()
                contentDescription = fragment.getString(
                    R.string.time_quiet_end_for_day, dayName
                )
                onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        selected: View?,
                        position: Int,
                        id: Long
                    ) {
                        if (position in 0..23) {
                            runCatching {
                                settings.setQuietEndForDay(day, position)
                            }
                            onStatusChanged()
                        }
                    }

                    override fun onNothingSelected(
                        parent: AdapterView<*>?
                    ) {}
                }
            }

            val switch = SwitchMaterial(fragment.requireContext()).apply {
                isChecked = dayEnabled
                minHeight = (48 * density).toInt()
                contentDescription = fragment.getString(
                    R.string.time_quiet_enabled_for_day, dayName
                )
                setOnCheckedChangeListener { _, checked ->
                    runCatching { settings.setDayQuietEnabled(day, checked) }
                    startSpinner.isEnabled = checked
                    endSpinner.isEnabled = checked
                    onStatusChanged()
                    fragment.view?.announceCompat(
                        fragment.getString(
                            if (checked) {
                                R.string.announcement_turned_on
                            } else {
                                R.string.announcement_turned_off
                            }
                        )
                    )
                }
            }

            val dayLabel = TextView(fragment.requireContext()).apply {
                text = dayName
                textSize = 16f
                minHeight = (48 * density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
                // اسم اليوم يقرؤه المفتاح نفسه فلا يتكرر عبر عقدتين
                importantForAccessibility = android.view.View
                    .IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            val dayRow = LinearLayout(fragment.requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 0)
                addView(
                    dayLabel,
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                addView(switch)
            }

            val startLabel = TextView(fragment.requireContext()).apply {
                text = fromLabel
                minHeight = (48 * density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val endLabel = TextView(fragment.requireContext()).apply {
                text = toLabel
                minHeight = (48 * density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val hoursRow = LinearLayout(fragment.requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 8)
                addView(startLabel)
                addView(
                    startSpinner,
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        marginStart = (8 * density).toInt()
                        marginEnd = (8 * density).toInt()
                    }
                )
                addView(endLabel)
                addView(
                    endSpinner,
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        marginStart = (8 * density).toInt()
                    }
                )
            }

            llQuietSchedule.addView(dayRow)
            llQuietSchedule.addView(hoursRow)
        }
    }
}
