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

    // مراجع العرض قابلة للتصفير في cleanup() عند تدمير عرض الفصيل
    // (بند 4.1) حتى لا تبقى شجرة العرض القديمة محتجزة في الخلفية.
    private var switchShakeToStop: SwitchMaterial? = null
    private var switchProximitySilence: SwitchMaterial? = null

    fun setup(view: View) {
        switchShakeToStop = view.findViewById(R.id.switch_shake_to_stop)
        switchProximitySilence =
            view.findViewById(R.id.switch_proximity_silence)

        switchShakeToStop?.isChecked =
            runCatching { settings.isShakeToStopEnabled() }
                .getOrDefault(false)
        switchShakeToStop?.setOnCheckedChangeListener { _, checked ->
            runCatching { settings.setShakeToStopEnabled(checked) }
            onStatusChanged()
            fragment.view?.announceCompat(
                fragment.getString(
                    if (checked) R.string.announcement_turned_on
                    else R.string.announcement_turned_off
                )
            )
        }

        switchProximitySilence?.isChecked =
            runCatching { settings.isProximitySilenceEnabled() }
                .getOrDefault(false)
        switchProximitySilence?.setOnCheckedChangeListener { _, checked ->
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

    /** يصفّر مراجع العرض (بند 4.1) — يُستدعى من onDestroyView. */
    fun cleanup() {
        switchShakeToStop = null
        switchProximitySilence = null
    }
}