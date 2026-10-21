package com.aymankhattab.nateq.settings.banking

import android.content.pm.PackageManager
import android.os.Build

/**
 * قراءةُ حزمةِ تثبيتِ التطبيق (ناطق) بأمانٍ على كل الإصدارات المدعومة.
 *
 * `getInstallSourceInfo` دُخِل في API 30 (Android 11)، وتطبيقنا يستهدف
 * `minSdk = 24`؛ فقراءتُه مباشرةً على 24–29 ترمي `NoSuchMethodError`
 * وقت التنفيذ لا وقت البناء. لذا المسارُ اثنان:
 *  - API 30+ : `getInstallSourceInfo(packageName).installingPackageName`
 *  - أقلّ من 30 : `getInstallerPackageName(packageName)` (مهجور لكنه
 *    المسار الوحيد المتاح على تلك النسخ).
 *
 * **الفارغ ≠ خطأ**: من لا نعرف حزمتَه المثبِّتة فهو مُثبَّتٌ من خارج
 * المتجر (تصنيفٌ احتياطيٌّ أمنيّ لا نفترض المتجرَ فيه بلا دليل).
 */
object InstallSourceReader {

    /** نتيجة القراءة — الاسم فقط كافٍ للاستدلال، والباقي تبع. */
    data class InstallSource(
        val installingPackageName: String?
    )

    /** قراءةٌ دفاعية: أي فشل يُرجع قراءةً فارغة لا استثناءً. */
    fun read(
        packageManager: PackageManager,
        packageName: String
    ): InstallSource = InstallSource(
        installingPackageName = readInstallingPackage(
            packageManager,
            packageName
        )
    )

    private fun readInstallingPackage(
        packageManager: PackageManager,
        packageName: String
    ): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName)
                .installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
    }.getOrNull()
}
