// ع٤٢-3: تحقّق من محاذاة مقاطع LOAD في مكتبات arm64 الجاهزة.
// الموردون لا يوفّرون مصدر هذه المكتبات، فلا يمكن إعادة بنائها محلياً
// بـ -Wl,-z,max-page-size=16384؛ نُصدر تحذيراً واضحاً بدل ادعاء الدعم.
// السكربت لا يفشل البناء أبداً، ويصمد حين يكون readelf غير متاح.
import java.io.File
import java.util.Properties

val arm64Dir = rootProject.file(
    "core/audio/src/main/jniLibs/arm64-v8a"
)
val arm32Vocalizer = rootProject.file(
    "core/audio/src/main/jniLibs/armeabi-v7a/libNuanceVocalizer.so"
)
val vendorArm64Libs = listOf(
    "libalclog.so",
    "libc++_shared.so",
    "libttsacattsandroidextended.so"
)
val requiredAlignment = 0x4000L

/** أصغر محاذاة LOAD في إخراج readelf -l، أو null إن لم تُوجد. */
fun minLoadAlignment(lines: List<String>): Long? {
    var min: Long? = null
    for (line in lines) {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.firstOrNull() != "LOAD") continue
        val raw = fields.lastOrNull()?.removePrefix("0x") ?: continue
        val value = raw.toLongOrNull(16) ?: continue
        min = if (min == null) value else minOf(min, value)
    }
    return min
}

/** صحيح حين تستوجب المحاذاة تحذيراً (غائبة أو أصغر من 16KB). */
fun needsAlignmentWarning(alignment: Long?): Boolean {
    return alignment == null || alignment < requiredAlignment
}

// بحث عن readelf دون تثبيت مسار خاص بآلة معيّنة في المستودع.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val sdkRoot: File? = listOf(
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
    localProperties.getProperty("sdk.dir")
).firstOrNull { !it.isNullOrBlank() }?.let { File(it) }

val ndkRoots = mutableListOf<File>()
System.getenv("ANDROID_NDK_HOME")
    ?.takeIf { it.isNotBlank() }
    ?.let { ndkRoots.add(File(it)) }
localProperties.getProperty("ndk.dir")
    ?.takeIf { it.isNotBlank() }
    ?.let { ndkRoots.add(File(it)) }
sdkRoot?.let { root ->
    File(root, "ndk").listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        ?.let { ndkRoots.addAll(it) }
}

val hostTags = listOf(
    "windows-x86_64",
    "linux-x86_64",
    "darwin-aarch64",
    "darwin-x86_64"
)
val readelfCandidates = mutableListOf<String>()
// على ويندوز يحمل الملف اللاحقة .exe ولا يضيفها ProcessBuilder تلقائياً.
val exeSuffix = if (
    System.getProperty("os.name").lowercase().contains("windows")
) ".exe" else ""
for (ndk in ndkRoots) {
    for (host in hostTags) {
        val exe = File(
            ndk,
            "toolchains/llvm/prebuilt/$host/bin/llvm-readelf$exeSuffix"
        )
        if (exe.isFile) readelfCandidates.add(exe.absolutePath)
    }
}
readelfCandidates.add("llvm-readelf$exeSuffix")
readelfCandidates.add("readelf$exeSuffix")

