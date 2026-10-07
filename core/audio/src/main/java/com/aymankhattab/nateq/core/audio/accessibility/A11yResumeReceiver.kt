package com.aymankhattab.nateq.core.audio.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * مستقبل استئناف خدمات الشاشة عند انتهاء المدة أو بعد إعادة التشغيل (ع٢٧-14).
 */
class A11yResumeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        val store = A11yStateStore.getInstance(context) ?: return

        when (action) {
            A11yPauseScheduler.ACTION_RESUME_A11Y -> {
                Log.i(TAG, "[A11yResume] Auto-resuming screen services")
                store.setPaused(paused = false, untilEpoch = 0L)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                val isPaused = store.isPaused()
                val until = store.getPausedUntilEpoch()
                val now = System.currentTimeMillis()

                if (isPaused && until > 0L) {
                    if (now >= until) {
                        Log.i(TAG, "[A11yResume] Expired pause at boot")
                        store.setPaused(paused = false, untilEpoch = 0L)
                    } else {
                        Log.i(TAG, "[A11yResume] Reschedule resume at boot")
                        A11yPauseScheduler.scheduleResumeAlarm(context, until)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "A11yResumeReceiver"
    }
}
