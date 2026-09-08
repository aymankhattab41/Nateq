package com.aymankhattab.nateq.settings

import android.content.pm.PackageManager
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.SeekBar
import com.aymankhattab.nateq.util.setSeekStateDescription
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSchedulerService
import com.aymankhattab.nateq.core.audio.announcement.NateqNotificationListener
import com.aymankhattab.nateq.util.announceCompat
import java.util.Locale
import com.aymankhattab.nateq.core.data.SettingsRepository

/** ضابط قسم «قراءة الرسائل الواردة»: وضع القراءة بالأذونات، الأصوات، السرعة والقالب. */
internal class SmsReadingController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val voices: List<NateqVoice>,
    private val onStatusChanged: () -> Unit
) {

    private lateinit var spinnerSmsMode: Spinner
    private lateinit var spinnerSmsVoice: Spinner
    private lateinit var seekSmsRate: SeekBar
    private lateinit var tvSmsRateValue: TextView
    private lateinit var seekSmsVolume: SeekBar
    private lateinit var tvSmsVolumeValue: TextView
    private lateinit var etSmsTemplate: com.google.android.material.textfield.TextInputEditText

    /** الوضع المختار (full/source) لا يُحفظ إلا بعد المنح الفعلي حتى لا يبقى
     *  مفعّلاً زوراً عند رفض المستخدم الإذن (بند [9]). */
    private var pendingSmsMode: String? = null

    fun setup(view: View) {
        spinnerSmsMode = view.findViewById(R.id.spinner_sms_reading_mode)
        spinnerSmsVoice = view.findViewById(R.id.spinner_sms_reading_voice)
        seekSmsRate = view.findViewById(R.id.seek_sms_reading_rate)
        tvSmsRateValue = view.findViewById(R.id.tv_sms_reading_rate_value)
        seekSmsVolume = view.findViewById(R.id.seek_sms_reading_volume)
        tvSmsVolumeValue = view.findViewById(R.id.tv_sms_reading_volume_value)
        etSmsTemplate = view.findViewById(R.id.et_sms_template)

        // وضع القراءة: مفعل / قراءة مصدر الرسالة فقط / معطل
        val modes = listOf(
            fragment.getString(R.string.sms_mode_full),
            fragment.getString(R.string.sms_mode_source),
            fragment.getString(R.string.sms_mode_off)
        )
        spinnerSmsMode.adapter = fragment.simpleAdapter(modes)
        val savedMode = runCatching { settings.getSmsReadingMode() }.getOrDefault("off")
        val modeIndex = when (savedMode) {
            "full" -> 0
            "source" -> 1
            else -> 2 // "off"
        }
        spinnerSmsMode.setSelection(modeIndex)
        spinnerSmsMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = when (position) {
                    0 -> "full"
                    1 -> "source"
                    else -> "off"
                }
                if (mode == "off") {
                    // "off": يُحفظ فوراً (لا يتطلب إذناً) ويرفع أي وضع معلّق.
                    pendingSmsMode = null
                    runCatching { settings.setSmsReadingMode("off") }
                    AnnouncementSchedulerService.syncIfRunning(fragment.requireContext())
                    onStatusChanged()
                    return
                }
                // وضع غير "off" (full/source): يتطلب إحدى قناتي الاستقبال لكي يعمل فعلاً:
                // 1) إذن RECEIVE_SMS (المستقبل المباشر للبث SMS_RECEIVED)، أو
                // 2) خدمة الاستماع للإشعارات (NLS) كبديل بلا إذن قيود.
                if (ContextCompat.checkSelfPermission(
                        fragment.requireContext(),
                        android.Manifest.permission.RECEIVE_SMS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // RECEIVE_SMS غير ممنوح: إن كانت NLS مفعّلة (من قسم قراءة
                    // الإشعارات) نعتمد عليها مباشرة دون طلب الإذن المقيد.
                    if (NateqNotificationListener.isPermissionGranted(fragment.requireContext()) &&
                        settings.isNotificationReadingEnabled()
                    ) {
                        pendingSmsMode = null
                        runCatching { settings.setSmsReadingMode(mode) }
                        AnnouncementSchedulerService.requestStart(fragment.requireContext())
                        fragment.view?.announceCompat(fragment.getString(R.string.sms_reading_via_nls))
                    } else {
                        pendingSmsMode = mode
                        runCatching {
                            fragment.smsPermLauncher.launch(android.Manifest.permission.RECEIVE_SMS)
                        }
                    }
                } else {
                    // الإذن ممنوح من قبل: نحفظ مباشرة.
                    pendingSmsMode = null
                    runCatching { settings.setSmsReadingMode(mode) }
                    AnnouncementSchedulerService.requestStart(fragment.requireContext())
                }
                // تنبيه سياسة أندرويد 17: رسائل OTP تُحجب 3 ساعات أولى بعد التفعيل.
                fragment.view?.announceCompat(fragment.getString(R.string.sms_otp_block_hint))
                onStatusChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // اختيار الصوت (العربية / الإنجليزية)
        spinnerSmsVoice.adapter = fragment.simpleAdapter(voices.map { it.displayName })
        val savedSmsVoice = runCatching { settings.getSmsReadingVoiceId() }.getOrNull()
        if (savedSmsVoice != null) {
            val idx = voices.indexOfFirst { it.name == savedSmsVoice }
            if (idx >= 0) spinnerSmsVoice.setSelection(idx)
        }
        spinnerSmsVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                runCatching { settings.setSmsReadingVoiceId(voices[position].name) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // سرعة النطق
        val smsRate = runCatching { settings.getSmsReadingRate() }.getOrDefault(1.0f)
        tvSmsRateValue.text = String.format(Locale.US, "%.1fx", smsRate)
        seekSmsRate.progress = (smsRate * 100).toInt().coerceIn(0, 200)
        seekSmsRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                tvSmsRateValue.text = String.format(Locale.US, "%.1fx", value)
                seekBar.setSeekStateDescription(tvSmsRateValue.text)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching { settings.setSmsReadingRate(seekBar.progress / 100f) }
                seekBar.announceCompat(String.format(Locale.US, "%.1fx", seekBar.progress / 100f))
            }
        })

        // مستوى الصوت
        val smsVolume = runCatching { settings.getSmsReadingVolume() }.getOrDefault(1.0f)
        tvSmsVolumeValue.text = "${(smsVolume * 100).toInt()}%"
        seekSmsVolume.progress = (smsVolume * 100).toInt().coerceIn(0, 100)
        seekSmsVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvSmsVolumeValue.text = "$progress%"
                seekBar.setSeekStateDescription(tvSmsVolumeValue.text)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                runCatching { settings.setSmsReadingVolume(seekBar.progress / 100f) }
                seekBar.announceCompat("${seekBar.progress}%")
            }
        })

        // قالب قراءة الرسائل: {name} للمرسل و{message} للرسالة
        etSmsTemplate.setText(runCatching { settings.getSmsAnnouncementTemplate() }.getOrNull())
        etSmsTemplate.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                runCatching {
                    settings.setSmsAnnouncementTemplate(
                        s?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    )
                }
            }
        })
    }

    /** نتيجة طلب إذن قراءة الرسائل: الوضع المعلّق يُثبَّت فقط عند المنح الفعلي. */
    fun onSmsPermissionResult(granted: Boolean) {
        val pending = pendingSmsMode
        pendingSmsMode = null
        if (granted) {
            // مُنح الإذن: الآن فقط نُثبّت الوضع المعلّق المطلوب (full/source).
            if (pending != null) {
                runCatching { settings.setSmsReadingMode(pending) }
            }
            fragment.view?.announceCompat(fragment.getString(R.string.permission_sms_granted))
            AnnouncementSchedulerService.requestStart(fragment.requireContext())
        } else {
            // رُفض: نعيد المفتاح إلى "off" (لم يكن قد حُفظ) ونعلن السبب.
            if (pending != null) {
                runCatching { settings.setSmsReadingMode("off") }
                AnnouncementSchedulerService.syncIfRunning(fragment.requireContext())
                if (::spinnerSmsMode.isInitialized) spinnerSmsMode.setSelection(2)
            }
            fragment.view?.announceCompat(fragment.getString(R.string.sms_permission_needed))
        }
    }
}
