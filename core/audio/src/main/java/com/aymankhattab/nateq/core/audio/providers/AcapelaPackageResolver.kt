package com.aymankhattab.nateq.core.audio.providers

import com.aymankhattab.nateq.core.audio.engine.AcapelaPerformanceCounters
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * محلل حزم أصوات Acapela الموحد بنطاق الحزمة (Package-Scoped Resolver).
 *
 * يعيد حزمة مترابطة ومتحققة للصوت المطلوب تتضمن:
 * 1. هوية الصوت (المتحدث) المستخلصة بدقة.
 * 2. ملف ini الأصلي وتوجيهاته وتحديد جذور التنفيذ بدقة:
 *    - جذر الحزمة (packageContainerRoot).
 *    - ملف ini الأصلي المعتمد (authenticIni).
 *    - قاعدة التبعيات النسبية (iniDependencyBase).
 *    - جذر محرك التنفيذ الأصلي (nativeEngineSearchRoot) الممرر إلى nInit.
 * 3. ملف metadata المحدد (config.yaml) إن وُجد وطابق المتحدث دون خلط.
 * 4. مراجع الدعم والترخيص الأصلية ومساراتها الشرعية (canonical).
 * 5. كشف التكرار والتهيئات الغامضة دون تفضيل عشوائي بترتيب المسح.
 * 6. التخزين المؤقت المربوط بمراجعة التثبيت ومسار الإلغاء الصريح.
 * 7. تهدئة الفشل المحدودة (failure cooldown) ومنع تكرار الإجراءات المتزامنة.
 */
object AcapelaPackageResolver {

    enum class PackageRelation {
        CO_LOCATED,
        NESTED,
        PARENT_CONTAINER
    }

    enum class ResolutionStatus {
        SUCCESS,
        FILE_NOT_FOUND,
        SPEAKER_MISMATCH,
        SUPPORT_REFERENCE_MISMATCH,
        MULTIPLE_CANDIDATES,
        UNSUPPORTED_FORMAT,
        METADATA_CONFLICT,
        NATIVE_FAILURE
    }

    data class ResolvedPackage(
        val status: ResolutionStatus,
        val packageContainerRoot: File,
        val authenticIni: File?,
        val iniDependencyBase: File?,
        val nativeEngineSearchRoot: File,
        val expectedSpeaker: String,
        val resolvedSpeaker: String?,
        val infFile: File?,
        val metadataYaml: File?,
        val hexFile: File?,
        val registration: String,
        val relation: PackageRelation?,
        val supportFiles: List<File>,
        val resolvedDependencies: List<File> = emptyList(),
        val missingDependencies: List<String> = emptyList(),
        val diagnosticReason: String
    ) {
        val isSuccess: Boolean
            get() = status == ResolutionStatus.SUCCESS

        /** توافق خلفي مع الكود القائم: يمثل جذر محرك التنفيذ الأصلي. */
        val voiceDir: File
            get() = nativeEngineSearchRoot

        /** توافق خلفي: يمثل ملف ini المعتمد. */
        val iniFile: File?
            get() = authenticIni
    }

    private val PARAM_REGEX = Regex(
        """(?:^|\n)\s*"?param"?\s*[:=]\s*"?([^"\r\n#]+)"?""",
        setOf(RegexOption.MULTILINE)
    )

    /**
     * الجذور العامة الممنوع اعتبارها حزمة مستقلة.
     * تم استبعاد "acapela" للسماح بحاويات أصوات صحيحة تحمل هذا الاسم.
     */
    private val GENERIC_ROOTS = setOf(
        "", "/", "\\", "c:", "c:\\", "storage", "emulated", "0",
        "android", "data", "app_voices", "files", "voice"
    )

    private const val MAX_PACKAGE_CACHE_SIZE = 50
    private const val MAX_COOLDOWN_CACHE_SIZE = 50

    private data class CacheKey(
        val canonicalPath: String,
        val expectedSpeaker: String,
        val revision: Long
    )

    private val packageCache =
        ConcurrentHashMap<CacheKey, ResolvedPackage>()

    private val inFlightLocks =
        ConcurrentHashMap<String, Any>()

    private data class FailureCooldown(
        val failedAtMs: Long,
        val backoffMs: Long,
        val revision: Long,
        val failedResult: ResolvedPackage
    )

    private val failureCooldowns =
        ConcurrentHashMap<String, FailureCooldown>()

    /**
     * إبطال صريح للذاكرة المؤقتة وفترات التهدئة عند تعديل/حذف/استيراد أصوات.
     */
    fun invalidateCache() {
        packageCache.clear()
        failureCooldowns.clear()
        inFlightLocks.clear()
    }

