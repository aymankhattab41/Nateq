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
import com.aymankhattab.nateq.core.audio.announcement.AudioCue
import com.aymankhattab.nateq.core.audio.announcement.AudioCuePlayer
import com.aymankhattab.nateq.core.audio.announcement.CueType
import com.aymankhattab.nateq.util.announceCompat
import com.aymankhattab.nateq.util.setSeekStateDescription
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Calendar
import com.aymankhattab.nateq.core.data.SettingsRepository

/** ضابط قسم «إعلان الوقت»: الفاصل الزمني/الصيغة/ساعات الهدوء. */
internal class TimeAnnouncementController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val voices: List<NateqVoice>,
    private val onOpenOemGuidance: () -> Unit,
    private val onStatusChanged: () -> Unit
) {

    // مراجع العرض قابلة للتصفير في cleanup() عند تدمير عرض الفصيل
    // (بند 4.1) حتى لا تبقى شجرة العرض القديمة محتجزة في الخلفية.
    private var switchTimeAnnouncement: SwitchMaterial? = null
    private var spinnerTimeInterval: Spinner? = null
    private var llQuietSchedule: LinearLayout? = null
    private var spinnerTimeFormat: Spinner? = null
    private var switchTime24h: SwitchMaterial? = null
    private var switchTimeChime: SwitchMaterial? = null
    private var cbTimeChimeAt0: MaterialCheckBox? = null
    private var cbTimeChimeAt15: MaterialCheckBox? = null
    private var cbTimeChimeAt30: MaterialCheckBox? = null
    private var cbTimeChimeAt45: MaterialCheckBox? = null
    private var spinnerTimeChimeSound: Spinner? = null
    private var seekTimeChimeVolume: SeekBar? = null

    /** صف منح إذن المنبهات الدقيقة — تُحدَّث رؤيته في onResume (بند 4.7). */
    private var llExactAlarmPermission: View? = null

    /** بند الأوامر 4: أزرار معاينة إعلان الوقت ورنة الساعة. */
    private var btnPreviewTime: View? = null
    private var btnPreviewChime: View? = null

    /** بند الأوامر 5: مفتاح «دقة قصوى للمنبه» (setAlarmClock). */
    private var switchTimeAlarmMaxPrecision: SwitchMaterial? = null

    // **بند 6.3:** علمُ الربط البرمجي لشريط رنة الوقت — إسنادُ setProgress
    // في setup ليس تعديلَ مستخدم، والحفظ في onProgressChanged ضروري لأن
    // تعديل TalkBack لا يمر بـ onStopTrackingTouch إطلاقاً.
    private var bindingSlider = false

    fun setup(view: View) {
        switchTimeAnnouncement =
            view.findViewById(R.id.switch_time_announcement)
        spinnerTimeInterval = view.findViewById(R.id.spinner_time_interval)
        llQuietSchedule = view.findViewById(R.id.ll_quiet_schedule)
        spinnerTimeFormat = view.findViewById(R.id.spinner_time_format)
        switchTime24h = view.findViewById(R.id.switch_time_display_24h)

        // بند الأوامر 3: فاصل الإعلان 5–60 بخطوة 5 (12 قيمة) بدل أربع قيم
        // ثابتة — تُبنى القائمة برمجياً ويدور الحفظُ/القراءة على القيمة
        // حسابياً ((value / 5) - 1) لا بالتطابق مع قائمة حرفية.
        val intervals = (5..60 step 5).map { minutes ->
            "$minutes ${fragment.getString(R.string.minutes_unit)}"
        }
        spinnerTimeInterval?.adapter = fragment.simpleAdapter(intervals)

        val formats = listOf(
            fragment.getString(R.string.time_format_natural),
            fragment.getString(R.string.time_format_digital)
        )
        spinnerTimeFormat?.adapter = fragment.simpleAdapter(formats)

        val intervalPref =
            runCatching { settings.getTimeAnnouncementInterval() }
                .getOrDefault(30)
        spinnerTimeInterval?.setSelection(intervalIndex(intervalPref))
        val formatPref =
            runCatching { settings.getTimeAnnouncementFormat() }
                .getOrDefault("arabic_natural")
        spinnerTimeFormat?.setSelection(
            if (formatPref == "digital") 1 else 0
        )
        switchTimeAnnouncement?.isChecked =
            runCatching { settings.isTimeAnnouncementEnabled() }
                .getOrDefault(true)
        setupQuietScheduleRows(view)
        setupExactAlarmPermissionRow(view)

        switchTimeAnnouncement?.setOnCheckedChangeListener { _, checked ->
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
        switchTime24h?.isChecked =
            runCatching { settings.isTime24Hour() }
                .getOrDefault(false)
        switchTime24h?.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTime24Hour(checked) }
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.toggle_on else R.string.toggle_off
                )
            )
        }

        // ─── رنة رأس الساعة ───
        switchTimeChime = view.findViewById(R.id.switch_time_chime)
        cbTimeChimeAt0 = view.findViewById(R.id.cb_time_chime_at_0)
        cbTimeChimeAt15 = view.findViewById(R.id.cb_time_chime_at_15)
        cbTimeChimeAt30 = view.findViewById(R.id.cb_time_chime_at_30)
        cbTimeChimeAt45 = view.findViewById(R.id.cb_time_chime_at_45)
        spinnerTimeChimeSound =
            view.findViewById(R.id.spinner_time_chime_sound)
        seekTimeChimeVolume =
            view.findViewById(R.id.seekbar_time_chime_volume)
        val chimeSounds = listOf(
            fragment.getString(R.string.time_chime_sound_classic_bell),
            fragment.getString(R.string.time_chime_sound_digital_chime),
            fragment.getString(R.string.time_chime_sound_soft_ding)
        )
        spinnerTimeChimeSound?.adapter =
            fragment.simpleAdapter(chimeSounds)
        val chimeEnabled = runCatching {
            settings.isTimeChimeEnabled()
        }.getOrDefault(true)
        switchTimeChime?.isChecked = chimeEnabled

        cbTimeChimeAt0?.isChecked = runCatching {
            settings.isTimeChimeAt0Enabled()
        }.getOrDefault(true)
        cbTimeChimeAt15?.isChecked = runCatching {
            settings.isTimeChimeAt15Enabled()
        }.getOrDefault(false)
        cbTimeChimeAt30?.isChecked = runCatching {
            settings.isTimeChimeAt30Enabled()
        }.getOrDefault(false)
        cbTimeChimeAt45?.isChecked = runCatching {
            settings.isTimeChimeAt45Enabled()
        }.getOrDefault(false)

        cbTimeChimeAt0?.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTimeChimeAt0Enabled(checked) }
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) {
                        R.string.time_chime_at_0_checked
                    } else {
                        R.string.time_chime_at_0_unchecked
                    }
                )
            )
        }
        cbTimeChimeAt15?.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTimeChimeAt15Enabled(checked) }
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) {
                        R.string.time_chime_at_15_checked
                    } else {
                        R.string.time_chime_at_15_unchecked
                    }
                )
            )
        }
        cbTimeChimeAt30?.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTimeChimeAt30Enabled(checked) }
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) {
                        R.string.time_chime_at_30_checked
                    } else {
                        R.string.time_chime_at_30_unchecked
                    }
                )
            )
        }
        cbTimeChimeAt45?.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTimeChimeAt45Enabled(checked) }
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) {
                        R.string.time_chime_at_45_checked
                    } else {
                        R.string.time_chime_at_45_unchecked
                    }
                )
            )
        }
        updateChimeSubControlsEnabled(chimeEnabled)

        val savedChimeSound = runCatching {
            settings.getTimeChimeSound()
        }.getOrDefault("classic_bell")
        val chimeSoundIndex = when (savedChimeSound) {
            "digital_chime" -> 1
            "soft_ding" -> 2
            else -> 0
        }
        spinnerTimeChimeSound?.setSelection(chimeSoundIndex)
        val savedChimeVol = runCatching {
            settings.getTimeChimeVolume()
        }.getOrDefault(0.5f)
        val seekProgress = ((savedChimeVol - 0.1f) / 0.9f * 100)
            .toInt().coerceIn(0, 100)
        seekTimeChimeVolume?.max = 100
        bindingSlider = true
        try {
            seekTimeChimeVolume?.progress = seekProgress
        } finally {
            bindingSlider = false
        }
        // وصف الحالة الإتاحي للشريط عند التهيئة: يقرأه TalkBack فور الوصول
        // إليه (بدل الوصول ثم انتظار حركةٍ بالتوقف).
        seekTimeChimeVolume?.setSeekStateDescription(
            "${(savedChimeVol * 100).toInt()}%"
        )

        switchTimeChime?.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTimeChimeEnabled(checked) }
            updateChimeSubControlsEnabled(checked)
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
        spinnerTimeChimeSound?.onItemSelectedListener =
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
        seekTimeChimeVolume?.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                sb: SeekBar, progress: Int, fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingSlider) return
                // وصف الحالة يتحدث أثناء الحركة (بمفاتيح الصوت) لا عند
                // التوقف فقط — نفس نمط بقية الشرائط في الإعدادات.
                val pct = ((0.1f + progress / 100f * 0.9f) * 100).toInt()
                sb.setSeekStateDescription("$pct%")
                // **بند 6.3:** الحفظ عند كل تغيير — تعديل TalkBack لا تصل
                // نهايته إلى onStopTrackingTouch أبداً.
                runCatching {
                    settings.setTimeChimeVolume(0.1f + progress / 100f * 0.9f)
                }
            }

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

        spinnerTimeInterval?.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val value = (position + 1) * 5
                runCatching { settings.setTimeAnnouncementInterval(value) }
                onStatusChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // بند الأوامر 4: أزرار المعاينة (إعلان الوقت ورنة الساعة).
        setupPreviewButtons(view)
        // بند الأوامر 5: خيار الدقة القصوى للمنبه.
        setupMaxPrecisionSwitch(view)
    }

    /** فهرس القائمة الحسابي لقيمة الفاصل المحفوظة (5..60 بخطوة 5):
     *  index = (value / 5) - 1 — لا قائمة حرفية نفسها. */
    private fun intervalIndex(interval: Int): Int =
        ((interval.coerceIn(5, 60) / 5) - 1).coerceIn(0, 11)

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
        llExactAlarmPermission = row
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
        // بند الأوامر 5: رابط «فتح إرشادات المصنّع» يعيد التوجيه إلى قسم
        // إرشادات توافق الجهاز (لا يُفتح شاشة المنبهات).
        view.findViewById<View>(R.id.btnOpenOemGuidance)?.setOnClickListener {
            onOpenOemGuidance()
        }
        refreshExactAlarmRow()
    }

    /** بند 4.7: يُعاد فحص إذن المنبهات الدقيقة عند العودة من إعدادات النظام
     *  (onResume) — بعد منحه يختفي صف الطلب فوراً بدل بقائه
     *  كما لو لم يُمنح. */
    fun refreshExactAlarmRow() {
        val row = llExactAlarmPermission ?: return
        val alarmManager = fragment.requireContext().getSystemService(
            Context.ALARM_SERVICE
        ) as? AlarmManager
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (alarmManager == null || !alarmManager.canScheduleExactAlarms())
        row.visibility = if (needsPermission) View.VISIBLE else View.GONE
    }

    /** بند الأوامر 4: يربط زرّي المعاينة (إعلان الوقت ورنة الساعة) —
     *  القيم تُقرأ من العرض الحالي وقت الضغط لا من القيم القديمة. */
    private fun setupPreviewButtons(view: View) {
        btnPreviewTime = view.findViewById(R.id.btnPreviewTime)
        btnPreviewTime?.setOnClickListener { previewTimeSpeech() }
        btnPreviewChime = view.findViewById(R.id.btnPreviewChime)
        btnPreviewChime?.setOnClickListener { previewChime() }
    }

    /** معاينة إعلان الوقت: تسمع صوت/محرك/سرعة/نبرة/مستوى فئة الوقت كما
     *  تعرضها صفوف الكتالوج (CategoryVoiceAdapter). */
    private fun previewTimeSpeech() {
        val category = SettingsRepository.VOICE_CATEGORY_TIME
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
        val sample = fragment.getString(R.string.sample_text_time_preview)
        fragment.previewSpeech(
            buildCategoryPreviewParams(
                voices = voices,
                voiceId = voiceId,
                enginePkg = engine,
                rate = rate,
                pitch = pitch,
                volume = volume,
                sampleText = sample
            )
        )
    }

    /** معاينة رنة رأس الساعة: تعزف النغمة المختارة بمستوى الشريط الحالي
     *  (رنات مُؤلَّفة عبر CueSynth — لا تلمس أي ملف صوتي). */
    private fun previewChime() {
        val sound = chimeSoundNameAt(
            spinnerTimeChimeSound?.selectedItemPosition ?: 0
        )
        val seek = seekTimeChimeVolume
        val volume = seek?.let { chimeVolumeFromProgress(it.progress) }
            ?: runCatching {
                settings.getTimeChimeVolume()
            }.getOrDefault(0.5f)
        fragment.view?.announceCompat(
            fragment.getString(R.string.sample_preview_starting)
        )
        AudioCuePlayer.getInstance(
            fragment.requireContext().applicationContext
        ).play(AudioCue(CueType.TIME_HOURLY, sound, volume)) { _ ->
            // إعلان النهاية يُنشر على الخيط الرئيسي (قد يُستدعى من خيط
            // SoundPool) ولا يُعلن بعد تدمير العرض.
            fragment.view?.post {
                if (fragment.isAdded) {
                    fragment.view?.announceCompat(
                        fragment.getString(R.string.sample_preview_done)
                    )
                }
            }
        }
    }

    /** بند الأوامر 5: مفتاح «الدقة القصوى» — تُعاد جدولة المنبه فوراً
     *  بالصيغة الجديدة (setAlarmClock) من TimeAlarmReceiver. */
    private fun setupMaxPrecisionSwitch(view: View) {
        switchTimeAlarmMaxPrecision =
            view.findViewById(R.id.switch_time_alarm_max_precision)
        switchTimeAlarmMaxPrecision?.isChecked = runCatching {
            settings.isTimeAlarmMaxPrecisionEnabled()
        }.getOrDefault(false)
        switchTimeAlarmMaxPrecision?.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setTimeAlarmMaxPrecisionEnabled(checked) }
            AnnouncementSchedulerService.requestStart(
                fragment.requireContext()
            )
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }
    }

    /**
     * يبني صفوف ساعات الهدوء السبعة: لكل يوم مفتاح تفعيل (سويتش) + سبنرا
     * بداية/نهاية 0..23، يُحفظان فور تعديلهما ويُفعَّلان/يُعطَّلان مع المفتاح
     * (Calendar.DAY_OF_WEEK: 1=الأحد…7=السبت).
     */
    private fun setupQuietScheduleRows(view: View) {
        llQuietSchedule?.removeAllViews()
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

        // Master Row
        val masterSwitch = SwitchMaterial(fragment.requireContext()).apply {
            minHeight = (48 * density).toInt()
        }
        val masterLabel = TextView(fragment.requireContext()).apply {
            text = fragment.getString(R.string.quiet_schedule_master)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val masterStartSpinner = Spinner(fragment.requireContext()).apply {
            adapter = fragment.simpleAdapter(hoursLabels)
            minimumHeight = (48 * density).toInt()
        }
        val masterEndSpinner = Spinner(fragment.requireContext()).apply {
            adapter = fragment.simpleAdapter(hoursLabels)
            minimumHeight = (48 * density).toInt()
        }
        val masterTimes = LinearLayout(fragment.requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(fragment.requireContext()).apply {
                text = fromLabel
                setPadding(8, 0, 8, 0)
            })
            addView(masterStartSpinner)
            addView(TextView(fragment.requireContext()).apply {
                text = toLabel
                setPadding(8, 0, 8, 0)
            })
            addView(masterEndSpinner)
        }
        val masterRow = LinearLayout(fragment.requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(masterLabel)
            addView(masterTimes)
            addView(masterSwitch)
        }
        llQuietSchedule?.addView(masterRow)

        // Individual rows container
        val individualContainer =
            LinearLayout(fragment.requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }

        val expandButton = TextView(fragment.requireContext()).apply {
            text = "▼ " +
                fragment.getString(R.string.quiet_schedule_individual)
            textSize = 14f
            setPadding(0, (16 * density).toInt(), 0, (8 * density).toInt())
            setOnClickListener {
                if (individualContainer.visibility == View.GONE) {
                    individualContainer.visibility = View.VISIBLE
                    text = "▲ " + fragment.getString(
                        R.string.quiet_schedule_individual
                    )
                } else {
                    individualContainer.visibility = View.GONE
                    text = "▼ " + fragment.getString(
                        R.string.quiet_schedule_individual
                    )
                }
            }
        }
        llQuietSchedule?.addView(expandButton)
        llQuietSchedule?.addView(individualContainer)

        var isUpdatingFromMaster = false
        val individualSwitches = mutableListOf<SwitchMaterial>()
        val individualStarts = mutableListOf<Spinner>()
        val individualEnds = mutableListOf<Spinner>()
        val individualTimes = mutableListOf<LinearLayout>()

        for ((labelRes, day) in days) {
            val dayName = fragment.getString(labelRes)
            val dayEnabled = runCatching {
                settings.isDayQuietEnabled(day)
            }.getOrDefault(false)
            val start = runCatching {
                settings.getQuietStartForDay(day)
            }.getOrDefault(23)
            val end = runCatching {
                settings.getQuietEndForDay(day)
            }.getOrDefault(7)

            val startSpinner = Spinner(fragment.requireContext()).apply {
                adapter = fragment.simpleAdapter(hoursLabels)
                setSelection(start)
                minimumHeight = (48 * density).toInt()
                contentDescription = fragment.getString(
                    R.string.time_quiet_start_for_day,
                    dayName
                )
                onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            p0: AdapterView<*>?,
                            p1: View?,
                            position: Int,
                            id: Long
                        ) {
                            if (isUpdatingFromMaster) return
                            if (position in 0..23) {
                                runCatching {
                                    settings.setQuietStartForDay(day, position)
                                }
                                onStatusChanged()
                            }
                        }
                        override fun onNothingSelected(p0: AdapterView<*>?) {}
                    }
            }
            val endSpinner = Spinner(fragment.requireContext()).apply {
                adapter = fragment.simpleAdapter(hoursLabels)
                setSelection(end)
                minimumHeight = (48 * density).toInt()
                contentDescription = fragment.getString(
                    R.string.time_quiet_end_for_day,
                    dayName
                )
                onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            p0: AdapterView<*>?,
                            p1: View?,
                            position: Int,
                            id: Long
                        ) {
                            if (isUpdatingFromMaster) return
                            if (position in 0..23) {
                                runCatching {
                                    settings.setQuietEndForDay(day, position)
                                }
                                onStatusChanged()
                            }
                        }
                        override fun onNothingSelected(p0: AdapterView<*>?) {}
                    }
            }

            val timesContainer =
                LinearLayout(fragment.requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    visibility = if (dayEnabled) View.VISIBLE else View.GONE
                    addView(TextView(fragment.requireContext()).apply {
                        text = fromLabel
                        setPadding(8, 0, 8, 0)
                    })
                    addView(startSpinner)
                    addView(TextView(fragment.requireContext()).apply {
                        text = toLabel
                        setPadding(8, 0, 8, 0)
                    })
                    addView(endSpinner)
                }

            val switch = SwitchMaterial(fragment.requireContext()).apply {
                isChecked = dayEnabled
                minHeight = (48 * density).toInt()
                contentDescription = fragment.getString(
                    R.string.time_quiet_enabled_for_day,
                    dayName
                )
                setOnCheckedChangeListener { _, checked ->
                    if (isUpdatingFromMaster) return@setOnCheckedChangeListener
                    runCatching { settings.setDayQuietEnabled(day, checked) }
                    timesContainer.visibility =
                        if (checked) View.VISIBLE else View.GONE
                    onStatusChanged()
                }
            }

            val dayLabel = TextView(fragment.requireContext()).apply {
                text = dayName
                textSize = 16f
                minHeight = (48 * density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val row = LinearLayout(fragment.requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(dayLabel)
                addView(timesContainer)
                addView(switch)
            }
            individualContainer.addView(row)
            
            individualSwitches.add(switch)
            individualStarts.add(startSpinner)
            individualEnds.add(endSpinner)
            individualTimes.add(timesContainer)
        }

        // Initialize master row state based on all days
        val allEnabled = (1..7).all {
            runCatching {
                settings.isDayQuietEnabled(it)
            }.getOrDefault(false)
        }
        val commonStart = runCatching {
            settings.getQuietStartForDay(Calendar.SUNDAY)
        }.getOrDefault(23)
        val commonEnd = runCatching {
            settings.getQuietEndForDay(Calendar.SUNDAY)
        }.getOrDefault(7)

        masterSwitch.isChecked = allEnabled
        masterTimes.visibility = if (allEnabled) View.VISIBLE else View.GONE
        masterStartSpinner.setSelection(commonStart)
        masterEndSpinner.setSelection(commonEnd)

        // Master switch listener
        masterSwitch.setOnCheckedChangeListener { _, checked ->
            masterTimes.visibility = if (checked) View.VISIBLE else View.GONE

            val s = masterStartSpinner.selectedItemPosition
            val e = masterEndSpinner.selectedItemPosition

            isUpdatingFromMaster = true
            try {
                for (i in days.indices) {
                    val day = days[i].second
                    runCatching { settings.setDayQuietEnabled(day, checked) }
                    if (checked) {
                        runCatching { settings.setQuietStartForDay(day, s) }
                        runCatching { settings.setQuietEndForDay(day, e) }
                    }

                    // Update individual UI silently without triggering
                    // listeners
                    individualSwitches[i].isChecked = checked
                    individualTimes[i].visibility =
                        if (checked) View.VISIBLE else View.GONE
                    if (checked) {
                        individualStarts[i].setSelection(s)
                        individualEnds[i].setSelection(e)
                    }
                }
            } finally {
                isUpdatingFromMaster = false
            }
            onStatusChanged()
        }

        masterStartSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    pos: Int,
                    id: Long
                ) {
                    if (!masterSwitch.isChecked) return
                    isUpdatingFromMaster = true
                    try {
                        for (i in days.indices) {
                            runCatching {
                                settings.setQuietStartForDay(
                                    days[i].second,
                                    pos
                                )
                            }
                            individualStarts[i].setSelection(pos)
                        }
                    } finally {
                        isUpdatingFromMaster = false
                    }
                    onStatusChanged()
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        masterEndSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    pos: Int,
                    id: Long
                ) {
                    if (!masterSwitch.isChecked) return
                    isUpdatingFromMaster = true
                    try {
                        for (i in days.indices) {
                            runCatching {
                                settings.setQuietEndForDay(days[i].second, pos)
                            }
                            individualEnds[i].setSelection(pos)
                        }
                    } finally {
                        isUpdatingFromMaster = false
                    }
                    onStatusChanged()
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
    }

    private fun updateChimeSubControlsEnabled(enabled: Boolean) {
        cbTimeChimeAt0?.isEnabled = enabled
        cbTimeChimeAt15?.isEnabled = enabled
        cbTimeChimeAt30?.isEnabled = enabled
        cbTimeChimeAt45?.isEnabled = enabled
        spinnerTimeChimeSound?.isEnabled = enabled
        seekTimeChimeVolume?.isEnabled = enabled
    }

    /** يصفّر مراجع العرض (بند 4.1) — يُستدعى من onDestroyView. */
    fun cleanup() {
        switchTimeAnnouncement = null
        spinnerTimeInterval = null
        llQuietSchedule = null
        spinnerTimeFormat = null
        switchTime24h = null
        switchTimeChime = null
        cbTimeChimeAt0 = null
        cbTimeChimeAt15 = null
        cbTimeChimeAt30 = null
        cbTimeChimeAt45 = null
        spinnerTimeChimeSound = null
        seekTimeChimeVolume = null
        llExactAlarmPermission = null
        btnPreviewTime = null
        btnPreviewChime = null
        switchTimeAlarmMaxPrecision = null
    }
}
