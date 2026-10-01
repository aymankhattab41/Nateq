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

    /**
     * سعةُ التخزين القصوى بالأسطر — كُبِّرت إلى 20 ألف سطر لأن جلسة
     * إعادة إنتاج قد تمتدّ دقائق وتغرق في ضجيج النظام قبل أن تصل
     * الأسطرُ المفيدة. ومع ذلك يبقى التقرير مُقيَّداً بسقفٍ مستقلّ.
     */
    private const val MAX_LINES = 20_000

    /**
     * سقفُ الذاكرة بالحروف (~6 ميغابايت) يمنع استنزاف الذاكرة في جلسة
     * طويلة؛ يُقصُّ من **الرأس** فيبقى الترتيبُ الزمني صحيحاً — مهمٌّ
     * لأن تشخيص ترتيب الأحداث (بثّ ثم نطق) يبطل بترتّبها.
     */
    private const val MAX_CHARS = 6_000_000

    /**
     * رصيدُ أسطرٍ سابقة يُلتقط مع بدء المراقبة بدل السطر الأخير فقط،
     * فلا تضيع الأحداثُ التي وقعت قبل الضغطة بلحظة (نمطٌ شائع: يبدأ
     * الرنين ثم يتّجه المستخدم إلى الإعدادات).
     */
    private const val START_BACKLOG_LINES = "500"

    /**
     * سطرُ logcat بصيغة الوقت: `10-01 14:48:56.882  4433 4643 D Wmf:`.
     * المجموعةُ الأولى هي حقلُ المستوى — وهو موضعُه الصحيح.
     */
    private val LOG_LINE = Regex(
        """^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+\d+""" +
            """\s+([VDIWEFAS])\s.*"""
    )

    /** هل هذا السطر من وسوم التطبيق التشخيصية (تُبرز في رأس التقرير)؟ */
    internal fun isDiagnosticLine(line: String): Boolean =
        line.contains("NATEQ_")

    private val lock = Any()
    private val buffer = ArrayDeque<String>()

    @Volatile
    private var recording = false
    private var readerThread: Thread? = null
    private var process: Process? = null

    /** مجموع حروف المخزن لحساب سقف الذاكرة دون مسحٍ لكل سطر. */
    private var charCount = 0

    private val defaultReaderProvider: () -> BufferedReader? = {
        runCatching {
            val p = Runtime.getRuntime().exec(
                arrayOf(
                    "logcat", "-T", START_BACKLOG_LINES,
                    "--pid", android.os.Process.myPid().toString()
                )
            )
            synchronized(lock) { process = p }
            p.inputStream.bufferedReader()
        }.getOrNull()
    }

    /**
     * مستوىُ خطِّ السطر التقني (`D` أو `W` أو `E`…) أو null.
     *
     * كان التقرير يكشف الخطورة بـ`line[0] == 'E'`، وهو خطأٌ صامت: أسطر
     * logcat تبدأ بالتاريخ، فالحرفُ الأول رقمٌ لا مستوى — فلم تُملأ
     * قسمةُ الأخطاء ولا التحذيرات ولا مرّة. الآن يُقرأ حقلُ المستوى
     * من موضعه الصحيح، مع احتياطٍ للسطور تبدأ بالمستوى مباشرة.
     */
    internal fun severityOf(line: String): Char? =
        LOG_LINE.find(line)?.groupValues?.getOrNull(1)?.firstOrNull()
            ?: line.firstOrNull()?.takeIf { it in "VDIWEF" }

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
            charCount += line.length
            trimHead(buffer, MAX_LINES)
            // سقفُ الذاكرة بالحروف — يُقصُّ من الرأس فيبقى الترتيب
            // الزمني صحيحاً (القصُّ من الوسط يفسد تسلسل الأحداث).
            while (charCount > MAX_CHARS && buffer.size > 1) {
                charCount -= buffer.removeFirst().length
            }
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