package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارس ع٤١-ب10، ع٤٥: مخزن أصوات جهات الاتصال وترميزه وحسم الأولوية والتطبيع.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ContactVoiceStoreTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun store(): ContactVoiceStore = ContactVoiceStore.create(context)

    @Test
    fun codec_roundTrips() {
        val map = mapOf(
            "+966500000000" to VoiceProfile(
                engine = "com.example.tts",
                voiceId = "ara-SAU-Salma",
                speechRate = 1.2f,
                pitch = 0.9f,
                volume = 0.8f,
                displayName = "سلمى",
                voiceLanguage = "ar"
            )
        )
        val decoded = VoiceProfileCodec.decode(VoiceProfileCodec.encode(map))
        assertEquals(map, decoded)
    }

    @Test
    fun codec_emptyAndPartial() {
        assertTrue(VoiceProfileCodec.decode(null).isEmpty())
        val decoded = VoiceProfileCodec.decode(
            VoiceProfileCodec.encode(
                mapOf("a" to VoiceProfile(voiceId = "v"))
            )
        )
        assertEquals("v", decoded["a"]?.voiceId)
        assertNull(decoded["a"]?.speechRate)
    }

    @Test
    fun store_putFindRemove() {
        val s = store()
        s.put("+966500000000", VoiceProfile(voiceId = "v1"))
        assertEquals(
            "v1",
            s.find(listOf("0500000000", "+966500000000"))?.voiceId
        )
        assertTrue(s.remove("+966500000000"))
        assertNull(s.find(listOf("+966500000000")))
        assertFalse(s.remove("+966500000000"))
    }

    @Test
    fun store_createReturnsSingletonPerProcess() {
        val s1 = ContactVoiceStore.create(context)
        val s2 = ContactVoiceStore.create(context)
        assertSame(s1, s2)
    }

    @Test
    fun store_autoIndexesNormalizedNameAndPhoneForms() {
        val s = store()
        s.put(
            "د. أحمد",
            VoiceProfile(
                voiceId = "ahmedVoice",
                displayName = "د. أحمد"
            )
        )
        assertEquals("ahmedVoice", s.get("احمد")?.voiceId)
        assertEquals("ahmedVoice", s.get("د. أحمد")?.voiceId)

        val entries = s.displayEntries()
        assertEquals(1, entries.filter { it.first == "د. أحمد" }.size)
        s.remove("د. أحمد")
    }

    @Test
    fun store_ownership_updateNameAndVoice_clearsOldNameIndices() {
        val s = store()
        val num = "+201011112222"
        s.put(num, VoiceProfile(voiceId = "vOld", displayName = "Old Name"))
        assertEquals("vOld", s.get("Old Name")?.voiceId)
        assertEquals("vOld", s.get(num)?.voiceId)

        // تحديث الاسم والصوت لنفس الرقم (البند 18)
        s.put(num, VoiceProfile(voiceId = "vNew", displayName = "New Name"))
        assertEquals("vNew", s.get("New Name")?.voiceId)
        assertEquals("vNew", s.get(num)?.voiceId)
        assertNull(s.get("Old Name"))

        s.remove(num)
        assertNull(s.get("New Name"))
        assertNull(s.get(num))
    }

    @Test
    fun store_ownership_removeByAnyKey_removesRecordAndDerivedForms() {
        val s = store()
        val num = "+201099998888"
        s.put(num, VoiceProfile(voiceId = "vTest", displayName = "سامر"))
        assertEquals("vTest", s.get("01099998888")?.voiceId)

        // الحذف بالاسم (مفتاح تدعمه الواجهة)
        assertTrue(s.remove("سامر"))
        assertNull(s.get(num))
        assertNull(s.get("01099998888"))
        assertNull(s.get("سامر"))
    }

    @Test
    fun store_ownership_repeatedModifications_remainClean() {
        val s = store()
        val num = "+201077776666"
        for (i in 1..5) {
            s.put(
                num,
                VoiceProfile(
                    voiceId = "v$i",
                    displayName = "اسم $i",
                    speechRate = 1.0f + (i * 0.1f)
                )
            )
        }
        assertEquals("v5", s.get(num)?.voiceId)
        assertEquals("v5", s.get("اسم 5")?.voiceId)
        assertNull(s.get("اسم 1"))
        assertNull(s.get("اسم 4"))
        assertEquals(1, s.displayEntries().filter { it.first == num }.size)
        s.remove(num)
    }

    @Test
    fun store_migration_conservativeMigration_doesNotPickAmbiguousRandomly() {
        val rawMap = mapOf(
            "+201000000001" to VoiceProfile(
                voiceId = "v1", displayName = "أحمد علي"
            ),
            "01000000001" to VoiceProfile(
                voiceId = "v1", displayName = "أحمد علي"
            ),
            "+201000000002" to VoiceProfile(
                voiceId = "v2", displayName = "أحمد علي"
            ),
            "01000000002" to VoiceProfile(
                voiceId = "v2", displayName = "أحمد علي"
            ),
            "احمد علي" to VoiceProfile(
                voiceId = "v2", displayName = "أحمد علي"
            ),
            "زيد" to VoiceProfile(
                voiceId = "v3", displayName = "زيد"
            )
        )
        val migrated = ContactVoiceStore.migrateLegacyEntries(rawMap)
        assertEquals(3, migrated.size)
        val keys = migrated.map { it.primaryKey }
        assertTrue(keys.contains("+201000000001"))
        assertTrue(keys.contains("+201000000002"))
        assertTrue(keys.contains("زيد"))
        assertFalse(keys.contains("احمد علي"))
    }

    @Test
    fun displayEntries_showsIndependentRecordsWithSameNameAndVoice() {
        val s = store()
        val num1 = "+201055551111"
        val num2 = "+201055552222"
        s.put(
            num1,
            VoiceProfile(
                voiceId = "sharedVoice",
                engine = "com.test.engine",
                displayName = "أحمد",
                speechRate = 1.0f
            )
        )
        s.put(
            num2,
            VoiceProfile(
                voiceId = "sharedVoice",
                engine = "com.test.engine",
                displayName = "أحمد",
                speechRate = 1.5f
            )
        )

        val entries = s.displayEntries()
        assertEquals(2, entries.size)

        s.put(
            num1,
            VoiceProfile(
                voiceId = "updatedVoice",
                displayName = "أحمد",
                speechRate = 1.0f
            )
        )
        assertEquals("updatedVoice", s.get(num1)?.voiceId)
        assertEquals("sharedVoice", s.get(num2)?.voiceId)

        assertTrue(s.remove(num1))
        assertEquals(1, s.displayEntries().size)
        assertEquals(num2, s.displayEntries().first().first)
        s.remove(num2)
    }

    @Test
    fun nameAmbiguity_twoPeopleSameName_disambiguatedByPhone() {
        val s = store()
        val num1 = "+201033331111"
        val num2 = "+201033332222"
        s.put(num1, VoiceProfile(voiceId = "vA", displayName = "محمد علي"))
        s.put(num2, VoiceProfile(voiceId = "vB", displayName = "محمد علي"))

        assertNull(s.get("محمد علي"))

        assertEquals("vA", s.get(num1)?.voiceId)
        assertEquals("vB", s.get(num2)?.voiceId)

        assertTrue(s.remove(num1))
        assertEquals("vB", s.get("محمد علي")?.voiceId)
        s.remove(num2)
    }

    @Test
    fun resolver_contactMultipliesRateAndPitchOverCategoryAndApp() {
        val base = EffectiveVoice()
        val contact = VoiceProfile(
            voiceId = "contactVoice", speechRate = 1.5f, pitch = 1.2f
        )
        val resolved = VoiceProfileResolver.effective(
            base = base,
            category = VoiceProfile(speechRate = 1.1f, pitch = 0.9f),
            appProfile = VoiceProfile(speechRate = 1.0f),
            contact = contact
        )
        assertEquals("contactVoice", resolved.voiceId)
        assertEquals(1.65f, resolved.speechRate!!, 0.001f)
        assertEquals(1.08f, resolved.pitch!!, 0.001f)
        assertNull(resolved.volume)
    }

    @Test
    fun resolver_appProfileBetweenCategoryAndContact() {
        val base = EffectiveVoice(
            engine = null, voiceId = null,
            speechRate = 1.0f, pitch = 1.0f, volume = 1.0f
        )
        val resolved = VoiceProfileResolver.effective(
            base = base,
            category = VoiceProfile(voiceId = "cat", volume = 0.5f),
            appProfile = VoiceProfile(voiceId = "app", pitch = 1.3f),
            contact = VoiceProfile(volume = 0.2f)
        )
        assertEquals("app", resolved.voiceId)
        assertEquals(1.0f, resolved.speechRate!!, 0.001f)
        assertEquals(1.3f, resolved.pitch!!, 0.001f)
        assertEquals(0.2f, resolved.volume!!, 0.001f)
    }

    @Test
    fun resolver_engineChangeResetsVoiceIdUnlessSpecified() {
        val base = EffectiveVoice(engine = "eng1", voiceId = "v1")
        val app = VoiceProfile(engine = "eng2")
        val resolved = VoiceProfileResolver.effective(
            base = base,
            appProfile = app
        )
        assertEquals("eng2", resolved.engine)
        assertNull(resolved.voiceId)
    }

    @Test
    fun migration_migratesLegacyContactVoicesFile() {
        val legacyPrefs = context.getSharedPreferences(
            "nateq_contact_voices", Context.MODE_PRIVATE
        )
        val profile = VoiceProfile(voiceId = "vContactLegacy")
        val raw = VoiceProfileCodec.encode(mapOf("0509999999" to profile))
        legacyPrefs.edit()
            .putString("contact_voices", raw)
            .commit()

        val repo = SettingsRepository.create(context)
        repo.migrateAppProfilesAndContactVoicesIfNeeded()

        val s = store()
        val migrated = s.get("0509999999")
        assertNotNull(migrated)
        assertEquals("vContactLegacy", migrated?.voiceId)
        s.remove("0509999999")
    }
}
