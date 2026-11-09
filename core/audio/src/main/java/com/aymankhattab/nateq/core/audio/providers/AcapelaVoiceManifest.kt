package com.aymankhattab.nateq.core.audio.providers

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * فحص وتعداد أصوات Acapela على القرص
 * دون الحاجة لمكتبات أصلية.
 */
object AcapelaVoiceManifest {

    /** معدل العينات الافتراضي لأصوات Acapela الأصلية. */
    const val DEFAULT_SAMPLE_RATE = 22050

    private const val MIN_SAMPLE_RATE = 8000
    private const val MAX_SAMPLE_RATE = 96000
    private const val MIN_DATA_FILE_BYTES = 5_000L

    private val CONFIG_EXTENSIONS = setOf("inf", "ini")
    private val DATA_EXTENSIONS = setOf(
        "bvcu", "dat", "bnf", "pho", "mdd", "hdr"
    )

    /**
     * بيانات صوت Acapela كما وردت على القرص.
     *
     * [rawSpeaker] هو الاسم الخام من حقل `speaker` في ملف `.inf` — وهو
     * الاسم الذي تقارنه مكتبة Acapela الأصلية داخل `nLoadVoice`، فلا
     * يجوز تطبيعه قبل تمريره إليها.
     */
    data class VoiceInfo(
        val id: String,
        val voiceName: String,
        val displayName: String,
        val locale: Locale,
        val sampleRate: Int = DEFAULT_SAMPLE_RATE,
        val dir: File,
        val rawSpeaker: String? = null,
        val configFile: File? = null
    )

    /**
     * أزواج المفاتيح الشائعة: رمز ISO-639-3 إلى ISO-639-1.
     * مكتبة Acapela تكتب `ISOCode = "ru-RU"` أو `lang = "Russian"`.
     */
    private val ISO3_TO_ISO1 = mapOf(
        "ara" to "ar", "eng" to "en", "fra" to "fr", "fre" to "fr",
        "deu" to "de", "ger" to "de", "spa" to "es", "rus" to "ru",
        "ita" to "it", "nld" to "nl", "dut" to "nl", "pol" to "pl",
        "por" to "pt", "tur" to "tr", "swe" to "sv", "nor" to "no",
        "dan" to "da", "fin" to "fi", "ell" to "el", "gre" to "el",
        "ces" to "cs", "cze" to "cs", "isl" to "is", "ice" to "is",
        "zho" to "zh", "chi" to "zh", "jpn" to "ja", "kor" to "ko",
        "hin" to "hi", "urd" to "ur", "fas" to "fa", "per" to "fa",
        "heb" to "he", "ron" to "ro", "rum" to "ro", "hun" to "hu",
        "ukr" to "uk", "cat" to "ca", "ind" to "id", "msa" to "ms",
        "may" to "ms", "tha" to "th", "vie" to "vi", "ben" to "bn",
        "tam" to "ta", "tel" to "te", "mar" to "mr", "guj" to "gu",
        "kan" to "kn", "mal" to "ml", "pan" to "pa", "slk" to "sk",
        "slo" to "sk", "slv" to "sl", "hrv" to "hr", "srp" to "sr",
        "bul" to "bg", "lit" to "lt", "lav" to "lv", "est" to "et",
        "eus" to "eu", "baq" to "eu", "glg" to "gl", "afr" to "af",
        "sqi" to "sq", "alb" to "sq", "mkd" to "mk", "mac" to "mk",
        "kat" to "ka", "geo" to "ka", "hye" to "hy", "arm" to "hy",
        "aze" to "az", "kaz" to "kk", "uzb" to "uz", "swa" to "sw",
        "amh" to "am", "som" to "so", "bel" to "be", "cym" to "cy",
        "nob" to "nb", "nno" to "nn"
    )

