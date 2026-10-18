package com.aymankhattab.nateq.settings.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * ميسّر اختيار الشريحة (SIM 1 / SIM 2) للأجهزة متعددة الشرائح.
 */
object SimCallHelper {

    fun getCallCapableAccounts(context: Context): List<PhoneAccountHandle> {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE)
            as? TelecomManager ?: return emptyList()
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState) return emptyList()

        return try {
            telecom.callCapablePhoneAccounts ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun getSimLabel(
        context: Context,
        simSlot: Int,
        accounts: List<PhoneAccountHandle>
    ): String {
        val fallback = if (simSlot == 0) "SIM 1" else "SIM 2"
        if (simSlot < accounts.size) {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE)
                as? TelecomManager
            val account = telecom?.getPhoneAccount(accounts[simSlot])
            val label = account?.label?.toString()
            if (!label.isNullOrBlank()) {
                return "$fallback ($label)"
            }
        }
        return fallback
    }

    fun showSimChooserDialog(
        context: Context,
        number: String,
        displayName: String? = null,
        onChosen: (Int) -> Unit
    ) {
        val accounts = getCallCapableAccounts(context)
        val sim1Label = getSimLabel(context, 0, accounts)
        val sim2Label = getSimLabel(context, 1, accounts)

        val title = if (!displayName.isNullOrBlank()) {
            "$displayName ($number)"
        } else {
            number
        }

        val options = arrayOf(
            "${context.getString(R.string.speed_dial_call_sim1)}: $sim1Label",
            "${context.getString(R.string.speed_dial_call_sim2)}: $sim2Label"
        )

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setItems(options) { _, which ->
                val slot = if (which == 0) 0 else 1
                onChosen(slot)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
