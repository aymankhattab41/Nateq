package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * حارس عقد القراءة القسرية للإعدادات بين العمليتين.
 *
 * الكسر الذي يحرسه: تعديلان متتاليان في نفس الثانية وبالطول
 * نفسه (مثل تحريك سرعة النطق) لا يغيّران توقيت الملف ولا طوله،
 * فكانت بوابة mtime+length تتخطّى التحديث فيبدو الإعداد كأنه لم يُحفظ.
 * `reload(force)` — مسار ContentObserver — يتجاوز البوابة عمداً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SettingsRepositoryForcedReloadTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.resetInstanceForTesting()
        repo = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        repo.unregister()
        SettingsRepository.resetInstanceForTesting()
    }

    @Test
    fun externalWriteWithIdenticalStamp_needsForcedReload() {
        val file = File(
            context.applicationInfo.dataDir,
            "shared_prefs/nateq_settings.xml"
        )
        file.parentFile?.mkdirs()
        file.writeText(prefsXml("1.5"))
        repo.reload(force = true)
        assertEquals(1.5f, repo.getDefaultSpeechRate(), 0.001f)

        // كتابة "خارجية" بنفس الطول ونفس التوقيت تماماً.
        val stamp = file.lastModified()
        val length = file.length()
        file.writeText(prefsXml("1.7"))
        assertEquals(
            "الكتابة التمثيلية بنفس الطول",
            length,
            file.length()
        )
        assertTrue(
            "نثبّت التوقيت لتمثيل كتابةٍ في نفس الثانية",
            file.setLastModified(stamp)
        )

        // القراءة العادية ترى البصمة نفسها فتتخطّى التحديث (السلوك
        // المقصود لتخفيف I/O) — وهذا بالضبط سبب الحاجة إلى force.
        repo.reload()
        assertEquals(1.5f, repo.getDefaultSpeechRate(), 0.001f)

        // والقراءة القسرية (مسار ContentObserver) تلتقط الجديد.
        repo.reload(force = true)
        assertEquals(1.7f, repo.getDefaultSpeechRate(), 0.001f)
    }

    private fun prefsXml(rate: String): String =
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
            "<map>\n" +
            "    <float name=\"default_speech_rate\" value=\"$rate\" />\n" +
            "</map>"
}