    /** أسماء اللغات بالإنجليزية كما ترد في `lang = "Russian"`. */
    private val LANGUAGE_NAMES = mapOf(
        "arabic" to "ar", "english" to "en", "french" to "fr",
        "german" to "de", "spanish" to "es", "russian" to "ru",
        "italian" to "it", "dutch" to "nl", "polish" to "pl",
        "portuguese" to "pt", "turkish" to "tr", "swedish" to "sv",
        "norwegian" to "no", "danish" to "da", "finnish" to "fi",
        "greek" to "el", "czech" to "cs", "icelandic" to "is",
        "chinese" to "zh", "japanese" to "ja", "korean" to "ko",
        "hindi" to "hi", "urdu" to "ur", "persian" to "fa",
        "farsi" to "fa", "hebrew" to "he", "romanian" to "ro",
        "hungarian" to "hu", "ukrainian" to "uk", "catalan" to "ca",
        "indonesian" to "id", "malay" to "ms", "thai" to "th",
        "vietnamese" to "vi", "bengali" to "bn", "tamil" to "ta",
        "telugu" to "te", "marathi" to "mr", "gujarati" to "gu",
        "kannada" to "kn", "malayalam" to "ml", "punjabi" to "pa",
        "slovak" to "sk", "slovenian" to "sl", "croatian" to "hr",
        "serbian" to "sr", "bulgarian" to "bg", "lithuanian" to "lt",
        "latvian" to "lv", "estonian" to "et", "basque" to "eu",
        "galician" to "gl", "afrikaans" to "af", "albanian" to "sq",
        "macedonian" to "mk", "georgian" to "ka", "armenian" to "hy",
        "azerbaijani" to "az", "kazakh" to "kk", "uzbek" to "uz",
        "swahili" to "sw", "amharic" to "am", "somali" to "so"
    )

    private val NAME_PREFIX_REGEX = Regex("^([a-z]{2,3})[_-]")

