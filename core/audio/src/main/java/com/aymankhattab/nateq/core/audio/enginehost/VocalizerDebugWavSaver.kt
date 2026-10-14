package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * حفظ عينات PCM لمحرك Vocalizer في ملف WAV تجريبي (ع٣٨-5 بند 3).
 * يُستخدم في وضع debug فقط للتحقق من سلامة الصوت خارج التطبيق.
 */
class VocalizerDebugWavSaver(
    val file: File,
    val sampleRate: Int,
    val channels: Int = 1
) {
    companion object {
        @Volatile
        var forceEnableForTesting: Boolean = false

        fun shouldEnable(context: Context): Boolean {
            if (forceEnableForTesting) return true
            val isDebuggable = (context.applicationInfo.flags and
                ApplicationInfo.FLAG_DEBUGGABLE) != 0
            return isDebuggable
        }
    }

    private var raf: RandomAccessFile? = null
    private var totalPcmBytes: Long = 0L

    init {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
            val r = RandomAccessFile(file, "rw")
            writeHeader(r, 0L)
            raf = r
        }
    }

    fun writeChunk(pcmBytes: ByteArray) {
        val r = raf ?: return
        if (pcmBytes.isEmpty()) return
        runCatching {
            r.write(pcmBytes)
            totalPcmBytes += pcmBytes.size
        }
    }

    fun close() {
        val r = raf ?: return
        runCatching {
            writeHeader(r, totalPcmBytes)
            r.close()
        }
        raf = null
    }

    private fun writeHeader(r: RandomAccessFile, pcmDataLength: Long) {
        r.seek(0)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + pcmDataLength).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        val byteRate = sampleRate * channels * 2
        header.putInt(byteRate)
        val blockAlign = (channels * 2).toShort()
        header.putShort(blockAlign)
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmDataLength.toInt())

        r.write(header.array())
    }
}
