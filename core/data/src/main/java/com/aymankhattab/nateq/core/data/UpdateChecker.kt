package com.aymankhattab.nateq.core.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.aymankhattab.nateq.core.common.AppDispatchers
import com.aymankhattab.nateq.util.NateqJson
import com.aymankhattab.nateq.util.optArray
import com.aymankhattab.nateq.util.optMember
import com.aymankhattab.nateq.util.optObject
import com.aymankhattab.nateq.util.optString
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.withContext

/**
 * فحص التحديثات وتنزيل الـ APK الجديد من GitHub Releases.
 * يستخدم مستودع المشروع العام كخادم توزيع.
 */
object UpdateChecker {

    private const val REPO = "aymankhattab41/Nateq"
    private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK_NAME = "lord_tts.apk"

    sealed class CheckResult {
        data class UpdateAvailable(val tag: String, val apkUrl: String) : CheckResult()
        object UpToDate : CheckResult()
        object NetworkError : CheckResult()
    }

    suspend fun check(currentVersionCode: Int): CheckResult =
        withContext(AppDispatchers.io) {
            try {
                val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    if (conn.responseCode != 200) return@withContext CheckResult.NetworkError
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    // تجزئة استجابة GitHub عبر مظلة JSON الموحّدة (البند 3):
                    // استجابة فاسدة/غير كائنية تُعامل كخطأ شبكة كما كان JSONObject سابقاً.
                    val root = NateqJson.parseObject(body)
                        ?: return@withContext CheckResult.NetworkError
                    val tag = root.optString("tag_name")
                    val remoteCode = tag.trimStart('v', 'V')
                        .takeWhile { it.isDigit() }
                        .toIntOrNull() ?: return@withContext CheckResult.UpToDate
                    val assets = root.optArray("assets")
                    val apkAsset = (0 until (assets?.size() ?: 0))
                        .mapNotNull { assets?.get(it)?.optObject() }
                        .firstOrNull { it.optString("name") == APK_NAME }
                    if (remoteCode > currentVersionCode && apkAsset != null) {
                        val urlEl = apkAsset.optMember("browser_download_url")
                        if (urlEl == null) return@withContext CheckResult.NetworkError
                        CheckResult.UpdateAvailable(tag, urlEl.optString())
                    } else {
                        CheckResult.UpToDate
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                CheckResult.NetworkError
            }
        }

    /** مسار مجلد التخزين المحلي للتنزيلات.
     *  يستخدم التخزين الخارجي المُخصَّص للتطبيق (getExternalFilesDir) لأن
     *  DownloadManager على أندرويد 10+ يرفض الوجهات داخل app_internal
     *  (SecurityException: Unsupported path) ويقبل فقط مسارات التخزين الخارجي. */
    private fun downloadsDir(context: Context): File =
        File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }

    /** مسار ملف الـ APK المُنزَل. */
    fun downloadedApk(context: Context): File =
        File(downloadsDir(context), APK_NAME)

    /**
     * يبدأ تنزيل الـ APK عبر DownloadManager ويُعيد معرّف التنزيل.
     * المستدعي يسجّل مستمع ACTION_DOWNLOAD_COMPLETE ويتفقّد الملف عند اكتماله.
     */
    fun enqueueDownload(context: Context, apkUrl: String): Long {
        val destination = File(downloadsDir(context), APK_NAME)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Lord TTS update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_MOBILE or DownloadManager.Request.NETWORK_WIFI
            )
        return manager.enqueue(request)
    }

    /** فتح شاشة تثبيت النظام لملف APK محلي عبر FileProvider. */
    fun promptInstall(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) return false
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}