package com.aymankhattab.nateq.settings

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.engine.AnnouncementSchedulerService
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Calendar

/** ضابط قسم «إعلان الوقت»: الفاصل الزمني/الصيغة/ساعات الهدوء/التاريخ الهجري. */
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

    fun setup(view: View) {
        switchTimeAnnouncement = view.findViewById(R.id.switch_time_announcement)
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

        val intervalPref = runCatching { settings.getTimeAnnouncementInterval() }.getOrDefault(30)
        spinnerTimeInterval.setSelection(intervalIndex(intervalPref))
        val formatPref = runCatching { settings.getTimeAnnouncementFormat() }.getOrDefault("arabic_natural")
        spinnerTimeFormat.setSelection(if (formatPref == "digital") 1 else 0)
        switchTimeAnnouncement.isChecked =
            runCatching { settings.isTimeAnnouncementEnabled() }.getOrDefault(true)
        setupQuietScheduleRows(view)

        switchClockWidget.isChecked =
            runCatching { settings.isClockWidgetEnabled() }.getOrDefault(true)
        setupExactAlarmPermissionRow(view)

        switchTimeAnnouncement.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTimeAnnouncementEnabled(checked) }
            if (checked) AnnouncementSchedulerService.requestStart(fragment.requireContext())
            onStatusChanged()
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on else R.string.announcement_turned_off
                )
            )
        }
        switchTime24h.isChecked = runCatching { settings.isTime24Hour() }.getOrDefault(false)
        switchTime24h.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTime24Hour(checked) }
            fragment.view?.announceCompat(
                fragment.getString(if (checked) R.string.toggle_on else R.string.toggle_off)
            )
        }
        switchHijriDate.isChecked = runCatching { settings.isHijriDateEnabled() }.getOrDefault(false)
        switchHijriDate.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setHijriDateEnabled(checked) }
            fragment.view?.announceCompat(
                fragment.getString(if (checked) R.string.toggle_on else R.string.toggle_off)
            )
        }
        switchClockWidget.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setClockWidgetEnabled(checked) }
            fragment.view?.announceCompat(
                fragment.getString(if (checked) R.string.toggle_on else R.string.toggle_off)
            )
        }
        spinnerTimeInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
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
        val row = view.findViewById<View>(R.id.ll_exact_alarm_permission) ?: return
        val alarmManager = fragment.requireContext().getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (alarmManager == null || !alarmManager.canScheduleExactAlarms())
        row.visibility = if (needsPermission) View.VISIBLE else View.GONE
        val onClick = View.OnClickListener {
            runCatching {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${fragment.requireContext().packageName}")
                }
                fragment.startActivity(intent)
            }.onFailure {
                android.util.Log.w("NATEQ_TTS", "exact alarm settings not opened", it)
            }
        }
        row.setOnClickListener(onClick)
        view.findViewById<View>(R.id.btn_exact_alarm_permission)?.setOnClickListener(onClick)
    }

    /**
     * يبني صفوف ساعات الهدوء السبعة (يوم → بداية/نهاية) بحقول رقمية 0..23
     * تُحفظ فور التعديل لكل يوم على حدة (Calendar.DAY_OF_WEEK: 1=الأحد…7=السبت).
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

        for ((labelRes, day) in days) {
            val dayName = fragment.getString(labelRes)
            val start = runCatching { settings.getQuietStartForDay(day) }.getOrDefault(23)
            val end = runCatching { settings.getQuietEndForDay(day) }.getOrDefault(7)

            val startField = EditText(fragment.requireContext()).apply {
                // اليومية في الـ hint تمنح قارئ الشاشة سياق اليوم لكل حقل
                hint = dayName + "، " + fragment.getString(R.string.time_quiet_start_hint)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(start.toString().padStart(2, '0'))
                maxLines = 1
                minHeight = (48 * density).toInt()
                addTextChangedListener(quietWatcher {
                    runCatching { settings.setQuietStartForDay(day, it) }
                })
            }
            val endField = EditText(fragment.requireContext()).apply {
                hint = dayName + "، " + fragment.getString(R.string.time_quiet_end_hint)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(end.toString().padStart(2, '0'))
                maxLines = 1
                minHeight = (48 * density).toInt()
                addTextChangedListener(quietWatcher {
                    runCatching { settings.setQuietEndForDay(day, it) }
                })
            }

            val label = TextView(fragment.requireContext()).apply {
                text = fragment.getString(labelRes)
                textSize = 16f
                minHeight = (48 * density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val row = LinearLayout(fragment.requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
                addView(
                    label,
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                addView(startField, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginEnd = (8 * density).toInt()
                    marginStart = (8 * density).toInt()
                })
                addView(endField, LinearLayout.LayoutParams(0, -2, 1f))
            }
            llQuietSchedule.addView(row)
        }
    }

    private fun quietWatcher(save: (Int) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val hour = s?.toString()?.trim()?.toIntOrNull()
            if (hour != null && hour in 0..23) save(hour)
        }
    }
}