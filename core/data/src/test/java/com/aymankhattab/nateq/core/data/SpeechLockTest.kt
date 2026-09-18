package com.aymankhattab.nateq.core.data

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * يغطي قفل النطق العابر بين عمليتي النطق والتخليق عبر مسار
 * `content://…/speaking` في [SettingsChangeProvider]:
 * - الرفع عبر [SpeechLock.setSpeaking] يُرى من [SpeechLock.isSpeaking] (ترفع
 *   العملية :tts ثم تستهل العملية الرئيسية).
 * - كل تحديثٍ للعلم يُبثّ إشعاراً للمراقبين (فيستيقظ [ContentObserver]
 *   معلّقو متحدث الإعلانات فور انخفاضه).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechLockTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Before
    fun installProvider() {
        // نثبّت المزوّد الحقيقي على سلطانه المعلن. لا بد من تمرير السلطان
        // صريحاً: الصيغة بلا سلطان تعتمد على PackageManager ولا تسجّل المزوّد
        // في بيئة اختبارات الوحدة، فتفشل update/query في المحرِّل المظلل.
        Robolectric.setupContentProvider(
            SettingsChangeProvider::class.java,
            SettingsChangeProvider.AUTHORITY
        )
    }

    @Test
    fun raiseAndLower_visibleThroughProvider() {
        SpeechLock.setSpeaking(context, true)
        assertTrue(
            "رفع العلم يُرى من العملية الأخرى",
            SpeechLock.isSpeaking(context)
        )
        SpeechLock.setSpeaking(context, false)
        assertFalse(
            "خفض العلم يُرى من العملية الأخرى",
            SpeechLock.isSpeaking(context)
        )
    }

    @Test
    fun everyUpdate_publishesChangeToSpeakingObservers() {
        val notified = AtomicInteger(0)
        val observer = object : ContentObserver(
            Handler(Looper.getMainLooper())
        ) {
            override fun onChange(selfChange: Boolean) {
                notified.incrementAndGet()
            }
        }
        context.contentResolver.registerContentObserver(
            SettingsChangeProvider.speakingUri(), false, observer
        )
        try {
            SpeechLock.setSpeaking(context, true)
            SpeechLock.setSpeaking(context, false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                "رفع وخفض يبثّان إشعارين للمراقبين على المسار الفرعي",
                2, notified.get()
            )
        } finally {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
}
