package com.aymankhattab.nateq.core.audio.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * قراءة العنصر المركّز بالتصوير والتعرّف الضوئي (OCR):
 * - عملية واحدة قيد التنفيذ أو الأحدث وإلغاء الأقدم وتحرير Bitmap فوراً.
 * - منع تكديس لقطات الشاشة أثناء تنزيل بيانات اللغة.
 * - تنزيل محدود محدد الحجم والحد الأقصى (تجنب الفروع المتحولة).
 * - ربط HandlerThread وTesseract بدورة حياة الخدمة مع إغلاق آمن.
 * - تعرّف محلي خالص دون رفع الصور أو حفظها في السجلات.
 */
object OcrScreenReader {

    private const val TAG = "NATEQ_A11Y"
    private const val LANGUAGE = "ara"
    private const val MIN_DATA_BYTES = 100_000L
    private const val MAX_DATA_BYTES = 10_000_000L // 10 ميغابايت كحد أقصى

    // تثبيت المسار على وسم محدد مستقر (4.1.0) بدلاً من فرع main المتحول
    private const val DATA_URL =
        "https://github.com/tesseract-ocr/tessdata_fast/" +
            "raw/4.1.0/ara.traineddata"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val THREAD_NAME = "NATEQ_OCR"

    private val lock = Any()
    private var workerThread: HandlerThread? = null
    private val inFlightRequestId = AtomicLong(0L)
    private val isDownloadingData = AtomicBoolean(false)

    fun readFocusedElement(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            announce(service, UNSUPPORTED)
            return
        }

        // منع تكديس اللقطات أثناء تنزيل بيانات اللغة
        if (isDownloadingData.get()) {
            announce(service, DATA_DOWNLOADING_WAIT)
            return
        }

        val focusBounds = AccessibilityBridge.focusSnapshot()
            ?.boundsInScreen
            ?.takeUnless { it.isEmpty }

