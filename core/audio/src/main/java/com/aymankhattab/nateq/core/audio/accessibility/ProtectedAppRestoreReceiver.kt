package com.aymankhattab.nateq.core.audio.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * مستقبل المنبه الاحتياطي لإعادة تفعيل إمكانية الوصول بعد إنستاباي
 * (الجولة 51 - الأمر 1، البند 7).
 */
class ProtectedAppRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ProtectedAppManager.ACTION_RESTORE_ALARM) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val restored = ProtectedAppManager.checkAndRestore(
                    context = context,
                    reason = "alarm"
                )
                if (!restored && ProtectedAppManager.isSuspended(context)) {
                    ProtectedAppManager.scheduleBackupAlarm(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
