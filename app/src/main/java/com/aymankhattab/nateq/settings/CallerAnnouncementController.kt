package com.aymankhattab.nateq.settings

import android.Manifest
import android.content.pm.PackageManager
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.engine.AnnouncementSchedulerService
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

/** ضابط قسم «إعلان اسم المتصل»: التفعيل بالأذونات، التكرار، السرعة، القالب والأصوات. */
internal class CallerAnnouncementController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val voices: List<NateqVoice>,
    private val onStatusChanged: () -> Unit
) {

    private lateinit var switchCallerAnnouncement: SwitchMaterial
    private lateinit var spinnerCallerRepeat: Spinner
    private lateinit var seekCallerRate: SeekBar
    private lateinit var tvCallerRateValue: TextView
    private lateinit var seekCallerVolume: SeekBar
    private lateinit var tvCallerVolumeValue: TextView
    private lateinit var etCallerTemplate: com.google.android.material.textfield.TextInputEditText
    private lateinit var spinnerCallerVoiceAr: Spinner
    private lateinit var spinnerCallerVoiceEn: Spinner

    /** يمنع مناداة المستمع من رد الطلب (تفادي إعادة طلب الأذونات دورياً) */
    private var callerSwitchGuard = false

    fun setup(view: View) {
        switchCallerAnnouncement = view.findViewById(R.id.switch_caller_announcement)
        spinnerCallerRepeat = view.findViewById(R.id.spinner_caller_repeat)
        seekCallerRate = view.findViewById(R.id.seek_caller_rate)
        tvCallerRateValue = view.findViewById(R.id.tv_caller_rate_value)
        seekCallerVolume = view.findViewById(R.id.seek_caller_volume)
        tvCallerVolumeValue = view.findViewById(R.id.tv_caller_volume_value)
        etCallerTemplate = view.findViewById(R.id.et_caller_template)
        spinnerCallerVoiceAr = view.findViewById(R.id.spinner_caller_voice_ar)
        spinnerCallerVoiceEn = view.findViewById(R.id.spinner_caller_voice_en)

        // المفتاح الرئيسي: عند التفعيل نطلب الأذونات أولاً (لا نفعّل إلا بمنحها)
        switchCallerAnnouncement.isChecked =
            runCatching { settings.isCallerAnnouncementEnabled() }.getOrDefault(false)
        switchCallerAnnouncement.setOnCheckedChangeListener { _, checked ->
            if (callerSwitchGuard) return@setOnCheckedChangeListener
            if (checked) {
                val needed = mutableListOf(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG
                )
                val hasContacts = ContextCompat.checkSelfPermission(
                    fragment.requireContext(), Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasContacts) needed.add(Manifest.permission.READ_CONTACTS)
                fragment.callerPermLauncher.launch(needed.toTypedArray())
            } else {
                runCatching { settings.setCallerAnnouncementEnabled(false) }
                // لا نوقف الخدمة؛ إن لم يبقَ أي إعلان مفعّل تتوقف هي نفسها.
                onStatusChanged()
                fragment.view?.announceCompat(fragment.getString(R.string.announcement_turned_off))
            }
        }

        // عدد مرات التكرار
        val repeats = listOf(
            fragment.getString(R.string.repeat_once),
            fragment.getString(R.string.repeat_twice),
            fragment.getString(R.string.repeat_3),
            fragment.getString(R.string.repeat_4),
            fragment.getString(R.string.repeat_5)
        )
        spinnerCallerRepeat.adapter = fragment.simpleAdapter(repeats)
        val savedRepeat = runCatching { settings.getCallerAnnouncementRepeat() }.getOrDefault(1)
        spinnerCallerRepeat.setSelection((savedRepeat - 1).coerceIn(0, repeats.size - 1))
        spinnerCallerRepeat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                runCatching { settings.setCallerAnnouncementRepeat(position + 1) }
                onStatusChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // سرعة النطق
        val callerRate = runCatching { settings.getCallerAnnouncementRate() }.getOrDefault(1.0f)
        tvCallerRateValue.text = String.format(Locale.US, "%.1fx", callerRate)
        seekCallerRate.progress = (callerRate * 100).toInt().coerceIn(0, 200)
        seekCallerRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                tvCallerRateValue.text = String.format(Locale.US, "%.1fx", value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val value = seekBar.progress / 100f
                runCatching { settings.setCallerAnnouncementRate(value) }
                seekBar.announceCompat(String.format(Locale.US, "%.1fx", value))
            }
        })

        // مستوى الصوت
        val callerVolume = runCatching { settings.getCallerAnnouncementVolume() }.getOrDefault(1.0f)
        tvCallerVolumeValue.text = "${(callerVolume * 100).toInt()}%"
        seekCallerVolume.progress = (callerVolume * 100).toInt().coerceIn(0, 100)
        seekCallerVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvCallerVolumeValue.text = "$progress%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching { settings.setCallerAnnouncementVolume(seekBar.progress / 100f) }
                seekBar.announceCompat("${seekBar.progress}%")
            }
        })

        // قالب إعلان المتصل: {name} لاسم المتصل
        etCallerTemplate.setText(runCatching { settings.getCallerAnnouncementTemplate() }.getOrNull())
        etCallerTemplate.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                runCatching {
                    settings.setCallerAnnouncementTemplate(
                        s?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    )
                }
            }
        })

        // صوت نطق الأسماء العربية في إعلان المتصل
        spinnerCallerVoiceAr.adapter = fragment.simpleAdapter(voices.map { it.displayName })
        val savedCallerVoiceAr =
            runCatching { settings.getCallerAnnouncementArabicVoiceId() }.getOrNull()
        if (savedCallerVoiceAr != null) {
            val idx = voices.indexOfFirst { it.name == savedCallerVoiceAr }
            if (idx >= 0) spinnerCallerVoiceAr.setSelection(idx)
        }
        spinnerCallerVoiceAr.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                runCatching { settings.setCallerAnnouncementArabicVoiceId(voices[position].name) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // صوت نطق الأسماء الإنجليزية في إعلان المتصل
        spinnerCallerVoiceEn.adapter = fragment.simpleAdapter(voices.map { it.displayName })
        val savedCallerVoiceEn =
            runCatching { settings.getCallerAnnouncementEnglishVoiceId() }.getOrNull()
        if (savedCallerVoiceEn != null) {
            val idx = voices.indexOfFirst { it.name == savedCallerVoiceEn }
            if (idx >= 0) spinnerCallerVoiceEn.setSelection(idx)
        }
        spinnerCallerVoiceEn.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                runCatching { settings.setCallerAnnouncementEnglishVoiceId(voices[position].name) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** نتيجة طلب أذونات المتصل: التفّعيل الفعلي لا يتم إلا بعد منح أي إذن. */
    fun onPermissionsResult(granted: Map<String, Boolean>) {
        val phoneGranted = granted[Manifest.permission.READ_PHONE_STATE] == true
        val callLogGranted = granted[Manifest.permission.READ_CALL_LOG] == true
        val contactsGranted = granted[Manifest.permission.READ_CONTACTS] == true
        if (phoneGranted || callLogGranted || contactsGranted) {
            runCatching { settings.setCallerAnnouncementEnabled(true) }
            // حارس يمنع المستمع من إعادة طلب الأذونات عند تعيين قيمة المفتاح هنا
            callerSwitchGuard = true
            switchCallerAnnouncement.isChecked = true
            callerSwitchGuard = false
            AnnouncementSchedulerService.requestStart(fragment.requireContext())
            onStatusChanged()
            val msg = when {
                callLogGranted -> R.string.caller_permission_granted_both
                phoneGranted -> R.string.caller_permission_granted_phone_only
                else -> R.string.caller_permission_granted_contacts_only
            }
            Toast.makeText(fragment.requireContext(), msg, Toast.LENGTH_LONG).show()
            fragment.view?.announceCompat(fragment.getString(msg))
        } else {
            switchCallerAnnouncement.isChecked = false
            runCatching { settings.setCallerAnnouncementEnabled(false) }
            onStatusChanged()
            Toast.makeText(
                fragment.requireContext(),
                R.string.caller_permission_needed,
                Toast.LENGTH_LONG
            ).show()
            fragment.view?.announceCompat(fragment.getString(R.string.caller_permission_needed))
        }
    }
}