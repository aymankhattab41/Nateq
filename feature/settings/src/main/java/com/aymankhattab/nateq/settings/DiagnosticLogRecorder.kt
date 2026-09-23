package com.aymankhattab.nateq.settings

import android.util.Log
import java.io.BufferedReader
import kotlin.concurrent.thread

/**
 * مسجِّل مراقبة السجل التقني لعملية التطبيق (logcat) أثناء جلسة
 * إعادة إنتاج مشكلة الصوت.
 *
 * الضغطة الأولى على زر «الإبلاغ عن خطأ» تُطلق [start]: خيط خلفي (Daemon)
 * يقود `logcat` في وضع التدفُّق (`-T 1` لبدء القراءة من السطر الأخير)،
 * فتمتلئ معلّقة دائرية محدودة السعة بأسطر العملية أثناء عمل المستخدم في
 * أي شاشة (لا يشترط البقاء في الإعدادات). الضغطة الثانية «تم — إنهاء
 * وجمع التقرير» تُطلق [stop] فتُدمَّر عملية logcat وتُعاد لقطة ما التقطه
 * السجل حتى تلك اللحظة — فيُبنى التقرير منها بدل لقطة لحظة الضغط فقط.
 *
 * [recording] هي علامة التفرد الوحيدة وتسوَّى في [start]/[stop] فقط؛
 * لا يلمسها خيط القارئ حتى لا تنكسر محددات الاختبار والترابط.
 */
internal object DiagnosticLogRecorder {

    private const val TAG = "NATEQ_TTS"
    private const val MAX_LINES = 4000

    private val lock = Any()
    private val buffer = ArrayDeque<String>()

    @Volatile
    private var recording = false
    private var readerThread: Thread? = null
    private var process: Process? = null

    private val defaultReaderProvider: () -> BufferedReader? = {
        runCatching {
            val p = Runtime.getRuntime().exec(
                arrayOf(
                    "logcat", "-T", "1",
                    "--pid", android.os.Process.myPid().toString()
                )
            )
            synchronized(lock) { process = p }
            p.inputStream.bufferedReader()
        }.getOrNull()
    }

    /** واجهة إنتاج قارئ التدفُّق — قابلة للاستبدال في الاختبارات
     *  لتجنّب تشغيل `logcat` حقيقي على جهاز القياس. */
    internal var readerProvider: () -> BufferedReader? =
        defaultReaderProvider

    /** إعادة مصدر القارئ إلى الافتراضي (يستدعيها اختبارات JUnit
     *  بعد استبدالها بمصدر اصطناعي). */
    internal fun resetToDefaultProvider() {
        readerProvider = defaultReaderProvider
    }

    fun isRecording(): Boolean = synchronized(lock) { recording }

    /** يبدأ جلسة مراقبة جديدة؛ false إذا كانت جلسة جارية أصلاً. */
    fun start(): Boolean {
        synchronized(lock) {
            if (recording) return false
            recording = true
            buffer.clear()
            buffer.addLast("=== بداية التقاط السجل التقني (${now()}) ===")
        }
        readerThread = thread(
            start = true,
            isDaemon = true,
            name = "nateq-log-recorder"
        ) {
            val reader = readerProvider()
            if (reader == null) {
                Log.e(TAG, "logcat recorder: تعذّر فتح قارئ السجل")
                return@thread
            }
            try {
                reader.useLines { lines ->
                    for (line in lines) {
                        if (!isRecording()) break
                        appendBounded(line)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "logcat recorder failed", e)
            } finally {
                synchronized(lock) { process = null }
            }
        }
        return true
    }

    /** يوقف الجلسة ويرجع لقطة الأسطر الملتقطة حتى هذه اللحظة. */
    fun stop(): List<String> {
        val snapshot: List<String>
        synchronized(lock) {
            if (!recording) return emptyList()
            recording = false
            process?.let { p -> runCatching { p.destroy() } }
            process = null
            snapshot = buffer.toList()
        }
        return snapshot
    }

    private fun appendBounded(line: String) {
        synchronized(lock) {
            if (!recording) return
            buffer.addLast(line)
            trimHead(buffer, MAX_LINES)
        }
    }

    /** يبقي القائمة ضمن السعة القصوى بإسقاط الأقدم من الرأس (نقي—يُختبر). */
    internal fun trimHead(list: MutableList<String>, maxLines: Int) {
        while (list.size > maxLines) list.removeAt(0)
    }

    private fun now(): String =
        java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
        ).format(java.util.Date())
}