    /**
     * فحص أمان المسار الشرعي لمنع directory traversal أو الهروب من المجلد.
     */
    fun isSafeCanonicalChild(parent: File, child: File): Boolean {
        val parentPath = runCatching {
            parent.canonicalPath
        }.getOrNull() ?: return false
        val childPath = runCatching {
            child.canonicalPath
        }.getOrNull() ?: return false
        return childPath == parentPath ||
            childPath.startsWith(parentPath + File.separator)
    }

    /**
     * قراءة param من ملف YAML محدد فقط دون تسلق أو بحث عشوائي.
     */
    fun readParamFromYaml(yamlFile: File): String? {
        if (!yamlFile.isFile || !yamlFile.exists()) return null
        if (yamlFile.length() > 512 * 1024L) return null
        val content = runCatching {
            yamlFile.readText(StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        val match = PARAM_REGEX.find(content) ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.ifBlank { null }
    }

    /**
     * التحقق من تطابق هوية المتحدث المذكورة في param مع هوية الصوت المطلوب.
     */
    fun checkYamlSpeakerMatch(
        yamlFile: File,
        expectedSpeaker: String
    ): Boolean? {
        val rawParam = readParamFromYaml(yamlFile) ?: return null
        val parsed = AcapelaVoiceManifest.splitConfigYamlParam(rawParam)
            ?: return null
        val yamlSpeakerToken = AcapelaVoiceManifest
            .tokenOfIdentity(parsed.first) ?: return null
        val expectedToken = AcapelaVoiceManifest
            .tokenOfIdentity(expectedSpeaker) ?: return null
        return yamlSpeakerToken == expectedToken
    }

    /**
     * التحقق من توثيق ملكية الحاوية الأب للصوت المطلوب (عبر voicelist
     * أو ملف YAML خاص بالصوت).
     */
    private fun isParentContainerDocumentedForVoice(
        parentDir: File,
        voiceDir: File,
        expectedSpeaker: String
    ): Boolean {
        if (!parentDir.exists() || !parentDir.isDirectory) return false
        val parentCanonical = runCatching {
            parentDir.canonicalFile
        }.getOrNull() ?: return false
        if (parentCanonical.parentFile == null) return false
        val parentName = parentCanonical.name.lowercase(Locale.ROOT)
        if (parentName in GENERIC_ROOTS) return false

        // 1. فحص ملف voicelist في الحاوية الأب
        val voicelistFile = File(parentDir, "voicelist")
        if (voicelistFile.isFile && voicelistFile.length() in 1..4096) {
            val content = runCatching {
                voicelistFile.readText(StandardCharsets.UTF_8)
            }.getOrDefault("").lowercase(Locale.ROOT)
            val speakerToken = AcapelaVoiceManifest
                .tokenOfIdentity(expectedSpeaker) ?: ""
            val dirToken = AcapelaVoiceManifest
                .tokenOfIdentity(voiceDir.name) ?: ""
            if (content.contains(expectedSpeaker.lowercase(Locale.ROOT)) ||
                (speakerToken.isNotBlank() && content.contains(speakerToken)) ||
                (dirToken.isNotBlank() && content.contains(dirToken))
            ) {
                return true
            }
        }

        // 2. فحص وجود YAML خاص بالصوت داخل الحاوية الأب
        val speakerToken = AcapelaVoiceManifest
            .tokenOfIdentity(expectedSpeaker)
        if (speakerToken != null) {
            val specificYaml = File(parentDir, "${speakerToken}.yaml")
                .takeIf { it.isFile }
                ?: File(parentDir, "config_${speakerToken}.yaml")
                    .takeIf { it.isFile }
            if (specificYaml != null &&
                checkYamlSpeakerMatch(specificYaml, expectedSpeaker) == true
            ) {
                return true
            }
        }

        // 3. فحص config.yaml/engines.yaml العام في الحاوية الأب (شرط تطابق الهوية)
        val defaultYaml = listOf("config.yaml", "engines.yaml")
            .map { File(parentDir, it) }
            .firstOrNull { it.isFile }
        if (defaultYaml != null) {
            return checkYamlSpeakerMatch(defaultYaml, expectedSpeaker) == true
        }

        return false
    }

    /**
     * جمع كل ملفات ini بأمان داخل الحاوية مع مراعاة حدود المسار الشرعي.
     */
    private fun collectSafeIniFiles(packageRoot: File): List<File> {
        val inis = mutableListOf<File>()
        val visited = mutableSetOf<String>()

        fun walk(dir: File, depth: Int) {
            if (depth > 5) return
            val canonical = runCatching {
                dir.canonicalFile
            }.getOrNull() ?: return
            if (!visited.add(canonical.canonicalPath)) return

            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    if (isSafeCanonicalChild(packageRoot, file)) {
                        walk(file, depth + 1)
                    }
                } else if (file.isFile &&
                    file.extension.equals("ini", ignoreCase = true)
                ) {
                    if (isSafeCanonicalChild(packageRoot, file)) {
                        inis += file
                    }
                }
            }
        }

        walk(packageRoot, 0)
        return inis.distinctBy { it.canonicalPath }
    }

