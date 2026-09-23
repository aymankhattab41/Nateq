package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * اختبارات الرنة المخصصة للساعة (بنود 1-7):
 * - اختيار ملف وأخذ صلاحية دائمة (FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
 * - محاكاة إعادة تشغيل الجهاز / فقدان العملية والتأكد من بقاء الرنة المخصصة
 * - التحقق من المسار البديل (Fallback) عند تعذر قراءة الملف
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class CustomChimeTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private class FakeSink : CueSink {
        var played: ShortArray? = null
        var volume: Float = 0f
        var playCalls = 0
        var onDone: ((Boolean) -> Unit)? = null

        override fun play(
            pcm: ShortArray,
            sampleRate: Int,
            volume: Float,
            cueKey: String,
            onDone: (Boolean) -> Unit
        ) {
            playCalls++
            played = pcm
            this.volume = volume
            this.onDone = onDone
        }

        override fun stop() {}
        override fun release() {}

        fun notifyDone(ok: Boolean = true) {
            onDone?.invoke(ok)
            onDone = null
        }
    }

    @Test
    fun testCustomChimePersistenceAcrossProcessRestart() {
        val settings = SettingsRepository.create(context)
        val uri = Uri.parse("content://media/external/audio/media/101")

        // 1) محاكاة Intent الاختيار مع علم الصلاحية الدائمة
        val openIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        assertTrue(
            (openIntent.flags and
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
        )

        // 2) أخذ صلاحية دائمة صريحة وحفظ الـ Uri
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        settings.setCustomChimeUri(uri.toString())
        assertEquals(uri.toString(), settings.getCustomChimeUri())

        // 3) التحقق من تسجيل الصلاحية الدائمة في ContentResolver
        val persisted = context.contentResolver.persistedUriPermissions
        assertTrue(persisted.any { it.uri == uri && it.isReadPermission })

        // 4) محاكاة إعادة تشغيل الجهاز / موت العملية عبر بناء مثيل جديد
        val restartedSettings = SettingsRepository.create(context)
        assertEquals(uri.toString(), restartedSettings.getCustomChimeUri())

        // التأكد من استمرار صلاحية الـ Uri الدائمة بعد إعادة التشغيل
        val persistedAfterRestart =
            context.contentResolver.persistedUriPermissions
        assertTrue(
            persistedAfterRestart.any {
                it.uri == uri && it.isReadPermission
            }
        )

        // 5) بناء كائن AudioCue والتأكد من حمل المسار المخصص
        val cue = AudioCue(
            type = CueType.TIME_HOURLY,
            soundName = restartedSettings.getTimeChimeSound(),
            volume = restartedSettings.getTimeChimeVolume(),
            customUri = restartedSettings.getCustomChimeUri()
        )
        assertEquals(uri.toString(), cue.customUri)
    }

    @Test
    fun testFallbackToSyntheticWhenCustomFileInaccessible() {
        val sink = FakeSink()
        val player = AudioCuePlayer.forTesting(
            sink = sink,
            synth = CueSynth,
            context = context
        )

        val inaccessibleUri = "content://com.example.provider/missing.mp3"
        val cue = AudioCue(
            type = CueType.TIME_HOURLY,
            soundName = "digital_chime",
            volume = 0.6f,
            customUri = inaccessibleUri
        )

        var finished = false
        player.play(cue) { ok ->
            finished = ok
        }

        // بما أن الملف غير قابل للوصول، يتراجع فوراً إلى الرنة المولدة عبر sink
        assertNotNull(sink.played)
        assertEquals(1, sink.playCalls)
        sink.notifyDone(true)
        ShadowLooper.idleMainLooper()
        assertTrue(finished)
    }

    @Test
    fun testClearCustomChimeReturnsToDefault() {
        val settings = SettingsRepository.create(context)
        val uri = Uri.parse("content://media/external/audio/media/202")

        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        settings.setCustomChimeUri(uri.toString())
        assertEquals(uri.toString(), settings.getCustomChimeUri())

        // حذف النغمة المخصصة والعودة للافتراضية
        context.contentResolver.releasePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        settings.setCustomChimeUri("")
        assertEquals("", settings.getCustomChimeUri())

        val cue = AudioCue(
            type = CueType.TIME_HOURLY,
            soundName = "classic_bell",
            volume = 0.5f,
            customUri = settings.getCustomChimeUri().takeIf { it.isNotBlank() }
        )
        assertNull(cue.customUri)
    }
}
