package com.aymankhattab.nateq.core.audio.providers

import android.util.Log
import com.aymankhattab.nateq.core.audio.enginehost.HostLog
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    internal val CONFIG_EXTENSIONS = setOf("inf", "ini")
    internal val DATA_EXTENSIONS = setOf(
        "bvcu", "dat", "bnf", "pho", "mdd", "hdr"
    )

    /**
     * عمر نتيجة التعداد المخزَّنة (ms).
     *
     * التخزين لا يُغني عن الإبطال: البصمة [EmbeddedStorage.lastBumpTime]
     * ترتفع مع كل استيراد أو تنزيل أو حذف فيُعاد التعداد فوراً. أما
     * الـTTL فيغطي التغيير الخارجي (ملف يُحذف من مدير الملفات مثلاً)
     * فيحدّه بثلاثين ثانية بدل أن يبقى قديماً إلى الأبد.
     */
    private const val VOICES_CACHE_TTL_MS = 30_000L

    /** لقطة تعداد صوت واحد في مجلد بحث واحد. */
    private data class VoicesCacheEntry(
        val bumpTime: Long,
        val stampMs: Long,
        val voices: List<VoiceInfo>
    )

    private val voicesCache =
        ConcurrentHashMap<String, VoicesCacheEntry>()

    /** يمنع تعدادين متوازيين فيُقرأ القرص مرتين لنفس المجلد. */
    private val voicesCacheMutex = Mutex()

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

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
        val configFile: File? = null,
        /**
         * بيانات التسجيل (metadata) للحزمة الممتدة — لا اسم ملف
         * `0x...` إطلاقاً. الفراغ يعني حزمة كلاسيكية تُشغَّل
         * بمحرك `libacattsandroid` ورخصة `runtime.dat`.
         */
        val registration: String = ""
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

    /** بادئةٌ تحمل اللغة والدولة معاً: `ar_sa_...` أو `ara-SAU-...`. */
    private val NAME_LOCALE_REGEX = Regex(
        "^([a-z]{2,3})[_-]([a-z]{2,3})(?:[_-]|$)"
    )

    /** رمز الدولة بثلاثة أحرف (ISO-3166-1 alpha-3) إلى حرفين. */
    private val ISO3_COUNTRY_TO_ISO2 = mapOf(
        "sau" to "SA", "usa" to "US", "gbr" to "GB", "fra" to "FR",
        "deu" to "DE", "esp" to "ES", "rus" to "RU", "ita" to "IT",
        "nld" to "NL", "bra" to "BR", "prt" to "PT", "tur" to "TR",
        "swe" to "SE", "dnk" to "DK", "nor" to "NO", "pol" to "PL",
        "grc" to "GR", "jpn" to "JP", "chn" to "CN", "kor" to "KR",
        "ind" to "IN", "pak" to "PK", "irn" to "IR", "isr" to "IL",
        "cze" to "CZ", "svk" to "SK", "hun" to "HU", "rou" to "RO",
        "ukr" to "UA", "bgr" to "BG", "hrv" to "HR", "srb" to "RS",
        "svn" to "SI", "ltu" to "LT", "lva" to "LV", "est" to "EE",
        "fin" to "FI", "isl" to "IS", "can" to "CA", "aus" to "AU",
        "mex" to "MX", "egy" to "EG", "mar" to "MA", "dza" to "DZ",
        "tun" to "TN", "jor" to "JO", "lbn" to "LB", "kwt" to "KW",
        "qat" to "QA", "are" to "AE", "irq" to "IQ", "syr" to "SY",
        "yem" to "YE", "sdn" to "SD", "lby" to "LY", "omn" to "OM",
        "bhr" to "BH", "pse" to "PS", "bel" to "BY", "kaz" to "KZ",
        "uzb" to "UZ", "ken" to "KE", "eth" to "ET", "som" to "SO",
        "zaf" to "ZA", "mys" to "MY", "tha" to "TH", "vnm" to "VN"
    )

    /**
     * دولةٌ افتراضية للغةٍ لا يذكر رمزها دولة. المحرك الممتد
     * يحتاج ISO3 للغة **والدولة** معاً (`ara` + `SAU`)، فإغفال
     * الدولة يُفشل `nSetLanguage`.
     */
    private val DEFAULT_COUNTRY = mapOf(
        "ar" to "SA", "en" to "US", "fr" to "FR", "de" to "DE",
        "es" to "ES", "ru" to "RU", "it" to "IT", "nl" to "NL",
        "pt" to "BR", "tr" to "TR", "sv" to "SE", "da" to "DK",
        "nb" to "NO", "nn" to "NO", "no" to "NO", "pl" to "PL",
        "el" to "GR", "ja" to "JP", "zh" to "CN", "ko" to "KR",
        "hi" to "IN", "ur" to "PK", "fa" to "IR", "he" to "IL",
        "cs" to "CZ", "sk" to "SK", "hu" to "HU", "ro" to "RO",
        "uk" to "UA", "bg" to "BG", "hr" to "HR", "sr" to "RS",
        "sl" to "SI", "lt" to "LT", "lv" to "LV", "et" to "EE",
        "fi" to "FI", "is" to "IS", "ca" to "ES", "gl" to "ES",
        "eu" to "ES", "af" to "ZA", "sq" to "AL", "mk" to "MK",
        "ka" to "GE", "hy" to "AM", "az" to "AZ", "kk" to "KZ",
        "uz" to "UZ", "sw" to "KE", "am" to "ET", "so" to "SO",
        "be" to "BY", "cy" to "GB", "id" to "ID", "ms" to "MY",
        "th" to "TH", "vi" to "VN", "bn" to "BD", "ta" to "IN",
        "te" to "IN", "mr" to "IN", "gu" to "IN", "kn" to "IN",
        "ml" to "IN", "pa" to "IN"
    )

    fun defaultCountryForLanguage(lang: String): String? =
        DEFAULT_COUNTRY[lang.lowercase(Locale.ROOT)]

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

    val HEX_REGISTRATION_REGEX = Regex(
        "^0x[0-9A-Fa-f]{8,16}$",
        RegexOption.IGNORE_CASE
    )

    /** عقد MultiTTS لبيانات تسجيل الحزمة الممتدة. */
    private val REGISTRATION_METADATA_REGEX = Regex(
        "[A-Za-z0-9@._+-]{1,288}"
    )

    /**
     * البحث عن ملف ترخيص Acapela المسمى بصيغة 0x...
     * في مجلد الصوت أو المجلدات الأب حتى 4 مستويات.
     */
    fun findHexRegistrationFile(dir: File): File? {
        if (!dir.exists()) return null
        val direct = dir.listFiles { f ->
            f.isFile && f.name.matches(HEX_REGISTRATION_REGEX)
        }?.firstOrNull()
        if (direct != null) return direct

        val regTxt = File(dir, "registration.txt")
        if (regTxt.exists()) {
            val text = runCatching {
                regTxt.readText(StandardCharsets.ISO_8859_1).trim()
            }.getOrNull()
            if (text != null && text.matches(HEX_REGISTRATION_REGEX)) {
                val f = File(dir, text)
                if (!f.exists()) runCatching { f.createNewFile() }
                return f
            }
        }

        var cur = dir.parentFile
        var depth = 0
        while (cur != null && depth < 4) {
            val parentFile = cur.listFiles { f ->
                f.isFile && f.name.matches(HEX_REGISTRATION_REGEX)
            }?.firstOrNull()
            if (parentFile != null) return parentFile
            val parentReg = File(cur, "registration.txt")
            if (parentReg.exists()) {
                val text = runCatching {
                    parentReg.readText(StandardCharsets.ISO_8859_1).trim()
                }.getOrNull()
                if (text != null && text.matches(HEX_REGISTRATION_REGEX)) {
                    val f = File(cur, text)
                    if (!f.exists()) runCatching { f.createNewFile() }
                    return f
                }
            }
            cur = cur.parentFile
            depth++
        }

        val subFiles = dir.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (sub in subFiles) {
            val subHex = sub.listFiles { f ->
                f.isFile && f.name.matches(HEX_REGISTRATION_REGEX)
            }?.firstOrNull()
            if (subHex != null) return subHex
        }
        return null
    }

    /**
     * فحص ما إذا كان المجلد يحوي قاموس مستخدم (.userdico) بنمط UTF-8.
     */
    fun hasUtf8Userdico(dir: File): Boolean {
        val files = dir.listFiles() ?: return false
        return files.any { f ->
            if (!f.isFile || !f.name.endsWith(
                    ".userdico",
                    ignoreCase = true
                )
            ) {
                return@any false
            }
            val firstLine = runCatching {
                f.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    reader.readLine()
                }
            }.getOrNull().orEmpty()
            firstLine.contains("UTF-8")
        }
    }

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
        var s = raw.removePrefix("acapela_embedded:")
            .removePrefix("acapela:")
            .removePrefix("acapela_")
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
            "(?:_)?(22k_ns|22k|44k|16k|8k|hm|hq|lf|ns)(?=_|$)",
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

    fun normalizeExtensionsCase(dir: File) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isFile) {
                val ext = f.extension
                val lowerExt = ext.lowercase(Locale.ROOT)
                if (ext != lowerExt && (lowerExt in CONFIG_EXTENSIONS ||
                        lowerExt in DATA_EXTENSIONS)
                ) {
                    val target = File(
                        dir,
                        "${f.nameWithoutExtension}.$lowerExt"
                    )
                    if (!target.exists()) {
                        runCatching { f.copyTo(target, overwrite = false) }
                    }
                }
            }
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
     * تجهيز مجلدات الأصوات بما تحتاجه المكتبة الأصلية **دون توليد أي
     * ملف تهيئة من الصفر**:
     *
     * 1. تطبيع امتدادات الملفات (`Inf` ← `inf`) بنسخةٍ لا تحذف الأصل.
     * 2. إنشاء `userdicos` لأن المكتبة تكتب فيه قواميس المستخدم.
     * 3. نسخ ملف مفتاح الحزمة `0x...` إلى مجلد الصوت نفسه؛ عقد
     *    MultiTTS يفحص مجلد ملف `.inf` بحثاً عنه، وهو مسار ملفٍ لا
     *    بيانات تسجيل.
     * 4. إكمال مفاتيح `.inf` الناقصة فقط (`speaker` و`ISOCode`
     *    و`NAME` و`freq`) بصيغة `KEY = "value"` التي يقرأها المحمّل.
     *
     * **لماذا لا يُولَّد `.inf` أو `.ini` أو `.cfg`:** عقد MultiTTS
     * يبني `.ini` من اسم `.inf`، ويجمع من كل قيمة مقتبسة فيه مسار
     * ملفٍ نسبيّ لينسخه إلى مجلد التهيئة. فملف `.inf` مصطنعٌ بمفاتيح
     * وصفية فقط لا يحمل أي مسار بيانات، فيُشغّل المكتبة على مجلدٍ
     * خالٍ من `.bvcu`/`.dat` فيفشل `nInit` — وهو جذر «فشل تهيئة
     * المحرك». الحزم الحقيقية تحمل مفاتيحها ومساراتها بنفسها.
     */
    fun ensureVoiceConfigs(baseDir: File) {
        if (!baseDir.exists() || !baseDir.isDirectory) return
        for (dir in completeVoiceDirs(baseDir)) {
            runCatching {
                normalizeExtensionsCase(dir)
                File(dir, "userdicos").mkdirs()
                copyRegistrationKeyInto(dir)
                val infFiles = dir.listFiles { f ->
                    f.isFile && f.extension.equals("inf", ignoreCase = true)
                } ?: emptyArray()
                for (inf in infFiles) {
                    completeInfProperties(inf)
                }
            }
        }
    }

    /** نسخ ملف المفتاح `0x...` إلى مجلد الصوت نفسه (عقد MultiTTS). */
    private fun copyRegistrationKeyInto(dir: File) {
        val hex = findHexRegistrationFile(dir) ?: return
        if (hex.parentFile?.canonicalPath == dir.canonicalPath) return
        val target = File(dir, hex.name)
        if (target.exists()) return
        runCatching { hex.copyTo(target, overwrite = false) }
    }

    /**
     * إكمال مفاتيح `.inf` الناقصة وحدها. وجود المفتاح أصلاً يمنع أي
     * كتابة، فلا يتغير محتوى حزمةٍ سليمة أبداً.
     */
    private fun completeInfProperties(inf: File) {
        val text = runCatching {
            inf.readText(StandardCharsets.ISO_8859_1)
        }.getOrNull() ?: return
        val pairs = readPairs(text)
        val speaker = firstValue(pairs, "speaker")
        val isoValue = firstValue(pairs, "isocode")
        val nameValue = firstValue(pairs, "name")
        val freqValue = firstValue(pairs, "freq")
        // المكتبة تقرأ `freq` وحده؛ فإن غاب ووُجد معدلٌ بصيغة أخرى
        // (`frequency`/`SampleRate`) اشتُقّ منه بدل افتراض 22k.
        val rateText = freqValue
            ?: firstValue(pairs, "samplerate", "frequency")

        val stem = inf.nameWithoutExtension
        val locale = resolveLocale(isoValue, speaker ?: stem, stem)
        val cleanSpeaker = cleanAcapelaSpeaker(speaker ?: stem).take(80)
        val isoCode = if (locale == Locale.ROOT) "" else isoCodeOf(locale)
        val freq = parseSampleRate(rateText)
            ?: sampleRateFromName(stem)
            ?: DEFAULT_SAMPLE_RATE

        val additions = buildString {
            if (speaker.isNullOrBlank()) {
                append("speaker = \"$cleanSpeaker\"\n")
            }
            if (isoValue.isNullOrBlank() && isoCode.isNotEmpty()) {
                append("ISOCode = \"$isoCode\"\n")
            }
            if (nameValue.isNullOrBlank()) {
                append("NAME = \"${asciiResourceName(cleanSpeaker)}\"\n")
            }
            if (freqValue.isNullOrBlank()) {
                append("freq = \"$freq\"\n")
            }
        }
        if (additions.isEmpty()) return
        val prefix = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
        runCatching {
            inf.appendText(prefix + additions, StandardCharsets.ISO_8859_1)
        }
    }

    /**
     * اسم موردٍ مطابق لعقد `[A-Za-z0-9_.-]{1,127}`: مكتبة MultiTTS
     * ترفض أي اسم فيه محارف غير لاتينية.
     */
    private fun asciiResourceName(raw: String): String {
        val cleaned = raw.map { c ->
            if (c.code in 32..126 && !c.isWhitespace()) c else '_'
        }.joinToString("").trim('_', '.', '-')
        return cleaned.take(127).ifBlank { "voice" }
    }

    /** معدل العينات من اسم ملف الصوت (`_22k_` ← 22050). */
    private fun sampleRateFromName(stem: String): Int? {
        val match = Regex(
            "(?:^|[_-])(\\d{2,3})k(?:[_-]|$)",
            RegexOption.IGNORE_CASE
        ).find(stem) ?: return null
        val khz = match.groupValues[1].toIntOrNull() ?: return null
        val rate = when (khz) {
            11 -> 11025
            22 -> 22050
            else -> khz * 1000
        }
        return rate.takeIf { it in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE }
    }

    /**
     * قراءة معدل العينات من `.inf` بمرونة: قد يرد عدداً صحيحاً
     * (`22050`) أو عشرياً (`22050.0`) أو بالكيلوهرتز (`22.0`).
     *
     * **ثوابت MultiTTS الحاكمة:** `22.0` ← `22050`، `44.0` ← `44100`،
     * `11.0` ← `11025`؛ وما دون `1000` يُضرَب بألف. القيمة الخام
     * `22` تعني 22 كيلوهرتز لا 22 هرتزاً.
     */
    private fun parseSampleRate(text: String?): Int? {
        val value = text?.trim()?.toDoubleOrNull() ?: return null
        if (value <= 0.0) return null
        val hz = when {
            value == 22.0 -> 22050.0
            value == 44.0 -> 44100.0
            value == 11.0 -> 11025.0
            value < 1000.0 -> value * 1000.0
            else -> value
        }
        return hz.roundToInt()
            .takeIf { it in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE }
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
        val sampleRate = parseSampleRate(rateText)
            ?: DEFAULT_SAMPLE_RATE
        val locale = resolveLocale(
            isoCode, finalName, cf.nameWithoutExtension
        )
        val display = displayNameOf(finalName, locale)
        val rawReg = firstValue(pairs, "registration")
        val rawParam = firstValue(pairs, "param")
        val parentDir = cf.parentFile ?: cf
        val reg = resolveRegistration(parentDir, rawReg, rawParam)

        return VoiceInfo(
            id = finalName,
            voiceName = finalName,
            displayName = display,
            locale = locale,
            sampleRate = sampleRate,
            dir = parentDir,
            rawSpeaker = rawSpeaker,
            configFile = cf,
            registration = reg
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
        val reg = resolveRegistration(dir, null, null)

        return VoiceInfo(
            id = voiceName,
            voiceName = voiceName,
            displayName = displayNameOf(voiceName, locale),
            locale = locale,
            dir = dir,
            registration = reg
        )
    }

    private fun diagLog(msg: String) {
        runCatching { Log.i("AcapelaManifest", msg) }
        runCatching { HostLog.i(HostProtocol.ENGINE_ACAPELA, msg) }
    }

    private fun maskContent(s: String): String {
        if (s.isEmpty()) return "(فارغ)"
        if (s.length <= 4) return "$s (طول=${s.length})"
        val head = s.take(2)
        val tail = s.takeLast(2)
        return "$head...$tail (طول=${s.length})"
    }

    private fun checkUtf8Validity(
        bytes: ByteArray
    ): Pair<Boolean, String?> {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            true to null
        } catch (t: Throwable) {
            false to "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun logCandidateFile(label: String, f: File) {
        val exists = f.exists()
        val existsStr = if (exists) "نعم" else "لا"
        val size = if (exists) {
            runCatching { f.length() }.getOrDefault(0L)
        } else {
            0L
        }
        val type = when {
            !exists -> "غير موجود"
            f.isDirectory -> "مجلد"
            f.isFile -> "ملف"
            else -> "آخر"
        }
        diagLog(
            "[مسار مرشح] $label | ${f.name} | موجود=$existsStr | " +
                "حجم=$size بايت | نوع=$type"
        )
    }

    private fun logCandidatePaths(dir: File) {
        logCandidateFile("voice dir", dir)
        dir.parentFile?.let { p ->
            logCandidateFile("parent dir", p)
        }
        val targets = listOf(
            "0x00169729",
            "config.yaml",
            "engines.yaml",
            "salma_lf.txt",
            "registration.txt",
            "voice.json",
            "config.json",
            "multivoice.json"
        )
        val dirsToCheck = listOfNotNull(dir, dir.parentFile)
        for (d in dirsToCheck) {
            val dLabel = if (d == dir) "voice_dir" else "parent_dir"
            for (name in targets) {
                logCandidateFile("$dLabel/$name", File(d, name))
            }
            d.listFiles { f ->
                f.isFile && f.extension.equals("inf", ignoreCase = true)
            }?.forEach { inf ->
                logCandidateFile("$dLabel/.inf", inf)
            }
            d.listFiles { f ->
                f.isFile && f.name.endsWith("_lf.txt", ignoreCase = true) &&
                    f.name != "salma_lf.txt"
            }?.forEach { lf ->
                logCandidateFile("$dLabel/*_lf.txt", lf)
            }
        }
    }

    fun findAllHexRegistrationFiles(dir: File): List<File> {
        val found = LinkedHashSet<File>()
        runCatching {
            dir.walkTopDown().maxDepth(3).forEach { f ->
                if (f.isFile && f.name.matches(HEX_REGISTRATION_REGEX)) {
                    found.add(f)
                }
            }
        }
        var cur: File? = dir.parentFile
        var depth = 0
        while (cur != null && depth < 5) {
            val p = cur
            runCatching {
                p.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.matches(HEX_REGISTRATION_REGEX)) {
                        found.add(f)
                    } else if (f.isDirectory) {
                        f.listFiles { sub ->
                            sub.isFile &&
                                sub.name.matches(HEX_REGISTRATION_REGEX)
                        }?.forEach { found.add(it) }
                    }
                }
            }
            cur = p.parentFile
            depth++
        }
        return found.toList()
    }

    /**
     * بيانات تسجيل الحزمة الممتدة (metadata) — **لا اسم ملف `0x...`**.
     *
     * المحرك الممتد `AcattsandroidService(audio, registration)` يقرأ هذه
     * السلسلة كبياناتٍ موقّعة؛ وتمرير اسم ملف المفتاح هنا يُفسد بناؤه.
     * لذلك يُرفض أي مرشح يطابق [HEX_REGISTRATION_REGEX]، ويُنسخ ملف
     * المفتاح نفسه إلى مجلد الصوت بواسطة [ensureVoiceConfigs].
     *
     * المرجع: MultiTTS يشترط `[A-Za-z0-9@._+-]{1,288}` لبيانات
     * التسجيل، ويقرأها من `voice.json` أو من `param` المفكوك.
     */
    fun resolveRegistration(
        dir: File,
        rawReg: String? = null,
        rawParam: String? = null
    ): String {
        diagLog("[تشخيص التسجيل] بدء فحص التسجيل لمجلد: ${dir.name}")
        logCandidatePaths(dir)

        val allHexCopies = findAllHexRegistrationFiles(dir)
        diagLog(
            "[نسخ المفتاح] عدد نسخ المفتاح المكتشفة: ${allHexCopies.size}"
        )
        allHexCopies.forEachIndexed { idx, f ->
            val sz = runCatching { f.length() }.getOrDefault(0L)
            diagLog(
                "[نسخة مفتاح #$idx] المجلد: ${f.parentFile?.absolutePath} | " +
                    "الاسم: ${f.name} | الحجم: $sz بايت"
            )
        }
        val pickedHex = findHexRegistrationFile(dir)
        diagLog(
            "[المفتاح المختار] الملف الذي اختاره findHexRegistrationFile: " +
                (pickedHex?.absolutePath ?: "لا يوجد")
        )

        rawReg?.let { value ->
            val trimmed = value.trim()
            val valid = isRegistrationMetadata(trimmed)
            diagLog("[فحص مباشر] rawReg: طول=${trimmed.length}, صالح=$valid")
            if (valid) {
                diagLog("[نتيجة التسجيل] تم اعتماد rawReg بنجاح")
                return trimmed
            }
        }

        findRegistrationInJson(dir)?.let {
            diagLog("[نتيجة التسجيل] تم اعتماد التسجيل من ملف JSON")
            return it
        }

        findRegistrationInHexFile(dir)?.let {
            diagLog("[نتيجة التسجيل] تم اعتماد التسجيل من محتوى ملف المفتاح")
            return it
        }

        findRegistrationInYaml(dir)?.let {
            diagLog("[نتيجة التسجيل] تم اعتماد التسجيل من ملف YAML")
            return it
        }

        val paramCandidate = rawParam?.takeIf { it.isNotBlank() }
            ?: findParamInJson(dir)
        if (!paramCandidate.isNullOrBlank()) {
            diagLog(
                "[فحص param] محاولة فك تشفير param " +
                    "(طول=${paramCandidate.length})"
            )
            val decoded = runCatching {
                AcapelaMetadataCodec.fromAssets().decode(paramCandidate)
            }.getOrNull()
            if (!decoded.isNullOrBlank()) {
                val parts = decoded.split(',')
                val meta = if (parts.size >= 3) {
                    parts[1].trim() + parts[2].trim()
                } else {
                    decoded.trim()
                }
                val valid = isRegistrationMetadata(meta)
                diagLog(
                    "[فحص param] الناتج المفكوك: طول=${meta.length}, " +
                        "صالح=$valid"
                )
                if (valid) {
                    diagLog("[نتيجة التسجيل] تم اعتماد التسجيل من param")
                    return meta
                }
            } else {
                diagLog("[فحص param] تعذّر فك تشفير param")
            }
        }

        readRegistrationTxt(dir)?.let {
            diagLog("[نتيجة التسجيل] تم اعتماد التسجيل من registration.txt")
            return it
        }
        dir.parentFile?.let { parent ->
            readRegistrationTxt(parent)?.let {
                diagLog(
                    "[نتيجة التسجيل] تم اعتماد التسجيل من " +
                        "registration.txt في المجلد الأب"
                )
                return it
            }
        }

        diagLog("[نتيجة التسجيل] لم يُعثر على بيانات تسجيل صالحة (empty)")
        return ""
    }

    /** عقد MultiTTS لبيانات التسجيل: محارف آمنة فقط بلا مسافات. */
    private fun isRegistrationMetadata(value: String): Boolean =
        value.isNotBlank() &&
            !value.matches(HEX_REGISTRATION_REGEX) &&
            value.matches(REGISTRATION_METADATA_REGEX)

    /** قراءة `registration.txt` إن كانت بيانات metadata لا اسم ملف. */
    private fun readRegistrationTxt(dir: File): String? {
        val regTxt = File(dir, "registration.txt")
        if (!regTxt.isFile) return null
        val text = runCatching {
            regTxt.readText(StandardCharsets.ISO_8859_1).trim()
        }.getOrNull().orEmpty()
        val valid = isRegistrationMetadata(text)
        diagLog(
            "[فحص registration.txt] في ${dir.name}: " +
                "طول=${text.length}, صالح=$valid"
        )
        return text.takeIf { valid }
    }

    private fun findParamInJson(dir: File): String? {
        val candidates = listOf("voice.json", "config.json", "multivoice.json")
        val dirs = listOfNotNull(dir, dir.parentFile)
        val regex = Regex("\"param\"\\s*:\\s*\"([^\"]+)\"")
        for (d in dirs) {
            for (name in candidates) {
                val f = File(d, name)
                if (f.isFile && f.exists()) {
                    val content = runCatching {
                        f.readText(StandardCharsets.UTF_8)
                    }.getOrNull()
                    if (content != null && content.contains("\"param\"")) {
                        val m = regex.find(content)
                        if (m != null) {
                            val p = m.groupValues[1]
                            diagLog(
                                "[فحص JSON] ${f.name} في ${d.name}: " +
                                    "طول param=${p.length}"
                            )
                            return p
                        }
                    }
                }
            }
        }
        return null
    }

    private fun findRegistrationInJson(dir: File): String? {
        val candidates = listOf("voice.json", "config.json", "multivoice.json")
        val dirs = listOfNotNull(dir, dir.parentFile)
        val regex = Regex("\"registration\"\\s*:\\s*\"([^\"]+)\"")
        for (d in dirs) {
            for (name in candidates) {
                val f = File(d, name)
                if (!f.isFile || !f.exists()) continue
                val content = runCatching {
                    f.readText(StandardCharsets.UTF_8)
                }.getOrNull() ?: continue
                if (!content.contains("\"registration\"")) continue
                val value = regex.find(content)?.groupValues
                    ?.getOrNull(1)?.trim().orEmpty()
                diagLog(
                    "[فحص JSON] ${f.name} في ${d.name}: " +
                        "طول registration=${value.length}"
                )
                if (isRegistrationMetadata(value)) return value
            }
        }
        return null
    }

    internal data class ParsedKeyCandidate(
        val token: String,
        val lineKey: String,
        val isPreferred: Boolean,
        val lineIndex: Int,
        val tokenIndex: Int
    )

    private fun stripMatchingQuotes(s: String): String {
        val t = s.trim()
        if ((t.startsWith('"') && t.endsWith('"') && t.length >= 2) ||
            (t.startsWith('\'') && t.endsWith('\'') && t.length >= 2)
        ) {
            return t.substring(1, t.length - 1).trim()
        }
        return t
    }

    internal fun maskFileShape(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            when {
                ch == '\r' -> {
                    if (i + 1 < raw.length && raw[i + 1] == '\n') {
                        // لا نطبع شيئاً لـ \r إن تلاها \n
                    } else {
                        sb.append('⏎')
                    }
                }
                ch == '\n' -> sb.append('⏎')
                ch in 'a'..'z' || ch in 'A'..'Z' -> sb.append('a')
                ch in '0'..'9' -> sb.append('9')
                ch.isLetter() -> sb.append('a')
                ch.isDigit() -> sb.append('9')
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    internal fun parseHexKeyTokens(
        rawText: String
    ): Pair<List<ParsedKeyCandidate>, Int> {
        val lines = rawText.lines()
        val candidates = mutableListOf<ParsedKeyCandidate>()
        for ((lineIdx, rawLine) in lines.withIndex()) {
            if (rawLine.trimStart().startsWith('#')) continue
            val withoutComment = rawLine.substringBefore('#')
            val trimmed = withoutComment.trim()
            if (trimmed.isEmpty()) continue

            val delimiterIdx = trimmed.indexOfAny(charArrayOf(':', '='))
            val (lineKey, rawValue) = if (delimiterIdx != -1) {
                trimmed.substring(0, delimiterIdx).trim() to
                    trimmed.substring(delimiterIdx + 1).trim()
            } else {
                "" to trimmed
            }

            val unquoted = stripMatchingQuotes(rawValue)
            if (unquoted.isEmpty()) continue

            val isPref = lineKey.contains("reg", ignoreCase = true) ||
                lineKey.contains("licen", ignoreCase = true)

            val rawTokens = if (unquoted.contains(' ') ||
                unquoted.contains('\t')
            ) {
                unquoted.split(Regex("\\s+"))
            } else {
                listOf(unquoted)
            }

            var tokIdx = 0
            for (rawTok in rawTokens) {
                val tok = stripMatchingQuotes(rawTok)
                if (isRegistrationMetadata(tok)) {
                    candidates.add(
                        ParsedKeyCandidate(
                            token = tok,
                            lineKey = lineKey,
                            isPreferred = isPref,
                            lineIndex = lineIdx,
                            tokenIndex = tokIdx
                        )
                    )
                }
                tokIdx++
            }
        }
        return candidates to lines.size
    }

    internal fun selectBestKeyCandidate(
        candidates: List<ParsedKeyCandidate>
    ): ParsedKeyCandidate? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        val preferred = candidates.filter { it.isPreferred }
        return if (preferred.isNotEmpty()) {
            preferred.maxWithOrNull(
                compareBy<ParsedKeyCandidate> { it.token.length }
                    .thenByDescending { -it.lineIndex }
                    .thenByDescending { -it.tokenIndex }
            )
        } else {
            candidates.maxWithOrNull(
                compareBy<ParsedKeyCandidate> { it.token.length }
                    .thenByDescending { -it.lineIndex }
                    .thenByDescending { -it.tokenIndex }
            )
        }
    }

    internal fun parseHexKeyContent(rawText: String): String? {
        if (rawText.isBlank()) return null
        val (candidates, _) = parseHexKeyTokens(rawText)
        return selectBestKeyCandidate(candidates)?.token
    }

    private fun findRegistrationInHexFile(dir: File): String? {
        val hexFile = findHexRegistrationFile(dir)
        if (hexFile == null) {
            diagLog(
                "[فحص المفتاح] findHexRegistrationFile أعاد null: " +
                    "لم يُعثر على ملف مفتاح"
            )
            return null
        }
        diagLog("[فحص المفتاح] فحص الملف: ${hexFile.absolutePath}")
        if (!hexFile.exists()) {
            diagLog(
                "[فحص المفتاح] رُفض: الملف غير موجود " +
                    "(${hexFile.absolutePath})"
            )
            return null
        }
        if (!hexFile.isFile) {
            diagLog(
                "[فحص المفتاح] رُفض: المسار ليس ملفاً عادياً " +
                    "(wrong directory / not a file)"
            )
            return null
        }

        var readEx: Throwable? = null
        val rawBytes = runCatching {
            hexFile.readBytes()
        }.onFailure { readEx = it }.getOrNull()

        if (rawBytes == null) {
            val errName = readEx?.javaClass?.simpleName ?: "Exception"
            val errMsg = readEx?.message ?: "unknown"
            diagLog(
                "[فحص المفتاح] رُفض: استثناء أثناء قراءة الملف " +
                    "(exception: $errName: $errMsg)"
            )
            return null
        }

        diagLog("[فحص المفتاح] عدد البايتات المقروءة: ${rawBytes.size} بايت")
        if (rawBytes.isEmpty()) {
            diagLog("[فحص المفتاح] رُفض: الملف فارغ (empty: 0 bytes)")
            return null
        }

        val (isUtf8, utf8Err) = checkUtf8Validity(rawBytes)
        diagLog(
            "[فحص المفتاح] هل المحتوى UTF-8 صالح: " +
                if (isUtf8) "نعم" else "لا ($utf8Err)"
        )

        val rawText = if (isUtf8) {
            String(rawBytes, StandardCharsets.UTF_8)
        } else {
            String(rawBytes, StandardCharsets.ISO_8859_1)
        }

        val maskedShape = maskFileShape(rawText)
        diagLog("[شكل ملف المفتاح المقنّع] $maskedShape")

        val keyLines = rawText.lines()
        val lineLengths = keyLines.mapIndexed { idx, line ->
            "L${idx + 1}:${line.length}"
        }.joinToString(", ")
        diagLog(
            "[أطوال أسطر المفتاح] عدد الأسطر: ${keyLines.size} | " +
                "الأطوال: $lineLengths"
        )

        val (candidates, linesCount) = parseHexKeyTokens(rawText)
        diagLog(
            "[تحليل المفتاح] الأسطر المفحوصة: $linesCount | " +
                "الرموز المطابقة: ${candidates.size}"
        )

        val chosen = selectBestKeyCandidate(candidates)
        if (chosen != null) {
            diagLog(
                "[تحليل المفتاح] السطر المختار=${chosen.lineIndex + 1}, " +
                    "الرمز المختار=${chosen.tokenIndex + 1}, " +
                    "طول=${chosen.token.length}, " +
                    "معاينة=${maskContent(chosen.token)}, " +
                    "المفتاح=${chosen.lineKey.ifEmpty { "(bare)" }}"
            )
            diagLog("[فحص المفتاح] تم قبول محتوى المفتاح بنجاح كبيانات تسجيل!")
            return chosen.token
        } else {
            diagLog(
                "[فحص المفتاح] رُفض: لم يتم العثور على أي رمز " +
                    "يطابق نمط بيانات التسجيل"
            )
            return null
        }
    }

    private val YAML_REGISTRATION_REGEX = Regex(
        "registration[\"']?\\s*[:=]\\s*[\"']?([^\"'\\r\\n#]+)[\"']?"
    )

    private fun findRegistrationInYaml(dir: File): String? {
        val candidates = listOf("config.yaml", "engines.yaml")
        val dirs = listOfNotNull(dir, dir.parentFile)
        for (d in dirs) {
            for (name in candidates) {
                val f = File(d, name)
                val exists = f.isFile && f.exists()
                diagLog(
                    "[فحص YAML] ${f.name} في ${d.name} | " +
                        "موجود=${if (exists) "نعم" else "لا"} | " +
                        "حجم=${if (exists) f.length() else 0} بايت"
                )
                if (!exists) continue
                var readEx: Throwable? = null
                val content = runCatching {
                    f.readText(StandardCharsets.UTF_8)
                }.onFailure { readEx = it }.getOrNull()
                if (content == null) {
                    diagLog(
                        "[فحص YAML] تعذّر قراءة ${f.name} " +
                            "(exception: ${readEx?.message})"
                    )
                    continue
                }
                val hasWord = content.contains("registration")
                diagLog(
                    "[فحص YAML] ${f.name}: يحتوي كلمة registration: " +
                        if (hasWord) "نعم" else "لا"
                )
                if (!hasWord) continue
                val match = YAML_REGISTRATION_REGEX.find(content)
                val matched = match != null
                diagLog(
                    "[فحص YAML] ${f.name}: تطابق نمط registration regex: " +
                        if (matched) "نعم" else "لا"
                )
                if (matched) {
                    val captured = match!!.groupValues.getOrNull(1)
                        ?.trim().orEmpty()
                    diagLog(
                        "[فحص YAML] ${f.name}: طول المجموعة الملتقطة: " +
                            "${captured.length} محرف"
                    )
                    val valid = isRegistrationMetadata(captured)
                    diagLog(
                        "[فحص YAML] ${f.name}: هل بيانات التسجيل صالحة: " +
                            if (valid) "نعم" else "لا"
                    )
                    if (valid) return captured
                }
            }
        }
        return null
    }

    /**
     * قائمة الأصوات المفروزة من كل المجلدات
     * والمجلدات الفرعية.
     *
     * **لا تُكتب أي ملفات هنا:** مكتبة Acapela الأصلية تبني مسار `.ini`
     * من اسم `.inf`، فأي `.inf` مُصطنع بلا `.ini` مقابل يُفسد تحميل
     * الصوت. التعداد قراءةٌ خالصة.
     *
     * هذه هي النواة المتزامنة (للاختبارات وسياقات الخيوط الخلفية فقط).
     * أي مستدعٍ على الخيط الرئيسي يستعمل [cachedVoices] بدلاً منها.
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
     * تعداد الأصوات مع تخزين مؤقت على [Dispatchers.IO].
     *
     * هو المسار المصرَّح به من الواجهة ومسار النطق: قراءة المانيفست تفتح
     * ملفات `.inf` وتشقّ شجرة المجلدات، فلا تُنفَّذ على الخيط الرئيسي
     * أبداً. النتيجة تُخزَّن لكل مجلد وتُبطَل عند تغيّر بصمة
     * [EmbeddedStorage.lastBumpTime] أو عند تجاوز [VOICES_CACHE_TTL_MS].
     */
    suspend fun cachedVoices(baseDir: File): List<VoiceInfo> =
        readVoicesCached(baseDir, VOICES_CACHE_TTL_MS, ::monotonicMs)

    /**
     * نسخة [cachedVoices] بحقن الساعة والمدة — تُستخدم في الاختبارات
     * لإثبات أن القراءة لا تتكرر وأن الإبطال يعمل، بلا انتظار فعلي.
     */
    internal suspend fun readVoicesCached(
        baseDir: File,
        ttlMs: Long,
        clockMs: () -> Long
    ): List<VoiceInfo> = withContext(Dispatchers.IO) {
        val key = baseDir.absolutePath
        voicesCacheMutex.withLock {
            val bump = EmbeddedStorage.lastBumpTime
            val now = clockMs()
            val hit = voicesCache[key]
            if (hit != null && hit.bumpTime == bump &&
                now - hit.stampMs in 0..ttlMs
            ) {
                hit.voices
            } else {
                val fresh = listVoices(baseDir)
                voicesCache[key] = VoicesCacheEntry(bump, now, fresh)
                fresh
            }
        }
    }

    /**
     * لقطة التعداد المخزَّن **بلا أي قراءة قرص** — آمنة على الخيط الرئيسي.
     *
     * [voices] آخر تعداد معروف، و[fresh] يعني مطابقته لبصمة
     * [EmbeddedStorage.lastBumpTime] وللحد الزمني. عند `fresh = false` لا
     * يجوز الاعتماد على اللقطة كمصدر حقيقة، بل يُشغَّل تعداد خلفي
     * ([cachedVoices]) ثم تُقرأ اللقطة من جديد.
     */
    data class VoicesSnapshot(
        val voices: List<VoiceInfo>,
        val fresh: Boolean
    )

    fun voicesSnapshot(baseDir: File): VoicesSnapshot =
        readVoicesSnapshot(baseDir, ::monotonicMs)

    /** نسخة [voicesSnapshot] بساعة محقونة — تُثبت الطزاجة في الاختبار. */
    internal fun readVoicesSnapshot(
        baseDir: File,
        clockMs: () -> Long
    ): VoicesSnapshot {
        val hit = voicesCache[baseDir.absolutePath]
            ?: return VoicesSnapshot(emptyList(), fresh = false)
        val age = clockMs() - hit.stampMs
        val fresh = hit.bumpTime == EmbeddedStorage.lastBumpTime &&
            age in 0..VOICES_CACHE_TTL_MS
        return VoicesSnapshot(hit.voices, fresh)
    }

    /**
     * إبطال كل النتائج المخزَّنة فوراً.
     *
     * يُستدعى صراحةً بعد الكتابة على القرص (استيراد/تنزيل/حذف) مع أن
     * [EmbeddedStorage.bumpVersion] يكفي وحده؛ الإبطال الصريح يحمي
     * الحالات التي تُغيّر الملفات ثم تقرأ قبل أن تُرفع البصمة.
     */
    fun invalidateVoicesCache() {
        voicesCache.clear()
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
     * تحويل رمز اللغة أو اسم الصوت إلى Locale مناسب مع **حفظ الدولة**.
     *
     * الدولة ليست ترفاً: المحرك الممتد يطلب ISO3 للغة **والدولة** معاً
     * (`nSetLanguage("ara", "SAU", "")`)، فإسقاط الدولة يُفشل التحميل.
     * المجهول يُعاد بـ[Locale.ROOT] (لا بالعربية) حتى لا يُصنَّف كل
     * صوتٍ عربياً فيُفشل فلترة اللغة.
     */
    private fun resolveLocale(
        isoCode: String?,
        voiceName: String,
        fileName: String? = null
    ): Locale {
        localeFromIso(isoCode)?.let { return it }

        for (candidate in listOfNotNull(fileName, voiceName)) {
            val lower = candidate.lowercase(Locale.ROOT)
            val full = NAME_LOCALE_REGEX.find(lower)
            if (full != null) {
                localeOf(
                    full.groupValues[1],
                    full.groupValues[2]
                )?.let { return it }
            }
            val match = NAME_PREFIX_REGEX.find(lower) ?: continue
            val lang = isoLanguageOf(match.groupValues[1]) ?: continue
            return localeWithDefaultCountry(lang)
        }

        val lowerName = voiceName.lowercase(Locale.ROOT)
        if (ARABIC_VOICE_NAMES.any { lowerName.contains(it) }) {
            return localeWithDefaultCountry("ar")
        }
        return Locale.ROOT
    }

    /** تحويل رمز ISO (`ar-sa` أو `ara-SAU` أو `Arabic`) إلى Locale. */
    private fun localeFromIso(isoCode: String?): Locale? {
        val normalized = isoCode?.trim()?.lowercase(Locale.ROOT)
            ?.replace('_', '-').orEmpty()
        if (normalized.isEmpty()) return null
        val head = normalized.substringBefore('-')
        val lang = isoLanguageOf(head) ?: return null
        val countryToken = normalized.substringAfter('-', "")
        return localeOf(lang, countryToken)
    }

    /** تركيب Locale من رمزَي لغةٍ ودولة، والدولة الافتراضية عند غيابها. */
    private fun localeOf(langToken: String, countryToken: String): Locale? {
        val lang = isoLanguageOf(langToken) ?: return null
        val country = countryIso2(countryToken)
            ?: DEFAULT_COUNTRY[lang].orEmpty()
        return if (country.isEmpty()) {
            Locale.forLanguageTag(lang)
        } else {
            Locale.Builder().setLanguage(lang).setRegion(country).build()
        }
    }

    private fun localeWithDefaultCountry(lang: String): Locale {
        val country = DEFAULT_COUNTRY[lang].orEmpty()
        return if (country.isEmpty()) {
            Locale.forLanguageTag(lang)
        } else {
            Locale.Builder().setLanguage(lang).setRegion(country).build()
        }
    }

    /** رمز لغةٍ ثنائي الحرف من رمز ISO-639 أو من اسم اللغة. */
    private fun isoLanguageOf(token: String): String? {
        val t = token.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return null
        LANGUAGE_NAMES[t]?.let { return it }
        if (t.length == 2 && t.all { it in 'a'..'z' }) return t
        return ISO3_TO_ISO1[t]
    }

    /** رمز دولةٍ ثنائي الحرف من `SA` أو `SAU`. */
    private fun countryIso2(token: String): String? {
        val t = token.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return null
        if (t.length == 2 && t.all { it in 'a'..'z' }) {
            return t.uppercase(Locale.ROOT)
        }
        return ISO3_COUNTRY_TO_ISO2[t]
    }

    /** رمز ISO ثنائي الحرفين `ll-CC` كما تقبله مكتبة Acapela. */
    private fun isoCodeOf(locale: Locale): String {
        val lang = isoLanguageOf(locale.language) ?: return ""
        val country = countryIso2(locale.country)
            ?: DEFAULT_COUNTRY[lang].orEmpty()
        if (country.isEmpty()) return ""
        return "$lang-${country.uppercase(Locale.ROOT)}"
    }
}
