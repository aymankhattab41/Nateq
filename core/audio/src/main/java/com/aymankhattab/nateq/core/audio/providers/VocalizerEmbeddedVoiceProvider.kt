package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.os.Environment
import android.util.Log
import com.nuance.android.vocalizer.VocalizerAudioOutputListener
import com.nuance.android.vocalizer.VocalizerAudioSettings
import com.nuance.android.vocalizer.VocalizerEngine
import com.nuance.android.vocalizer.VocalizerVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * مزود صوت محلي مدمج لمحرك Nuance Vocalizer Expressive 2 (Embedded Engine).
 * يقوم بتخليق الصوت مباشرة من ملفات .dat دون الحاجة لتطبيق وسيط خارجي.
 */
class VocalizerEmbeddedVoiceProvider(
    private val context: Context,
    private val customVoiceDir: File? = null
) : VoiceProvider {

    companion object {
        private const val TAG = "VocalizerEmbedded"
        const val PROVIDER_ID = "vocalizer_embedded"

        val DEFAULT_SEARCH_PATHS = listOf(
            File("/sdcard/VocalizerEx2"),
            File("/storage/emulated/0/VocalizerEx2"),
            File(
                Environment.getExternalStorageDirectory(),
                "VocalizerEx2"
            )
        )
    }

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Vocalizer Expressive 2 (مدمج)"

    private var engine: VocalizerEngine? = null
    private var isInitialized = false
    private var activeDataPath: String? = null

    init {
        initEngine()
    }

    @Synchronized
    private fun initEngine(): Boolean {
        if (isInitialized && engine != null) return true

        val searchDirs = mutableListOf<File>()
        customVoiceDir?.let { searchDirs.add(it) }
        searchDirs.addAll(DEFAULT_SEARCH_PATHS)
        context.getExternalFilesDir(null)?.let {
            searchDirs.add(File(it, "VocalizerEx2"))
        }

        var selectedDir: File? = null
        for (dir in searchDirs) {
            if (dir.exists() && dir.isDirectory) {
                val datFiles = dir.listFiles { f ->
                    f.extension == "dat" || f.extension == "hdr"
                }
                if (!datFiles.isNullOrEmpty()) {
                    selectedDir = dir
                    break
                }
            }
        }

        val targetPath = selectedDir?.absolutePath
            ?: DEFAULT_SEARCH_PATHS[0].absolutePath
        activeDataPath = targetPath

        return try {
            val eng = VocalizerEngine(context)
            val success = eng.initialize(targetPath)
            if (success) {
                engine = eng
                isInitialized = true
                Log.i(TAG, "Vocalizer embedded engine ready at: $targetPath")
            }
            success
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize embedded Vocalizer: ${t.message}")
            false
        }
    }

    override fun isConfigured(): Boolean {
        return isInitialized && engine != null
    }

    override suspend fun listVoices(locale: Locale): List<VoiceDescriptor> {
        return withContext(Dispatchers.IO) {
            if (!initEngine()) return@withContext emptyList()
            val voices = engine?.voiceList ?: return@withContext emptyList()
            val list = mutableListOf<VoiceDescriptor>()

            for (v in voices) {
                val lang = v.language ?: ""
                val matchesLang = locale.language.isEmpty() ||
                    lang.startsWith(locale.language, ignoreCase = true)
                if (matchesLang) {
                    list.add(
                        VoiceDescriptor(
                            id = "vocalizer_${v.voiceName.lowercase()}",
                            providerId = PROVIDER_ID,
                            displayName = v.voiceName,
                            locale = Locale.forLanguageTag(lang)
                        )
                    )
                }
            }
            list
        }
    }

    override suspend fun synthesize(
        text: String,
        voice: VoiceDescriptor,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onFormatInfo: (sampleRateInHz: Int, channelCount: Int) -> Unit,
        onAudioChunk: (ByteArray, Int) -> Unit,
        enginePackage: String?,
        voiceLocale: Locale?,
        desiredVoiceName: String?
    ) {
        withContext(Dispatchers.IO) {
            if (!initEngine()) {
                Log.e(TAG, "Cannot synthesize: Engine not initialized")
                return@withContext
            }
            val eng = engine ?: return@withContext

            val targetVoiceName = desiredVoiceName ?: voice.displayName
            val cleanTarget = targetVoiceName.removePrefix("vocalizer_")
            val voices = eng.voiceList
            val matchedVoice = voices?.firstOrNull {
                it.voiceName.equals(targetVoiceName, ignoreCase = true) ||
                    it.voiceName.equals(cleanTarget, ignoreCase = true)
            } ?: voices?.firstOrNull()

            if (matchedVoice != null) {
                eng.loadVoice(matchedVoice)
            }

            val rateInt = (speechRate * 100).toInt().coerceIn(20, 400)
            val pitchInt = (pitch * 100).toInt().coerceIn(50, 200)
            val volumeInt = (volume * 100).toInt().coerceIn(0, 100)

            eng.setSpeechRate(rateInt)
            eng.setSpeechPitch(pitchInt)
            eng.setSpeechVolume(volumeInt)

            val sampleRate = matchedVoice?.sampleRate ?: 22050
            onFormatInfo(sampleRate, 1)

            eng.setAudioOutputListener(
                object : VocalizerAudioOutputListener {
                    override fun onAudioData(
                        settings: VocalizerAudioSettings?,
                        audioData: ShortArray?
                    ) {
                        if (audioData == null || audioData.isEmpty()) return
                        val byteBuffer = ByteBuffer.allocate(audioData.size * 2)
                        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        for (s in audioData) {
                            byteBuffer.putShort(s)
                        }
                        val byteArray = byteBuffer.array()
                        onAudioChunk(byteArray, byteArray.size)
                    }
                },
                true
            )

            try {
                eng.speak(text)
            } finally {
                eng.stop()
            }
        }
    }

    override fun shutdown() {
        try {
            engine?.release()
            engine = null
            isInitialized = false
        } catch (t: Throwable) {
            Log.e(TAG, "Error closing Vocalizer engine: ${t.message}")
        }
    }
}
