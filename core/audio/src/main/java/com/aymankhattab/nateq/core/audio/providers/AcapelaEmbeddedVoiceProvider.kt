package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.util.Log
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * معلومات صوت Acapela مثبت محلياً.
 */
data class AcapelaVoiceItem(
    val id: String,
    val voiceName: String,
    val displayName: String,
    val locale: Locale,
    val voiceDir: File,
    val sizeMb: Double
)

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

        /**
         * المسار الأساسي لحفظ واستيراد أصوات Acapela — داخل filesDir
         * دائماً (ع٢٩-3): بلا `/sdcard` وبلا `Environment`.
         */
        fun getAcapelaBaseDir(context: Context): File {
            val base = EmbeddedStorage.baseDir(
                context,
                EmbeddedStorage.Engine.ACAPELA
            )
            if (!base.exists()) base.mkdirs()
            return base
        }

        /** كل المجلدات التي يقرأ منها المزوّد (المصدر الموحّد). */
        fun getSearchDirectories(
            context: Context,
            customVoiceDir: File? = null
        ): List<File> {
            val extra = listOfNotNull(customVoiceDir)
            return EmbeddedStorage.allSearchDirs(
                context,
                EmbeddedStorage.Engine.ACAPELA,
                extra
            ).filter { it.exists() && it.isDirectory }
        }

        /** الحصول على قائمة كل أصوات Acapela المثبتة في الجهاز */
        fun getInstalledVoices(context: Context): List<AcapelaVoiceItem> {
            val results = mutableListOf<AcapelaVoiceItem>()
            val seen = mutableSetOf<String>()
            val searchDirs = getSearchDirectories(context)

            for (dir in searchDirs) {
                scanDirForVoices(dir, results, seen)
            }
            return results
        }

        /** حذف مجلد صوت Acapela */
        fun deleteVoice(item: AcapelaVoiceItem): Boolean {
            return try {
                if (item.voiceDir.exists()) {
                    val removed = item.voiceDir.deleteRecursively()
                    if (removed) {
                        EmbeddedStorage.bumpVersion()
                        EnginePicker.invalidateAfterStorageChange()
                    }
                    removed
                } else {
                    false
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to delete Acapela voice: ${t.message}")
                false
            }
        }

        private fun scanDirForVoices(
            rootDir: File,
            results: MutableList<AcapelaVoiceItem>,
            seen: MutableSet<String>,
            depth: Int = 0
        ) {
            if (depth > 4 || !rootDir.exists() || !rootDir.isDirectory) return
            val files = rootDir.listFiles() ?: return

            // فحص ملفات .inf في هذا المجلد
            for (f in files) {
                if (f.isFile && f.name.endsWith(".inf", ignoreCase = true)) {
                    val info = parseInfFile(f)
                    if (info != null && seen.add(info.id)) {
                        results.add(info)
                    }
                }
            }

            // فحص المجلدات الفرعية
            for (f in files) {
                if (f.isDirectory) {
                    val lower = f.name.lowercase()
                    if (lower.contains("22k") || lower.contains("_hq") ||
                        lower.contains("_lf") || lower.contains("salma") ||
                        lower.contains("nizar") || lower.contains("mehdi") ||
                        lower.contains("leila")
                    ) {
                        // مجلد صوت مباشر
                        val infFile = f.listFiles { sub ->
                            sub.extension.equals("inf", ignoreCase = true)
                        }?.firstOrNull()

                        if (infFile != null) {
                            val info = parseInfFile(infFile)
                            if (info != null && seen.add(info.id)) {
                                results.add(info)
                            }
                        } else {
                            val voiceName = deduceVoiceName(f.name)
                            val isAr = voiceName in listOf(
                                "Nizar", "Salma", "Mehdi", "Leila"
                            )
                            val langTag = if (isAr) "ar-SA" else "en-US"
                            val sizeMb = computeDirSizeMb(f)
                            val id = "acapela_${voiceName.lowercase()}"
                            if (seen.add(id)) {
                                results.add(
                                    AcapelaVoiceItem(
                                        id = id,
                                        voiceName = voiceName,
                                        displayName = "Acapela $voiceName",
                                        locale = Locale.forLanguageTag(langTag),
                                        voiceDir = f,
                                        sizeMb = sizeMb
                                    )
                                )
                            }
                        }
                    } else {
                        scanDirForVoices(f, results, seen, depth + 1)
                    }
                }
            }
        }

        private fun parseInfFile(infFile: File): AcapelaVoiceItem? {
            return try {
                val lines = infFile.readLines()
                val map = mutableMapOf<String, String>()
                for (line in lines) {
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim().lowercase()
                        val value = parts[1].trim().trim('"', '\'')
                        map[key] = value
                    }
                }

                val speaker = map["speaker"]
                    ?: map["name"]?.removeSuffix(".bvcu")
                    ?: deduceVoiceName(infFile.parentFile?.name ?: "")
                if (speaker.isEmpty()) return null

                val iso = map["isocode"] ?: if (
                    speaker.lowercase() in listOf(
                        "nizar", "salma", "mehdi", "leila"
                    )
                ) "ar-SA" else "en-US"

                val name = map["name"] ?: speaker
                val parentDir = infFile.parentFile ?: infFile
                val sizeMb = computeDirSizeMb(parentDir)

                AcapelaVoiceItem(
                    id = "acapela_${speaker.lowercase()}",
                    voiceName = speaker,
                    displayName = "Acapela $speaker",
                    locale = Locale.forLanguageTag(iso),
                    voiceDir = parentDir,
                    sizeMb = sizeMb
                )
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "Failed parsing inf file ${infFile.name}: ${t.message}"
                )
                null
            }
        }

        private fun deduceVoiceName(raw: String): String {
            val lower = raw.lowercase()
            return when {
                lower.contains("nizar") -> "Nizar"
                lower.contains("salma") -> "Salma"
                lower.contains("mehdi") -> "Mehdi"
                lower.contains("leila") || lower.contains("layla") -> "Leila"
                lower.contains("will") -> "Will"
                lower.contains("sharon") -> "Sharon"
                lower.contains("ryan") -> "Ryan"
                lower.contains("tracy") -> "Tracy"
                lower.contains("peter") -> "Peter"
                lower.contains("rachel") -> "Rachel"
                else -> raw.replace("22k", "")
                    .replace("embedded", "")
                    .replace("_hq", "")
                    .replace("_lf", "")
                    .replace("_", "")
                    .replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.ROOT)
                        else it.toString()
                    }
            }
        }

        private fun computeDirSizeMb(dir: File): Double {
            return try {
                var bytes = 0L
                dir.walkTopDown().forEach {
                    if (it.isFile) bytes += it.length()
                }
                String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
                    .toDouble()
            } catch (t: Throwable) {
                0.0
            }
        }
    }

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Acapela TTS (مدمج)"

    /** مسار تهيئة مُتحقق منه يُعاد استخدامه (بلا مسح قرصي متكرر). */
    @Volatile
    private var cachedInitPath: String? = null

    /**
     * ع٢٩-5: تهيئة المحرّك **في المضيف** (`:engine_host`). الفحص
     * القرصي يجري مرة واحدة هنا، ثم init يُستدعى عند كل نطق حتى
     * يعود المضيف المُعاد تشغيله (session جديدة) إلى جاهزيته.
     */
    private suspend fun ensureInitialized(): Boolean {
        if (cachedInitPath == null) {
            val searchDirs = getSearchDirectories(context, customVoiceDir)
            var selectedDir: File? = null
            for (baseDir in searchDirs) {
                val candidate = findBestInitDir(baseDir)
                if (candidate != null) {
                    selectedDir = candidate
                    break
                }
            }
            // ع٢٩-2 (6): لا نُنشئ محركاً أصلياً بلا مجلد بيانات صالح؛
            // تمرير مسار غير موجود للمكتبة الأصلي سلوكٌ غير معرّف.
            if (selectedDir == null) {
                Log.w(
                    TAG,
                    "No Acapela data dir (voicelist/voices) found — " +
                        "engine will not be created"
                )
                return false
            }
            cachedInitPath = selectedDir.absolutePath
        }
        val result = EngineHostClient.init(
            context,
            HostProtocol.ENGINE_ACAPELA,
            cachedInitPath.orEmpty()
        )
        if (!result.ok) {
            Log.e(
                TAG,
                "تهيئة المضيف فشلت [${result.code}]: ${result.message}"
            )
            return false
        }
        // ع٢٩-9: نجاح التهيئة يُبطل كاش المحركات والاكتشاف.
        EnginePicker.invalidateAfterStorageChange()
        return true
    }

    /**
     * ع٢٩-2 (3): فحص خفيف لا يلمس المكتبة الأصلي — يعود true
     * إذا وُجد ملف voicelist أو مجلد صوت في أحد المجلدات.
     */
    override fun isConfigured(): Boolean {
        for (dir in getSearchDirectories(context, customVoiceDir)) {
            if (findBestInitDir(dir) != null) return true
        }
        return false
    }

    private fun findBestInitDir(dir: File, depth: Int = 0): File? {
        if (depth > 3 || !dir.exists() || !dir.isDirectory) return null

        val files = dir.listFiles() ?: return null
        val hasVoicelist = files.any {
            it.name.equals("voicelist", ignoreCase = true)
        }
        val hasLicense = files.any { it.name.startsWith("0x00") }
        val hasVoiceSubdir = files.any {
            it.isDirectory && (
                it.name.contains("22k") ||
                    it.name.contains("salma") ||
                    it.name.contains("nizar") ||
                    it.name.contains("mehdi") ||
                    it.name.contains("leila")
            )
        }

        if (hasVoicelist || hasLicense || hasVoiceSubdir) {
            return dir
        }

        for (f in files) {
            if (f.isDirectory) {
                val found = findBestInitDir(f, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    override suspend fun listVoices(locale: Locale): List<VoiceDescriptor> {
        return withContext(Dispatchers.IO) {
            val installed = getInstalledVoices(context)
            val list = mutableListOf<VoiceDescriptor>()

            for (item in installed) {
                val matches = locale.language.isEmpty() ||
                    item.locale.language.equals(
                        locale.language,
                        ignoreCase = true
                    )
                if (matches) {
                    list.add(
                        VoiceDescriptor(
                            id = item.id,
                            providerId = PROVIDER_ID,
                            displayName = item.voiceName,
                            locale = item.locale
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
            if (!ensureInitialized()) {
                Log.e(TAG, "Cannot synthesize: host not initialized")
                return@withContext
            }
            val targetVoiceName = desiredVoiceName ?: voice.displayName
            val result = EngineHostClient.synthesize(
                context = context,
                engineId = HostProtocol.ENGINE_ACAPELA,
                voiceId = targetVoiceName,
                text = text,
                rate = speechRate,
                pitch = pitch,
                volume = volume,
                onFormatInfo = onFormatInfo,
                onAudioChunk = onAudioChunk
            )
            if (!result.ok) {
                Log.e(
                    TAG,
                    "نطق المضيف فشل [${result.code}]: ${result.message}"
                )
            }
        }
    }

    override fun shutdown() {
        cachedInitPath = null
        EngineHostClient.release(
            context,
            HostProtocol.ENGINE_ACAPELA
        )
    }
}