/** تشغيل readelf وإرجاع إخراجه، أو null عند التعذّر/فشل الخروج. */
fun runReadelf(executable: String, args: List<String>): String? {
    return try {
        val process = ProcessBuilder(
            listOf(executable) + args
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use {
            it.readText()
        }
        if (process.waitFor() == 0) text else null
    } catch (t: Throwable) {
        null
    }
}

val verifySoAlignment = tasks.register("verifyArm64SoAlignment") {
    group = "verification"
    description = "يحذّر إن كانت محاذاة LOAD أقل من 16KB في مكتبات arm64."
    doLast {
        var executable: String? = null
        val probe = File(arm64Dir, vendorArm64Libs.first())
        if (probe.isFile) {
            for (candidate in readelfCandidates) {
                val output = runReadelf(
                    candidate,
                    listOf("-W", "-l", probe.absolutePath)
                )
                if (output?.contains("LOAD") == true) {
                    executable = candidate
                    break
                }
            }
        }
        if (executable == null) {
            logger.warn(
                "[16KB-ALIGN] readelf not found; vendor arm64 alignment " +
                    "NOT verified. تحذير: تعذّر العثور على readelf."
            )
        } else {
            for (name in vendorArm64Libs) {
                val lib = File(arm64Dir, name)
                if (!lib.isFile) {
                    logger.warn(
                        "[16KB-ALIGN] Missing file: ${lib.path} | " +
                            "الملف مفقود"
                    )
                    continue
                }
                val output = runReadelf(
                    executable, listOf("-W", "-l", lib.absolutePath)
                )
                val alignment = output?.let {
                    minLoadAlignment(it.lines())
                }
                if (needsAlignmentWarning(alignment)) {
                    logger.warn(
                        "[16KB-ALIGN] WARNING: $name LOAD=$alignment " +
                            "(required >= 0x4000). Vendor prebuilt with " +
                            "no source to rebuild; NOT 16KB-page safe on " +
                            "Android 15+. تحذير محاذاة 16KB: $name " +
                            "محاذاة LOAD=$alignment (المطلوب 0x4000)."
                    )
                } else {
                    logger.lifecycle(
                        "[16KB-ALIGN] OK: $name LOAD=$alignment " +
                            "(16KB-compatible) | متوافق"
                    )
                }
            }
        }

        // ع٤٢-2: تأكيد أن مكتبة Vocalizer على 32-بت ELF32/ARM فعلًا.
        if (arm32Vocalizer.isFile) {
            val header = executable?.let {
                runReadelf(it, listOf("-h", arm32Vocalizer.absolutePath))
            }
            when {
                header == null -> logger.warn(
                    "[32BIT] Cannot read libNuanceVocalizer.so header | " +
                        "تعذّر قراءة الرأس"
                )
                !header.contains("ELF32") ||
                    !header.contains("ARM") -> logger.warn(
                    "[32BIT] Vocalizer is NOT ELF32/ARM | " +
                        "المكتبة ليست ELF32/ARM"
                )
                else -> logger.lifecycle(
                    "[32BIT] OK: libNuanceVocalizer.so = ELF32/ARM | " +
                        "تحقق 32-بت"
                )
            }
            // تحقق تبعيات التحميل: كل مكتبة غير نظامية يجب أن تكون
            // موجودة بجانبها في مجلد armeabi-v7a.
            if (executable != null) {
                val deps = runReadelf(
                    executable,
                    listOf("-d", arm32Vocalizer.absolutePath)
                ).orEmpty()
                val systemLibs = setOf(
                    "libc.so", "libm.so", "libdl.so",
                    "liblog.so", "libstdc++.so"
                )
                val needed = deps.lines().mapNotNull { line ->
                    Regex("NEEDED\\s+\\[([^]]+)\\]")
                        .find(line)?.groupValues?.get(1)
                }.distinct()
                val missing = needed.filter {
                    it !in systemLibs &&
                        !File(arm32Vocalizer.parentFile, it).isFile
                }
                if (missing.isEmpty()) {
                    logger.lifecycle(
                        "[32BIT] OK: Vocalizer dependencies complete | " +
                            "التبعيات مكتملة"
                    )
                } else {
                    logger.warn(
                        "[32BIT] MISSING Vocalizer dependencies: " +
                            missing.joinToString(", ") +
                            " | تبعيات مفقودة"
                    )
                }
            }
        }
    }
}

// يُشغَّل مع بناء الوحدة المالكة لملفات jniLibs، فيسري على بناء التطبيق.
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifySoAlignment)
}
