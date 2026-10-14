package com.aymankhattab.nateq.core.audio.accessibility

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager

/**
 * بوّابة تنفيذ أوامر المكالمة (ع٤١-ب8) — تُجرَّد ليتسنّى اختبارها.
 */
interface CallGateway {
    /** ردّ على المكالمة الرنّانة. */
    fun acceptRingingCall(): Boolean

    /** إنهاء/رفض المكالمة. */
    fun endCall(): Boolean
}

/**
 * تنفيذٌ حقيقي عبر [TelecomManager] (ع٤١-ب8.2).
 * - الردّ: `acceptRingingCall()` على API 26+.
 * - الرفض: `endCall()` على API 28+.
 * كلاهما يتطلّب `ANSWER_PHONE_CALLS`؛ وإن غاب لا يُنفَّذ شيء.
 */
class AndroidCallGateway(
    private val context: Context
) : CallGateway {

    // acceptRingingCall() مهجورة على API 34+ (بديلها CallControl المتاح
    // لخدمات InCallService فقط)، ولا بديل مباشر لتطبيق إتاحة، فنُبقيها.
    @Suppress("DEPRECATION")
    override fun acceptRingingCall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!hasAnswerPermission(context)) return false
        return runCatching {
            telecom()?.acceptRingingCall()
            true
        }.getOrDefault(false)
    }

    // endCall() مهجورة على API 34+ لنفس السبب؛ لا بديل مباشر.
    @Suppress("DEPRECATION")
    override fun endCall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (!hasAnswerPermission(context)) return false
        return runCatching {
            telecom()?.endCall()
            true
        }.getOrDefault(false)
    }

    private fun telecom(): TelecomManager? =
        context.getSystemService(TelecomManager::class.java)

    companion object {
        /** هل يمتلك التطبيق إذن إجابة المكالمات؟ (ع٤١-ب8.2) */
        fun hasAnswerPermission(context: Context): Boolean =
            context.checkSelfPermission(
                Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * تنفيذ فعل مكالمةٍ عبر بوّابة (نقي + قابل للاختبار — ع٤١-ب8.6).
 */
object CallActionInvoker {

    /**
     * ينفّذ [action] عبر [gateway].
     * @return هل نجح التنفيذ؟ (false إن كانت البوّابة غير متاحة أو رفضت)
     */
    fun invoke(action: CallAction, gateway: CallGateway): Boolean =
        when (action) {
            CallAction.ANSWER -> gateway.acceptRingingCall()
            CallAction.REJECT -> gateway.endCall()
        }

    /** نصّ التأكيد القصير المنطوق (ع٤١-ب8.3). */
    fun confirmationText(action: CallAction): String =
        when (action) {
            CallAction.ANSWER -> "تم الرد"
            CallAction.REJECT -> "تم الرفض"
        }
}

/**
 * قراءة حالة رنين المكالمة (ع٤١-ب8.1).
 * تتطلّب `READ_PHONE_STATE`؛ وإن غاب تُعيد false بأمان.
 */
object CallStateReader {

    // callState مهجورة على API 31+، والحقلُ المرصود عبر TelephonyCallback
    // يتطلّب تسجيلاً دائماً؛ قراءةٌ عند الضغط أخفّ وأدقّ لحاجتنا اللحظية.
    @Suppress("DEPRECATION")
    fun isRinging(context: Context): Boolean {
        if (context.checkSelfPermission(
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val telephony = context.getSystemService(
            TelephonyManager::class.java
        ) ?: return false
        return runCatching {
            telephony.callState == TelephonyManager.CALL_STATE_RINGING
        }.getOrDefault(false)
    }
}