    /**
     * أزواج `KEY = "value"` — قد يرد عشراتٌ منها في سطر واحد،
     * وكل تحليل سطري يفشل حتماً معها.
     */
    private val QUOTED_PAIR_REGEX = Regex(
        "([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"([^\"]*)\""
    )

    private val ARABIC_VOICE_NAMES = setOf(
        "mehdi", "salma", "leila", "nizar", "youssef", "tarik", "mariam"
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
            f.isFile && f.extension.lowercase(Locale.ROOT) in
                CONFIG_EXTENSIONS
        }
        val hasData = files.any { f ->
            f.isFile && f.length() > MIN_DATA_FILE_BYTES &&
                f.extension.lowercase(Locale.ROOT) in DATA_EXTENSIONS
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
        for (ext in listOf(
            ".bvcu", ".dat", ".inf", ".ini", ".mdd", ".pho", ".bnf"
        )) {
            s = s.removeSuffix(ext)
        }
        val langPrefixRegex = Regex(
            "^[a-z]{2,3}_[a-z]{2,3}_",
            RegexOption.IGNORE_CASE
        )
        s = s.replace(langPrefixRegex, "")
        val suffixRegex = Regex(
            "_(22k_ns|22k|44k|16k|8k|hm|hq|lf|ns)(?=_|$)",
            RegexOption.IGNORE_CASE
        )
        while (true) {
            val replaced = s.replace(suffixRegex, "")
            if (replaced == s) break
            s = replaced
        }
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
                        if (trimmed.startsWith(
                            "speaker",
                            ignoreCase = true
                        )) {
                            val eq = trimmed.indexOf('=')
                            if (eq > 0) {
                                val value = trimmed.substring(eq + 1)
                                    .trim()
                                    .removeSurrounding("\"")
                                    .trim()
                                if (value.isNotBlank()) {
                                    detectedSpeaker =
                                        cleanAcapelaSpeaker(value)
                                }
                            }
                            validInfFound = true
                            break
                        }
                    }
                    if (validInfFound) break
                }

                if (!validInfFound) {
                    val speaker = detectedSpeaker
                        ?: run {
                            val iniFiles = dir.listFiles { f ->
                                f.isFile && f.extension.equals(
                                    "ini",
                                    ignoreCase = true
                                )
                            } ?: emptyArray()
                            var fromIni: String? = null
                            for (ini in iniFiles) {
                                for (l in ini.readLines(
                                    StandardCharsets.ISO_8859_1
                                )) {
                                    val trimmed = l.trim()
                                    if (trimmed.startsWith(
                                        "speaker",
                                        ignoreCase = true
                                    ) || trimmed.startsWith(
                                        "voicename",
                                        ignoreCase = true
                                    )) {
                                        val eq = trimmed.indexOf('=')
                                        if (eq > 0) {
                                            fromIni = cleanAcapelaSpeaker(
                                                trimmed.substring(eq + 1)
                                                    .trim()
                                                    .removeSurrounding("\"")
                                                    .trim()
                                            )
                                            break
                                        }
                                    }
                                }
                                if (fromIni != null) break
                            }
                            fromIni
                        }
                        ?: run {
                            val dataFiles = dir.listFiles { f ->
                                f.isFile && f.extension.lowercase(
                                    Locale.ROOT
                                ) in setOf(
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
                            File(dir, "${speaker.lowercase(Locale.ROOT)}.inf")
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
        val text = runCatching {
            cf.readText(StandardCharsets.ISO_8859_1)
        }.getOrDefault("")
        val pairs = readPairs(text)

        val rawSpeaker = firstValue(pairs, "speaker")
        val nameFromNameKey = pairs.firstOrNull {
            it.first == "name" && !it.second.contains('.')
        }?.second
        val nameCandidate = firstValue(pairs, "voicename")
            ?: nameFromNameKey
            ?: firstValue(pairs, "voice")
        val rawName = rawSpeaker ?: nameCandidate
            ?: cf.nameWithoutExtension
        val finalName = cleanAcapelaSpeaker(rawName)

        val isoCode = firstValue(pairs, "isocode", "lang", "language")
        val rateText = firstValue(
            pairs, "samplerate", "frequency", "freq"
        )
        val sampleRate = rateText?.toIntOrNull()
            ?.takeIf { it in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE }
            ?: DEFAULT_SAMPLE_RATE
        val locale = resolveLocale(
            isoCode, finalName, cf.nameWithoutExtension
        )
        val display = displayNameOf(finalName, locale)

        return VoiceInfo(
            id = finalName,
            voiceName = finalName,
            displayName = display,
            locale = locale,
            sampleRate = sampleRate,
            dir = cf.parentFile ?: cf,
            rawSpeaker = rawSpeaker,
            configFile = cf
        )
    }

    /**
     * استخراج بيانات الصوت من مجلد الصوت.
     */
    fun parseVoice(dir: File): VoiceInfo {
        val configFiles = dir.listFiles { f ->
            f.isFile && f.extension.lowercase(Locale.ROOT) in
                CONFIG_EXTENSIONS
        } ?: emptyArray()

        val chosen = configFiles.firstOrNull {
            it.extension.equals("inf", ignoreCase = true)
        } ?: configFiles.firstOrNull()
        if (chosen != null) return parseConfigFile(chosen)

        val dataFiles = dir.listFiles { f ->
            f.isFile && f.extension.lowercase(Locale.ROOT) in
                DATA_EXTENSIONS
        } ?: emptyArray()

        val rawName = dataFiles.firstOrNull()?.nameWithoutExtension
            ?: dir.name
        val voiceName = cleanAcapelaSpeaker(rawName)
        val locale = resolveLocale(null, voiceName, rawName)

        return VoiceInfo(
            id = voiceName,
            voiceName = voiceName,
            displayName = displayNameOf(voiceName, locale),
            locale = locale,
            dir = dir
        )
    }

    /**
     * قائمة الأصوات المفروزة من كل المجلدات
     * والمجلدات الفرعية.
     *
     * **لا تُكتب أي ملفات هنا:** مكتبة Acapela الأصلية تبني مسار `.ini`
     * من اسم `.inf`، فأي `.inf` مُصطنع بلا `.ini` مقابل يُفسد تحميل
     * الصوت. التعداد قراءةٌ خالصة.
     */
    fun listVoices(baseDir: File): List<VoiceInfo> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        val result = mutableListOf<VoiceInfo>()
        val configsByDir = linkedMapOf<File, MutableList<File>>()

        baseDir.walkTopDown().maxDepth(6).forEach { f ->
            if (f.isFile &&
                f.extension.lowercase(Locale.ROOT) in CONFIG_EXTENSIONS
            ) {
                val dir = f.parentFile ?: return@forEach
                configsByDir.getOrPut(dir) { mutableListOf() }.add(f)
            }
        }

        for ((dir, files) in configsByDir) {
            val infFiles = files.filter {
                it.extension.equals("inf", ignoreCase = true)
            }
            val chosen = if (infFiles.isNotEmpty()) infFiles else files
            for (cf in chosen) {
                val info = runCatching {
                    parseConfigFile(cf)
                }.getOrNull() ?: continue
                if (isNonVoiceConfig(info, dir)) continue
                result.add(info)
            }
        }

        if (result.isEmpty()) {
            for (dir in completeVoiceDirs(baseDir)) {
                runCatching { result.add(parseVoice(dir)) }
            }
        }

        return result.distinctBy {
            "${it.dir.absolutePath}|${it.id.lowercase(Locale.ROOT)}"
        }
    }

    /**
     * ملف تهيئة لا يمثل صوتاً (بلا متحدث وبلا لغة وبلا بيانات):
     * مثل `.ini` التنظيمي أو `.inf` هيكلي لمجلد عام.
     */
    private fun isNonVoiceConfig(info: VoiceInfo, dir: File): Boolean {
        if (info.rawSpeaker != null) return false
        if (info.locale.language.isNotEmpty()) return false
        return !hasVoiceData(dir)
    }

    private fun hasVoiceData(dir: File): Boolean =
        dir.listFiles()?.any { f ->
            f.isFile && f.length() > MIN_DATA_FILE_BYTES &&
                f.extension.lowercase(Locale.ROOT) in DATA_EXTENSIONS
        } ?: false

    /**
     * قراءة أزواج `KEY = "value"` من كامل النص.
     * الصيغة الحقيقية قد تضع عشرات الأزواج في سطر واحد؛ فإن وُجد
     * أي زوج مُقتبس اعتمدنا عليه، وإلا رجعنا للصيغة السطرية القديمة
     * (`Speaker=Mehdi`) توافقاً مع ملفاتٍ بسيطة.
     */
    private fun readPairs(text: String): List<Pair<String, String>> {
        val quoted = QUOTED_PAIR_REGEX.findAll(text).map {
            it.groupValues[1].lowercase(Locale.ROOT) to
                it.groupValues[2].trim()
        }.toList()
        if (quoted.isNotEmpty()) return quoted

        return text.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") ||
                trimmed.startsWith(";") || trimmed.startsWith("[")
            ) {
                null
            } else {
                val eq = trimmed.indexOf('=')
                if (eq <= 0) {
                    null
                } else {
                    val key = trimmed.substring(0, eq).trim()
                        .lowercase(Locale.ROOT)
                    val value = trimmed.substring(eq + 1).trim()
                        .removeSurrounding("\"").trim()
                    key to value
                }
            }
        }.toList()
    }

    private fun firstValue(
        pairs: List<Pair<String, String>>,
        vararg keys: String
    ): String? = keys.firstNotNullOfOrNull { key ->
        pairs.firstOrNull { it.first == key }?.second
            ?.takeIf { it.isNotBlank() }
    }

    private fun displayNameOf(name: String, locale: Locale): String {
        val language = locale.getDisplayLanguage(locale).trim()
        return if (language.isEmpty()) name else "$name ($language)"
    }

    /**
     * تحويل رمز اللغة أو اسم الصوت إلى Locale مناسب.
     * المجهول يُعاد بـ[Locale.ROOT] (لا بالعربية) حتى لا يُصنَّف كل
     * صوتٍ عربياً فيُفشل فلترة اللغة.
     */
    private fun resolveLocale(
        isoCode: String?,
        voiceName: String,
        fileName: String? = null
    ): Locale {
        val iso = isoCode?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (iso.isNotEmpty()) {
            LANGUAGE_NAMES[iso]?.let {
                return Locale.forLanguageTag(it)
            }
            val head = iso.substringBefore('-').substringBefore('_')
            if (head.length == 2) return Locale.forLanguageTag(head)
            ISO3_TO_ISO1[head]?.let {
                return Locale.forLanguageTag(it)
            }
        }

        for (candidate in listOfNotNull(fileName, voiceName)) {
            val match = NAME_PREFIX_REGEX.find(
                candidate.lowercase(Locale.ROOT)
            ) ?: continue
            val prefix = match.groupValues[1]
            if (prefix.length == 2) {
                return Locale.forLanguageTag(prefix)
            }
            ISO3_TO_ISO1[prefix]?.let {
                return Locale.forLanguageTag(it)
            }
        }

        val lowerName = voiceName.lowercase(Locale.ROOT)
        if (ARABIC_VOICE_NAMES.any { lowerName.contains(it) }) {
            return Locale.forLanguageTag("ar")
        }
        return Locale.ROOT
    }
}
