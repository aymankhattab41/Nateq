package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارس سباق الكتابة المحلية غير المفرَّغة: إشعارُ المراقب يستدعي
 * `reload(force = true)` فيقرأ القرص قبل أن يُفرَّغ `apply`، فلا يجوز أن
 * يُسقط تلك الكتابة من لقطة القراءة التالية.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SnapshotPrefsTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    private fun newRaw() = context.getSharedPreferences(
        "snapshot_prefs_test",
        Context.MODE_PRIVATE
    ).also { it.edit().clear().commit() }

    @Test
    fun replaceSnapshot_keepsLocalApplyUntilDiskCachesUp() {
        val raw = newRaw()
        raw.edit().putString("key", "0").commit()
        var stamp = 0L to 0L
        val wrapper = SnapshotPrefs(
            delegate = raw,
            stampProvider = { stamp }
        )

        wrapper.edit().putString("key", "1").apply()

        // قراءة القرص لا تزال قديمة وبصمتها لم تتغيّر: تُحفظ الكتابة المحلية
        wrapper.replaceSnapshot(mapOf("key" to "0"))
        assertEquals("1", wrapper.getString("key", null))

        // تغيّرت البصمة (تفريغ أو كتابة عملية أخرى): القرص صار المرجع
        stamp = 1L to 100L
        wrapper.replaceSnapshot(mapOf("key" to "2"))
        assertEquals("2", wrapper.getString("key", null))
    }

    @Test
    fun replaceSnapshot_keepsLocalRemovalUntilDiskCachesUp() {
        val raw = newRaw()
        raw.edit().putString("key", "0").commit()
        var stamp = 7L to 70L
        val wrapper = SnapshotPrefs(
            delegate = raw,
            stampProvider = { stamp }
        )

        wrapper.edit().remove("key").apply()

        // القرص القديم ما زال يحمل القيمة: لا تعود إلى اللقطة
        wrapper.replaceSnapshot(mapOf("key" to "0"))
        assertEquals(null, wrapper.getString("key", null))

        // بعد تغيّر البصمة يُؤخذ القرص (عملية أخرى أعادت القيمة)
        stamp = 8L to 70L
        wrapper.replaceSnapshot(mapOf("key" to "0"))
        assertEquals("0", wrapper.getString("key", null))
    }

    @Test
    fun commit_clearsPendingLocalWrites() {
        val raw = newRaw()
        var stamp = 3L to 30L
        val wrapper = SnapshotPrefs(
            delegate = raw,
            stampProvider = { stamp }
        )

        wrapper.edit().putString("key", "3").apply()
        wrapper.edit().putString("key", "4").commit()

        // الالتزام بلغ القرص: أي قراءة تالية للقرص هي المرجع ولو بنفس البصمة
        wrapper.replaceSnapshot(mapOf("key" to "5"))
        assertEquals("5", wrapper.getString("key", null))
    }
}
