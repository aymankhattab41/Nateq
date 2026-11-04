package com.aymankhattab.nateq.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.aymankhattab.nateq.core.audio.CrashGuard
import com.aymankhattab.nateq.core.audio.EmergencyVoice
import com.aymankhattab.nateq.core.audio.EmergencyVoiceConfig
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.audio.providers.VocalizerEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.common.VisibleLanguages
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/**
 * ضابط لوحة صوت الطوارئ الاحتياطي (الأمر 1).
 */
internal object EmergencyVoiceController {

    fun show(
        context: Context,
        settings: SettingsRepository,
        catalog: EngineVoicesCatalog,
        voicePreview: VoicePreviewHelper? = null
    ) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_emergency_voice, null)

        val spinnerEngine = view.findViewById<Spinner>(
            R.id.spinner_emergency_engine
        )
        val spinnerVoice = view.findViewById<Spinner>(
            R.id.spinner_emergency_voice
        )
        val tvSafeStatus = view.findViewById<TextView>(
            R.id.tv_safe_mode_status
        )
        val btnExitSafe = view.findViewById<View>(
            R.id.btn_exit_safe_mode
        )
        val tvRate = view.findViewById<TextView>(
            R.id.tv_emergency_rate_val
        )
        val seekRate = view.findViewById<SeekBar>(
            R.id.seek_emergency_rate
        )
        val tvPitch = view.findViewById<TextView>(
            R.id.tv_emergency_pitch_val
        )
        val seekPitch = view.findViewById<SeekBar>(
            R.id.seek_emergency_pitch
        )
        val tvVol = view.findViewById<TextView>(
            R.id.tv_emergency_volume_val
        )
        val seekVol = view.findViewById<SeekBar>(
            R.id.seek_emergency_volume
        )
        val btnPreview = view.findViewById<View>(
            R.id.btn_emergency_preview
        )
        val btnSave = view.findViewById<View>(
            R.id.btn_emergency_save
        )

        val isSafe = CrashGuard.isSafeModeActive(context)
        if (isSafe) {
            tvSafeStatus.visibility = View.VISIBLE
            btnExitSafe.visibility = View.VISIBLE
        }

        btnExitSafe.setOnClickListener {
            CrashGuard.exitEmergencyMode(context)
            tvSafeStatus.visibility = View.GONE
            btnExitSafe.visibility = View.GONE
            Toast.makeText(
                context,
                R.string.emergency_voice_exit_success,
                Toast.LENGTH_SHORT
            ).show()
        }

        // محركات النظام المثبتة فقط (استبعاد المدمج والتصفية بالمحركات الظاهرة)
        val allInstalled = EnginePicker.installedEngines(context)
        val systemEngines = allInstalled.filter {
            it.packageName != VocalizerEmbeddedVoiceProvider.PROVIDER_ID &&
                VisibleEngines.isVisible(it.packageName)
        }
        val engineLabels = systemEngines.map { it.label }
        val engineAdapter = simpleAdapter(context, engineLabels)
        spinnerEngine.adapter = engineAdapter

        val savedEngine = settings.getEmergencyEngine()
        val initEngineIdx = systemEngines.indexOfFirst {
            it.packageName == savedEngine
        }.coerceAtLeast(0)
        if (systemEngines.isNotEmpty()) {
            spinnerEngine.setSelection(initEngineIdx)
        }

        var voiceOptions: List<VoiceOption> = emptyList()

        fun updateVoiceSpinner(enginePkg: String?) {
            val allVoices = mutableListOf<VoiceOption>()
            if (enginePkg != null) {
                for (lang in catalog.languages()) {
                    if (VisibleLanguages.isVisible(lang)) {
                        allVoices.addAll(catalog.voicesFor(lang, enginePkg))
                    }
                }
            }
            voiceOptions = allVoices.distinctBy { it.name }
            val labels = voiceOptions.map { it.label }
            spinnerVoice.adapter = simpleAdapter(context, labels)
            val savedVoice = settings.getEmergencyVoice()
            val vIdx = voiceOptions.indexOfFirst {
                it.name == savedVoice
            }.coerceAtLeast(0)
            if (voiceOptions.isNotEmpty()) {
                spinnerVoice.setSelection(vIdx)
            }
        }

        val initPkg = systemEngines.getOrNull(initEngineIdx)?.packageName
        updateVoiceSpinner(initPkg)

        spinnerEngine.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    v: View?,
                    position: Int,
                    id: Long
                ) {
                    val pkg = systemEngines.getOrNull(position)?.packageName
                    updateVoiceSpinner(pkg)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        // سرعة: 0.5 .. 3.0 (progress 0..25)
        fun rateFromProgress(p: Int): Float = 0.5f + p * 0.1f
        fun progressFromRate(r: Float): Int =
            ((r - 0.5f) / 0.1f).toInt().coerceIn(0, 25)

        val savedRate = settings.getEmergencyRate()
        seekRate.progress = progressFromRate(savedRate)
        tvRate.text = String.format(Locale.US, "%.1fx", savedRate)
        seekRate.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: SeekBar?,
                    p: Int,
                    fromUser: Boolean
                ) {
                    val r = rateFromProgress(p)
                    tvRate.text = String.format(Locale.US, "%.1fx", r)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }
        )

        // نبرة: 0.5 .. 2.0 (progress 0..15)
        fun pitchFromProgress(p: Int): Float = 0.5f + p * 0.1f
        fun progressFromPitch(pitch: Float): Int =
            ((pitch - 0.5f) / 0.1f).toInt().coerceIn(0, 15)

        val savedPitch = settings.getEmergencyPitch()
        seekPitch.progress = progressFromPitch(savedPitch)
        tvPitch.text = String.format(Locale.US, "%.1f", savedPitch)
        seekPitch.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: SeekBar?,
                    p: Int,
                    fromUser: Boolean
                ) {
                    val pt = pitchFromProgress(p)
                    tvPitch.text = String.format(Locale.US, "%.1f", pt)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }
        )

        // مستوى صوت: 0.0 .. 1.0 (progress 0..20)
        fun volFromProgress(p: Int): Float = p * 0.05f
        fun progressFromVol(vol: Float): Int =
            (vol / 0.05f).toInt().coerceIn(0, 20)

        val savedVol = settings.getEmergencyVolume()
        seekVol.progress = progressFromVol(savedVol)
        tvVol.text = String.format(Locale.US, "%d%%", (savedVol * 100).toInt())
        seekVol.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: SeekBar?,
                    p: Int,
                    fromUser: Boolean
                ) {
                    val vl = volFromProgress(p)
                    tvVol.text = String.format(
                        Locale.US, "%d%%", (vl * 100).toInt()
                    )
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }
        )

        val dialog: AlertDialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .create()

        btnPreview.setOnClickListener {
            val eng = systemEngines.getOrNull(
                spinnerEngine.selectedItemPosition
            )?.packageName
            val vOpt = voiceOptions.getOrNull(
                spinnerVoice.selectedItemPosition
            )
            val r = rateFromProgress(seekRate.progress)
            val p = pitchFromProgress(seekPitch.progress)
            val v = volFromProgress(seekVol.progress)
            val sample = context.getString(
                R.string.emergency_voice_sample_text
            )
            voicePreview?.play(
                PreviewParams(
                    enginePkg = eng,
                    voiceName = vOpt?.name.orEmpty(),
                    languageTag = "ar",
                    speechRate = r,
                    pitch = p,
                    volume = v,
                    sampleText = sample
                )
            )
        }

        btnSave.setOnClickListener {
            val eng = systemEngines.getOrNull(
                spinnerEngine.selectedItemPosition
            )?.packageName
            val vOpt = voiceOptions.getOrNull(
                spinnerVoice.selectedItemPosition
            )
            val r = rateFromProgress(seekRate.progress)
            val p = pitchFromProgress(seekPitch.progress)
            val v = volFromProgress(seekVol.progress)

            settings.setEmergencyEngine(eng)
            settings.setEmergencyVoice(vOpt?.name)
            settings.setEmergencyRate(r)
            settings.setEmergencyPitch(p)
            settings.setEmergencyVolume(v)

            EmergencyVoice.save(
                context,
                EmergencyVoiceConfig(
                    engine = eng,
                    voice = vOpt?.name,
                    rate = r,
                    pitch = p,
                    volume = v,
                    isSaved = true
                )
            )
            Toast.makeText(
                context,
                R.string.emergency_voice_saved,
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
        }

        dialog.show()
    }
}
