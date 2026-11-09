package com.aymankhattab.nateq.core.audio.providers

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * فحص وتعداد أصوات Acapela على القرص
 * دون الحاجة لمكتبات أصلية.
 */
object AcapelaVoiceManifest {

    data class VoiceInfo(
        val id: String,
        val voiceName: String,
        val displayName: String,
        val locale: Locale,
        val sampleRate: Int = 22050,
        val dir: File
    )

    private val VOICE_EXTENSIONS = setOf(
        "ini", "inf", "bvcu", "dat", "hdr", "bnf", "pho", "dic", "mdd"
    )

    /**
     * هل يحوي المجلد صوتاً مكتملاً لـ Acapela؟
     * يتطلب وجود ملف تهيئة (.ini أو .inf)
     * أو ملف بيانات واحد على الأقل.
     */
    fun isCompleteVoiceDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val files = dir.listFiles() ?: return false
        val hasConfig = files.any { f ->
            f.isFile && (f.extension.equals("ini", ignoreCase = true) ||
                f.extension.equals("inf", ignoreCase = true))
        }
        val hasData = files.any { f ->
            f.isFile && f.length() > 5_000L &&
                f.extension.lowercase() in setOf(
                    "bvcu", "dat", "bnf", "pho", "mdd", "hdr"
                )
        }
        return hasConfig || hasData
    }

    /**
     * قائمة المجلدات التي تحوي أصوات Acapela
     * تحت المجلد الأساسي.
     */
    fun completeVoiceDirs(baseDir: File): List<File> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        val result = mutableSetOf<File>()

        if (isCompleteVoiceDir(baseDir)) {
            result.add(baseDir)
        }

        baseDir.walkTopDown().maxDepth(6).forEach { f ->
            if (f.isDirectory && isCompleteVoiceDir(f)) {
                result.add(f)
            }
        }
        return result.distinctBy { it.absolutePath }
    }

    /**
     * تطبيع اسم المتحدث واستخراج الاسم النقي
     * بدون لاحقات أو بادئات.
     */
    fun cleanAcapelaSpeaker(raw: String): String {
        var s = raw.removePrefix("acapela_")
            .substringBefore('(')
            .trim()
        s = s.removeSuffix(".bvcu").removeSuffix(".dat")
            .removeSuffix(".inf").removeSuffix(".ini")
            .removeSuffix(".mdd").removeSuffix(".pho")
            .removeSuffix(".bnf")
        val langPrefixRegex = Regex(
            "^[a-z]{2,3}_[a-z]{2,3}_",
            RegexOption.IGNORE_CASE
        )
        s = s.replace(langPrefixRegex, "")
        val suffixRegex = Regex(
            "_(22k_ns|22k|8k|16k|44k|HM|HQ|LF|ns)",
            RegexOption.IGNORE_CASE
        )
        s = s.replace(suffixRegex, "")
        val cleaned = s.trim()
        return if (cleaned.isNotEmpty()) {
            cleaned.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT)
                else it.toString()
            }
        } else {
            raw.trim()
        }
    }

    /**
     * التحقق من وجود ملف تهيئة .inf متوافق مع
     * متطلبات مكتبة Acapela الأصلية لكل مجلد صوت،
     * وإنشائه أو تحديثه بصيغة `speaker = " Name"`
     * إذا لزم الأمر.
     */
    fun ensureVoiceInfFiles(baseDir: File) {
        if (!baseDir.exists() || !baseDir.isDirectory) return
        val dirs = completeVoiceDirs(baseDir)
        for (dir in dirs) {
            runCatching {
                val infFiles = dir.listFiles { f ->
                    f.isFile && f.extension.equals("inf", ignoreCase = true)
                } ?: emptyArray()

                var validInfFound = false
                var detectedSpeaker: String? = null

                for (inf in infFiles) {
                    val lines = inf.readLines(StandardCharsets.ISO_8859_1)
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("speaker", ignoreCase = true)) {
                            val eq = trimmed.indexOf('=')
                            if (eq > 0) {
                                val value = trimmed.substring(eq + 1)
                                    .trim()
                                    .removeSurrounding("\"")
                                    .trim()
                                if (value.isNotBlank()) {
                                    detectedSpeaker = cleanAcapelaSpeaker(value)
                                }
                            }
                            if (trimmed.matches(
                                Regex(
                                    "^speaker\\s*=\\s*\"\\s*[^\"\\s]+.*",
                                    RegexOption.IGNORE_CASE
                                )
                            )) {
                                validInfFound = true
                            }
                        }
                    }
                }

                if (!validInfFound) {
                    val speaker = detectedSpeaker?.ifBlank { null }
                        ?: run {
                            val dataFiles = dir.listFiles { f ->
                                f.isFile && f.extension.lowercase() in setOf(
                                    "bvcu", "dat", "bnf", "pho", "mdd"
                                )
                            } ?: emptyArray()
                            val name = dataFiles.firstOrNull()
                                ?.nameWithoutExtension ?: dir.name
                            cleanAcapelaSpeaker(name)
                        }

                    if (speaker.isNotBlank()) {
                        val targetInf = if (infFiles.isNotEmpty()) {
                            infFiles.first()
                        } else {
                            File(dir, "${speaker.lowercase()}.inf")
                        }
                        val header = buildString {
                            append("[Voice]\n")
                            append("speaker = \" $speaker\"\n")
                            append("codepage = \" utf-8\"\n")
                            append("frequency = \" 22050\"\n")
                        }
                        if (targetInf.exists()) {
                            val existing = targetInf.readText(
                                StandardCharsets.ISO_8859_1
                            )
                            if (!existing.contains(
                                "speaker = \"",
                                ignoreCase = true
                            )) {
                                targetInf.writeText(
                                    header + "\n" + existing,
                                    StandardCharsets.ISO_8859_1
                                )
                            }
                        } else {
                            targetInf.writeText(
                                header,
                                StandardCharsets.ISO_8859_1
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * استخراج بيانات الصوت من ملف التهيئة
     * (.inf أو .ini).
     */
    fun parseConfigFile(cf: File): VoiceInfo {
        var speakerName: String? = null
        var isoCode: String? = null
        var sampleRate = 22050

        runCatching {
            cf.readLines(StandardCharsets.ISO_8859_1).forEach { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("#") &&
                    !trimmed.startsWith(";") &&
                    !trimmed.startsWith("[")
                ) {
                    val eq = trimmed.indexOf('=')
                    if (eq > 0) {
                        val key = trimmed.substring(0, eq).trim().lowercase()
                        val rawVal = trimmed.substring(eq + 1).trim()
                        val value = rawVal.removeSurrounding("\"").trim()

                        when (key) {
                            "speaker", "voicename", "name", "voice" ->
                                if (speakerName == null) speakerName = value
                            "isocode", "lang", "language" ->
                                if (isoCode == null) isoCode = value
                            "samplerate", "frequency", "freq" ->
                                value.toIntOrNull()?.let { sampleRate = it }
                        }
                    }
                }
            }
        }

        val fallbackName = cf.nameWithoutExtension
        val rawName = speakerName?.ifBlank { null } ?: fallbackName
        val finalName = cleanAcapelaSpeaker(rawName)
        val locale = resolveLocale(isoCode, finalName)
        val display = "$finalName (${locale.displayLanguage})"
        val parentDir = cf.parentFile ?: cf

        return VoiceInfo(
            id = finalName,
            voiceName = finalName,
            displayName = display,
            locale = locale,
            sampleRate = sampleRate,
            dir = parentDir
        )
    }

    /**
     * استخراج بيانات الصوت من مجلد الصوت.
     */
    fun parseVoice(dir: File): VoiceInfo {
        val configFiles = dir.listFiles { f ->
            f.isFile && (f.extension.equals("ini", ignoreCase = true) ||
                f.extension.equals("inf", ignoreCase = true))
        } ?: emptyArray()

        if (configFiles.isNotEmpty()) {
            return parseConfigFile(configFiles.first())
        }

        val dataFiles = dir.listFiles { f ->
            f.isFile && f.extension.lowercase() in setOf(
                "bvcu", "dat", "bnf", "pho", "mdd"
            )
        } ?: emptyArray()

        val rawName = dataFiles.firstOrNull()?.nameWithoutExtension
            ?: dir.name
        val voiceName = cleanAcapelaSpeaker(rawName)
        val locale = resolveLocale(null, voiceName)
        val display = "$voiceName (${locale.displayLanguage})"

        return VoiceInfo(
            id = voiceName,
            voiceName = voiceName,
            displayName = display,
            locale = locale,
            sampleRate = 22050,
            dir = dir
        )
    }

    /**
     * تحويل رمز اللغة أو اسم الصوت إلى Locale مناسب.
     */
    private fun resolveLocale(isoCode: String?, voiceName: String): Locale {
        val lowerIso = isoCode?.lowercase()?.trim().orEmpty()
        if (lowerIso.startsWith("ar")) return Locale.forLanguageTag("ar")
        if (lowerIso == "ara") return Locale.forLanguageTag("ar")
        if (lowerIso.startsWith("en") || lowerIso == "eng") {
            return Locale.ENGLISH
        }
        if (lowerIso.startsWith("fr") || lowerIso == "fra") return Locale.FRENCH
        if (lowerIso.startsWith("de") || lowerIso == "ger") return Locale.GERMAN
        if (lowerIso.startsWith("es") || lowerIso == "spa") {
            return Locale.forLanguageTag("es")
        }

        val lowerName = voiceName.lowercase()
        val arabicNames = setOf(
            "mehdi", "salma", "leila", "nizar", "youssef", "tarik", "mariam"
        )
        if (arabicNames.any { lowerName.contains(it) }) {
            return Locale.forLanguageTag("ar")
        }

        return if (lowerIso.length in 2..3) {
            Locale.forLanguageTag(lowerIso)
        } else {
            Locale.forLanguageTag("ar")
        }
    }

    /**
     * قائمة الأصوات المفروزة من كل المجلدات
     * والمجلدات الفرعية.
     */
    fun listVoices(baseDir: File): List<VoiceInfo> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        ensureVoiceInfFiles(baseDir)
        val result = mutableListOf<VoiceInfo>()

        // 1. مسح كل ملفات التهيئة (.inf / .ini) في أي عمق
        baseDir.walkTopDown().maxDepth(6).forEach { f ->
            if (f.isFile && (f.extension.equals("inf", ignoreCase = true) ||
                f.extension.equals("ini", ignoreCase = true))
            ) {
                runCatching {
                    result.add(parseConfigFile(f))
                }
            }
        }

        // 2. إذا لم توجد ملفات تهيئة، فحص المجلدات التي تحوي بيانات
        if (result.isEmpty()) {
            val dirs = completeVoiceDirs(baseDir)
            for (d in dirs) {
                runCatching {
                    result.add(parseVoice(d))
                }
            }
        }

        return result.distinctBy { it.id.lowercase() }
    }
}
