package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
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

    /** امتداد ملف البيانات ← مفتاح التوجيه المقابل في `<stem>.ini`. */
    private val INI_KEY_BY_EXTENSION = linkedMapOf(
        "bvcu" to "FILEMAP",
        "dat" to "FILE",
        "bnf" to "BNF",
        "phm" to "PHM",
        "ivx" to "IVX",
        "mbr" to "MBR",
        "slv" to "SLV",
        "sll" to "SLL",
        "alf" to "ALF",
        "erf" to "ERF",
        "lpc" to "LPC",
        "ann" to "ANN",
        "pom" to "POM",
        "hpa" to "HPA",
        "stl" to "STL"
    )

    /**
     * المفاتيح التوجيهية المقبولة من `.inf` عند توليد `.ini`.
     *
     * **ليست كلها:** `PATH` و`MODE` لهما في المحمّل نمطان يحتاجان رمزاً
     * إضافياً بين المفتاح والقيمة (`PATH %s "%[^"]"`)، ولا يُخترع رمزٌ
     * مجهول؛ والمجلد ووضع الصوت يصلان أصلاً إلى `load_voice`.
     * و`ENDIAN_*` تصف ترتيب البايت لا ملفاً يُحمَّل.
     */
    private val INI_DIRECTIVE_KEYS = setOf(
        "FILE", "FILEMAP", "BNF", "PHM", "IVX", "MBR", "SLV", "SLL",
        "ALF", "ERF", "LPC", "ANN", "POM", "HPA", "STL", "BVCU"
    )

    /**
     * سطر توجيه: `KEY = "value"` أو `KEY "value"` أو `KEY "value" [ RAM ]`.
     * القيمة المقتبسة إلزامية، فالسطر بلا اقتباس ليس توجيهاً.
     */
    private val INI_DIRECTIVE_REGEX = Regex(
        "([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=\\s*)?\"([^\"]+)\"" +
            "\\s*(?:\\[\\s*([^\\]]*?)\\s*])?"
    )

    /** مرجع تبعية مُعلن في تهيئة ini أصلية (مثل PRO/SLV/NLP/ملف صوت). */
    data class IniDependency(
        val key: String,
        val rawValue: String,
        val relativePath: String
    )

    /** حالة تهيئة ini المكتشفة لحزمة/صوت محدد. */
    enum class IniDiscoveryState {
        /** لا يوجد أي ملف ini داخل الحزمة المحددة. */
        NO_INI,
        /** يوجد ini لكنه مختصر/مولّد/بديل أو غير متوافق مع الصوت المطلوب. */
        INCOMPATIBLE_INI,
        /** وُجدت تهيئة أصلية كاملة متوافقة قابلة للاعتماد. */
        AUTHENTIC,
        /** تعارض: أكثر من تهيئة أصلية متوافقة بلا حسم واضح. */
        CONFLICT
    }

    /** مرشح تهيئة ini مع تصنيفه وأسباب تقييمه. */
    data class IniCandidate(
        val file: File,
        val state: IniDiscoveryState,
        val reasons: List<String>,
        val identityScore: Int = 0
    )

    /**
     * نتيجة اكتشاف موحّدة لحزمة/صوت Acapela: تحدد جذر الحزمة ومجلد
     * الصوت وملف ini الأصلي المختار، أو سبب التعذّر بوضوح.
     */
    data class VoiceDiscoveryResult(
        val packageRoot: File,
        val voiceDir: File?,
        val iniFile: File?,
        val state: IniDiscoveryState,
        val reasons: List<String>,
        val candidates: List<IniCandidate> = emptyList()
    )

    /**
     * اكتشاف موحّد لتهيئة الصوت الأصلية داخل حزمة محددة بحدود واضحة.
     *
     * يبحث في [startDir] وبمستوى عمق محدود داخله فقط (لا كامل التخزين
     * ولا حزم أصوات أخرى)، ويصنّف المرشحين بالمحتوى والهوية ونسخة
     * البيانات. عند التعارض بين تهيئتين أصليتين متوافقتين دون حسم
     * يتوقف بتعارض واضح بدل الاختيار الأبجدي.
     */
    fun discoverVoiceConfig(
        startDir: File,
        expectedSpeakerHint: String? = null,
        maxDepth: Int = 6
    ): VoiceDiscoveryResult {
        val packageRoot = findPackageRoot(startDir)
        val reasons = mutableListOf<String>()
        val iniFiles = collectIniFiles(packageRoot, maxDepth)
        if (iniFiles.isEmpty()) {
            reasons += "لم يُعثر على أي ملف ini داخل الحزمة: " +
                packageRoot.absolutePath
            return VoiceDiscoveryResult(
                packageRoot = packageRoot,
                voiceDir = if (startDir.isDirectory) startDir else null,
                iniFile = null,
                state = IniDiscoveryState.NO_INI,
                reasons = reasons
            )
        }

        val hint = normalizeIdentity(expectedSpeakerHint)
        val candidates = iniFiles.map { classifyIniCandidate(it, hint) }
        val authentic = candidates.filter {
            it.state == IniDiscoveryState.AUTHENTIC
        }

        val selected: IniCandidate?
        val state: IniDiscoveryState
        when {
            authentic.isEmpty() -> {
                selected = null
                state = IniDiscoveryState.INCOMPATIBLE_INI
                reasons += "وُجدت ملفات ini لكنها مختصرة/مولّدة أو " +
                    "غير متوافقة مع الصوت المطلوب"
            }
            authentic.size == 1 -> {
                selected = authentic.first()
                state = IniDiscoveryState.AUTHENTIC
                reasons += "اختيرت تهيئة أصلية متوافقة وحيدة"
                reasons += selected.reasons
            }
            else -> {
                val resolved = resolveAuthenticConflict(authentic, hint)
                if (resolved.second != null) {
                    selected = resolved.second
                    state = IniDiscoveryState.AUTHENTIC
                    reasons += resolved.first
                    reasons += selected!!.reasons
                } else {
                    selected = null
                    state = IniDiscoveryState.CONFLICT
                    reasons += resolved.first
                    reasons += authentic.map { c ->
                        "مرشح متعارض: ${c.file.absolutePath}"
                    }
                }
            }
        }

        val voiceDir = selected?.file?.parentFile
            ?: bestVoiceDirForHint(packageRoot, hint)
            ?: if (startDir.isDirectory) startDir else packageRoot
        return VoiceDiscoveryResult(
            packageRoot = packageRoot,
            voiceDir = voiceDir,
            iniFile = selected?.file,
            state = state,
            reasons = reasons.distinct(),
            candidates = candidates
        )
    }

    /**
     * توافق خلفي: يختار تهيئة أصلية متوافقة من [dir] أو شجرته المحدودة.
     * لا يعيد ملفات مولّدة/غير متوافقة، ولا يختار أبجدياً عند التعارض.
     */
    fun findAuthenticIni(dir: File): File? {
        if (!dir.exists()) return null
        val result = runCatching {
            discoverVoiceConfig(dir, expectedSpeakerHint = dir.name)
        }.getOrNull() ?: return null
        return result.iniFile
    }

    /** جذر الحزمة المحددة: ابن مباشر لجذر Acapela يضم [startDir]،
     * أو [startDir]. */
    private fun findPackageRoot(startDir: File): File {
        val base = if (startDir.isDirectory) startDir
        else (startDir.parentFile ?: startDir)
        val acapelaRoot = runCatching { findAcapelaRootDir(base) }
            .getOrNull()
        if (acapelaRoot != null) {
            val aPath = runCatching { acapelaRoot.canonicalPath }
                .getOrDefault(acapelaRoot.absolutePath)
            val bPath = runCatching { base.canonicalPath }
                .getOrDefault(base.absolutePath)
            if (aPath != bPath) {
                var cur: File? = base
                while (cur != null) {
                    val p = cur.parentFile ?: break
                    val pPath = runCatching { p.canonicalPath }
                        .getOrDefault(p.absolutePath)
                    if (pPath == aPath) return cur
                    cur = p
                }
            }
        }
        return base
    }

    /** جمع ملفات ini داخل [root] بعمق محدود، دون الخروج عن حدوده. */
    private fun collectIniFiles(root: File, maxDepth: Int): List<File> {
        if (!root.exists()) return emptyList()
        val rootPath = runCatching { root.canonicalPath }
            .getOrDefault(root.absolutePath)
        val out = mutableListOf<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth || !dir.isDirectory) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                val fPath = runCatching { f.canonicalPath }
                    .getOrDefault(f.absolutePath)
                if (!fPath.startsWith(rootPath)) continue
                if (f.isDirectory) {
                    walk(f, depth + 1)
                } else if (f.isFile &&
                    f.extension.equals("ini", ignoreCase = true)
                ) {
                    out += f
                }
            }
        }
        if (root.isFile) {
            if (root.extension.equals("ini", ignoreCase = true)) out += root
        } else {
            walk(root, 0)
        }
        return out.distinctBy {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
        }
    }

    private fun normalizeIdentity(hint: String?): String =
        hint?.removePrefix("acapela_embedded:")
            ?.removePrefix("acapela:")
            ?.substringBefore('(')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()

    /** رموز هوية أصوات معروفة لمنع اختيار تهيئة صوت آخر. */
    private val KNOWN_VOICE_TOKENS = setOf(
        "salma", "leila", "layla", "heather", "mehdi", "nizar",
        "youssef", "tarik", "mariam", "antoine", "alice", "ryan",
        "laura", "julia", "claire", "zoe", "peter", "kenny"
    )

    private fun tokenOfIdentity(identity: String): String? {
        val id = identity.lowercase(Locale.ROOT)
        return KNOWN_VOICE_TOKENS.firstOrNull { id.contains(it) }
    }

    /** تصنيف مرشح ini بالمحتوى والهوية (لا بالحجم وحده). */
    private fun classifyIniCandidate(
        file: File,
        expectedIdentity: String
    ): IniCandidate {
        val reasons = mutableListOf<String>()
        val name = file.name.lowercase(Locale.ROOT)
        val path = file.absolutePath.lowercase(Locale.ROOT)
        if (!file.isFile || file.length() <= 0L) {
            return IniCandidate(
                file, IniDiscoveryState.INCOMPATIBLE_INI,
                listOf("ملف ini فارغ أو غير قابل للقراءة: ${file.name}")
            )
        }
        val text = runCatching {
            file.readText(StandardCharsets.ISO_8859_1)
        }.getOrNull()
        if (text.isNullOrBlank()) {
            return IniCandidate(
                file, IniDiscoveryState.INCOMPATIBLE_INI,
                listOf("تعذرت قراءة محتوى ini: ${file.name}")
            )
        }

        val substitute = isSubstituteIniContent(text)
        if (substitute) {
            reasons += "تهيئة مختصرة/مولّدة/بديلة أو غير متوافقة " +
                "(لا تُعتمد بديلاً عن التهيئة الأصلية الكاملة)"
        }
        val substantial = hasSubstantialVoiceDirectives(text)
        if (!substantial && !substitute) {
            reasons += "لا تحتوي توجيهات تهيئة أصلية كافية مطلوبة للصوت"
        }

        val expectedToken = tokenOfIdentity(expectedIdentity)
        val fileTokens = KNOWN_VOICE_TOKENS.filter {
            name.contains(it) || path.contains(it)
        }.toSet()
        val contentTokens = KNOWN_VOICE_TOKENS.filter {
            text.lowercase(Locale.ROOT).contains(it)
        }.toSet()
        val allTokens = fileTokens + contentTokens
        if (expectedToken != null) {
            val foreign = allTokens - expectedToken
            if (foreign.isNotEmpty() && expectedToken !in allTokens) {
                reasons += "التهيئة تخص صوتاً آخر " +
                    "(${foreign.joinToString()}) " +
                    "لا الصوت المطلوب ($expectedToken)"
                return IniCandidate(
                    file, IniDiscoveryState.INCOMPATIBLE_INI, reasons
                )
            }
            if (foreign.isNotEmpty() && expectedToken in allTokens) {
                reasons += "التهيئة تخلط هوية الصوت المطلوب مع " +
                    "(${foreign.joinToString()})"
                return IniCandidate(
                    file, IniDiscoveryState.INCOMPATIBLE_INI, reasons
                )
            }
        } else if (fileTokens.size > 1) {
            reasons += "هوية صوت غير محددة في " +
                "المرشح: ${fileTokens.joinToString()}"
        }

        val incompatible = substitute || !substantial
        if (incompatible) {
            return IniCandidate(
                file, IniDiscoveryState.INCOMPATIBLE_INI, reasons
            )
        }

        var score = 0
        if (expectedToken != null) {
            if (name.contains(expectedToken)) score += 5
            if (path.contains(expectedToken)) score += 3
            if (contentTokens.contains(expectedToken)) score += 4
        }
        score += dataVersionAffinityScore(file, expectedToken)
        reasons += "تهيئة أصلية بمحتوى وتوجيهات صوتية فعلية"
        return IniCandidate(
            file, IniDiscoveryState.AUTHENTIC, reasons, score
        )
    }

    /**
     * هل النص يحوي توجيهات تهيئة أصلية جوهرية مطلوبة للصوت؟
     * بديل MODE/FILEMAP وحدهما (أو FILEMAP بلا بنية أصلية) لا يُعدّ
     * تهيئة أصلية كاملة متوافقة.
     */
    private fun hasSubstantialVoiceDirectives(text: String): Boolean {
        val hasPro = Regex("""(?im)^\s*PRO\b""").containsMatchIn(text)
        val hasSlv = Regex("""(?im)^\s*SLV\b""").containsMatchIn(text)
        val hasNlpRef = text.contains("NLP/", ignoreCase = true) ||
            text.contains("NLP\\", ignoreCase = true)
        if (hasPro || hasSlv || hasNlpRef) return true
        // توجيهات لغوية/صوتية غنية (ليست MODE/FILEMAP/FILE وحدها).
        val richKeys = listOf(
            "BNF", "PHM", "IVX", "MBR", "ALF", "ERF", "LPC", "ANN",
            "POM", "HPA", "STL", "DIC", "DCA", "PITCH", "HMM", "DUR",
            "SEL", "F0", "LEX", "GRI", "FST", "SLL", "DCT", "PROSODY"
        )
        return richKeys.any { k ->
            Regex("""(?im)^\s*$k\b""").containsMatchIn(text)
        }
    }

    /**
     * هل يمثل المحتوى تهيئة بديلة/مختصرة/مولّدة غير متوافقة
     * (مثل MODE/FILEMAP وحدهما) لا تُعتمد بديلاً عن الأصلية؟
     */
    private fun isSubstituteIniContent(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val hasMode = Regex("""(?im)^\s*#?\s*MODE\s*=""")
            .containsMatchIn(trimmed) ||
            trimmed.contains("MODE=", ignoreCase = true)
        val hasFileMap = Regex("""(?im)^\s*#?\s*\d*\s*FILEMAP\b""")
            .containsMatchIn(trimmed) ||
            trimmed.contains("FILEMAP", ignoreCase = true)
        val hasFileOnlyMap = Regex("""(?im)^\s*#?\s*\d*\s*FILE\b""")
            .containsMatchIn(trimmed)
        val hasPro = Regex("""(?im)^\s*PRO\b""").containsMatchIn(trimmed)
        val hasSlv = Regex("""(?im)^\s*SLV\b""").containsMatchIn(trimmed)
        val hasNlp = trimmed.contains("NLP/", ignoreCase = true) ||
            trimmed.contains("NLP\\", ignoreCase = true)
        val hasOriginalSignals = hasPro || hasSlv || hasNlp
        val substantial = hasSubstantialVoiceDirectives(trimmed)
        // MODE/FILEMAP/FILE-ربط دون توجيهات أصلية جوهرية = بديل/غير متوافق.
        if ((hasMode || hasFileMap || hasFileOnlyMap) &&
            !hasOriginalSignals && !substantial
        ) {
            return true
        }
        // تهيئة تفتقر كلياً للتوجيهات الجوهرية تُعدّ ناقصة/غير متوافقة.
        return !substantial
    }

    /**
     * تقارب نسخة البيانات: يفضّل ini التي تتطابق تسميتها/موقعها مع
     * ملفات بيانات الصوت الظاهرة (bvcu/dat) في مجلدها/الحزمة.
     */
    private fun dataVersionAffinityScore(
        ini: File,
        expectedToken: String?
    ): Int {
        val dir = ini.parentFile ?: return 0
        val stem = ini.nameWithoutExtension.lowercase(Locale.ROOT)
        var score = 0
        val data = dir.listFiles { f ->
            f.isFile && f.extension.lowercase(Locale.ROOT) in DATA_EXTENSIONS
        }.orEmpty()
        if (data.any {
                stem.contains(
                    it.nameWithoutExtension.lowercase(Locale.ROOT)
                ) ||
                it.nameWithoutExtension.lowercase(Locale.ROOT).contains(
                    stem.take(12)
                )
        }) score += 3
        if (expectedToken != null && dir.name.lowercase(Locale.ROOT)
                .contains(expectedToken)
        ) score += 2
        // وجود تبعيات معلنة قابلة للحل من نفس المجلد مؤشر نسخة متطابقة.
        val deps =
            runCatching { parseIniDependencies(ini) }.getOrDefault(emptyList())
        if (deps.isNotEmpty()) {
            val resolved = deps.count {
                resolveRelativeFile(dir, it.relativePath) != null
            }
            if (resolved == deps.size && resolved > 0) score += 4
            else if (resolved > 0) score += 1
        }
        return score
    }

    /** حسم التعارض بين تهيئات أصلية: تطابق هوية/نسخة، وإلا تعارض واضح. */
    private fun resolveAuthenticConflict(
        candidates: List<IniCandidate>,
        expectedIdentity: String
    ): Pair<String, IniCandidate?> {
        val maxScore = candidates.maxOf { it.identityScore }
        val top = candidates.filter { it.identityScore == maxScore }
        if (top.size == 1 && maxScore > 0) {
            return "اختيرت التهيئة الأكثر تطابقاً مع هوية الصوت " +
                "ونسخة بياناته" to top.first()
        }
        val expectedToken = tokenOfIdentity(expectedIdentity)
        if (expectedToken != null) {
            val byName = top.filter {
                it.file.name.lowercase(Locale.ROOT).contains(expectedToken)
            }
            if (byName.size == 1) {
                return "اختيرت التهيئة المطابقة لهوية الصوت ونسخة " +
                    "بياناته (بالاسم والمحتوى)" to byName.first()
            }
        }
        // تطابق تام في المحتوى؟ لا تعارض فعلي.
        val byContent = top.groupBy { c ->
            runCatching { fingerprintContent(c.file) }.getOrDefault(c.file.name)
        }
        if (byContent.size == 1) {
            return "التهيئات الأصلية المتعددة متطابقة المحتوى؛ " +
                "اختيرت إحداها حتماً" to top.first()
        }
        return "تعارض: وُجدت أكثر من تهيئة أصلية متوافقة داخل الحزمة " +
            "دون حسم آمن لهوية الصوت/نسخة البيانات" to null
    }

    private fun fingerprintContent(file: File): String = runCatching {
        file.readText(StandardCharsets.ISO_8859_1)
            .replace("\r\n", "\n").trim()
    }.getOrDefault("")

    private fun bestVoiceDirForHint(
        packageRoot: File,
        hint: String
    ): File? {
        val token = tokenOfIdentity(hint) ?: return null
        return runCatching {
            packageRoot.walkTopDown().maxDepth(4).firstOrNull { f ->
                f.isDirectory &&
                    f.name.lowercase(Locale.ROOT).contains(token)
            }
        }.getOrNull()
    }

    /**
     * استخراج تبعيات الملفات المُعلنة في تهيئة ini أصلية. لا تُخمَّن
     * توجيهات ولا مسارات؛ تُقرأ القيم المقتبسة كما وردت مع تطبيع الفواصل.
     */
    fun parseIniDependencies(iniFile: File): List<IniDependency> {
        if (!iniFile.isFile) return emptyList()
        val text = runCatching {
            iniFile.readText(StandardCharsets.ISO_8859_1)
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<IniDependency>()
        INI_DIRECTIVE_REGEX.findAll(text).forEach { m ->
            val key = m.groupValues[1].trim().uppercase(Locale.ROOT)
            val raw = m.groupValues[2].trim()
            if (key.isEmpty() || raw.isEmpty()) return@forEach
            val rel = raw.replace('\\', '/').trimStart('/')
            if (rel.isEmpty()) return@forEach
            val looksLikePath = rel.contains('/') || rel.contains('.') ||
                key in INI_DIRECTIVE_KEYS
            if (looksLikePath) {
                out += IniDependency(
                    key = key,
                    rawValue = raw,
                    relativePath = rel
                )
            }
        }
        return out.distinctBy {
            "${it.key}|${it.relativePath.lowercase(Locale.ROOT)}"
        }
    }

    /**
     * إيجاد ملف داخل [baseDir] لمسار نسبي مع مراعاة اختلاف حالة الأحرف
     * وفواصل المسار دون تخمين مسارات بديلة ودون الخروج عن [baseDir].
     * يحترم دلالة المسار كما ورد (بما فيه `..`) بشرط البقاء داخل الحد.
     */
    fun resolveRelativeFile(baseDir: File, relativePath: String): File? {
        if (!baseDir.exists()) return null
        val basePath = runCatching { baseDir.canonicalPath }
            .getOrDefault(baseDir.absolutePath)
        fun withinBase(f: File): File? {
            val p = runCatching { f.canonicalPath }
                .getOrDefault(f.absolutePath)
            val inside = p == basePath ||
                p.startsWith(basePath + File.separator)
            return if (inside) f else null
        }

        val cleaned = relativePath.replace('\\', '/').trim()
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        // محاولة مباشرة (تحافظ على المسار كما ورد قدر الإمكان).
        val direct = File(baseDir, cleaned)
        if (direct.exists()) {
            return withinBase(direct)?.takeIf { it.exists() }
        }

        // مطابقة مكوّنات مع تجاهل حالة الأحرف، مع دعم "." و".."
        // والتحقق من البقاء داخل [baseDir] بعد كل خطوة.
        var current = baseDir
        for (part in parts) {
            if (part == ".") continue
            if (part == "..") {
                val parent = current.parentFile ?: return null
                val bounded = withinBase(parent) ?: return null
                current = bounded
                continue
            }
            if (!current.isDirectory) return null
            val exact = File(current, part)
            current = if (exact.exists()) {
                exact
            } else {
                current.listFiles()?.firstOrNull {
                    it.name.equals(part, ignoreCase = true)
                } ?: return null
            }
            if (withinBase(current) == null) return null
        }
        return withinBase(current)?.takeIf { it.exists() }
    }

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
        "[A-Za-z0-9@._+,-]{1,288}"
    )

    /**
     * البحث عن ملف ترخيص Acapela المسمى بصيغة 0x...
     * في مجلد الصوت أو المجلدات الأب حتى 4 مستويات.
     */
    fun findHexRegistrationFile(dir: File): File? {
        if (!dir.exists()) return null
        val direct = dir.listFiles { f ->
            f.isFile && f.name.matches(HEX_REGISTRATION_REGEX) &&
                f.length() > 0L
        }?.firstOrNull()
        if (direct != null) return direct

        var cur = dir.parentFile
        var depth = 0
        while (cur != null && depth < 4) {
            val parentFile = cur.listFiles { f ->
                f.isFile && f.name.matches(HEX_REGISTRATION_REGEX) &&
                    f.length() > 0L
            }?.firstOrNull()
            if (parentFile != null) return parentFile
            cur = cur.parentFile
            depth++
        }

        val subFiles = dir.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (sub in subFiles) {
            val subHex = sub.listFiles { f ->
                f.isFile && f.name.matches(HEX_REGISTRATION_REGEX) &&
                    f.length() > 0L
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
     * هل يحوي المجلد صوت Acapela بتهيئة أصلية متوافقة قابلة للاعتماد؟
     * (لا يكفي وجود ملف بيانات أو تهيئة بديلة/مختصرة/غير متوافقة).
     */
    fun isCompleteVoiceDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val discovery = runCatching {
            discoverVoiceConfig(
                dir,
                expectedSpeakerHint = dir.name,
                maxDepth = 4
            )
        }.getOrNull() ?: return false
        if (discovery.state != IniDiscoveryState.AUTHENTIC) return false
        val iniDir = discovery.iniFile?.parentFile ?: return false
        val dPath = runCatching { dir.canonicalPath }
            .getOrDefault(dir.absolutePath)
        val iPath = runCatching { iniDir.canonicalPath }
            .getOrDefault(iniDir.absolutePath)
        return iPath == dPath || iPath.startsWith(dPath + File.separator)
    }

    /**
     * مجلدات الأصوات التي تملك تهيئة أصلية متوافقة تحت المجلد الأساسي.
     */
    fun completeVoiceDirs(baseDir: File): List<File> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        val result = linkedSetOf<File>()
        val inis = collectIniFiles(baseDir, 6)
        for (ini in inis) {
            val c = classifyIniCandidate(ini, "")
            if (c.state != IniDiscoveryState.AUTHENTIC) continue
            val vDir = ini.parentFile ?: continue
            val hint = voiceHintFromIni(ini, vDir)
            val d = discoverVoiceConfig(
                vDir,
                expectedSpeakerHint = hint,
                maxDepth = 2
            )
            if (d.state == IniDiscoveryState.AUTHENTIC &&
                d.iniFile?.absolutePath == ini.absolutePath
            ) {
                result.add(vDir)
            }
        }
        return result.distinctBy {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
        }
    }

    /** تلميح هوية الصوت المستخلص من اسم/محتوى ini ومجلده. */
    private fun voiceHintFromIni(ini: File, dir: File): String {
        val text = runCatching {
            ini.readText(StandardCharsets.ISO_8859_1)
        }.getOrDefault("")
        val lower = (ini.name + " " + dir.name + " " + text.take(4000))
            .lowercase(Locale.ROOT)
        return KNOWN_VOICE_TOKENS.firstOrNull { lower.contains(it) }
            ?: dir.name
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
        if (isFabricatedIni(f)) return false
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
                t.startsWith("#", ignoreCase = true) ||
                t.startsWith("PRO", ignoreCase = true) ||
                t.startsWith("SLV", ignoreCase = true) ||
                t.contains(".bvcu", ignoreCase = true) ||
                t.contains(".dat", ignoreCase = true)
        }
    }

    /**
     * تهيئة ini مصطنعة/بديلة/مختصرة (مثل MODE+FILEMAP فقط) لا تُعدّ
     * دليلاً على صحة الصوت ولا يجوز توليدها أو حشوها أو الاعتماد عليها.
     * الكشف بالمحتوى لا بالحجم.
     */
    internal fun isFabricatedIni(file: File): Boolean {
        if (!file.isFile) return false
        if (!file.extension.equals("ini", ignoreCase = true)) return false
        val text = runCatching {
            file.readText(StandardCharsets.ISO_8859_1)
        }.getOrNull() ?: return true
        return isSubstituteIniContent(text)
    }

    /**
     * هل هذا `.ini` تهيئة أصلية صالحة قابلة للاعتماد (لا مصطنعة/بديلة)؟
     * لا يُحكم بالحجم، ولا يُستبدل بملف صوت آخر.
     */
    internal fun isAuthenticIni(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val text = runCatching {
            file.readText(StandardCharsets.ISO_8859_1)
        }.getOrNull() ?: return false
        if (text.isBlank()) return false
        if (isSubstituteIniContent(text)) return false
        return hasSubstantialVoiceDirectives(text)
    }

    /** وجود تهيئة أصلية متوافقة قابلة للاعتماد في مجلد/نطاق الصوت. */
    fun hasVerbatimOriginalConfig(dir: File): Boolean =
        discoverVoiceConfig(dir, expectedSpeakerHint = dir.name, maxDepth = 4)
            .state == IniDiscoveryState.AUTHENTIC

    /** ممنوع تغيير حالة أحرف المسارات/الامتدادات أو تعديل المصدر. */
    fun normalizeExtensionsCase(@Suppress("UNUSED_PARAMETER") dir: File) {
        // متعمّد: دون تغيير حالة أحرف أو نسخ/تعديل (عقد req 3).
    }

    /**
     * إزالة ملفات التهيئة **المصطنعة** التي ثُبِت أنها بلا أي توجيه
     * صوتي، وعند وجود ملف `.inf` غنيّ سليم في المجلد نفسه فقط.
     *
     * **لماذا الحذف لا الكتابة هنا:** المكتبة الأصلية تبني مسار `.ini`
     * من اسم `.inf`؛ فإن سبق سجلٌّ مصطنع (speaker فقط، بلا FILE/FILEMAP)
     * الصوتَ الحقيقي في `find_voices` فشل تحميل الصوت بصمت. وكانت
     * النسخة السابقة **تكتب** تهيئة مصطنعة وتستبدل الصالح، فتفقد
     * الصوت توجيهه — وهو مسار الصمت الأقوى. هنا قراءةٌ للتمييز
     * وحذفٌ للسجل المصطنع فقط.
     *
     * **`.ini` لا يُحذف هنا أبداً:** صار ملفاً نافعاً يُولَّد عند غيابه
     * ([ensureIniFor])، وحذفُه يهدم التهيئة التي يعتمد عليها المحمّل.
     *
     * **حماية الصوت العامل:** لا يُحذف شيء إذا لم يوجد `.inf` غنيّ في
     * المجلد نفسه.
     */
    /**
     * لا حذف ولا توليد ولا تعديل تهيئة: الاكتشاف الموحّد يصنّف
     * التهيئة المصطنعة/غير المتوافقة ويرفضها دون لمس الملفات.
     */
    fun pruneSyntheticConfigs(@Suppress("UNUSED_PARAMETER") baseDir: File) {
        // متعمّد: دون حذف/توليد/تعديل (عقد req 2).
    }
    /** ممنوع تسطيح المسارات أو تعديل المصدر/المرجع. */
    fun flattenNestedVoiceFiles(@Suppress("UNUSED_PARAMETER") dir: File) {
        // متعمّد: دون تسطيح أو نسخ/تعديل (عقد req 3).
    }


    /**
     * لا توليد/حشو/تعديل تسمية لملفات التهيئة: الاكتشاف الموحّد
     * يحدّد التهيئة الأصلية الكاملة المتوافقة أو يبلّغ بغيابها.
     * تُبقي الواجهة للتوافق دون أي كتابة.
     */
    fun ensureVoiceConfigs(@Suppress("UNUSED_PARAMETER") baseDir: File) {
        // متعمّد: دون توليد/حشو/تعديل تسمية/تسطيح/نسخ (عقد req 2,3).
    }

    /**
     * يُحظَر توليد أو حشو ملف ini بديل. واجهة توافق بلا كتابة.
     */
    internal fun ensureIniFor(@Suppress("UNUSED_PARAMETER") inf: File) {
        // لا توليد ولا حشو لملف ini بديل.
    }
    /** سطر توجيه في `<stem>.ini`: مفتاحٌ وقيمةٌ وقسمُ تحميل. */
    private data class IniEntry(
        val key: String,
        val value: String,
        val section: String
    )


    /** التوجيهات المقروءة من `.inf` والموجودة ملفاتها فعلاً. */
    private fun directivesFromInf(
        inf: File,
        dir: File
    ): List<IniEntry> {
        val text = runCatching {
            inf.readText(StandardCharsets.ISO_8859_1)
        }.getOrNull() ?: return emptyList()
        val seen = mutableSetOf<String>()
        val entries = mutableListOf<IniEntry>()
        for (line in text.lineSequence()) {
            for (match in INI_DIRECTIVE_REGEX.findAll(line)) {
                val key = match.groupValues[1].uppercase(Locale.ROOT)
                if (key !in INI_DIRECTIVE_KEYS) continue
                val value = resolveVoiceFile(dir, match.groupValues[2])
                    ?: continue
                if (!seen.add("$key|$value")) continue
                val section = match.groupValues[3].trim()
                    .ifEmpty { "RAM" }
                entries.add(IniEntry(key, value, section))
            }
        }
        return entries
    }

    /**
     * بديلٌ عند `.inf` بلا توجيهات: تُشتق التوجيهات من ملفات البيانات
     * الموجودة فعلاً، فتبقى التهيئة مطابقةً لما على القرص.
     */
    private fun entriesFromDataFiles(dir: File): List<IniEntry> =
        dir.walkTopDown().maxDepth(3)
            .filter { it.isFile && it.length() > 0L }
            .mapNotNull { file ->
                val key = INI_KEY_BY_EXTENSION[
                    file.extension.lowercase(Locale.ROOT)
                ] ?: return@mapNotNull null
                IniEntry(
                    key,
                    file.relativeTo(dir).path.replace('\\', '/'),
                    "RAM"
                )
            }
            .sortedBy { it.value.lowercase(Locale.ROOT) }
            .distinctBy { "${it.key}|${it.value}" }
            .toList()

    /**
     * مطابقة قيمة توجيهٍ بملفٍ موجود فعلاً داخل مجلد الصوت (بلا حساسية
     * لحالة الأحرف)، وإرجاع مسارٍ نسبيٍّ يفهمه المحمّل. القيمة التي لا
     * ملف لها تُسقط: تهيئةٌ تشير إلى ملف غائب تُفشل التحميل.
     */
    private fun resolveVoiceFile(dir: File, raw: String): String? {
        val cleaned = raw.trim().trim('"').replace('\\', '/')
            .removePrefix("./")
        if (cleaned.isEmpty()) return null
        if (File(dir, cleaned).isFile) return cleaned
        val wanted = cleaned.lowercase(Locale.ROOT)
        val wantedName = wanted.substringAfterLast('/')
        var byName: String? = null
        for (file in dir.walkTopDown().maxDepth(3)) {
            if (!file.isFile) continue
            val rel = file.relativeTo(dir).path.replace('\\', '/')
            if (rel.lowercase(Locale.ROOT) == wanted) return rel
            if (byName == null &&
                file.name.lowercase(Locale.ROOT) == wantedName
            ) {
                byName = rel
            }
        }
        return byName
    }

    /** ممنوع نسخ/تعديل ملفات المصدر (مفتاح التسجيل). */
    @Suppress("UNUSED_PARAMETER")
    private fun copyRegistrationKeyInto(dir: File) {
        // متعمّد: دون نسخ/تعديل المصدر (عقد req 3).
    }

    /** ممنوع حشو/تعديل تهيئة المصدر. */
    @Suppress("UNUSED_PARAMETER")
    private fun completeInfProperties(inf: File) {
        // متعمّد: دون حشو/تعديل المصدر (عقد req 2,3).
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
        val hexFile = findHexRegistrationFile(parentDir)
        val isValidRegistered = (sampleRate == 22050) &&
            (hexFile != null && hexFile.length() > 0L)
        val reg = if (isValidRegistered) {
            resolveRegistration(parentDir, rawReg, rawParam)
        } else {
            ""
        }

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
        val hexFile = findHexRegistrationFile(dir)
        val reg = if (hexFile != null && hexFile.length() > 0L) {
            resolveRegistration(dir, null, null)
        } else {
            ""
        }

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

    /**
     * تشخيص حالة ملفات الصوت الحرجة بعد الاستيراد:
     * - هل `config.yaml` موجود وعدد أجزاء `param` وأطوالها
     * - محتوى مقنَّع وحجم `salma_lf.txt`
     * - محتوى مقنَّع وحجم ملف `.inf`
     *
     * لا تُسجَّل قيم الإيميل أو الكود — الأطوال فقط.
     */
    fun logVoiceFilesDiagnostics(voiceDir: File) {
        diagLog("[تشخيص ملفات الصوت] المجلد: ${voiceDir.name}")
        diagLog("- اسم الحزمة المستعار: org.nobody.multitts")

        // config.yaml
        val foundYaml = findConfigYamlWithParam(voiceDir)
        val configYaml = foundYaml?.first ?: File(voiceDir, "config.yaml")
        val configExists = configYaml.isFile && configYaml.exists()
        diagLog(
            "[config.yaml] موجود=${if (configExists) "نعم" else "لا"}" +
                (if (configExists) {
                    " | مسار=${configYaml.absolutePath} | " +
                        "حجم=${configYaml.length()} بايت"
                } else {
                    ""
                })
        )
        if (foundYaml != null) {
            val rawParam = foundYaml.second
            val parts = rawParam.split(',')
            val lengths = parts.mapIndexed { i, p ->
                "جزء$i:${p.trim().length}"
            }.joinToString(", ")
            diagLog(
                "[config.yaml/param] عدد الأجزاء=${parts.size}" +
                    " | الأطوال=[$lengths]"
            )
        } else {
            diagLog("[config.yaml/param] سطر param غير موجود")
        }

        // salma_lf.txt أو أي *_lf.txt
        val lfFiles = voiceDir.listFiles { f ->
            f.isFile && f.name.endsWith("_lf.txt", ignoreCase = true)
        } ?: emptyArray()
        if (lfFiles.isEmpty()) {
            diagLog("[*_lf.txt] غير موجود")
        } else {
            for (lf in lfFiles) {
                val bytes = runCatching { lf.readBytes() }
                    .getOrDefault(ByteArray(0))
                val masked = maskBytes(bytes, 32)
                diagLog(
                    "[${lf.name}] حجم=${lf.length()} بايت" +
                        " | معاينة_مقنَّعة=$masked"
                )
            }
        }

        // ملف .inf
        val infFiles = voiceDir.listFiles { f ->
            f.isFile && f.extension.equals("inf", ignoreCase = true)
        } ?: emptyArray()
        if (infFiles.isEmpty()) {
            diagLog("[.inf] غير موجود")
        } else {
            for (inf in infFiles) {
                val bytes = runCatching { inf.readBytes() }
                    .getOrDefault(ByteArray(0))
                val masked = maskBytes(bytes, 48)
                diagLog(
                    "[${inf.name}] حجم=${inf.length()} بايت" +
                        " | معاينة_مقنَّعة=$masked"
                )
            }
        }
    }

    /**
     * معاينة مقنَّعة لأول [previewLen] بايت: الأحرف الأبجدية تُخفى
     * بـ`a`، الأرقام بـ`9`، علامات الترقيم والمسافات تُبقى كما هي.
     */
    private fun maskBytes(bytes: ByteArray, previewLen: Int): String {
        val preview = bytes.take(previewLen)
        val sb = StringBuilder()
        for (b in preview) {
            val c = b.toInt().and(0xFF).toChar()
            when {
                c.isLetter() -> sb.append('a')
                c.isDigit() -> sb.append('9')
                c == '\n' -> sb.append('↵')
                c == '\r' -> { /* تجاهل */ }
                else -> sb.append(c)
            }
        }
        val hidden = bytes.size - preview.size
        return if (hidden > 0) "$sb…(+$hidden)" else sb.toString()
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
                if (f.isFile && f.name.matches(HEX_REGISTRATION_REGEX) &&
                    f.length() > 0L
                ) {
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
                    if (f.isFile && f.name.matches(HEX_REGISTRATION_REGEX) &&
                        f.length() > 0L
                    ) {
                        found.add(f)
                    } else if (f.isDirectory) {
                        f.listFiles { sub ->
                            sub.isFile &&
                                sub.name.matches(HEX_REGISTRATION_REGEX) &&
                                sub.length() > 0L
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

        // 1. قراءة param من config.yaml أولاً واعتماده لتسجيل HD
        val yamlParam = readParamFromConfigYaml(dir)
        if (!yamlParam.isNullOrBlank()) {
            val regFromParam = extractRegistrationFromParam(yamlParam)
            if (isRegistrationMetadata(regFromParam)) {
                diagLog(
                    "[نتيجة التسجيل] تم اعتماد التسجيل من param في " +
                        "config.yaml (طول=${regFromParam.length})"
                )
                return regFromParam
            }
            val parsed = splitConfigYamlParam(yamlParam)
            if (parsed != null) {
                val (_, email, hexCode) = parsed
                val combined = "$email$hexCode"
                if (isRegistrationMetadata(combined)) {
                    diagLog(
                        "[نتيجة التسجيل] تم اعتماد التسجيل من param في " +
                            "config.yaml (طول=${combined.length})"
                    )
                    return combined
                }
            }
            if (isRegistrationMetadata(yamlParam)) {
                diagLog(
                    "[نتيجة التسجيل] تم اعتماد التسجيل من param كاملاً " +
                        "في config.yaml"
                )
                return yamlParam
            }
        }

        // 2. فحص registration الصريح في ملفات YAML
        findRegistrationInYaml(dir)?.let {
            diagLog("[نتيجة التسجيل] تم اعتماد التسجيل من ملف YAML")
            return it
        }

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

        // 3. ملف المفتاح 0x... (احتياطي ثانوي أخير فقط — بنمط صالح)
        findRegistrationInHexFile(dir)?.let { hexVal ->
            if (isRegistrationMetadata(hexVal)) {
                diagLog(
                    "[نتيجة التسجيل] تم اعتماد التسجيل من محتوى " +
                        "ملف المفتاح (احتياطي)"
                )
                return hexVal
            }
        }

        diagLog("[نتيجة التسجيل] لم يُعثر على بيانات تسجيل صالحة (empty)")
        return ""
    }

    /**
     * عقد MultiTTS لبيانات التسجيل: محارف آمنة فقط بلا مسافات،
     * وطول يكفي لأن يكون بيانات حقيقية (8 أحرف فأكثر)، ويحتوي
     * على حرف `@` أو `.` أو `-` أو `_` ليميّزها من رمز hex قصير
     * مثل `7e88`.
     *
     * **السبب:** ملف `0x...` قد يحتوي `7e88` فيتجاوز
     * [REGISTRATION_METADATA_REGEX] رغم أنه ليس بيانات تسجيل.
     * القيمة الصحيحة من MultiTTS تحتوي دائماً على `@` (إيميل)
     * أو فاصلة (email,code) أو `-` بمعنى ملحوظ.
     */
    fun isRegistrationMetadata(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.matches(HEX_REGISTRATION_REGEX)) return false
        if (value.startsWith("aljl", ignoreCase = true) &&
            value.endsWith("d1b7", ignoreCase = true)
        ) return true
        if (!value.matches(REGISTRATION_METADATA_REGEX)) return false
        if (value.length < 8) return false
        val hasStructure = value.contains('@') ||
            value.contains(',') ||
            (value.contains('.') && value.length >= 12) ||
            (value.contains('-') && value.length >= 12) ||
            (value.contains('_') && value.length >= 12) ||
            value.length >= 16
        return hasStructure
    }

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
        if (chosen != null && isRegistrationMetadata(chosen.token)) {
            diagLog(
                "[تحليل المفتاح] السطر المختار=${chosen.lineIndex + 1}, " +
                    "الرمز المختار=${chosen.tokenIndex + 1}, " +
                    "طول=${chosen.token.length}, " +
                    "معاينة=${maskContent(chosen.token)}, " +
                    "المفتاح=${chosen.lineKey.ifEmpty { "(bare)" }}"
            )
            diagLog(
                "[فحص المفتاح] تم قبول محتوى المفتاح بنجاح كبيانات تسجيل!"
            )
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
        val root = runCatching { findAcapelaRootDir(dir) }.getOrNull()
        val dirs = LinkedHashSet<File>().apply {
            add(dir)
            dir.parentFile?.let { add(it) }
            root?.let { add(it) }
        }
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
     * العثور على مجلد Acapela الأساسي صعوداً أو هبوطاً من [dir].
     */
    fun findAcapelaRootDir(dir: File): File {
        var cur: File? = dir
        while (cur != null) {
            if (cur.name.equals("Acapela", ignoreCase = true)) {
                return cur
            }
            cur = cur.parentFile
        }
        val sub = File(dir, "Acapela")
        if (sub.isDirectory) return sub
        return dir.parentFile ?: dir
    }

    /**
     * بحث متكرر عن ملف config.yaml داخل كل المجلدات والمجلدات الفرعية
     * تحت مجلد Acapela، واستخدام أول ملف يحتوي على سطر param.
     * يُعيد الزوج (الملف، قيمة param الخام).
     */
    fun findConfigYamlWithParam(dir: File): Pair<File, String>? {
        val root = findAcapelaRootDir(dir)
        val paramRegex = Regex(
            """(?:^|\n)\s*"?param"?\s*[:=]\s*"?([^"\r\n#]+)"?""",
            setOf(RegexOption.MULTILINE)
        )

        val candidates = LinkedHashSet<File>()
        listOfNotNull(dir, dir.parentFile, root).forEach { d ->
            val direct = File(d, "config.yaml")
            if (direct.isFile && direct.exists()) candidates.add(direct)
        }

        if (root.exists() && root.isDirectory) {
            runCatching {
                root.walkTopDown().maxDepth(6).forEach { f ->
                    if (f.isFile &&
                        f.name.equals("config.yaml", ignoreCase = true)
                    ) {
                        candidates.add(f)
                    }
                }
            }
        }

        if (dir.exists() && dir.isDirectory && dir != root) {
            runCatching {
                dir.walkTopDown().maxDepth(6).forEach { f ->
                    if (f.isFile &&
                        f.name.equals("config.yaml", ignoreCase = true)
                    ) {
                        candidates.add(f)
                    }
                }
            }
        }

        for (file in candidates) {
            if (!file.isFile || !file.exists()) continue
            val content = runCatching {
                file.readText(StandardCharsets.UTF_8)
            }.getOrNull() ?: continue
            val match = paramRegex.find(content) ?: continue
            val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (raw.isNotBlank()) {
                diagLog(
                    "[config.yaml] عُثر على param في ${file.absolutePath} " +
                        "(حجم=${file.length()} بايت، طول=${raw.length})"
                )
                return file to raw
            }
        }
        diagLog("[config.yaml] لم يُعثر على param في أي ملف config.yaml")
        return null
    }

    /**
     * بحث متكرر عن ملف config.yaml عبر مجموعة من مسارات البحث.
     */
    fun findConfigYamlWithParam(
        dirs: Collection<File>
    ): Pair<File, String>? {
        for (d in dirs) {
            val found = findConfigYamlWithParam(d)
            if (found != null) return found
        }
        return null
    }

    /**
     * قراءة سطر `param` من `config.yaml` عبر البحث المتكرر في شجرة Acapela،
     * وإرجاعه خاماً بلا فك تشفير.
     */
    fun readParamFromConfigYaml(voiceDir: File): String? =
        findConfigYamlWithParam(voiceDir)?.second

    /**
     * استخراج قيمة التسجيل من سطر param (مطابق تماماً لعقد MultiTTS):
     * voiceCode,email,hexCode -> email + hexCode (دمج مباشر بدون فاصلة)
     * مع فك التشفير أولاً إن كان مشفراً عبر AcapelaMetadataCodec.
     */
    fun extractRegistrationFromParam(
        rawParam: String,
        context: Context? = null
    ): String {
        val decoded = AcapelaMetadataCodec.resolveAcapelaParam(
            rawParam = rawParam,
            context = context
        )
        if (decoded.registration.isNotBlank()) {
            return decoded.registration
        }
        val clean = AcapelaMetadataCodec.trim(rawParam)
        val parts = clean.split(',').map { AcapelaMetadataCodec.trim(it) }
        if (parts.size == 3) {
            val email = parts[1]
            val hexCode = parts[2]
            return "$email$hexCode"
        }
        return clean
    }

    /**
     * تقسيم سطر `param` المكوّن من ثلاثة أجزاء مفصولة بفاصلة:
     * `voiceCode,email,hexCode16` — ويُعيد الأجزاء الثلاثة أو null
     * إن لم يتطابق الشكل.
     *
     * الصيغة المتوقعة:
     *   جزء = كود الصوت (مثل salma22k_hq)
     *   جزء = الإيميل
     *   جزء = كود 16 حرف hex
     */
    fun splitConfigYamlParam(
        raw: String
    ): Triple<String, String, String>? {
        val clean = raw.trim()
            .removeSurrounding("\"").removeSurrounding("'")
        val parts = clean.split(',').map {
            it.trim().removeSurrounding("\"").removeSurrounding("'")
        }
        if (parts.size < 3) return null
        val email = parts.firstOrNull { it.contains('@') }
        val hexCode = parts.firstOrNull {
            it.matches(Regex("^[0-9A-Fa-f]{16}$"))
        } ?: parts.lastOrNull { it != email }
        val voiceCode = parts.firstOrNull {
            it != email && it != hexCode
        } ?: parts[0]
        if (voiceCode.isNullOrBlank() ||
            email.isNullOrBlank() ||
            hexCode.isNullOrBlank()
        ) return null
        return Triple(voiceCode, email, hexCode)
    }

    /**
     * تصنيف نوع جزء من أجزاء سطر param:
     * بريد، كود صوت، كود 16 خانة، أو نوع آخر.
     */
    fun classifyParamPart(part: String): String {
        val trimmed = part.trim()
            .removeSurrounding("\"").removeSurrounding("'")
        return when {
            trimmed.contains('@') -> "بريد"
            trimmed.matches(Regex("^[0-9A-Fa-f]{16}$")) -> "كود 16 خانة"
            trimmed.contains("salma", ignoreCase = true) ||
                (trimmed.matches(Regex("^[A-Za-z0-9_]{3,30}$")) &&
                    !trimmed.contains('@')) -> "كود صوت"
            else -> "سلسلة محارف"
        }
    }

    /**
     * تعداد الأصوات ذات التهيئة الأصلية المتوافقة فقط (اكتشاف موحّد).
     * قراءة خالصة: لا توليد/حشو/تعديل/تسطيح/نسخ.
     */
    fun listVoices(baseDir: File): List<VoiceInfo> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        val dirs = completeVoiceDirs(baseDir)
        val result = mutableListOf<VoiceInfo>()
        for (dir in dirs) {
            val discovery = discoverVoiceConfig(
                dir, expectedSpeakerHint = dir.name, maxDepth = 4
            )
            if (discovery.state != IniDiscoveryState.AUTHENTIC) continue
            val ini = discovery.iniFile ?: continue
            val info = runCatching {
                val parsed = parseVoice(dir)
                parsed.copy(
                    dir = ini.parentFile ?: dir,
                    configFile = ini
                )
            }.getOrNull() ?: continue
            if (isNonVoiceConfig(info, dir)) continue
            result.add(info)
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
