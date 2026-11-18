package com.aymankhattab.nateq.core.audio.enginehost

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * تقرير تشخيص انهيار العملية المضيفة HOST_EOF (إصدار 10712).
 *
 * يسجل: كود خروج العملية المضيفة (:engine_host)، الإشارة المنتهية بها
 * (SIGSEGV / SIGABRT)، سجل stderr، مدة بقائها حية، معمارية 64-بت، أسطر
 * crash/DEBUG/libc من logcat، وآخر نداء وإطار وصل عبر الأنبوب.
 */
object HostCrashDiagnostics {

    data class HostExitInfo(
        val found: Boolean,
        val pid: Int = -1,
        val reasonCode: Int = -1,
        val reasonString: String = "",
        val statusCode: Int = -1,
        val statusString: String = "",
        val description: String = "",
        val timestamp: Long = 0L,
        val trace: String = ""
    )

    fun formatReason(reason: Int): String = when (reason) {
        1 -> "REASON_EXIT_SELF (1) - خرجت العملية بنفسها"
        2 -> "REASON_SIGNALED (2) - قُتلت العملية بإشارة نظام"
        3 -> "REASON_LOW_MEMORY (3) - إنهاء لنقص الذاكرة (LMK)"
        4 -> "REASON_CRASH (4) - انهيار Java/Kotlin غير مُعالج"
        5 -> "REASON_CRASH_NATIVE (5) - انهيار أصلي C/C++ (Native Crash)"
        6 -> "REASON_ANR (6) - تجمد التطبيق وعدم الاستجابة (ANR)"
        7 -> "REASON_INITIALIZATION_FAILURE (7) - فشل تهيئة العملية"
        8 -> "REASON_PERMISSION_CHANGE (8) - تغير الأذونات"
        9 -> "REASON_EXCESSIVE_RESOURCE_USAGE (9) - إفراط في الموارد"
        10 -> "REASON_USER_REQUESTED (10) - طلب المستخدم"
        11 -> "REASON_USER_STOPPED (11) - إيقاف المستخدم"
        12 -> "REASON_DEPENDENCY_DIED (12) - موت تبعية"
        13 -> "REASON_OTHER (13) - سبب آخر"
        else -> "REASON_UNKNOWN ($reason)"
    }

    fun formatSignal(status: Int): String = when (status) {
        1 -> "1 (SIGHUP - تعليق)"
        2 -> "2 (SIGINT - مقاطعة)"
        3 -> "3 (SIGQUIT - إنهاء مع تفريغ)"
        4 -> "4 (SIGILL - تعليمة غير صالحة Illegal Instruction)"
        5 -> "5 (SIGTRAP - مصيدة تتبع Trace Trap)"
        6 -> "6 (SIGABRT - إجهاض abort())"
        7 -> "7 (SIGBUS - خطأ ناقل محاذاة Bus Error)"
        8 -> "8 (SIGFPE - استثناء نقطة عائمة)"
        9 -> "9 (SIGKILL - قتل جبري من النظام)"
        11 -> "11 (SIGSEGV - وصول غير مصرح للذاكرة Segmentation Fault)"
        13 -> "13 (SIGPIPE - انكسار الأنبوب Broken Pipe)"
        15 -> "15 (SIGTERM - إنهاء اعتيادي)"
        else -> "$status"
    }

    fun collectExitInfo(context: Context): HostExitInfo {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return HostExitInfo(
                found = false,
                description = "getHistoricalProcessExitReasons غير مدعوم " +
                    "(أقل من API 30)"
            )
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE)
            as? ActivityManager ?: return HostExitInfo(
            found = false,
            description = "تعذر الحصول على ActivityManager"
        )

        var hostExit: ApplicationExitInfo? = null
        for (poll in 0..2) {
            val reasons = runCatching {
                am.getHistoricalProcessExitReasons(context.packageName, 0, 15)
            }.getOrNull()
            hostExit = reasons?.firstOrNull {
                it.processName.endsWith(":engine_host")
            }
            if (hostExit != null) break
            SystemClock.sleep(100L)
        }

        if (hostExit == null) {
            return HostExitInfo(
                found = false,
                description = "لم يُعثر على سجل خروج لـ :engine_host في النظام"
            )
        }

        val traceStr = runCatching {
            hostExit.traceInputStream?.use { stream ->
                stream.bufferedReader(StandardCharsets.UTF_8).readText()
            }
        }.getOrNull().orEmpty()

