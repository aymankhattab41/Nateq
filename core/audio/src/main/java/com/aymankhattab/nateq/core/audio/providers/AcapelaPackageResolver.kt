package com.aymankhattab.nateq.core.audio.providers

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * محلل حزم أصوات Acapela الموحد بنطاق الحزمة (Package-Scoped Resolver).
 *
 * يعيد حزمة مترابطة ومتحققة للصوت المطلوب تتضمن:
 * 1. هوية الصوت (المتحدث) المستخلصة بدقة.
 * 2. ملف ini الأصلي وتوجيهاته النسبية.
 * 3. ملف metadata المحدد (config.yaml) إن وُجد وطابق المتحدث.
 * 4. مراجع الدعم والترخيص الأصلية ومساراتها الشرعية (canonical).
 * 5. علاقة المجلدات: متجاور (CO_LOCATED)، متداخل (NESTED)،
 *    أو في حاوية أب موثقة (PARENT_CONTAINER).
 *
 * يمنع استعارة ملفات من أصوات شقيقة أو من جذر عام، ويفصل قراءة
 * config.yaml عن التسلق الشجري، ويعزل أخطاء ليلى عن سلمى.
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
        val voiceDir: File,
        val expectedSpeaker: String,
        val resolvedSpeaker: String?,
        val iniFile: File?,
        val infFile: File?,
        val metadataYaml: File?,
        val hexFile: File?,
        val registration: String,
        val relation: PackageRelation?,
        val supportFiles: List<File>,
        val diagnosticReason: String
    ) {
        val isSuccess: Boolean
            get() = status == ResolutionStatus.SUCCESS
    }

    private val PARAM_REGEX = Regex(
        """(?:^|\n)\s*"?param"?\s*[:=]\s*"?([^"\r\n#]+)"?""",
        setOf(RegexOption.MULTILINE)
    )

    private val GENERIC_ROOTS = setOf(
        "", "/", "\\", "c:", "c:\\", "storage", "emulated", "0",
        "android", "data", "app_voices", "files", "voice", "acapela"
    )

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

        // 3. فحص config.yaml العام في الحاوية الأب (شرط تطابق الهوية)
        val defaultYaml = File(parentDir, "config.yaml").takeIf { it.isFile }
        if (defaultYaml != null) {
            return checkYamlSpeakerMatch(defaultYaml, expectedSpeaker) == true
        }

        return false
    }

    /**
     * حل حزمة صوت Acapela بنطاق الحزمة الصارم.
     *
     * @param voiceDir مجلد حزمة الصوت المطلوب فحصها.
     * @param expectedSpeakerHint تلميح هوية المتحدث (اختياري).
     */
    fun resolvePackage(
        voiceDir: File,
        expectedSpeakerHint: String? = null
    ): ResolvedPackage {
        if (!voiceDir.exists() || !voiceDir.isDirectory) {
            return ResolvedPackage(
                status = ResolutionStatus.FILE_NOT_FOUND,
                voiceDir = voiceDir,
                expectedSpeaker = expectedSpeakerHint ?: voiceDir.name,
                resolvedSpeaker = null,
                iniFile = null,
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
                voiceDir = voiceDir,
                expectedSpeaker = expectedSpeakerHint ?: voiceDir.name,
                resolvedSpeaker = null,
                iniFile = null,
                infFile = null,
                metadataYaml = null,
                hexFile = null,
                registration = "",
                relation = null,
                supportFiles = emptyList(),
                diagnosticReason = "مسار الصوت غير صالح أو يقع في جذر ممنوع"
            )
        }

        // 1. تحديد هوية المتحدث المبدئية
        val hintToken = expectedSpeakerHint?.let {
            AcapelaVoiceManifest.tokenOfIdentity(it)
        }
        val dirToken = AcapelaVoiceManifest.tokenOfIdentity(
            canonicalVoiceDir.name
        )

        // 2. البحث عن ملفات .ini و .inf داخل حدود الحزمة فقط
        val directInis = canonicalVoiceDir.listFiles { f ->
            f.isFile && f.extension.equals("ini", ignoreCase = true)
        }?.toList().orEmpty()

        val subInis = if (directInis.isEmpty()) {
            canonicalVoiceDir.listFiles { it.isDirectory }?.flatMap { sub ->
                if (isSafeCanonicalChild(canonicalVoiceDir, sub)) {
                    sub.listFiles { f ->
                        f.isFile && f.extension.equals("ini", ignoreCase = true)
                    }?.toList().orEmpty()
                } else emptyList()
            }.orEmpty()
        } else emptyList()

        val allInis = (directInis + subInis).distinct()

        val directInfs = canonicalVoiceDir.listFiles { f ->
            f.isFile && f.extension.equals("inf", ignoreCase = true)
        }?.toList().orEmpty()

        val subInfs = if (directInfs.isEmpty()) {
            canonicalVoiceDir.listFiles { it.isDirectory }?.flatMap { sub ->
                if (isSafeCanonicalChild(canonicalVoiceDir, sub)) {
                    sub.listFiles { f ->
                        f.isFile && f.extension.equals("inf", ignoreCase = true)
                    }?.toList().orEmpty()
                } else emptyList()
            }.orEmpty()
        } else emptyList()

        val allInfs = (directInfs + subInfs).distinct()

        val infFile = allInfs.firstOrNull()
        val parsedSpeakerFromInf = infFile?.let { f ->
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

        // 3. اختيار ملف ini ومطابقة هويته
        val authenticIniCandidate = allInis.firstOrNull { ini ->
            val iniTok = AcapelaVoiceManifest.tokenOfIdentity(ini.name)
            targetToken == null || iniTok == null || iniTok == targetToken
        } ?: allInis.firstOrNull()

        if (authenticIniCandidate != null && targetToken != null) {
            val iniTok = AcapelaVoiceManifest
                .tokenOfIdentity(authenticIniCandidate.name)
            if (iniTok != null && iniTok != targetToken) {
                return ResolvedPackage(
                    status = ResolutionStatus.SPEAKER_MISMATCH,
                    voiceDir = canonicalVoiceDir,
                    expectedSpeaker = expectedSpeaker,
                    resolvedSpeaker = resolvedSpeaker,
                    iniFile = authenticIniCandidate,
                    infFile = infFile,
                    metadataYaml = null,
                    hexFile = null,
                    registration = "",
                    relation = null,
                    supportFiles = emptyList(),
                    diagnosticReason =
                        "ملف ini (${authenticIniCandidate.name}) " +
                        "يخص صوتاً آخر ولا يطابق " +
                        "الصوت المطلوب ($expectedSpeaker)"
                )
            }
        }

        // 4. فحص ملف config.yaml المباشر وفك الارتباط المتضارب
        val directYaml = File(canonicalVoiceDir, "config.yaml")
            .takeIf { it.isFile }
        var matchingYaml: File? = null
        var hasConflictingMetadata = false

        if (directYaml != null) {
            val isMatch = checkYamlSpeakerMatch(directYaml, expectedSpeaker)
            if (isMatch == true) {
                matchingYaml = directYaml
            } else if (isMatch == false) {
                hasConflictingMetadata = true
            } else {
                matchingYaml = directYaml
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
                    voiceDir = canonicalVoiceDir,
                    expectedSpeaker = expectedSpeaker,
                    resolvedSpeaker = resolvedSpeaker,
                    iniFile = authenticIniCandidate,
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
                    voiceDir = canonicalVoiceDir,
                    expectedSpeaker = expectedSpeaker,
                    resolvedSpeaker = resolvedSpeaker,
                    iniFile = authenticIniCandidate,
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
                    voiceDir = canonicalVoiceDir,
                    expectedSpeaker = expectedSpeaker,
                    resolvedSpeaker = resolvedSpeaker,
                    iniFile = authenticIniCandidate,
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
                voiceDir = canonicalVoiceDir,
                expectedSpeaker = expectedSpeaker,
                resolvedSpeaker = resolvedSpeaker,
                iniFile = authenticIniCandidate,
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

        if (authenticIniCandidate == null && hexCandidate == null &&
            matchingYaml == null
        ) {
            return ResolvedPackage(
                status = ResolutionStatus.FILE_NOT_FOUND,
                voiceDir = canonicalVoiceDir,
                expectedSpeaker = expectedSpeaker,
                resolvedSpeaker = resolvedSpeaker,
                iniFile = null,
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

        val actualVoiceDir = authenticIniCandidate?.parentFile
            ?: canonicalVoiceDir

        return ResolvedPackage(
            status = ResolutionStatus.SUCCESS,
            voiceDir = actualVoiceDir,
            expectedSpeaker = expectedSpeaker,
            resolvedSpeaker = resolvedSpeaker,
            iniFile = authenticIniCandidate,
            infFile = infFile,
            metadataYaml = matchingYaml,
            hexFile = hexCandidate,
            registration = authenticReg,
            relation = relation,
            supportFiles = supportFiles,
            diagnosticReason = reason
        )
    }
}
