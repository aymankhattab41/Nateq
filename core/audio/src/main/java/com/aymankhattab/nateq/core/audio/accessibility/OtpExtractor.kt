package com.aymankhattab.nateq.core.audio.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * ناسخ رموز التحقق (ع٥٦-أمر 9).
 * يستخرج رموز التحقق (4 إلى 8 أرقام) من الرسائل والإشعارات وينسخها بأمان.
 */
object OtpExtractor {

    private val KEYWORDS = listOf(
        "رمز", "كود", "تحقق", "التحقق", "تأكيد", "otp", "code",
        "verification", "one-time", "password", "pin"
    )

    private val DIGITS_REGEX = Regex("""(?<!\d)(\d{4,8})(?!\d)""")

    private val ARABIC_INDIC_DIGITS = mapOf(
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
        '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9'
    )

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun normalizeDigits(input: String): String {
        val sb = java.lang.StringBuilder(input.length)
        for (ch in input) {
            val mapped = ARABIC_INDIC_DIGITS[ch]
            if (mapped != null) {
                sb.append(mapped)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun hasOtpKeyword(text: String): Boolean {
        val lower = text.lowercase()
        return KEYWORDS.any { kw -> lower.contains(kw) }
    }

    fun extractOtp(text: String?): String? {
        if (text.isNullOrBlank()) return null
        if (!hasOtpKeyword(text)) return null

        val normalized = normalizeDigits(text)
        val matches = DIGITS_REGEX.findAll(normalized)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        return if (matches.size == 1) {
            matches[0]
        } else {
            null
        }
    }

    fun copyOtpToClipboard(
        context: Context,
        otp: String,
        clearDelaySec: Int = 120
    ) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager ?: return
        val clip = ClipData.newPlainText("OTP", otp)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
            clip.description.extras = extras
        }

        cm.setPrimaryClip(clip)

        val repo = SettingsRepository.create(context)
        val speakDigits = repo.isExtraEnabled("extra_otp_speak_code")
        val announceMsg = if (speakDigits) {
            val spacedDigits = otp.toCharArray().joinToString(" ")
            context.getString(R.string.otp_copied) + ": $spacedDigits"
        } else {
            context.getString(R.string.otp_copied)
        }
        A11ySpeechCoordinator.speakAnnouncement(context, announceMsg)

        val delayMs = (clearDelaySec.coerceIn(30, 300)) * 1000L
        mainHandler.postDelayed({
            try {
                val curr = cm.primaryClip
                if (curr != null && curr.itemCount > 0) {
                    val currText = curr.getItemAt(0)?.text?.toString()
                    if (currText == otp) {
                        if (Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.P
                        ) {
                            cm.clearPrimaryClip()
                        } else {
                            cm.setPrimaryClip(ClipData.newPlainText("", ""))
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }, delayMs)
    }
}
