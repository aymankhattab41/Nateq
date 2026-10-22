package com.aymankhattab.nateq.core.audio.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * مستقبل المنبه الاحتياطي وإشارات الاتصال لإعادة تفعيل إمكانية الوصول
 * (الجولة 51 والجولة 52 - الأمر 1).
 */
class ProtectedAppRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ProtectedAppManager.ACTION_RESTORE_ALARM -> {
                        val restored = ProtectedAppManager.checkAndRestore(
                            context = context,
                            reason = "alarm"
                        )
                        if (!restored &&
                            ProtectedAppManager.isSuspended(context)
                        ) {
                            ProtectedAppManager.scheduleBackupAlarm(context)
                        }
                    }
                    ProtectedAppManager.ACTION_A11Y_CONNECTED -> {
                        if (ProtectedAppManager.isSuspended(context)) {
                            val suspension =
                                ProtectedAppSuspensionStore.load(context)
                            val suspendedPkg = suspension?.packageName
                                ?: SettingsRepository.create(context)
                                    .getSuspendedProtectedApp()
                            val currentFg =
                                ProtectedAppManager.getForegroundPackage(
                                    context
                                )
                            if (!suspendedPkg.isNullOrBlank() &&
                                currentFg != suspendedPkg
                            ) {
                                ProtectedAppSuspensionStore.clear(context)
                                SettingsRepository.create(context)
                                    .setSuspendedProtectedApp(null)
                                ProtectedAppManager.cancelBackupAlarm(context)
                                ProtectedAppWatcherService.stopService(context)
                                ProtectedAppManager.resetStateAfterRestore()
                            }
                        }
                    }
                    ProtectedAppManager.ACTION_A11Y_DISCONNECTED -> {
                        // إشعار انفصال الخدمة
                    }
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
