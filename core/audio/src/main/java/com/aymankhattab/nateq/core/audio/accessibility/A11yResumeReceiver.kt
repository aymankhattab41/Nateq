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

        // هذا المستقبل غير مُصدَّر: يستقبل فقط الإجراء الداخلي الذي يرسله
        // التطبيق عبر PendingIntent صريح. بثّ الإقلاع صار في مستقبل منفصل
        // (A11yBootReceiver) لأن البثّ الضمني للنظام لا يصل لغير المُصدَّر
        // على Android 14+، ولا يجوز تعريض إجراء الاستئناف لأي تطبيق.
        if (action != A11yPauseScheduler.ACTION_RESUME_A11Y) return

        val isPaused = store.isPaused()
        val untilEpoch = store.getPausedUntilEpoch()
        val currentGen = store.getPauseGeneration()

        // التحقق من الحصانة ضد التنبيهات القديمة:
        // 1. إذا كانت الخدمة تعمل بالفعل فلا داعي للاستئناف
        if (!isPaused) {
            Log.i(TAG, "[A11yResume] Ignored: service already running")
            return
        }

        // 2. إذا كانت الخدمة في وضع الإيقاف اليدوي (untilEpoch <= 0) فلا يجوز
        // الاستئناف التلقائي إطلاقاً
        if (untilEpoch <= 0L) {
            Log.i(TAG, "[A11yResume] Ignored: service in manual pause mode")
            return
        }

        // 3. التحقق من تطابق الجيل أو الموعد إن وُجد في الـ Intent
        val intentGen = intent.getLongExtra(
            A11yPauseScheduler.EXTRA_PAUSE_GENERATION, -1L
        )
        val intentEpoch = intent.getLongExtra(
            A11yPauseScheduler.EXTRA_RESUME_EPOCH, -1L
        )
        if (intentGen != -1L && intentGen != currentGen) {
            Log.i(
                TAG,
                "[A11yResume] Ignored: stale gen $intentGen vs $currentGen"
            )
            return
        }
        if (intentEpoch != -1L && intentEpoch < untilEpoch) {
            Log.i(
                TAG,
                "[A11yResume] Ignored: stale epoch $intentEpoch < $untilEpoch"
            )
            return
        }

        Log.i(TAG, "[A11yResume] Auto-resuming screen services")
        store.setPaused(paused = false, untilEpoch = 0L)
    }

    companion object {
        private const val TAG = "A11yResumeReceiver"

        /** منطق الإقلاع مشترك مع [A11yBootReceiver] المُصدَّر والمحمي بإذن. */
        fun handleBootCompleted(context: Context) {
            val store = A11yStateStore.getInstance(context) ?: return
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