        val requestId = inFlightRequestId.incrementAndGet()
        captureScreenshot(service, focusBounds, requestId)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenshot(
        service: AccessibilityService,
        bounds: Rect?,
        requestId: Long
    ) {
        runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(
                        result: AccessibilityService.ScreenshotResult
                    ) {
                        // إلغاء الطلب وتحرير الذاكرة إن تجاوزه طلب أحدث
                        if (requestId != inFlightRequestId.get()) {
                            runCatching { result.hardwareBuffer.close() }
                            return
                        }

                        val bitmap = decode(result)
                        if (bitmap == null) {
                            announce(service, CAPTURE_FAILED)
                            return
                        }

                        if (requestId != inFlightRequestId.get()) {
                            bitmap.recycle()
                            return
                        }

                        handler().post {
                            if (requestId == inFlightRequestId.get()) {
                                process(service, bitmap, bounds, requestId)
                            } else {
                                bitmap.recycle()
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "screenshot error code=$errorCode")
                        announce(service, CAPTURE_FAILED)
                    }
                }
            )
        }.onFailure {
            Log.w(TAG, "takeScreenshot failed", it)
            announce(service, CAPTURE_FAILED)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun decode(
        result: AccessibilityService.ScreenshotResult
    ): Bitmap? {
        return try {
            val buffer = result.hardwareBuffer
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
            val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
            buffer.close()
            copy
        } catch (t: Throwable) {
            Log.w(TAG, "screenshot decode failed", t)
            null
        }
    }

    private fun process(
        service: AccessibilityService,
        source: Bitmap,
        bounds: Rect?,
        requestId: Long
    ) {
        var image = source
        try {
            if (requestId != inFlightRequestId.get()) return

            val cropped = crop(source, bounds)
            if (cropped !== source) {
                source.recycle()
                image = cropped
            }

            if (requestId != inFlightRequestId.get()) return

            val dataDir = ensureLanguageData(service.applicationContext)
            if (dataDir == null) {
                announce(service, DATA_FAILED)
                return
            }

            if (requestId != inFlightRequestId.get()) return

            recognize(service, image, dataDir, requestId)
        } finally {
            if (!image.isRecycled) {
                image.recycle()
            }
        }
    }

    private fun recognize(
        service: AccessibilityService,
        bitmap: Bitmap,
        dataDir: File,
        requestId: Long
    ) {
        val api = TessBaseAPI()
        try {
            if (!api.init(dataDir.absolutePath, LANGUAGE)) {
                announce(service, ENGINE_FAILED)
                return
            }
            if (requestId != inFlightRequestId.get()) return

            api.setImage(bitmap)
            val text = api.utF8Text?.trim().orEmpty()

            if (requestId == inFlightRequestId.get()) {
                announce(service, if (text.isEmpty()) NO_TEXT else text)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "OCR failed", t)
            announce(service, READ_FAILED)
        } finally {
            runCatching { api.recycle() }
        }
    }

    private fun crop(source: Bitmap, bounds: Rect?): Bitmap {
        if (bounds == null) return source
        val left = bounds.left.coerceIn(0, source.width)
        val top = bounds.top.coerceIn(0, source.height)
        val right = bounds.right.coerceIn(left, source.width)
        val bottom = bounds.bottom.coerceIn(top, source.height)
        if (right - left <= 0 || bottom - top <= 0) return source
        return Bitmap.createBitmap(
            source,
            left,
            top,
            right - left,
            bottom - top
        )
    }

    private fun ensureLanguageData(context: Context): File? {
        val tessdata = File(context.filesDir, "tessdata").apply { mkdirs() }
        val target = File(tessdata, "$LANGUAGE.traineddata")
        if (target.exists() && target.length() >= MIN_DATA_BYTES) {
            return context.filesDir
        }

        if (!isDownloadingData.compareAndSet(false, true)) {
            return null
        }

        val temp = File(tessdata, "$LANGUAGE.traineddata.tmp")
        return try {
            download(DATA_URL, temp)
            if (!moveIntoPlace(temp, target)) return null
            context.filesDir
        } catch (t: Throwable) {
            Log.w(TAG, "language data download failed", t)
            temp.delete()
            null
        } finally {
            isDownloadingData.set(false)
        }
    }

    private fun moveIntoPlace(temp: File, target: File): Boolean {
        if (target.exists()) target.delete()
        if (temp.renameTo(target)) return true
        return runCatching {
            temp.copyTo(target, overwrite = true)
            temp.delete()
            true
        }.getOrDefault(false)
    }

    private fun download(url: String, dest: File) {
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")

            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_DATA_BYTES) {
                error("تجاوز ملف اللغة الحد الأقصى المسموح")
            }

            var totalRead = 0L
            val buffer = ByteArray(8192)

            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    var n = input.read(buffer)
                    while (n != -1) {
                        totalRead += n
                        if (totalRead > MAX_DATA_BYTES) {
                            error("تجاوز تدفق البيانات الحد الأقصى المسموح")
                        }
                        output.write(buffer, 0, n)
                        n = input.read(buffer)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (dest.length() < MIN_DATA_BYTES) {
            error("ملف بيانات اللغة غير مكتمل")
        }
    }

    private fun handler(): Handler {
        synchronized(lock) {
            var thread = workerThread
            if (thread == null || !thread.isAlive) {
                thread = HandlerThread(THREAD_NAME)
                thread.start()
                workerThread = thread
            }
            return Handler(thread.looper)
        }
    }

    /**
     * إغلاق خيط المعالجة الخلفية وإلغاء أي مهام معلقة عند تدمير الخدمة.
     */
    fun shutdown() {
        synchronized(lock) {
            inFlightRequestId.incrementAndGet()
            workerThread?.quitSafely()
            workerThread = null
        }
    }

    private fun announce(context: Context, text: String) {
        A11ySpeechCoordinator.speakAnnouncement(
            context = context.applicationContext,
            text = text,
            isInterruptAllowed = true
        )
    }

    private const val UNSUPPORTED = "قراءة الصور تتطلب أندرويد 11 أو أحدث"
    private const val CAPTURE_FAILED = "تعذّر التقاط الشاشة"
    private const val NO_TEXT = "لا يوجد نصّ في العنصر"
    private const val DATA_FAILED = "تعذّر تجهيز بيانات اللغة"
    private const val DATA_DOWNLOADING_WAIT =
        "جاري تجهيز بيانات قراءة الصور، يرجى الانتظار"
    private const val ENGINE_FAILED = "تعذّر تهيئة محرّك قراءة الصور"
    private const val READ_FAILED = "تعذّر قراءة الصورة"
}