        return HostExitInfo(
            found = true,
            pid = hostExit.pid,
            reasonCode = hostExit.reason,
            reasonString = formatReason(hostExit.reason),
            statusCode = hostExit.status,
            statusString = formatSignal(hostExit.status),
            description = hostExit.description ?: "",
            timestamp = hostExit.timestamp,
            trace = traceStr
        )
    }

    fun collectStderr(context: Context, exitTrace: String): String {
        val stderrFile = File(context.cacheDir, "engine_host_stderr.log")
        if (stderrFile.exists() && stderrFile.length() > 0L) {
            val lines = runCatching {
                stderrFile.readLines(StandardCharsets.UTF_8).takeLast(100)
            }.getOrNull()
            if (!lines.isNullOrEmpty()) {
                return lines.joinToString("\n")
            }
        }
        if (exitTrace.isNotBlank()) {
            val lines = exitTrace.lines().takeLast(100)
            return lines.joinToString("\n")
        }
        return "(لم يُسجّل أي نص في stderr أو traceInputStream)"
    }

    fun getArchInfo(): String {
        val is64 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Process.is64Bit()
        } else {
            false
        }
        val primary = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val supported64 = Build.SUPPORTED_64_BIT_ABIS
            .joinToString(", ")
            .ifBlank { "لا يوجد" }
        val supported32 = Build.SUPPORTED_32_BIT_ABIS
            .joinToString(", ")
            .ifBlank { "لا يوجد" }
        return "هل تعمل بنسخة 64 بت: " +
            "${if (is64) "نعم (64-bit)" else "لا (32-bit)"}\n" +
            "  - المعمارية الأساسية للعملية: $primary\n" +
            "  - معماريات 64-بت المدعومة: $supported64\n" +
            "  - معماريات 32-بت المدعومة: $supported32\n" +
            "  - طبيعة العملية المضيفة: :engine_host خدمة محلية منبثقة " +
            "من نفس حزمة التطبيق وتعمل بنفس معمارية العملية الأم"
    }

    fun collectCrashLogcat(maxLines: Int = 300): List<String> {
        return runCatching {
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "-t", "1500")
            )
            val lines = proc.inputStream
                .bufferedReader(StandardCharsets.UTF_8)
                .readLines()
            proc.waitFor()
            val crashKeywords = listOf(
                "crash", "tombstone", "DEBUG", "crash_dump", "libc",
                "Fatal signal", "Abort message", "SIGSEGV", "SIGABRT",
                "SIGILL", "SIGBUS", "signal 11", "signal 6", "fault addr",
                "backtrace:", "engine_host", "acattsandroid", "tts_function",
                "BABILE", "terminating with", "eb=-"
            )
            val matched = lines.filter { line ->
                crashKeywords.any { kw ->
                    line.contains(kw, ignoreCase = true)
                }
            }
            if (matched.isNotEmpty()) {
                matched.takeLast(maxLines)
            } else {
                lines.takeLast(50)
            }
        }.getOrElse { t ->
            listOf("(تعذر جمع أسطر logcat: ${t.message})")
        }
    }

    fun formatTime(timeMs: Long): String {
        if (timeMs <= 0L) return "—"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return sdf.format(Date(timeMs))
    }

    fun buildHostReport(
        context: Context,
        engineId: String,
        synthResult: HostResult
    ): String {
        val exitInfo = collectExitInfo(context)
        val stderrText = SpeechTextRedactor.redactQuotedText(
            collectStderr(context, exitInfo.trace)
        )
        val logcatLines = collectCrashLogcat(300).map {
            SpeechTextRedactor.redactQuotedText(it)
        }

        val connectedWallMs = EngineHostClient.hostConnectedAtWallMs
        val connectedUptimeMs = EngineHostClient.hostConnectedAtUptimeMs
        val lifetimeMs = if (exitInfo.timestamp > 0L && connectedWallMs > 0L) {
            maxOf(0L, exitInfo.timestamp - connectedWallMs)
        } else if (connectedUptimeMs > 0L) {
            maxOf(0L, SystemClock.uptimeMillis() - connectedUptimeMs)
        } else {
            0L
        }
        val lifetimeStr = if (lifetimeMs > 0L) {
            val sec = String.format(Locale.US, "%.2f", lifetimeMs / 1000.0)
            "$lifetimeMs مللي ثانية ($sec ثانية)"
        } else {
            "(غير محدد بدقة / أقل من 1ms)"
        }

        // البند ١٥: تقارير الانهيار تُعرض للمستخدم، فتُنظَّف من أي نص
        // منطوق قبل إدراجها (كان breadcrumb يحتوي text='...' كاملاً).
        val lastAcapelaCall = SpeechTextRedactor.redactQuotedText(
            runCatching {
                val f = File(context.cacheDir, "last_acapela_call.txt")
                if (f.exists()) f.readText(StandardCharsets.UTF_8).trim()
                else "(لا يوجد نداء داخلي مسجل)"
            }.getOrNull() ?: "(تعذر قراءة نداء Acapela الداخلي)"
        )

        val trialsProgress = SpeechTextRedactor.redactQuotedText(
            runCatching {
                val f = File(context.cacheDir, "acapela_trials_progress.txt")
                if (f.exists()) f.readText(StandardCharsets.UTF_8).trim()
                else ""
            }.getOrNull().orEmpty()
        )

        val sb = StringBuilder()
        sb.appendLine(
            "=== [تقرير تشخيص انهيار العملية المضيفة " +
                "HOST_EOF (إصدار 10712)] ==="
        )
        sb.appendLine("")
        sb.appendLine("1) تحديد العملية والخيط والأنبوب:")
        sb.appendLine(
            "  - العملية المضيفة (HOST Process): " +
                "com.aymankhattab.nateq:engine_host"
        )
        sb.appendLine(
            "    (المعرّفة في AndroidManifest.xml كخدمة معزولة " +
                "EmbeddedEngineHostService)"
        )
        sb.appendLine(
            "  - خيط التنفيذ (HOST Thread): خيط executor المسَمّى " +
                "'engine-host-synth'"
        )
        sb.appendLine(
            "    داخل EmbeddedEngineHostService المنفّذ لنداء " +
                "session.speak(...)"
        )
        sb.appendLine(
            "  - الأنبوب (Pipe): ParcelFileDescriptor.createPipe() في " +
                "EngineHostClient.synthesize"
        )
        sb.appendLine(
            "    * طرف القراءة (readFd): في العملية الأمامية " +
                "(خيط 'engine-host-read')"
        )
        sb.appendLine(
            "    * طرف الكتابة (writeFd): سُلِّم عبر Binder إلى " +
                ":engine_host وربط بـ PcmSink"
        )
        val codePart = synthResult.code ?: "—"
        val msgPart = synthResult.message ?: "—"
        sb.appendLine(
            "  - نتيجة التوليد: ok=${synthResult.ok} " +
                "[$codePart]: $msgPart"
        )
        sb.appendLine("")

        sb.appendLine("2) كود خروج العملية المضيفة والإشارة المنتهية بها:")
        if (exitInfo.found) {
            sb.appendLine(
                "  - سبب الخروج (Exit Reason): ${exitInfo.reasonString}"
            )
            sb.appendLine(
                "  - كود الحالة / الإشارة (Status / Signal): " +
                    exitInfo.statusString
            )
            val descPart = exitInfo.description.ifBlank { "لا يوجد" }
            sb.appendLine("  - وصف النظام (Description): $descPart")
            sb.appendLine("  - PID العملية المنهارة: ${exitInfo.pid}")
            sb.appendLine(
                "  - وقت الخروج: ${formatTime(exitInfo.timestamp)}"
            )
        } else {
            sb.appendLine("  - حالة السجل: ${exitInfo.description}")
        }
        sb.appendLine("")

        sb.appendLine("3) مدة بقاء العملية المضيفة حية:")
        sb.appendLine("  - مدة البقاء: $lifetimeStr")
        sb.appendLine(
            "  - وقت الاتصال بالمضيف: ${formatTime(connectedWallMs)}"
        )
        sb.appendLine("")

        sb.appendLine("4) معمارية التشغيل (64 بت / 32 بت):")
        sb.appendLine("  ${getArchInfo()}")
        sb.appendLine("")

        sb.appendLine("5) آخر ما كتبته العملية إلى stderr:")
        sb.appendLine(stderrText.prependIndent("  "))
        sb.appendLine("")

        sb.appendLine("6) آخر نداء أُرسل عبر الأنبوب وآخر إطار وصل:")
        sb.appendLine("  - آخر نداء أُرسل للمضيف:")
        sb.appendLine("    ${EngineHostClient.lastCallSent}")
        sb.appendLine(
            "  - وقت إرسال النداء: " +
                formatTime(EngineHostClient.lastCallSentTimeMs)
        )
        sb.appendLine(
            "  - آخر إطار وصل عبر الأنبوب: " +
                EngineHostClient.lastFrameReceived
        )
        sb.appendLine(
            "  - إجمالي الإطارات المستلمة عبر الأنبوب: " +
                EngineHostClient.framesReceivedCount
        )
        sb.appendLine("  - آخر نداء Acapela داخلي قبل الانقطاع:")
        sb.appendLine("    $lastAcapelaCall")
        if (trialsProgress.isNotBlank()) {
            sb.appendLine("  - المحاولات المسجلة قبل الانقطاع:")
            sb.appendLine(trialsProgress.prependIndent("    "))
        }
        sb.appendLine("")

        sb.appendLine(
            "7) أسطر crash و tombstone و DEBUG و libc من logcat:"
        )
        if (logcatLines.isNotEmpty()) {
            logcatLines.forEach { sb.appendLine("  $it") }
        } else {
            sb.appendLine("  (لم يُعثر على أسطر مطابقة في logcat)")
        }

        return sb.toString()
    }
}
