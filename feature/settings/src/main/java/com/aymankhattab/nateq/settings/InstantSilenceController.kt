package com.aymankhattab.nateq.settings

import android.view.View
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.aymankhattab.nateq.core.data.SettingsRepository

/** ضابط قسم «الإسكات الفوري»: هز الجهاز / تغطية مستشعر القرب. */
internal class InstantSilenceController(
    private val fragment: VoiceSelectionFragment,
    private val settings: SettingsRepository,
    private val onStatusChanged: () -> Unit
) {

    private lateinit var switchShakeToStop: SwitchMaterial
    private lateinit var switchProximitySilence: SwitchMaterial

    fun setup(view: View) {
        switchShakeToStop = view.findViewById(R.id.switch_shake_to_stop)
        switchProximitySilence =
            view.findViewById(R.id.switch_proximity_silence)

        switchShakeToStop.isChecked =
            runCatching { settings.isShakeToStopEnabled() }
                .getOrDefault(false)
        switchShakeToStop.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setShakeToStopEnabled(checked) }
            onStatusChanged()
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        switchProximitySilence.isChecked =
            runCatching { settings.isProximitySilenceEnabled() }
                .getOrDefault(false)
        switchProximitySilence.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setProximitySilenceEnabled(checked) }
            onStatusChanged()
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }
    }
}