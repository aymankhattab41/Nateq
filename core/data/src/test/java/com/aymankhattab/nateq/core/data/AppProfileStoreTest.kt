package com.aymankhattab.nateq.core.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارس ع٤١-ب11: ملفات تعريف التطبيقات وترميزها.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AppProfileStoreTest {

    private fun store(): AppProfileStore =
        AppProfileStore.create(ApplicationProvider.getApplicationContext())

    @Test
    fun codec_roundTripsWithFeatures() {
        val profile = AppProfile(
            packageName = "com.whatsapp",
            engine = "com.google.android.tts",
            voiceId = "ar-x-aa",
            speechRate = 1.1f,
            pitch = 0.95f,
            volume = 0.7f,
            detailLevel = 3,
            features = setOf("a", "b")
        )
        val decoded = AppProfileCodec.decode(
            AppProfileCodec.encode(mapOf("com.whatsapp" to profile))
        )
        assertEquals(profile, decoded["com.whatsapp"])
    }

    @Test
    fun store_putGetRemove() {
        val s = store()
        s.put(AppProfile(packageName = "com.whatsapp", detailLevel = 2))
        assertEquals(2, s.get("com.whatsapp")?.detailLevel)
        assertTrue(s.packages().contains("com.whatsapp"))
        assertTrue(s.remove("com.whatsapp"))
        assertEquals(null, s.get("com.whatsapp"))
    }
}
