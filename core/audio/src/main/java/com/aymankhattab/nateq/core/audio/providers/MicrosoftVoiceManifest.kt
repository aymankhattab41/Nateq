package com.aymankhattab.nateq.core.audio.providers

import java.io.File

/**
 * فحص وتحديد مجلدات أصوات مايكروسوفت المكتملة على القرص.
 */
object MicrosoftVoiceManifest {

    private const val DECODER_NAME = "am_v5_decoder.bin"
    private const val MIN_COMPLETE_BYTES = 5L * 1024L * 1024L

    fun isCompleteVoiceDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val decoder = File(dir, DECODER_NAME)
        if (!decoder.exists() || !decoder.isFile || decoder.length() <= 0L) {
            return false
        }
        val totalBytes = dir.listFiles()?.sumOf { it.length() } ?: 0L
        return totalBytes >= MIN_COMPLETE_BYTES
    }

    fun completeVoiceDirs(baseDir: File): List<File> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        val result = mutableListOf<File>()
        if (isCompleteVoiceDir(baseDir)) {
            result.add(baseDir)
        }
        val children = baseDir.listFiles() ?: return result
        for (child in children) {
            if (child.isDirectory && isCompleteVoiceDir(child)) {
                result.add(child)
            }
        }
        return result
    }

    fun hasCompleteVoice(baseDir: File): Boolean =
        completeVoiceDirs(baseDir).isNotEmpty()
}
