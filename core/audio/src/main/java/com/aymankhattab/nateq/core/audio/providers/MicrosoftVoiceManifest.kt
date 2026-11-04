package com.aymankhattab.nateq.core.audio.providers

import java.io.File

/**
 * فحص وتحديد مجلدات أصوات مايكروسوفت المكتملة على القرص.
 */
object MicrosoftVoiceManifest {

    private const val DECODER_NAME = "am_v5_decoder.bin"

    fun isCompleteVoiceDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val decoder = File(dir, DECODER_NAME)
        return decoder.exists() && decoder.isFile && decoder.length() > 0L
    }

    fun completeVoiceDirs(baseDir: File): List<File> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        val result = mutableListOf<File>()
        if (isCompleteVoiceDir(baseDir)) {
            result.add(baseDir)
        }
        val children = baseDir.listFiles() ?: return result
        for (child in children) {
            if (child.isDirectory) {
                if (isCompleteVoiceDir(child)) {
                    result.add(child)
                } else {
                    val subChildren = child.listFiles() ?: continue
                    for (sub in subChildren) {
                        if (sub.isDirectory && isCompleteVoiceDir(sub)) {
                            result.add(sub)
                        }
                    }
                }
            }
        }
        return result.distinctBy { it.absolutePath }
    }

    fun hasCompleteVoice(baseDir: File): Boolean =
        completeVoiceDirs(baseDir).isNotEmpty()
}
