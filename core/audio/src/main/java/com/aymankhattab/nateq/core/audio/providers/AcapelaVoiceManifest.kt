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
        val qualityPrefixRegex = Regex(
            "^(hd|hq|hm)_",
            RegexOption.IGNORE_CASE
        )
        s = s.replace(qualityPrefixRegex, "")
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

    private fun isRichConfig(f: File): Boolean {
        if (!f.exists() || !f.isFile) return false
        val lines = runCatching {
            f.readLines(StandardCharsets.ISO_8859_1)
        }.getOrDefault(emptyList())
        return lines.size > 5 || lines.any { l ->
            val t = l.trim()
            t.startsWith("FILE", ignoreCase = true) ||
                t.startsWith("FILEMAP", ignoreCase = true) ||
                t.startsWith("PATH", ignoreCase = true) ||
                t.startsWith("BNF", ignoreCase = true) ||
                t.startsWith("PHM", ignoreCase = true) ||
                t.contains(".bvcu", ignoreCase = true) ||
                t.contains(".dat", ignoreCase = true)
        }
    }

    /**
     * إزالة ملفات التهيئة **المصطنعة** التي ثُبِت أنها بلا أي توجيه
     * صوتي، وعند وجود ملف `.inf` غنيّ سليم في المجلد نفسه فقط.
     *
     * **لماذا الحذف لا الكتابة:** المكتبة الأصلية تبني مسار `.ini` من
     * اسم `.inf`؛ فإن سبق سجلٌّ مصطنع (speaker فقط، بلا FILE/FILEMAP)
     * الصوتَ الحقيقي في `find_voices` فشل تحميل الصوت بصمت. وكانت
     * النسخة السابقة **تكتب** تهيئة مصطنعة وتستبدل الصالح، فتفقد
     * الصوت توجيهه — وهو مسار الصمت الأقوى. هنا قراءةٌ للتمييز
     * وحذفٌ للمصطنع المثبت فقط.
     *
     * **حماية الصوت العامل:** لا يُحذف `.ini` غنيّ أبداً، ولا يُحذف
     * شيء إذا لم يوجد `.inf` غنيّ في المجلد نفسه.
     */
    fun pruneSyntheticConfigs(baseDir: File) {
        if (!baseDir.exists() || !baseDir.isDirectory) return
        for (dir in completeVoiceDirs(baseDir)) {
            runCatching {
                val infFiles = dir.listFiles { f ->
                    f.isFile && f.extension.equals("inf", ignoreCase = true)
                } ?: emptyArray()
                if (infFiles.size < 2) return@runCatching
                // لا حذف بلا بديل سليم في المجلد نفسه.
                if (infFiles.none { isRichConfig(it) }) return@runCatching
                for (inf in infFiles) {
                    if (!isContentlessConfig(inf)) continue
                    val corrIni = File(
                        dir,
                        "${inf.nameWithoutExtension}.ini"
                    )
                    if (corrIni.exists() && isRichConfig(corrIni)) continue
                    runCatching { inf.delete() }
                    if (corrIni.exists() &&
                        isContentlessConfig(corrIni)
                    ) {
                        runCatching { corrIni.delete() }
                    }
                }
            }
        }
    }

    /**
     * التحقق من وجود ملفات تهيئة .inf و .ini و .cfg متوافقة تماماً مع
     * متطلبات مكتبة Acapela الأصلية (libacattsandroid.so):
     *
     * 1. دوال البحث [tts_function_find_voices] تبحث حصراً عن ملفات
     *    `.inf` وتستخرج `speaker` عبر sscanf: `%s = " %[^"]" `
     *    التي تتطلب حتماً مسافة قبل علامة المساواة (`speaker = " ..."`).
     *
     * 2. دالة التحميل [tts_function_load_voice] تفتح ملف `.ini` المطابق
     *    لاسم `.inf`، وتقرأ عبر sscanf: `%s = %[^"] ` وتبحث عن مفتاح
     *    [MBR]؛ فإن لم تجده انهارت بـ ERROR_BABILE_INITLANGDBAEX (-7).
     *
     * 3. لا يُمسّ أي ملف تهيئة غنيّ يحوي توجيهات أصلية، بل يُستكمل
     *    بإضافة [MBR] وتنسيق speaker إن كانا ناقصين.
     */
    fun ensureVoiceConfigs(baseDir: File) {
        if (!baseDir.exists() || !baseDir.isDirectory) return
        for (dir in completeVoiceDirs(baseDir)) {
            runCatching {
                val dataFiles = dir.listFiles { f ->
                    f.isFile && f.extension.lowercase(Locale.ROOT) in
                        DATA_EXTENSIONS
                } ?: emptyArray()

                val bvcuFiles = dataFiles.filter {
                    it.extension.equals("bvcu", ignoreCase = true)
                }
                val datFiles = dataFiles.filter {
                    it.extension.equals("dat", ignoreCase = true)
                }
                val infFiles = dir.listFiles { f ->
                    f.isFile && f.extension.equals("inf", ignoreCase = true)
                } ?: emptyArray()
                val iniFiles = dir.listFiles { f ->
                    f.isFile && f.extension.equals("ini", ignoreCase = true)
                } ?: emptyArray()

                if (dataFiles.isEmpty() && infFiles.isEmpty() &&
                    iniFiles.isEmpty()
                ) {
                    return@runCatching
                }

                // كشف المتحدث واللغة ومعدل العينات من الملفات الحالية
                var detectedSpeaker: String? = null
                var detectedIso: String? = null
                var detectedRate: Int? = null

                for (cf in infFiles + iniFiles) {
                    val info = runCatching {
                        parseConfigFile(cf)
                    }.getOrNull()
                    if (info != null) {
                        if (detectedSpeaker == null &&
                            info.rawSpeaker != null
                        ) {
                            detectedSpeaker = info.rawSpeaker
                        }
                        if (detectedIso == null &&
                            info.locale != Locale.ROOT
                        ) {
                            detectedIso = info.locale.language
                        }
                        if (detectedRate == null && info.sampleRate > 0) {
                            detectedRate = info.sampleRate
                        }
                    }
                }

                val primaryData = bvcuFiles.firstOrNull()
                    ?: dataFiles.firstOrNull()
                if (detectedSpeaker.isNullOrBlank()) {
                    val rawDataName = primaryData?.nameWithoutExtension
                    detectedSpeaker = rawDataName?.let {
                        cleanAcapelaSpeaker(it)
                    } ?: cleanAcapelaSpeaker(dir.name)
                }
                if (detectedSpeaker.isNullOrBlank()) {
                    detectedSpeaker = "Voice"
                }

                val sampleRate = detectedRate ?: DEFAULT_SAMPLE_RATE
                val loc = resolveLocale(
                    detectedIso,
                    detectedSpeaker,
                    primaryData?.nameWithoutExtension
                )

                // جدول رموز ISO لمكتبة Acapela (دالّة 0x5bb30):
                // العربية "ar-sa" حصراً، والإنجليزية "en-us" أو "en-gb".
                val isoCode = when (loc.language.lowercase(Locale.ROOT)) {
                    "ar" -> "ar-sa"
                    "en" -> if (loc.country.equals("GB", ignoreCase = true)) {
                        "en-gb"
                    } else {
                        "en-us"
                    }
                    "fr" -> if (loc.country.equals("CA", ignoreCase = true)) {
                        "fr-ca"
                    } else {
                        "fr-fr"
                    }
                    "es" -> if (loc.country.equals("US", ignoreCase = true) ||
                        loc.country.equals("MX", ignoreCase = true)
                    ) {
                        "es-us"
                    } else {
                        "es-es"
                    }
                    "de" -> "de-de"
                    "it" -> "it-it"
                    "ru" -> "ru-ru"
                    "pt" -> if (loc.country.equals("PT", ignoreCase = true)) {
                        "pt-pt"
                    } else {
                        "pt-br"
                    }
                    "nl" -> if (loc.country.equals("BE", ignoreCase = true)) {
                        "nl-be"
                    } else {
                        "nl-nl"
                    }
                    "tr" -> "tr-tr"
                    "sv" -> if (loc.country.equals("FI", ignoreCase = true)) {
                        "sv-fi"
                    } else {
                        "sv-se"
                    }
                    "da" -> "da-dk"
                    "nb", "no" -> "nb-no"
                    "pl" -> "pl-pl"
                    "el" -> "el-gr"
                    "ja" -> "ja-jp"
                    "zh" -> "zh-cn"
                    "ko" -> "ko-kr"
                    else -> "ar-sa"
                }

                val langName = when {
                    isoCode.startsWith("ar", ignoreCase = true) -> "Arabic"
                    isoCode.startsWith("en", ignoreCase = true) -> "English"
                    isoCode.startsWith("fr", ignoreCase = true) -> "French"
                    isoCode.startsWith("de", ignoreCase = true) -> "German"
                    isoCode.startsWith("es", ignoreCase = true) -> "Spanish"
                    isoCode.startsWith("ru", ignoreCase = true) -> "Russian"
                    isoCode.startsWith("it", ignoreCase = true) -> "Italian"
                    else -> "Arabic"
                }

                val cleanSpk = cleanAcapelaSpeaker(detectedSpeaker)

                // الاسم المعتمد الوحيد لملف التهيئة لتجنب تكرار .inf
                // في readdir عند البحث nGetVoiceList
                val richInf = infFiles.firstOrNull { isRichConfig(it) }
                val canonicalName = richInf?.nameWithoutExtension
                    ?: infFiles.firstOrNull()?.nameWithoutExtension
                    ?: cleanSpk.ifBlank { detectedSpeaker }

                // حذف أي ملفات .inf / .ini / .cfg بديلة في المجلد نفسه
                // لمنع التكرار والتعارض
                for (f in infFiles) {
                    if (!f.nameWithoutExtension.equals(canonicalName, false)) {
                        runCatching { f.delete() }
                    }
                }
                for (f in iniFiles) {
                    if (!f.nameWithoutExtension.equals(canonicalName, false) &&
                        !isRichConfig(f)
                    ) {
                        runCatching { f.delete() }
                    }
                }
                dir.listFiles { f ->
                    f.isFile && f.extension.equals("cfg", ignoreCase = true) &&
                        !f.nameWithoutExtension.equals(canonicalName, false)
                }?.forEach { runCatching { it.delete() } }

                val dataName = bvcuFiles.firstOrNull()?.name
                    ?: primaryData?.name.orEmpty()
                val datName = datFiles.firstOrNull()?.name.orEmpty()

                val targetInf = File(dir, "$canonicalName.inf")
                val targetIni = File(dir, "$canonicalName.ini")
                val targetCfg = File(dir, "$canonicalName.cfg")

                // 1) كتابة .inf بتنسيق sscanf الصارم: %s = " %[^"]" 
                val infHeader = buildString {
                    append("[Voice]\n")
                    append("speaker = \" $cleanSpk\"\n")
                    append("language = \" $langName\"\n")
                    append("ISOCode = \" $isoCode\"\n")
                    append("frequency = \" $sampleRate\"\n")
                }
                if (!targetInf.exists() || targetInf.length() == 0L ||
                    !isRichConfig(targetInf)
                ) {
                    targetInf.writeText(
                        infHeader,
                        StandardCharsets.ISO_8859_1
                    )
                } else {
                    val text = targetInf.readText(
                        StandardCharsets.ISO_8859_1
                    )
                    var updated = text
                    if (!updated.contains("speaker = \" $cleanSpk\"")) {
                        updated += "\nspeaker = \" $cleanSpk\"\n"
                    }
                    if (updated.contains("ara-SAU") ||
                        updated.contains("ar-SAU")
                    ) {
                        updated = updated.replace("ara-SAU", "ar-sa")
                            .replace("ar-SAU", "ar-sa")
                    }
                    if (!updated.contains("ISOCode = \" $isoCode\"")) {
                        updated += "\nISOCode = \" $isoCode\"\n"
                    }
                    if (updated != text) {
                        targetInf.writeText(
                            updated,
                            StandardCharsets.ISO_8859_1
                        )
                    }
                }

                // 2) كتابة .ini لضمان التوافق مع ini_loader و cfg_loader
                // تتطلب المكتبة MBR "..." [ RAM ] لعدّ السجلات في الدورة
                // الأولى (0x5b1a4) و MBR = ... للقراءة في 0x5b548.
                val mbrDirectives = buildString {
                    if (dataName.isNotEmpty()) {
                        append("MBR \"$dataName\" [ RAM ]\n")
                        append("MBR \"$dataName\"\n")
                        append("MBR = \"$dataName\"\n")
                        append("MBR = $dataName\"\n")
                        append("MBR = $dataName\n")
                        append("FILE \"$dataName\" [ RAM ]\n")
                    }
                    if (datName.isNotEmpty()) {
                        append("FILEMAP \"$datName\" [ FILEMAP ]\n")
                    }
                }

                if (!targetIni.exists() || targetIni.length() == 0L) {
                    val iniContent = buildString {
                        append("[Voice]\n")
                        append("speaker = \" $cleanSpk\"\n")
                        append("speaker = \"$cleanSpk\"\n")
                        append("speaker \"$cleanSpk\"\n")
                        append("language = \" $langName\"\n")
                        append("language \"$langName\"\n")
                        append("ISOCode = \" $isoCode\"\n")
                        append("ISOCode \"$isoCode\"\n")
                        append("frequency = \" $sampleRate\"\n")
                        append("frequency \"$sampleRate\"\n")
                        append(mbrDirectives)
                    }
                    targetIni.writeText(
                        iniContent,
                        StandardCharsets.ISO_8859_1
                    )
                } else {
                    val text = targetIni.readText(
                        StandardCharsets.ISO_8859_1
                    )
                    if (!text.contains("MBR \"$dataName\"") &&
                        dataName.isNotEmpty()
                    ) {
                        targetIni.appendText(
                            "\n$mbrDirectives",
                            StandardCharsets.ISO_8859_1
                        )
                    }
                }

                // 3) كتابة .cfg إن لم يوجد
                if (!targetCfg.exists() && dataName.isNotEmpty()) {
                    val cfgContent = buildString {
                        append("FILE \"$dataName\" [ RAM ]\n")
                        if (datName.isNotEmpty()) {
                            append("FILEMAP \"$datName\" [ FILEMAP ]\n")
                        }
                    }
                    targetCfg.writeText(
                        cfgContent,
                        StandardCharsets.ISO_8859_1
                    )
                }
            }
        }
    }

    /**
     * ملف تهيئة بلا أي توجيه صوتي فعلي: قصير ولا يذكر أي ملف بيانات
     * أو مسار (FILE/FILEMAP/PATH/BNF/PHM). هذا هو المصطنع الذي يُفسد
     * تحميل الصوت الحقيقي، وليس التهيئة الغنية التي توجّه العينات.
     */
    private fun isContentlessConfig(f: File): Boolean = !isRichConfig(f)

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
