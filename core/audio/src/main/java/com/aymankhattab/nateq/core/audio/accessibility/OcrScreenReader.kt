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

/**
 * قراءة العنصر المركّز بالتصوير والتعرّف الضوئي (ع٤١-ب12).
 *
 * - تتطلب أندرويد 11 (API 30) لالتقاط الشاشة عبر takeScreenshot.
 * - تُقصّ اللقطة على حدود العنصر المركّز إن وُجد.
 * - بيانات اللغة (ara.traineddata) تُنزَّل مرة واحدة إلى تخزين التطبيق
 *   الخاص عند أول استعمال (لا تُحزَم في الحزمة).
 * - خصوصية: لا تُحفظ الصور ولا يُرسل المحتوى لأي جهة خارجية.
 */
object OcrScreenReader {

    private const val TAG = "NATEQ_A11Y"
    private const val LANGUAGE = "ara"
    private const val MIN_DATA_BYTES = 100_000L
    private const val DATA_URL =
        "https://raw.githubusercontent.com/tesseract-ocr/" +
            "tessdata_fast/main/ara.traineddata"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val THREAD_NAME = "NATEQ_OCR"

    private val lock = Any()
    private var workerThread: HandlerThread? = null

    fun readFocusedElement(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            announce(service, UNSUPPORTED)
            return
        }
        val focusBounds = AccessibilityBridge.focusSnapshot()
            ?.boundsInScreen
            ?.takeUnless { it.isEmpty }
        captureScreenshot(service, focusBounds)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenshot(
        service: AccessibilityService,
        bounds: Rect?
    ) {
        runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(
                        result: AccessibilityService.ScreenshotResult
                    ) {
                        val bitmap = decode(result)
                        if (bitmap == null) {
                            announce(service, CAPTURE_FAILED)
                            return
                        }
                        handler().post { process(service, bitmap, bounds) }
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
        bounds: Rect?
    ) {
        var image = source
        try {
            val cropped = crop(source, bounds)
            if (cropped !== source) {
                source.recycle()
                image = cropped
            }
            val dataDir = ensureLanguageData(service.applicationContext)
            if (dataDir == null) {
                announce(service, DATA_FAILED)
                return
            }
            recognize(service, image, dataDir)
        } finally {
            if (!image.isRecycled) image.recycle()
        }
    }

    private fun recognize(
        service: AccessibilityService,
        bitmap: Bitmap,
        dataDir: File
    ) {
        val api = TessBaseAPI()
        try {
            if (!api.init(dataDir.absolutePath, LANGUAGE)) {
                announce(service, ENGINE_FAILED)
                return
            }
            api.setImage(bitmap)
            val text = api.utF8Text?.trim().orEmpty()
            announce(service, if (text.isEmpty()) NO_TEXT else text)
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
        val temp = File(tessdata, "$LANGUAGE.traineddata.tmp")
        return try {
            download(DATA_URL, temp)
            if (!moveIntoPlace(temp, target)) return null
            context.filesDir
        } catch (t: Throwable) {
            Log.w(TAG, "language data download failed", t)
            temp.delete()
            null
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
            connection.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        if (dest.length() < MIN_DATA_BYTES) error("incomplete language data")
    }

    private fun handler(): Handler {
        synchronized(lock) {
            var thread = workerThread
            if (thread == null) {
                thread = HandlerThread(THREAD_NAME)
                thread.start()
                workerThread = thread
            }
            return Handler(thread.looper)
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
    private const val ENGINE_FAILED = "تعذّر تهيئة محرّك قراءة الصور"
    private const val READ_FAILED = "تعذّر قراءة الصورة"
}
