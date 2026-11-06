package com.aymankhattab.nateq.core.audio.providers

import java.io.File
import java.util.Locale

/**
 * فحص وتعداد أصوات Acapela على القرص دون الحاجة لمكتبات أصلية.
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
        "ini", "inf", "bvcu", "dat", "hdr", "bnf", "pho", "dic"
    )

    /**
     * هل يحوي المجلد صوتاً مكتملاً لـ Acapela؟
     * يتطلب وجود ملف تهيئة (.ini أو .inf) وملف بيانات واحد على الأقل.
     */
    fun isCompleteVoiceDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val files = dir.listFiles() ?: return false
        val hasConfig = files.any { f ->
            f.isFile && (f.extension.equals("ini", ignoreCase = true) ||
                f.extension.equals("inf", ignoreCase = true))
        }
        val hasData = files.any { f ->
            f.isFile && f.length() > 10_000L &&
                f.extension.lowercase() in setOf("bvcu", "dat", "bnf", "pho")
        }
        return hasConfig || hasData
    }

    /**
     * قائمة المجلدات التي تحوي أصوات Acapela كاملة تحت المجلد الأساسي.
     */
    fun completeVoiceDirs(baseDir: File): List<File> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        val result = mutableListOf<File>()

        // فحص المجلد الأساسي نفسه إن كان يحوي صوتاً مباشرة
        if (isCompleteVoiceDir(baseDir)) {
            result.add(baseDir)
        }

        // فحص المجلدات الفرعية
        val subs = baseDir.listFiles { f -> f.isDirectory } ?: return result
        for (sub in subs) {
            if (isCompleteVoiceDir(sub)) {
                result.add(sub)
            } else {
                // قد تكون الأصوات داخل مجلدات متداخلة (مثل voicedream/mehdi)
                val nested = sub.listFiles { f -> f.isDirectory } ?: continue
                for (n in nested) {
                    if (isCompleteVoiceDir(n)) {
                        result.add(n)
                    }
                }
            }
        }
        return result.distinctBy { it.absolutePath }
    }

    /**
     * استخراج بيانات الصوت من مجلد الصوت.
     */
    fun parseVoice(dir: File): VoiceInfo {
        var speakerName: String? = null
        var isoCode: String? = null
        var sampleRate = 22050

        val configFiles = dir.listFiles { f ->
            f.isFile && (f.extension.equals("ini", ignoreCase = true) ||
                f.extension.equals("inf", ignoreCase = true))
        } ?: emptyArray()

        for (cf in configFiles) {
            runCatching {
                cf.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("#") ||
                            trimmed.startsWith(";") ||
                            trimmed.startsWith("[")
                        ) continue
                        val eq = trimmed.indexOf('=')
                        if (eq <= 0) continue
                        val key = trimmed.substring(0, eq).trim().lowercase()
                        val rawVal = trimmed.substring(eq + 1).trim()
                        val value = rawVal.removeSurrounding("\"").trim()

                        when (key) {
                            "speaker", "voicename", "name" ->
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

        val finalName = speakerName?.ifBlank { null } ?: dir.name
        val locale = resolveLocale(isoCode, finalName)
        val display = "$finalName (${locale.displayLanguage})"

        return VoiceInfo(
            id = finalName,
            voiceName = finalName,
            displayName = display,
            locale = locale,
            sampleRate = sampleRate,
            dir = dir
        )
    }

    /**
     * تحويل رمز اللغة أو اسم الصوت إلى Locale مناسب.
     */
    private fun resolveLocale(isoCode: String?, voiceName: String): Locale {
        val lowerIso = isoCode?.lowercase()?.trim().orEmpty()
        if (lowerIso.startsWith("ar")) return Locale("ar")
        if (lowerIso == "ara") return Locale("ar")
        if (lowerIso.startsWith("en") || lowerIso == "eng") {
            return Locale.ENGLISH
        }
        if (lowerIso.startsWith("fr") || lowerIso == "fra") return Locale.FRENCH
        if (lowerIso.startsWith("de") || lowerIso == "ger") return Locale.GERMAN
        if (lowerIso.startsWith("es") || lowerIso == "spa") return Locale("es")

        // تخمين بالاسم المشهور لأصوات Acapela العربية
        val lowerName = voiceName.lowercase()
        val arabicNames = setOf(
            "mehdi", "salma", "leila", "nizar", "youssef", "tarik", "mariam"
        )
        if (arabicNames.any { lowerName.contains(it) }) {
            return Locale("ar")
        }

        return if (lowerIso.length in 2..3) {
            Locale(lowerIso)
        } else {
            Locale("ar")
        }
    }

    /**
     * قائمة الأصوات المفروزة من كل المجلدات الفرعية.
     */
    fun listVoices(baseDir: File): List<VoiceInfo> {
        val dirs = completeVoiceDirs(baseDir)
        return dirs.map { parseVoice(it) }.distinctBy { it.id }
    }
}
