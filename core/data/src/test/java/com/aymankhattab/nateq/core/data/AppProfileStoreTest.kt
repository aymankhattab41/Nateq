package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارس ع٤١-ب11، ع٤٥: ملفات تعريف التطبيقات وترميزها وsingleton وترحيلها.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AppProfileStoreTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun store(): AppProfileStore = AppProfileStore.create(context)

    @Test
    fun codec_roundTripsWithFeaturesAndLanguage() {
        val profile = AppProfile(
            packageName = "com.whatsapp",
            engine = "com.google.android.tts",
            voiceId = "ar-x-aa",
            speechRate = 1.1f,
            pitch = 0.95f,
            volume = 0.7f,
            detailLevel = 3,
            features = setOf("a", "b"),
            voiceLanguage = "ar"
        )
        val decoded = AppProfileCodec.decode(
            AppProfileCodec.encode(mapOf("com.whatsapp" to profile))
        )
        assertEquals(profile, decoded["com.whatsapp"])
        assertEquals("ar", decoded["com.whatsapp"]?.voiceLanguage)
    }

    @Test
    fun store_putGetRemove() {
        val s = store()
        s.put(
            AppProfile(
                packageName = "com.whatsapp",
                detailLevel = 2,
                voiceLanguage = "ar"
            )
        )
        assertEquals(2, s.get("com.whatsapp")?.detailLevel)
        assertEquals("ar", s.get("com.whatsapp")?.voiceLanguage)
        assertTrue(s.packages().contains("com.whatsapp"))
        assertTrue(s.remove("com.whatsapp"))
        assertNull(s.get("com.whatsapp"))
    }

    @Test
    fun store_createReturnsSingletonPerProcess() {
        val s1 = AppProfileStore.create(context)
        val s2 = AppProfileStore.create(context)
        assertSame(s1, s2)
    }

    @Test
    fun store_sequentialPutsPreserveAllEntries() {
        val s = store()
        s.put(AppProfile(packageName = "app1", voiceId = "v1"))
        s.put(AppProfile(packageName = "app2", voiceId = "v2"))

        assertNotNull(s.get("app1"))
        assertNotNull(s.get("app2"))
        assertEquals("v1", s.get("app1")?.voiceId)
        assertEquals("v2", s.get("app2")?.voiceId)

        s.remove("app1")
        s.remove("app2")
    }

    @Test
    fun migration_migratesLegacyPrefsFile() {
        val legacyPrefs = context.getSharedPreferences(
            "nateq_app_profiles", Context.MODE_PRIVATE
        )
        val profile = AppProfile(
            packageName = "com.legacy.app",
            voiceId = "vLegacy"
        )
        val raw = AppProfileCodec.encode(mapOf("com.legacy.app" to profile))
        legacyPrefs.edit()
            .putString("app_profiles", raw)
            .commit()

        val repo = SettingsRepository.create(context)
        repo.migrateAppProfilesAndContactVoicesIfNeeded()

        val s = store()
        val migrated = s.get("com.legacy.app")
        assertNotNull(migrated)
        assertEquals("vLegacy", migrated?.voiceId)
        s.remove("com.legacy.app")
    }
}
