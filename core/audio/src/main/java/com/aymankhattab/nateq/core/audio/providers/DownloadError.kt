package com.aymankhattab.nateq.core.audio.providers

import java.io.IOException

/**
 * خطأ تنزيل موحد للأصوات المدمجة يحدد رمز الخطأ ورسالته (ع٣٠-6).
 */
class DownloadError(
    val code: String,
    val details: String = ""
) : IOException(
    if (details.isBlank()) "[$code]" else "[$code] $details"
) {

    companion object {
        const val TIMEOUT = "TIMEOUT"
        const val NO_SPACE = "NO_SPACE"
        const val REDIRECT_BLOCKED = "REDIRECT_BLOCKED"
        const val NOT_A_ZIP = "NOT_A_ZIP"
        const val EMPTY_ARCHIVE = "EMPTY_ARCHIVE"
        const val HEADER_MISSING = "HEADER_MISSING"
        const val NETWORK = "NETWORK"
        const val MODEL_TOO_SMALL = "MODEL_TOO_SMALL"
        const val TOKENS_EMPTY = "TOKENS_EMPTY"

        fun http(code: Int): String = "HTTP_$code"
    }
}
