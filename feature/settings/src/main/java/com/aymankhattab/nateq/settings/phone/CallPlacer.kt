package com.aymankhattab.nateq.settings.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * المدخل الموحد والوحيد لإجراء المكالمات الهاتفية في التطبيق.
 */
class CallPlacer(
    private val activity: ComponentActivity,
    private val speak: (String) -> Unit
) {

    private data class PendingCall(
        val number: String,
        val simSlot: Int?,
        val source: String
    )

    private var pendingCall: PendingCall? = null

    private val permissionLauncher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            val pending = pendingCall
            pendingCall = null
            if (isGranted) {
                if (pending != null) {
                    executeCall(
                        pending.number,
                        pending.simSlot,
                        pending.source
                    )
                }
            } else {
                handlePermissionDenied()
            }
        }

    fun placeCall(
        number: String,
        simSlot: Int? = null,
        source: String = "unknown"
    ) {
        val clean = number.trim()
        if (clean.isEmpty()) {
            speak("الرجاء كتابة رقم الهاتف أولاً")
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            pendingCall = PendingCall(clean, simSlot, source)
            permissionLauncher.launch(Manifest.permission.CALL_PHONE)
            return
        }

        executeCall(clean, simSlot, source)
    }

    private fun handlePermissionDenied() {
        val canShowRationale = ActivityCompat
            .shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.CALL_PHONE
            )

        if (!canShowRationale) {
            speak("إذن الاتصال مرفوض، سأفتح إعدادات التطبيق")
            try {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                ).apply {
                    data = Uri.fromParts(
                        "package",
                        activity.packageName,
                        null
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open app details settings", e)
            }
        } else {
            speak("إذن الاتصال مطلوب لإجراء المكالمة")
        }
    }

    private fun executeCall(
        cleanNumber: String,
        simSlot: Int?,
        source: String
    ) {
        val isAirplane = Settings.Global.getInt(
            activity.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0
        ) != 0

        if (isAirplane) {
            val msg = "وضع الطيران مفعّل، لا يمكن إجراء المكالمة"
            speak(msg)
            Log.w(
                TAG,
                "[CallPlacer] Aborted: Airplane mode ON (source=$source)"
            )
            return
        }

        val tm = activity.getSystemService(TelephonyManager::class.java)
        val generalSimState = tm?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
        val targetSimState = if (
            simSlot != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            try {
                tm?.getSimState(simSlot) ?: generalSimState
            } catch (_: Exception) {
                generalSimState
            }
        } else {
            generalSimState
        }

        when (targetSimState) {
            TelephonyManager.SIM_STATE_ABSENT -> {
                val msg = if (simSlot != null) {
                    "الشريحة المطلوبة غير موجودة في الجهاز"
                } else {
                    "لا توجد شريحة SIM في الجهاز"
                }
                speak(msg)
                Log.w(
                    TAG,
                    "[CallPlacer] Aborted: SIM absent " +
                        "(simSlot=$simSlot, source=$source)"
                )
                return
            }
            TelephonyManager.SIM_STATE_PIN_REQUIRED,
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> {
                speak("شريحة SIM مقفلة برمز الحماية")
                Log.w(TAG, "[CallPlacer] Aborted: SIM locked (source=$source)")
                return
            }
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> {
                speak("شريحة SIM مقفلة على الشبكة")
                Log.w(
                    TAG,
                    "[CallPlacer] Aborted: SIM network locked " +
                        "(source=$source)"
                )
                return
            }
        }

        val accounts = SimCallHelper.getCallCapableAccounts(activity)
        val handle = if (simSlot != null) {
            val resolved = resolvePhoneAccountHandle(
                activity,
                simSlot,
                accounts
            )
            if (resolved == null && accounts.isNotEmpty()) {
                speak(
                    "تعذّر تحديد الشريحة تلقائياً، " +
                        "سيطلب النظام اختيار الشريحة"
                )
            }
            resolved
        } else {
            null
        }

        val uri = Uri.fromParts("tel", cleanNumber, null)
        val extras = Bundle()
        if (handle != null) {
            extras.putParcelable(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                handle
            )
        }

        val telecom = activity.getSystemService(TelecomManager::class.java)
        var usedPath = "placeCall"
        var callException: Throwable? = null

        try {
            if (telecom != null) {
                telecom.placeCall(uri, extras)
            } else {
                throw IllegalStateException("TelecomManager is null")
            }
        } catch (e: Exception) {
            callException = e
            usedPath = "ACTION_CALL"
            try {
                val callIntent = Intent(Intent.ACTION_CALL, uri).apply {
                    if (handle != null) {
                        putExtra(
                            TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                            handle
                        )
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(callIntent)
            } catch (e2: Exception) {
                callException = e2
                usedPath = "FAILED"
                speak("تعذّر إجراء المكالمة")
            }
        }

        Log.i(
            TAG,
            "[CallPlacer] source=$source, number=$cleanNumber, " +
                "simSlot=$simSlot, handle=$handle, path=$usedPath, " +
                "error=${callException?.message}"
        )
    }

    companion object {
        private const val TAG = "CallPlacer"

        fun resolvePhoneAccountHandle(
            context: Context,
            simSlot: Int,
            accounts: List<PhoneAccountHandle>
        ): PhoneAccountHandle? {
            if (accounts.isEmpty() || simSlot < 0) return null

            val hasPhoneState = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPhoneState) {
                val sm = context.getSystemService(
                    SubscriptionManager::class.java
                )
                val tm = context.getSystemService(
                    TelephonyManager::class.java
                )
                val subInfo = try {
                    sm?.getActiveSubscriptionInfoForSimSlotIndex(simSlot)
                } catch (_: SecurityException) {
                    null
                }

                if (subInfo != null) {
                    val targetSubId = subInfo.subscriptionId
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        tm != null
                    ) {
                        for (h in accounts) {
                            try {
                                val handleTm =
                                    tm.createForPhoneAccountHandle(h)
                                if (
                                    handleTm != null &&
                                    handleTm.subscriptionId == targetSubId
                                ) {
                                    return h
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    val telecom = context.getSystemService(
                        TelecomManager::class.java
                    )
                    for (h in accounts) {
                        try {
                            val account = telecom?.getPhoneAccount(h)
                                ?: continue
                            val subAddress = account.subscriptionAddress
                                ?.schemeSpecificPart
                            val number = subInfo.number
                            if (
                                !number.isNullOrEmpty() &&
                                subAddress == number
                            ) {
                                return h
                            }
                            val label = account.label?.toString().orEmpty()
                            val disp = subInfo.displayName?.toString().orEmpty()
                            if (
                                disp.isNotEmpty() &&
                                label.contains(disp, ignoreCase = true)
                            ) {
                                return h
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            return null
        }
    }
}
