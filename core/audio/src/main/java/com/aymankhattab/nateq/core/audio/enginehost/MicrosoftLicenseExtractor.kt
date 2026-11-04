package com.aymankhattab.nateq.core.audio.enginehost

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * مستخرج ترخيص نموذج مايكروسوفت المدمج من ملف am_v5_decoder.bin.
 * النص قبل علامة ffffffff هو مفتاح الترخيص الصريح المطلوب للتهيئة.
 */
object MicrosoftLicenseExtractor {

    private val MAGIC = "ffffffff".toByteArray(StandardCharsets.US_ASCII)
    private const val MAX_HEADER_BYTES = 4096

    fun extractLicense(voiceDir: File): String {
        val isDecoder = voiceDir.isFile &&
            voiceDir.name == "am_v5_decoder.bin"
        val decoder = if (isDecoder) {
            voiceDir
        } else {
            File(voiceDir, "am_v5_decoder.bin")
        }
        if (!decoder.exists() || !decoder.isFile) return ""
        return extractFromFile(decoder)
    }

    private fun extractFromFile(file: File): String {
        return runCatching {
            file.inputStream().use { stream ->
                val buffer = ByteArray(MAX_HEADER_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                findLicenseInBuffer(buffer, total)
            }
        }.getOrDefault("")
    }

    private fun findLicenseInBuffer(buffer: ByteArray, length: Int): String {
        val limit = length - MAGIC.size
        for (i in 0..limit) {
            var match = true
            for (j in MAGIC.indices) {
                if (buffer[i + j] != MAGIC[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                return String(
                    buffer,
                    0,
                    i,
                    StandardCharsets.UTF_8
                ).trim()
            }
        }
        return ""
    }
}
