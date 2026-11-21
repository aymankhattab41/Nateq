package com.aymankhattab.nateq

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.settings.ReadLaterProcessTextActivity
import com.aymankhattab.nateq.settings.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReadLaterManifestTest {

    private val context =
        ApplicationProvider.getApplicationContext<android.content.Context>()
    private val pm = context.packageManager

    @Test
    fun actionSend_withTextPlain_resolvesToSettingsActivity() {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "نص تجريبي")
        }
        val resolved = pm.queryIntentActivities(
            sendIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        val match = resolved.firstOrNull {
            it.activityInfo.packageName == context.packageName &&
                it.activityInfo.name == SettingsActivity::class.java.name
        }
        assertNotNull(
            "SettingsActivity يجب أن يستقبل ACTION_SEND بنوع text/plain",
            match
        )
    }

    @Test
    fun actionSend_withZip_stillResolvesToSettingsActivity() {
        val zipIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
        }
        val resolved = pm.queryIntentActivities(
            zipIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        val match = resolved.firstOrNull {
            it.activityInfo.packageName == context.packageName &&
                it.activityInfo.name == SettingsActivity::class.java.name
        }
        assertNotNull(
            "SettingsActivity يجب أن يستقبل ACTION_SEND بنوع zip دون كسر",
            match
        )
    }

    @Test
    fun actionProcessText_resolvesToReadLaterProcessTextActivity() {
        val processIntent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "نص محدد")
        }
        val resolved = pm.queryIntentActivities(
            processIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        val match = resolved.firstOrNull {
            it.activityInfo.packageName == context.packageName &&
                it.activityInfo.name ==
                ReadLaterProcessTextActivity::class.java.name
        }
        assertNotNull(
            "ReadLaterProcessTextActivity يجب أن يستقبل ACTION_PROCESS_TEXT",
            match
        )

        // التحقق من الاسم المناسب لقائمة تحديد النص وعدم وجود Launcher إضافي
        val label = match?.loadLabel(pm)?.toString()
        val expectedLabel = context.getString(R.string.read_later_title)
        assertEquals(expectedLabel, label)

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            `package` = context.packageName
        }
        val launchers = pm.queryIntentActivities(launcherIntent, 0)
        assertFalse(
            "ReadLaterProcessTextActivity يجب ألا يظهر في Launcher",
            launchers.any {
                it.activityInfo.name ==
                    ReadLaterProcessTextActivity::class.java.name
            }
        )
    }

    @Test
    fun launcherActivity_remainsLordTts() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            `package` = context.packageName
        }
        val launchers = pm.queryIntentActivities(launcherIntent, 0)
        val mainLauncher = launchers.firstOrNull {
            it.activityInfo.name == SettingsActivity::class.java.name
        }
        assertNotNull(mainLauncher)
        val label = mainLauncher?.loadLabel(pm)?.toString()
        assertEquals("Lord TTS", label)
    }
}
