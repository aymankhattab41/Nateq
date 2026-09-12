package com.aymankhattab.nateq.settings

import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.announceCompat
import com.aymankhattab.nateq.util.setSeekStateDescription
import com.aymankhattab.nateq.core.data.SettingsRepository

/** ضابط قسم «الإعدادات العامة»: السرعة/النبرة/مستوى الصوت الافتراضية. */
internal class GeneralSettingsController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val onStatusChanged: () -> Unit
) {

    private lateinit var seekDefaultSpeechRate: SeekBar
    private lateinit var tvDefaultSpeechRateValue: TextView
    private lateinit var seekDefaultPitch: SeekBar
    private lateinit var tvDefaultPitchValue: TextView
    private lateinit var seekDefaultVolume: SeekBar
    private lateinit var tvDefaultVolumeValue: TextView

    // **بند 6.3:** علمُ الربط البرمجي — يُسنَّع حول setProgress في attach
    // حتى لا يُفسَّر الإسنادُ البرمجي تعديلَ مستخدم (يُخزَّن تفريغاً). دون
    // الحفظ في onProgressChanged كان TalkBack (تعديلٌ عبر أداء الوصول لا
    // يمر بـ onStopTrackingTouch إطلاقاً) يفقد أي تعديل على أشرطة التمرير.
    private var bindingSlider = false

    fun setup(view: View) {
        seekDefaultSpeechRate =
            view.findViewById(R.id.seek_default_speech_rate)
        tvDefaultSpeechRateValue =
            view.findViewById(R.id.tv_default_speech_rate_value)
        seekDefaultPitch = view.findViewById(R.id.seek_default_pitch)
        tvDefaultPitchValue = view.findViewById(R.id.tv_default_pitch_value)
        seekDefaultVolume = view.findViewById(R.id.seek_default_volume)
        tvDefaultVolumeValue = view.findViewById(R.id.tv_default_volume_value)

        val rate = runCatching { settings.getDefaultSpeechRate() }
            .getOrDefault(1.0f)
            .coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        val pitch = runCatching { settings.getDefaultPitch() }
            .getOrDefault(1.0f)
            .coerceAtLeast(MIN_SPEED_PITCH_FACTOR)
        val volume = runCatching { settings.getDefaultVolume() }
            .getOrDefault(1.0f)

        tvDefaultSpeechRateValue.text = RateLabel.of(
            fragment.requireContext(), rate
        )
        bindingSlider = true
        try {
            seekDefaultSpeechRate.progress =
                (rate * 100).toInt().coerceIn(0, 200)
        } finally {
            bindingSlider = false
        }
        tvDefaultPitchValue.text = RateLabel.of(
            fragment.requireContext(), pitch
        )
        bindingSlider = true
        try {
            seekDefaultPitch.progress = (pitch * 100).toInt().coerceIn(0, 200)
        } finally {
            bindingSlider = false
        }
        tvDefaultVolumeValue.text = "${(volume * 100).toInt()}%"
        bindingSlider = true
        try {
            seekDefaultVolume.progress =
                (volume * 100).toInt().coerceIn(0, 100)
        } finally {
            bindingSlider = false
        }

        seekDefaultSpeechRate.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingSlider) return
                val value = progress.speedFactor()
                tvDefaultSpeechRateValue.text = RateLabel.of(
                    fragment.requireContext(),
                    value
                )
                seekBar.setSeekStateDescription(
                    tvDefaultSpeechRateValue.text
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

        seekDefaultPitch.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingSlider) return
                val value = progress.speedFactor()
                tvDefaultPitchValue.text = RateLabel.of(
                    fragment.requireContext(),
                    value
                )
                seekBar.setSeekStateDescription(
                    tvDefaultPitchValue.text
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

        seekDefaultVolume.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean
            ) {
                // الربط البرمجي ليس تعديلَ مستخدم — يُهمل بلا حفظ.
                if (bindingSlider) return
                tvDefaultVolumeValue.text = "$progress%"
                seekBar.setSeekStateDescription(
                    tvDefaultVolumeValue.text
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
    }
}
