package com.aymankhattab.nateq.settings

import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.util.announceCompat
import java.util.Locale

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

    fun setup(view: View) {
        seekDefaultSpeechRate = view.findViewById(R.id.seek_default_speech_rate)
        tvDefaultSpeechRateValue = view.findViewById(R.id.tv_default_speech_rate_value)
        seekDefaultPitch = view.findViewById(R.id.seek_default_pitch)
        tvDefaultPitchValue = view.findViewById(R.id.tv_default_pitch_value)
        seekDefaultVolume = view.findViewById(R.id.seek_default_volume)
        tvDefaultVolumeValue = view.findViewById(R.id.tv_default_volume_value)

        val rate = runCatching { settings.getDefaultSpeechRate() }.getOrDefault(1.0f)
        val pitch = runCatching { settings.getDefaultPitch() }.getOrDefault(1.0f)
        val volume = runCatching { settings.getDefaultVolume() }.getOrDefault(1.0f)

        tvDefaultSpeechRateValue.text = String.format(Locale.US, "%.1fx", rate)
        seekDefaultSpeechRate.progress = (rate * 100).toInt().coerceIn(0, 200)
        tvDefaultPitchValue.text = String.format(Locale.US, "%.1fx", pitch)
        seekDefaultPitch.progress = (pitch * 100).toInt().coerceIn(0, 200)
        tvDefaultVolumeValue.text = "${(volume * 100).toInt()}%"
        seekDefaultVolume.progress = (volume * 100).toInt().coerceIn(0, 100)

        seekDefaultSpeechRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                tvDefaultSpeechRateValue.text = String.format(Locale.US, "%.1fx", value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val value = seekBar.progress / 100f
                runCatching { settings.setDefaultSpeechRate(value) }
                seekBar.announceCompat(String.format(Locale.US, "%.1fx", value))
                onStatusChanged()
            }
        })

        seekDefaultPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                tvDefaultPitchValue.text = String.format(Locale.US, "%.1fx", value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val value = seekBar.progress / 100f
                runCatching { settings.setDefaultPitch(value) }
                seekBar.announceCompat(String.format(Locale.US, "%.1fx", value))
                onStatusChanged()
            }
        })

        seekDefaultVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvDefaultVolumeValue.text = "$progress%"
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