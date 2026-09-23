package com.aymankhattab.nateq.settings

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSpinner
import com.aymankhattab.nateq.core.engine.AudioExpansionLevels
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.announceCompat
import com.aymankhattab.nateq.util.setSeekStateDescription
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.google.android.material.switchmaterial.SwitchMaterial

/** ضابط قسم «الإعدادات العامة»: السرعة/النبرة/مستوى الصوت الافتراضية
 *  ومفتاح مسار الوسائط الدائم للإعلانات (بند 1.4). */
internal class GeneralSettingsController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val onStatusChanged: () -> Unit
) {

    // مراجع العرض قابلة للتصفير في cleanup() عند تدمير عرض الفصيل
    // (بند 4.1) حتى لا تبقى شجرة العرض القديمة محتجزة في الخلفية.
    private var seekDefaultSpeechRate: SeekBar? = null
    private var tvDefaultSpeechRateValue: TextView? = null
    private var seekDefaultPitch: SeekBar? = null
    private var tvDefaultPitchValue: TextView? = null
    private var seekDefaultVolume: SeekBar? = null
    private var tvDefaultVolumeValue: TextView? = null
    private var switchMediaStreamAlways: SwitchMaterial? = null
    private var switchDuckMedia: SwitchMaterial? = null
    private var switchFollowReaderRate: SwitchMaterial? = null
    private var spinnerAudioExpansion: AppCompatSpinner? = null

    // **بند 6.3:** علمُ الربط البرمجي — يُسنَّع حول setProgress في attach
    // حتى لا يُفسَّر الإسنادُ البرمجي تعديلَ مستخدم (يُخزَّن تفريغاً). دون
    // الحفظ في onProgressChanged كان TalkBack (تعديلٌ عبر أداء الوصول لا
    // يمر بـ onStopTrackingTouch إطلاقاً) يفقد أي تعديل على أشرطة التمرير.
    private var bindingSlider = false
    private var bindingAudioExpansion = false

    fun setup(view: View) {
        seekDefaultSpeechRate =
            view.findViewById(R.id.seek_default_speech_rate)
        tvDefaultSpeechRateValue =
            view.findViewById(R.id.tv_default_speech_rate_value)
        seekDefaultPitch = view.findViewById(R.id.seek_default_pitch)
        tvDefaultPitchValue = view.findViewById(R.id.tv_default_pitch_value)
        seekDefaultVolume = view.findViewById(R.id.seek_default_volume)
        tvDefaultVolumeValue = view.findViewById(R.id.tv_default_volume_value)
        switchMediaStreamAlways =
            view.findViewById(R.id.switch_media_stream_always)
        switchMediaStreamAlways?.isChecked =
            runCatching { settings.isAnnouncementMediaStreamAlways() }
                .getOrDefault(false)
        switchMediaStreamAlways?.setOnCheckedChangeListener { _, checked ->
            // بند 1.4: «دائماً على مسار الوسائط» — يُمكّن المستخدم من
            // تجاوز كتم مسار الإتاحة على الأجهزة التي يخفت فيها صوته
            // دون قارئ شاشة.
            runCatching { settings.setAnnouncementMediaStreamAlways(checked) }
            onStatusChanged()
        }

        switchDuckMedia = view.findViewById(R.id.switch_duck_media)
        switchDuckMedia?.isChecked = runCatching {
            settings.isDuckMediaDuringAnnouncements()
        }.getOrDefault(true)
        switchDuckMedia?.setOnCheckedChangeListener { _, checked ->
            // بند الصوتيات: «خفض صوت الوسائط أثناء النطق» — يُترجم إلى
            // نوع الطلب MAY_DUCK (خفض) مقابل GAIN_TRANSIENT (توقف مؤقت)
            // في AnnouncementSpeaker.requestAudioFocus.
            runCatching {
                settings.setDuckMediaDuringAnnouncements(checked)
            }
            onStatusChanged()
        }

        switchFollowReaderRate =
            view.findViewById(R.id.switch_follow_reader_rate)
        switchFollowReaderRate?.isChecked =
            runCatching { settings.isFollowReaderRateEnabled() }
                .getOrDefault(true)
        switchFollowReaderRate?.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setFollowReaderRateEnabled(checked) }
            onStatusChanged()
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        val rate = runCatching { settings.getDefaultSpeechRate() }
            .getOrDefault(1.0f)
            .coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        val pitch = runCatching { settings.getDefaultPitch() }
            .getOrDefault(1.0f)
            .coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        val volume = runCatching { settings.getDefaultVolume() }
            .getOrDefault(1.0f)

        tvDefaultSpeechRateValue?.text = RateLabel.of(
            fragment.requireContext(), rate
        )
        bindingSlider = true
        try {
            seekDefaultSpeechRate?.progress =
                (rate * 100).toInt().coerceIn(0, 200)
        } finally {
            bindingSlider = false
        }
        tvDefaultPitchValue?.text = RateLabel.of(
            fragment.requireContext(), pitch
        )
        bindingSlider = true
        try {
            seekDefaultPitch?.progress =
                (pitch * 100).toInt().coerceIn(0, 200)
        } finally {
            bindingSlider = false
        }
        tvDefaultVolumeValue?.text = "${(volume * 100).toInt()}%"
        bindingSlider = true
        try {
            seekDefaultVolume?.progress =
                (volume * 100).toInt().coerceIn(0, 100)
        } finally {
            bindingSlider = false
        }

        seekDefaultSpeechRate?.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingSlider) return
                val value = progress.speedFactor()
                tvDefaultSpeechRateValue?.text = RateLabel.of(
                    fragment.requireContext(),
                    value
                )
                seekBar.setSeekStateDescription(
                    tvDefaultSpeechRateValue?.text ?: ""
                )
                // **بند 6.3:** الحفظ عند كل تغيير (لا عند رفع الإصبع فقط) —
                // تعديل TalkBack لا يصل إلى onStopTrackingTouch أبداً.
                runCatching { settings.setDefaultSpeechRate(value) }
                onStatusChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekBar.snapSpeedMin()
                val value = seekBar.progress.speedFactor()
                runCatching { settings.setDefaultSpeechRate(value) }
                seekBar.announceCompat(
                    RateLabel.of(fragment.requireContext(), value)
                )
                onStatusChanged()
            }
        })

        seekDefaultPitch?.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingSlider) return
                val value = progress.speedFactor()
                tvDefaultPitchValue?.text = RateLabel.of(
                    fragment.requireContext(),
                    value
                )
                seekBar.setSeekStateDescription(
                    tvDefaultPitchValue?.text ?: ""
                )
                // **بند 6.3:** الحفظ عند كل تغيير — تعديل TalkBack لا تصل
                // نهايته إلى onStopTrackingTouch أبداً.
                runCatching { settings.setDefaultPitch(value) }
                onStatusChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seekBar.snapSpeedMin()
                val value = seekBar.progress.speedFactor()
                runCatching { settings.setDefaultPitch(value) }
                seekBar.announceCompat(
                    RateLabel.of(fragment.requireContext(), value)
                )
                onStatusChanged()
            }
        })

        seekDefaultVolume?.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingSlider) return
                tvDefaultVolumeValue?.text = "$progress%"
                seekBar.setSeekStateDescription(
                    tvDefaultVolumeValue?.text ?: ""
                )
                // **بند 6.3:** الحفظ عند كل تغيير — تعديل TalkBack لا تصل
                // نهايته إلى onStopTrackingTouch أبداً.
                runCatching { settings.setDefaultVolume(progress / 100f) }
                onStatusChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val value = seekBar.progress / 100f
                runCatching { settings.setDefaultVolume(value) }
                seekBar.announceCompat("${seekBar.progress}%")
                onStatusChanged()
            }
        })

        spinnerAudioExpansion =
            view.findViewById(R.id.spinner_audio_expansion)
        val expansionLabels = arrayOf(
            fragment.getString(R.string.audio_expansion_off),
            fragment.getString(R.string.audio_expansion_light),
            fragment.getString(R.string.audio_expansion_medium)
        )
        val savedExpansion = runCatching { settings.getAudioExpansionLevel() }
            .getOrDefault(AudioExpansionLevels.DEFAULT)
            .coerceIn(AudioExpansionLevels.MIN, AudioExpansionLevels.MAX)
        spinnerAudioExpansion?.adapter = ArrayAdapter(
            fragment.requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            expansionLabels
        )
        bindingAudioExpansion = true
        try {
            spinnerAudioExpansion?.setSelection(savedExpansion)
        } finally {
            bindingAudioExpansion = false
        }
        spinnerAudioExpansion?.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (bindingAudioExpansion) return
                val level = position.coerceIn(
                    AudioExpansionLevels.MIN,
                    AudioExpansionLevels.MAX
                )
                runCatching {
                    settings.setAudioExpansionLevel(level)
                }
                onStatusChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** يصفّر مراجع العرض (بند 4.1) — يُستدعى من onDestroyView. */
    fun cleanup() {
        seekDefaultSpeechRate = null
        tvDefaultSpeechRateValue = null
        seekDefaultPitch = null
        tvDefaultPitchValue = null
        seekDefaultVolume = null
        tvDefaultVolumeValue = null
        switchMediaStreamAlways = null
        switchDuckMedia = null
        switchFollowReaderRate = null
        spinnerAudioExpansion = null
    }
}
