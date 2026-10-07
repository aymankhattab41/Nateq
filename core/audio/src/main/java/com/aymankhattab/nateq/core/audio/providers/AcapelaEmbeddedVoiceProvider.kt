package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.os.Environment
import android.speech.tts.SynthesisCallback
import android.util.Log
import com.acapelagroup.android.tts.AcattsandroidService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * مزود صوت محلي مدمج لمحرك Acapela TTS (Embedded Engine).
 * يقوم بتخليق الصوت مباشرة من ملفات الأصوات دون الحاجة لتطبيق وسيط خارجي.
 */
class AcapelaEmbeddedVoiceProvider(
    private val context: Context,
    private val customVoiceDir: File? = null
) : VoiceProvider {

    companion object {
        private const val TAG = "AcapelaEmbedded"
        const val PROVIDER_ID = "acapela_embedded"

        val DEFAULT_SEARCH_PATHS = listOf(
            File("/sdcard/AcapelaTTS"),
            File("/sdcard/AcapelaGroup"),
            File("/storage/emulated/0/AcapelaTTS"),
            File("/storage/emulated/0/AcapelaGroup"),
            File(
                Environment.getExternalStorageDirectory(),
                "AcapelaTTS"
            ),
            File(
                Environment.getExternalStorageDirectory(),
                "AcapelaGroup"
            )
        )
    }

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Acapela TTS (مدمج)"

    private var service: AcattsandroidService? = null
    private var isInitialized = false
    private var activeDataPath: String? = null

    init {
        initEngine()
    }

    @Synchronized
    private fun initEngine(): Boolean {
        if (isInitialized && service != null) return true

        val searchDirs = mutableListOf<File>()
        customVoiceDir?.let { searchDirs.add(it) }
        searchDirs.addAll(DEFAULT_SEARCH_PATHS)
        context.getExternalFilesDir(null)?.let {
            searchDirs.add(File(it, "AcapelaTTS"))
            searchDirs.add(File(it, "AcapelaGroup"))
        }

        var selectedDir: File? = null
        for (dir in searchDirs) {
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles { f ->
                    f.extension == "dat" ||
                        f.extension == "bvz" ||
                        f.extension == "bab" ||
                        f.name.contains("22k", ignoreCase = true)
                }
                if (!files.isNullOrEmpty()) {
                    selectedDir = dir
                    break
                }
            }
        }

        val targetPath = selectedDir?.absolutePath
            ?: DEFAULT_SEARCH_PATHS[0].absolutePath
        activeDataPath = targetPath

        return try {
            val s = AcattsandroidService()
            val res = s.nInit(targetPath)
            if (res >= 0) {
                service = s
                isInitialized = true
                Log.i(TAG, "Acapela embedded engine ready at: $targetPath")
            }
            isInitialized
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize embedded Acapela: ${t.message}")
            false
        }
    }

    override fun isConfigured(): Boolean {
        return isInitialized && service != null
    }

    override suspend fun listVoices(locale: Locale): List<VoiceDescriptor> {
        return withContext(Dispatchers.IO) {
            if (!initEngine()) return@withContext emptyList()
            val list = mutableListOf<VoiceDescriptor>()

            val searchDirs = mutableListOf<File>()
            customVoiceDir?.let { searchDirs.add(it) }
            activeDataPath?.let { searchDirs.add(File(it)) }
            searchDirs.addAll(DEFAULT_SEARCH_PATHS)

            val seenVoices = mutableSetOf<String>()
            for (dir in searchDirs) {
                if (dir.exists() && dir.isDirectory) {
                    val files = dir.listFiles() ?: continue
                    for (f in files) {
                        val name = f.nameWithoutExtension
                        val lower = name.lowercase()
                        val voiceName = when {
                            lower.contains("salma") -> "Salma"
                            lower.contains("mehdi") -> "Mehdi"
                            lower.contains("leila") ||
                                lower.contains("layla") -> "Leila"
                            lower.contains("nizar") -> "Nizar"
                            lower.contains("will") -> "Will"
                            lower.contains("sharon") -> "Sharon"
                            lower.contains("ryan") -> "Ryan"
                            lower.contains("tracy") -> "Tracy"
                            lower.contains("peter") -> "Peter"
                            lower.contains("rachel") -> "Rachel"
                            else -> if (f.extension in listOf("dat", "bvz")) {
                                name.replace("22k", "")
                                    .replace("embedded", "")
                                    .replace("_", "")
                                    .replaceFirstChar {
                                        if (it.isLowerCase()) {
                                            it.titlecase(Locale.ROOT)
                                        } else {
                                            it.toString()
                                        }
                                    }
                            } else null
                        }

                        if (voiceName != null && seenVoices.add(voiceName)) {
                            val isArabic = voiceName in listOf(
                                "Salma", "Mehdi", "Leila", "Nizar"
                            ) || lower.startsWith("ara")
                            val lang = if (isArabic) "ar" else "en"
                            val matches = locale.language.isEmpty() ||
                                lang.startsWith(
                                    locale.language,
                                    ignoreCase = true
                                )
                            if (matches) {
                                list.add(
                                    VoiceDescriptor(
                                        id = "acapela_${voiceName.lowercase()}",
                                        providerId = PROVIDER_ID,
                                        displayName = voiceName,
                                        locale = Locale.forLanguageTag(lang)
                                    )
                                )
                            }
                        }
                    }
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
            val s = service ?: return@withContext

            val targetVoiceName = desiredVoiceName ?: voice.displayName
            val cleanTarget = targetVoiceName.removePrefix("acapela_")

            s.nSetCurrentVoice(cleanTarget)

            val rateInt = (speechRate * 100).toInt().coerceIn(20, 400)
            val pitchInt = (pitch * 100).toInt().coerceIn(50, 200)
            val volumeInt = (volume * 100).toInt().coerceIn(0, 100)

            s.nSetProperty("Rate", rateInt.toString())
            s.nSetProperty("Pitch", pitchInt.toString())
            s.nSetProperty("Volume", volumeInt.toString())

            var started = false
            val wrapperCallback = object : SynthesisCallback {
                override fun getMaxBufferSize(): Int = 8192

                override fun start(
                    sampleRateInHz: Int,
                    audioFormat: Int,
                    channelCount: Int
                ): Int {
                    started = true
                    onFormatInfo(sampleRateInHz, channelCount)
                    return 0
                }

                override fun audioAvailable(
                    buffer: ByteArray?,
                    offset: Int,
                    length: Int
                ): Int {
                    if (buffer != null && length > 0) {
                        if (!started) {
                            started = true
                            onFormatInfo(22050, 1)
                        }
                        val chunk = if (offset == 0 && length == buffer.size) {
                            buffer
                        } else {
                            buffer.copyOfRange(offset, offset + length)
                        }
                        onAudioChunk(chunk, chunk.size)
                    }
                    return 0
                }

                override fun done(): Int = 0

                override fun error() {}

                override fun error(errorCode: Int) {}

                override fun hasStarted(): Boolean = started

                override fun hasFinished(): Boolean = false
            }

            try {
                s.nSpeak(text, wrapperCallback)
            } finally {
                s.nStop()
            }
        }
    }

    override fun shutdown() {
        try {
            service?.nShutdown()
            service = null
            isInitialized = false
        } catch (t: Throwable) {
            Log.e(TAG, "Error closing Acapela engine: ${t.message}")
        }
    }
}