    private data class CandidateEvaluation(
        val file: File,
        val isAuthentic: Boolean,
        val score: Int,
        val resolvedFiles: List<File>,
        val missingDirectives: List<String>,
        val dependencyBase: File
    )

    /**
     * حل المسار النسبي ضمن حدود حاوية محددة لمنع الهروب خارج حزمة الصوت.
     */
    private fun resolveRelativeWithinBoundary(
        startDir: File,
        boundaryDir: File,
        relativePath: String
    ): File? {
        val canonicalBoundary = runCatching {
            boundaryDir.canonicalFile
        }.getOrNull() ?: return null
        val boundaryPath = canonicalBoundary.canonicalPath

        fun isInside(f: File): Boolean {
            val p = runCatching { f.canonicalPath }
                .getOrNull() ?: return false
            return p == boundaryPath ||
                p.startsWith(boundaryPath + File.separator)
        }

        val cleaned = relativePath.replace('\\', '/').trim()
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        var current = startDir
        for (part in parts) {
            if (part == ".") continue
            if (part == "..") {
                val parent = current.parentFile ?: return null
                if (!isInside(parent)) return null
                current = parent
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
            if (!isInside(current)) return null
        }
        return if (isInside(current) && current.exists()) current else null
    }

    /**
     * فحص توجيهات ini وحل تبعياتها داخل حدود الحزمة بأمان.
     */
    private fun evaluateCandidate(
        iniFile: File,
        targetToken: String?,
        packageRoot: File
    ): CandidateEvaluation {
        val classification = AcapelaVoiceManifest
            .classifyIniCandidate(iniFile, targetToken ?: "")
        val isAuthentic = classification.state ==
            AcapelaVoiceManifest.IniDiscoveryState.AUTHENTIC

        val dependencies = AcapelaVoiceManifest.parseIniDependencies(iniFile)
        val resolved = mutableListOf<File>()
        val missing = mutableListOf<String>()

        val parentDir = iniFile.parentFile ?: packageRoot
        val parentBound = packageRoot.parentFile
        val isParentSafe = parentBound != null &&
            parentBound.parentFile != null &&
            parentBound.name.lowercase(Locale.ROOT) !in GENERIC_ROOTS
        val boundary = if (isParentSafe) parentBound else packageRoot

        var resolvedAtParent = 0
        var resolvedAtRoot = 0

        dependencies.forEach { dep ->
            val rel = dep.relativePath
            val fromParent = AcapelaVoiceManifest
                .resolveRelativeFile(parentDir, rel)
            val fromRoot = AcapelaVoiceManifest
                .resolveRelativeFile(packageRoot, rel)

            when {
                fromParent != null && fromParent.exists() -> {
                    resolved += fromParent
                    resolvedAtParent++
                }
                fromRoot != null && fromRoot.exists() -> {
                    resolved += fromRoot
                    resolvedAtRoot++
                }
                rel.contains("..") -> {
                    val fromParentBound = resolveRelativeWithinBoundary(
                        parentDir,
                        boundary,
                        rel
                    )
                    val fromRootBound = resolveRelativeWithinBoundary(
                        packageRoot,
                        boundary,
                        rel
                    )
                    val found = fromParentBound ?: fromRootBound
                    if (found != null && found.exists()) {
                        resolved += found
                        if (fromParentBound != null) {
                            resolvedAtParent++
                        } else {
                            resolvedAtRoot++
                        }
                    } else {
                        missing += "${dep.key}: $rel"
                    }
                }
                else -> {
                    missing += "${dep.key}: $rel"
                }
            }
        }

        val chosenBase = if (resolvedAtParent >= resolvedAtRoot) {
            parentDir
        } else {
            packageRoot
        }

        return CandidateEvaluation(
            file = iniFile,
            isAuthentic = isAuthentic,
            score = classification.identityScore,
            resolvedFiles = resolved.distinctBy { it.canonicalPath },
            missingDirectives = missing,
            dependencyBase = chosenBase
        )
    }

    /**
     * تحديد جذر محرك التنفيذ الأصلي الممرر إلى nInit وفق عقد التنفيذ الفعلي
     * والمسار الناجح لسلمى، دون الاقتصار السطحي على ini.parentFile.
     */
    private fun determineNativeSearchRoot(
        packageRoot: File,
        canonicalVoiceDir: File,
        authenticIni: File?,
        dependencyBase: File?,
        voiceSubpackageDir: File? = null
    ): File {
        if (authenticIni == null) {
            return voiceSubpackageDir ?: canonicalVoiceDir
        }
        val iniParent = authenticIni.parentFile ?: voiceSubpackageDir
            ?: canonicalVoiceDir

        if (iniParent.canonicalPath == packageRoot.canonicalPath) {
            return packageRoot
        }

        // إذا كان جذر الحزمة يحتوي على ini مكرر أو بديل سطحي يختلف عن الأصلي،
        // فإن تمرير جذر الحزمة لـ nInit سيجعل المكتبة الأصلية تفتح الملف السطحي
        // الفاسد وتفشل بـ eb=-27 و nlpInitError=-34. في هذه الحالة يجب تمرير
        // المجلد الذي يحتوي على ini الأصلي المعتمد (مثل salma_lf).
        val shallowInis = packageRoot.listFiles { f ->
            f.isFile && f.extension.equals("ini", ignoreCase = true)
        }?.toList().orEmpty()

        val hasConflictingShallow = shallowInis.any {
            it.canonicalPath != authenticIni.canonicalPath
        }

        if (hasConflictingShallow) {
            return iniParent
        }

        return if (dependencyBase != null &&
            dependencyBase.canonicalPath == iniParent.canonicalPath
        ) {
            iniParent
        } else {
            dependencyBase ?: iniParent
        }
    }

    /**
     * حل حزمة صوت Acapela بنطاق الحزمة الصارم وبشكل آمن متزامن.
     *
     * @param voiceDir مجلد حزمة الصوت المطلوب فحصها.
     * @param expectedSpeakerHint تلميح هوية المتحدث (اختياري).
     * @param forceRetry تجاوز فترة التهدئة وإعادة الفحص الفعلي.
     */
    fun resolvePackage(
        voiceDir: File,
        expectedSpeakerHint: String? = null,
        forceRetry: Boolean = false
    ): ResolvedPackage {
        if (!voiceDir.exists() || !voiceDir.isDirectory) {
            return ResolvedPackage(
                status = ResolutionStatus.FILE_NOT_FOUND,
                packageContainerRoot = voiceDir,
                authenticIni = null,
                iniDependencyBase = null,
                nativeEngineSearchRoot = voiceDir,
                expectedSpeaker = expectedSpeakerHint ?: voiceDir.name,
                resolvedSpeaker = null,
                infFile = null,
                metadataYaml = null,
                hexFile = null,
                registration = "",
                relation = null,
                supportFiles = emptyList(),
                diagnosticReason = "مجلد الصوت غير موجود: ${voiceDir.name}"
            )
        }

        val canonicalVoiceDir = runCatching {
            voiceDir.canonicalFile
        }.getOrNull()
        if (canonicalVoiceDir == null ||
            canonicalVoiceDir.parentFile == null ||
            canonicalVoiceDir.name.lowercase(Locale.ROOT) in GENERIC_ROOTS
        ) {
            return ResolvedPackage(
                status = ResolutionStatus.UNSUPPORTED_FORMAT,
                packageContainerRoot = voiceDir,
                authenticIni = null,
                iniDependencyBase = null,
                nativeEngineSearchRoot = voiceDir,
                expectedSpeaker = expectedSpeakerHint ?: voiceDir.name,
                resolvedSpeaker = null,
                infFile = null,
                metadataYaml = null,
                hexFile = null,
                registration = "",
                relation = null,
                supportFiles = emptyList(),
                diagnosticReason = "مسار الصوت غير صالح أو يقع في جذر ممنوع"
            )
        }

        val normalizedSpeaker = expectedSpeakerHint?.trim()?.ifBlank { null }
            ?: canonicalVoiceDir.name
        val currentRevision = AcapelaVoiceRepository.getStorageRevision()
        val cacheKey = CacheKey(
            canonicalPath = canonicalVoiceDir.canonicalPath,
            expectedSpeaker = normalizedSpeaker,
            revision = currentRevision
        )

        if (!forceRetry) {
            packageCache[cacheKey]?.let { return it }

            val cooldownKey =
                "${canonicalVoiceDir.canonicalPath}|$normalizedSpeaker"
            val cooldown = failureCooldowns[cooldownKey]
            if (cooldown != null && cooldown.revision == currentRevision) {
                val elapsed = System.currentTimeMillis() - cooldown.failedAtMs
                if (elapsed < cooldown.backoffMs) {
                    return cooldown.failedResult
                }
            }
        }

        val lockKey = "${canonicalVoiceDir.canonicalPath}|$normalizedSpeaker"
        val lock = inFlightLocks.computeIfAbsent(lockKey) { Any() }

        return synchronized(lock) {
            if (!forceRetry) {
                packageCache[cacheKey]?.let { return@synchronized it }
            }

            AcapelaPerformanceCounters.recordScan("AcapelaPackageResolver")
            val resolved = resolvePackageInternal(
                canonicalVoiceDir = canonicalVoiceDir,
                expectedSpeakerHint = expectedSpeakerHint
            )

            if (resolved.isSuccess) {
                if (packageCache.size >= MAX_PACKAGE_CACHE_SIZE) {
                    val oldest = packageCache.keys().toList().firstOrNull()
                    if (oldest != null) packageCache.remove(oldest)
                }
                packageCache[cacheKey] = resolved
                failureCooldowns.remove(lockKey)
            } else {
                val prevCooldown = failureCooldowns[lockKey]
                val prevBackoff = prevCooldown?.backoffMs ?: 500L
                val nextBackoff = (prevBackoff * 2L).coerceAtMost(15_000L)
                if (failureCooldowns.size >= MAX_COOLDOWN_CACHE_SIZE) {
                    val oldest = failureCooldowns.keys().toList().firstOrNull()
                    if (oldest != null) failureCooldowns.remove(oldest)
                }
                failureCooldowns[lockKey] = FailureCooldown(
                    failedAtMs = System.currentTimeMillis(),
                    backoffMs = nextBackoff,
                    revision = currentRevision,
                    failedResult = resolved
                )
            }

            resolved
        }
    }

    private fun resolvePackageInternal(
        canonicalVoiceDir: File,
        expectedSpeakerHint: String?
    ): ResolvedPackage {
        val packageContainerRoot = runCatching {
            AcapelaVoiceManifest.findPackageRoot(canonicalVoiceDir)
        }.getOrDefault(canonicalVoiceDir)

        // 1. تحديد هوية المتحدث المبدئية
        val hintToken = expectedSpeakerHint?.let {
            AcapelaVoiceManifest.tokenOfIdentity(it)
        }
        val dirToken = AcapelaVoiceManifest.tokenOfIdentity(
            canonicalVoiceDir.name
        )

        // 2. البحث عن ملفات .inf لاستخلاص المتحدث الرسمي
        val directInfs = canonicalVoiceDir.listFiles { f ->
            f.isFile && f.extension.equals("inf", ignoreCase = true)
        }?.toList().orEmpty()

        val subInfs = if (directInfs.isEmpty()) {
            packageContainerRoot.listFiles { it.isDirectory }?.flatMap { sub ->
                if (isSafeCanonicalChild(packageContainerRoot, sub)) {
                    sub.listFiles { f ->
                        f.isFile && f.extension.equals("inf", ignoreCase = true)
                    }?.toList().orEmpty()
                } else emptyList()
            }.orEmpty()
        } else emptyList()

        val allInfs = (directInfs + subInfs).distinct()
        val infFile = allInfs.firstOrNull()
        val parsedSpeakerFromInf = infFile?.let { f ->
            if (f.length() > 512 * 1024L) return@let null
            runCatching {
                val text = f.readText(StandardCharsets.ISO_8859_1)
                val pattern =
                    """(?i)^\s*(?:speaker|voicename)\s*[:=]\s*"?([^"\r\n]+)"?"""
                val raw = Regex(pattern, RegexOption.MULTILINE)
                    .find(text)?.groupValues?.getOrNull(1)?.trim()
                raw?.let { AcapelaVoiceManifest.cleanAcapelaSpeaker(it) }
            }.getOrNull()
        }
        val infToken = parsedSpeakerFromInf?.let {
            AcapelaVoiceManifest.tokenOfIdentity(it)
        }

        val targetToken = hintToken ?: infToken ?: dirToken
        val resolvedSpeaker = parsedSpeakerFromInf
            ?: expectedSpeakerHint?.trim()?.ifBlank { null }
            ?: dirToken?.replaceFirstChar { it.titlecase(Locale.ROOT) }
            ?: AcapelaVoiceManifest.cleanAcapelaSpeaker(canonicalVoiceDir.name)

        val expectedSpeaker = expectedSpeakerHint?.trim()?.ifBlank { null }
            ?: resolvedSpeaker

        // 3. جمع وفحص كافة ملفات ini داخل الحزمة
        val allInis = collectSafeIniFiles(packageContainerRoot)
        val evaluations = allInis.map { ini ->
            evaluateCandidate(ini, targetToken, packageContainerRoot)
        }

        // فحص مطابقة الهوية لملفات ini المستبعدة
        val speakerMismatches = allInis.filter { ini ->
            val tok = AcapelaVoiceManifest.tokenOfIdentity(ini.name)
            targetToken != null && tok != null && tok != targetToken
        }

        // عزل المرشحات المتطابقة مع هوية الصوت المطلوب
        val matchingCandidates = evaluations.filter { ev ->
            val tok = AcapelaVoiceManifest.tokenOfIdentity(ev.file.name)
            targetToken == null || tok == null || tok == targetToken
        }

        val authenticCandidates = matchingCandidates.filter { it.isAuthentic }

        val chosenEvaluation: CandidateEvaluation?
        var ambiguousConflictReason: String? = null

        when {
            authenticCandidates.size == 1 -> {
                chosenEvaluation = authenticCandidates.first()
            }
            authenticCandidates.size > 1 -> {
                // كشف التكرار: إذا كانت التهيئات متطابقة تماماً في المحتوى
                val byFingerprint = authenticCandidates.groupBy { c ->
                    if (c.file.length() > 512 * 1024L) {
                        return@groupBy c.file.name
                    }
                    runCatching {
                        c.file.readText(StandardCharsets.ISO_8859_1)
                            .replace("\r\n", "\n").trim()
                    }.getOrDefault(c.file.name)
                }

                if (byFingerprint.size == 1) {
                    // اختيار النسخة التي تطابق قاعدة التبعيات المحلولة
                    val best = authenticCandidates.maxByOrNull {
                        it.resolvedFiles.size
                    } ?: authenticCandidates.first()
                    chosenEvaluation = best
                } else {
                    // مقارنة عدد التبعيات المحلولة على القرص
                    val maxResolved = authenticCandidates.maxOf {
                        it.resolvedFiles.size
                    }
                    val topResolved = authenticCandidates.filter {
                        it.resolvedFiles.size == maxResolved &&
                            it.missingDirectives.isEmpty()
                    }
                    if (topResolved.size == 1) {
                        chosenEvaluation = topResolved.first()
                    } else {
                        // مرشحات أصيلة متعددة متعارضة دون حسم آمن
                        chosenEvaluation = null
                        ambiguousConflictReason =
                            "تعدد ملفات ini الأصيلة " +
                            "(${authenticCandidates.size}) " +
                            "المتعارضة داخل الحزمة"
                    }
                }
            }
            else -> {
                chosenEvaluation = null
            }
        }

        if (ambiguousConflictReason != null) {
            return ResolvedPackage(
                status = ResolutionStatus.MULTIPLE_CANDIDATES,
                packageContainerRoot = packageContainerRoot,
                authenticIni = null,
                iniDependencyBase = null,
                nativeEngineSearchRoot = canonicalVoiceDir,
                expectedSpeaker = expectedSpeaker,
                resolvedSpeaker = resolvedSpeaker,
                infFile = infFile,
                metadataYaml = null,
                hexFile = null,
                registration = "",
                relation = null,
                supportFiles = emptyList(),
                diagnosticReason = ambiguousConflictReason
            )
        }

        if (chosenEvaluation == null &&
            matchingCandidates.isEmpty() &&
            speakerMismatches.isNotEmpty()
        ) {
            val foreign = speakerMismatches.first()
            return ResolvedPackage(
                status = ResolutionStatus.SPEAKER_MISMATCH,
                packageContainerRoot = packageContainerRoot,
                authenticIni = null,
                iniDependencyBase = null,
                nativeEngineSearchRoot = canonicalVoiceDir,
                expectedSpeaker = expectedSpeaker,
                resolvedSpeaker = resolvedSpeaker,
                infFile = infFile,
                metadataYaml = null,
                hexFile = null,
                registration = "",
                relation = null,
                supportFiles = emptyList(),
                diagnosticReason = "ملف ini (${foreign.name}) يخص صوتاً آخر " +
                    "ولا يطابق الصوت المطلوب ($expectedSpeaker)"
            )
        }

        val voiceSubpackageDir = if (targetToken != null) {
            val directSub = canonicalVoiceDir.listFiles { f ->
                f.isDirectory &&
                    f.name.lowercase(Locale.ROOT).contains(targetToken)
            }?.firstOrNull()
            directSub ?: canonicalVoiceDir
        } else {
            canonicalVoiceDir
        }

        val authenticIni = chosenEvaluation?.file
        val iniDependencyBase = chosenEvaluation?.dependencyBase
        val resolvedDeps = chosenEvaluation?.resolvedFiles.orEmpty()
        val missingDeps = chosenEvaluation?.missingDirectives.orEmpty()

        val nativeEngineSearchRoot = determineNativeSearchRoot(
            packageRoot = packageContainerRoot,
            canonicalVoiceDir = canonicalVoiceDir,
            authenticIni = authenticIni,
            dependencyBase = iniDependencyBase,
            voiceSubpackageDir = voiceSubpackageDir
        )

        // 4. فحص ملف config.yaml/engines.yaml (المجلد الفرعي أولاً ثم الحاوية)
        val subYaml = listOf("config.yaml", "engines.yaml")
            .map { File(voiceSubpackageDir, it) }
            .firstOrNull { it.isFile }
        val rootYaml = listOf("config.yaml", "engines.yaml")
            .map { File(canonicalVoiceDir, it) }
            .firstOrNull { it.isFile }
            ?: listOf("config.yaml", "engines.yaml")
                .map { File(packageContainerRoot, it) }
                .firstOrNull { it.isFile }

        val directYaml = subYaml ?: rootYaml
        var matchingYaml: File? = null
        var hasConflictingMetadata = false

        if (subYaml != null) {
            val isSubMatch = checkYamlSpeakerMatch(subYaml, expectedSpeaker)
            if (isSubMatch == true) {
                matchingYaml = subYaml
            } else if (isSubMatch == false) {
                hasConflictingMetadata = true
            } else {
                matchingYaml = subYaml
            }
        } else if (rootYaml != null) {
            val isRootMatch = checkYamlSpeakerMatch(rootYaml, expectedSpeaker)
            if (isRootMatch == true) {
                matchingYaml = rootYaml
            } else if (isRootMatch == false) {
                hasConflictingMetadata = true
            } else {
                matchingYaml = rootYaml
            }
        }

        // 5. حل ملف الدعم السداسي (0x...) عبر الترتيبات الثلاثة
        val coLocatedHexList = canonicalVoiceDir.listFiles { f ->
            f.isFile && f.name.matches(
                AcapelaVoiceManifest.HEX_REGISTRATION_REGEX
            ) && f.length() > 0L
        }?.toList().orEmpty()

        val nestedHexList = canonicalVoiceDir.listFiles {
            it.isDirectory
        }?.flatMap { subDir ->
            if (isSafeCanonicalChild(canonicalVoiceDir, subDir)) {
                subDir.listFiles { f ->
                    f.isFile && f.name.matches(
                        AcapelaVoiceManifest.HEX_REGISTRATION_REGEX
                    ) && f.length() > 0L
                }?.toList().orEmpty()
            } else emptyList()
        }.orEmpty()

        val parentContainer = canonicalVoiceDir.parentFile
            ?.takeIf { it.exists() && it.isDirectory }
        val isParentDocumented = parentContainer != null &&
            isParentContainerDocumentedForVoice(
                parentDir = parentContainer,
                voiceDir = canonicalVoiceDir,
                expectedSpeaker = expectedSpeaker
            )

        val parentHexList = if (isParentDocumented) {
            parentContainer?.listFiles { f ->
                f.isFile && f.name.matches(
                    AcapelaVoiceManifest.HEX_REGISTRATION_REGEX
                ) && f.length() > 0L
            }?.toList().orEmpty()
        } else emptyList()

        val hexCandidate: File?
        val relation: PackageRelation?

        when {
            coLocatedHexList.size == 1 -> {
                hexCandidate = coLocatedHexList.first()
                relation = PackageRelation.CO_LOCATED
            }
            coLocatedHexList.size > 1 -> {
                return ResolvedPackage(
                    status = ResolutionStatus.MULTIPLE_CANDIDATES,
                    packageContainerRoot = packageContainerRoot,
                    authenticIni = authenticIni,
                    iniDependencyBase = iniDependencyBase,
                    nativeEngineSearchRoot = nativeEngineSearchRoot,
                    expectedSpeaker = expectedSpeaker,
                    resolvedSpeaker = resolvedSpeaker,
                    infFile = infFile,
                    metadataYaml = matchingYaml,
                    hexFile = null,
                    registration = "",
                    relation = PackageRelation.CO_LOCATED,
                    supportFiles = coLocatedHexList,
                    diagnosticReason = "تعدد ملفات الترخيص " +
                        "(${coLocatedHexList.size}) المتجاورة دون حسم"
                )
            }
            nestedHexList.size == 1 -> {
                hexCandidate = nestedHexList.first()
                relation = PackageRelation.NESTED
            }
            nestedHexList.size > 1 -> {
                return ResolvedPackage(
                    status = ResolutionStatus.MULTIPLE_CANDIDATES,
                    packageContainerRoot = packageContainerRoot,
                    authenticIni = authenticIni,
                    iniDependencyBase = iniDependencyBase,
                    nativeEngineSearchRoot = nativeEngineSearchRoot,
                    expectedSpeaker = expectedSpeaker,
                    resolvedSpeaker = resolvedSpeaker,
                    infFile = infFile,
                    metadataYaml = matchingYaml,
                    hexFile = null,
                    registration = "",
                    relation = PackageRelation.NESTED,
                    supportFiles = nestedHexList,
                    diagnosticReason = "تعدد ملفات الترخيص " +
                        "(${nestedHexList.size}) المتداخلة دون حسم"
                )
            }
            isParentDocumented && parentHexList.size == 1 -> {
                hexCandidate = parentHexList.first()
                relation = PackageRelation.PARENT_CONTAINER
            }
            isParentDocumented && parentHexList.size > 1 -> {
                return ResolvedPackage(
                    status = ResolutionStatus.MULTIPLE_CANDIDATES,
                    packageContainerRoot = packageContainerRoot,
                    authenticIni = authenticIni,
                    iniDependencyBase = iniDependencyBase,
                    nativeEngineSearchRoot = nativeEngineSearchRoot,
                    expectedSpeaker = expectedSpeaker,
                    resolvedSpeaker = resolvedSpeaker,
                    infFile = infFile,
                    metadataYaml = matchingYaml,
                    hexFile = null,
                    registration = "",
                    relation = PackageRelation.PARENT_CONTAINER,
                    supportFiles = parentHexList,
                    diagnosticReason = "تعدد ملفات الترخيص " +
                        "(${parentHexList.size}) في الحاوية الأب دون حسم"
                )
            }
            else -> {
                hexCandidate = null
                relation = null
            }
        }

        // 6. التحقق من التعارض وإمكانية الاعتماد
        if (hasConflictingMetadata && hexCandidate == null) {
            return ResolvedPackage(
                status = ResolutionStatus.METADATA_CONFLICT,
                packageContainerRoot = packageContainerRoot,
                authenticIni = authenticIni,
                iniDependencyBase = iniDependencyBase,
                nativeEngineSearchRoot = nativeEngineSearchRoot,
                expectedSpeaker = expectedSpeaker,
                resolvedSpeaker = resolvedSpeaker,
                infFile = infFile,
                metadataYaml = null,
                hexFile = null,
                registration = "",
                relation = null,
                supportFiles = listOfNotNull(directYaml),
                diagnosticReason = "ملف config.yaml يخص صوتاً آخر ولا توجد " +
                    "ملفات دعم أصلية للصوت $expectedSpeaker"
            )
        }

        if (authenticIni == null && hexCandidate == null &&
            matchingYaml == null
        ) {
            return ResolvedPackage(
                status = ResolutionStatus.FILE_NOT_FOUND,
                packageContainerRoot = packageContainerRoot,
                authenticIni = null,
                iniDependencyBase = null,
                nativeEngineSearchRoot = nativeEngineSearchRoot,
                expectedSpeaker = expectedSpeaker,
                resolvedSpeaker = resolvedSpeaker,
                infFile = infFile,
                metadataYaml = null,
                hexFile = null,
                registration = "",
                relation = null,
                supportFiles = emptyList(),
                diagnosticReason = "حزمة الصوت $expectedSpeaker غير مكتملة " +
                    "(غياب ملفات ini والترخيص والتهيئة)"
            )
        }

        // 7. استخراج سلسلة التسجيل الأصلية دون اختراع أو استعارة أسرار
        val authenticReg = when {
            matchingYaml != null -> {
                readParamFromYaml(matchingYaml)?.let { rawParam ->
                    val extracted = AcapelaVoiceManifest
                        .extractRegistrationFromParam(rawParam)
                    if (AcapelaVoiceManifest
                            .isRegistrationMetadata(extracted)
                    ) {
                        extracted
                    } else {
                        val parsed = AcapelaVoiceManifest
                            .splitConfigYamlParam(rawParam)
                        if (parsed != null) {
                            val comb = "${parsed.second}${parsed.third}"
                            if (AcapelaVoiceManifest
                                    .isRegistrationMetadata(comb)
                            ) comb else ""
                        } else ""
                    }
                }.orEmpty()
            }
            else -> ""
        }

        val supportFiles = listOfNotNull(
            matchingYaml,
            hexCandidate
        ).distinct()

        val reason = if (hasConflictingMetadata) {
            "تم اعتماد حزمة $expectedSpeaker الأصلية (${relation?.name}) " +
                "وتجاهل ملف metadata المنسوخ المتعارض"
        } else {
            "تم التحقق بنجاح من حزمة $expectedSpeaker (${relation?.name})"
        }

        return ResolvedPackage(
            status = ResolutionStatus.SUCCESS,
            packageContainerRoot = packageContainerRoot,
            authenticIni = authenticIni,
            iniDependencyBase = iniDependencyBase,
            nativeEngineSearchRoot = nativeEngineSearchRoot,
            expectedSpeaker = expectedSpeaker,
            resolvedSpeaker = resolvedSpeaker,
            infFile = infFile,
            metadataYaml = matchingYaml,
            hexFile = hexCandidate,
            registration = authenticReg,
            relation = relation,
            supportFiles = supportFiles,
            resolvedDependencies = resolvedDeps,
            missingDependencies = missingDeps,
            diagnosticReason = reason
        )
    }
}
