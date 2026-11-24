package com.aymankhattab.nateq.core.audio.accessibility

import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.ContactVoiceStore
import com.aymankhattab.nateq.core.data.VoiceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات مطابقة وتطبيع أسماء وأرقام جهات الاتصال (ع٤٢-11، ع٤٥، البنود 17-20).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ContactVoiceLookupTest {

    private val context =
        ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun norm(name: String) =
        ContactVoiceLookup.normalizeContactName(name)

    @Test
    fun normalizeContactName_stripsTashkeelAndTatweel() {
        assertEquals("محمد", norm("مُحَمَّدٌ"))
        assertEquals("علي", norm("عـــــلِيّ"))
    }

    @Test
    fun normalizeContactName_normalizesAlefYaaTaa() {
        assertEquals("احمد", norm("أحمد"))
        assertEquals("ابراهيم", norm("إبراهيم"))
        assertEquals("ادم", norm("آدم"))
        assertEquals("مني", norm("منى"))
        assertEquals("فاطمه", norm("فاطمة"))
    }

    @Test
    fun normalizeContactName_stripsPrefixesAndTitles() {
        assertEquals("احمد", norm("د. أحمد"))
        assertEquals("احمد", norm("دكتور أحمد"))
        assertEquals("عمر", norm("مهندس عمر"))
        assertEquals("عمر", norm("م. عمر"))
        assertEquals("حسام", norm("أستاذ حسام"))
        assertEquals("حسام", norm("أ. حسام"))
        assertEquals("علي", norm("الشيخ علي"))
        assertEquals("خالد", norm("السيد خالد"))
        assertEquals("john", norm("Dr. John"))
        assertEquals("smith", norm("Eng. Smith"))
    }

    @Test
    fun find_matchesNormalizedNameInStore() {
        val store = ContactVoiceStore.create(context)
        store.put("أحمد", VoiceProfile(voiceId = "ahmedVoice"))

        val foundWithTitle = ContactVoiceLookup.find(context, "د. أحمد")
        assertNotNull(foundWithTitle)
        assertEquals("ahmedVoice", foundWithTitle!!.voiceId)

        val foundWithDiacritics = ContactVoiceLookup.find(context, "أَحْمَد")
        assertNotNull(foundWithDiacritics)
        assertEquals("ahmedVoice", foundWithDiacritics!!.voiceId)

        val foundWithSpaces = ContactVoiceLookup.find(context, " أ ح م د ")
        assertNotNull(foundWithSpaces)
        assertEquals("ahmedVoice", foundWithSpaces!!.voiceId)
        store.remove("أحمد")
    }

    @Test
    fun find_matchesConversationKeyFirst() {
        val store = ContactVoiceStore.create(context)
        store.put("conv_123", VoiceProfile(voiceId = "convVoice"))
        store.put("أحمد", VoiceProfile(voiceId = "ahmedVoice"))

        val found = ContactVoiceLookup.find(
            context = context,
            keyOrName = "أحمد",
            conversationKey = "conv_123"
        )
        assertNotNull(found)
        assertEquals("convVoice", found!!.voiceId)
        store.remove("conv_123")
        store.remove("أحمد")
    }

    @Test
    fun find_matchesPhoneNumberForms() {
        val store = ContactVoiceStore.create(context)
        store.put("+201000000000", VoiceProfile(voiceId = "numVoice"))

        val found = ContactVoiceLookup.find(context, "01000000000")
        assertNotNull(found)
        assertEquals("numVoice", found!!.voiceId)
        store.remove("+201000000000")
    }

    @Test
    fun find_suffixMatching_distinguishesNameWithDigitsFromPhoneNumber() {
        val store = ContactVoiceStore.create(context)
        // اسم يحوي رقماً واحداً (البند 17)
        store.put("Person 1", VoiceProfile(voiceId = "personOneVoice"))

        // استعلام برقم هاتف ينتهي بـ1
        val found = ContactVoiceLookup.find(context, "+201000000001")
        assertNull(found)

        store.remove("Person 1")
    }

    @Test
    fun find_suffixMatching_requiresSafeThresholdOnBothSides() {
        val store = ContactVoiceStore.create(context)
        // رقم قصير مشترك (أقل من 7 خانات دالة)
        store.put("123", VoiceProfile(voiceId = "shortCodeVoice"))

        // استعلام برقم طويل ينتهي بالرقم القصير
        val found = ContactVoiceLookup.find(context, "+201000000123")
        assertNull(found)

        store.remove("123")
    }

    @Test
    fun find_suffixMatching_differentNumbers_doesNotPickRandom() {
        val store = ContactVoiceStore.create(context)
        store.put("0101234567", VoiceProfile(voiceId = "v1"))
        store.put("0111234567", VoiceProfile(voiceId = "v2"))

        // استعلام باللاحقة المشتركة 1234567 (7 خانات دالة) يلتبس بين v1 و v2
        val found = ContactVoiceLookup.find(context, "1234567")
        // لا يجوز أن يرجع أول مدخل عشوائي (البند 17)
        assertNull(found)

        store.remove("0101234567")
        store.remove("0111234567")
    }

    @Test
    fun find_suffixMatching_localAndInternationalAndSymbols() {
        val store = ContactVoiceStore.create(context)
        store.put("+201012345678", VoiceProfile(voiceId = "intlVoice"))

        val local = ContactVoiceLookup.find(context, "01012345678")
        assertNotNull(local)
        assertEquals("intlVoice", local?.voiceId)

        val zeros = ContactVoiceLookup.find(context, "00201012345678")
        assertNotNull(zeros)
        assertEquals("intlVoice", zeros?.voiceId)

        val symbols = ContactVoiceLookup.find(context, "+20 (101) 234-5678")
        assertNotNull(symbols)
        assertEquals("intlVoice", symbols?.voiceId)

        store.remove("+201012345678")
    }

    @Test
    fun find_nameAmbiguity_twoPeopleSameName_disambiguated() {
        val store = ContactVoiceStore.create(context)
        val num1 = "+201011111111"
        val num2 = "+201022222222"
        store.put(
            num1, VoiceProfile(voiceId = "vA", displayName = "محمد علي")
        )
        store.put(
            num2, VoiceProfile(voiceId = "vB", displayName = "محمد علي")
        )
        store.put(
            "conv_A",
            VoiceProfile(voiceId = "vA", displayName = "محمد علي")
        )

        // الاسم وحده ملتبس: لا يختار شخصاً بغير دليل (البند 20)
        assertNull(ContactVoiceLookup.find(context, "محمد علي"))

        // الرقم الصريح يحسم هوية كل شخص
        assertEquals("vA", ContactVoiceLookup.find(context, num1)?.voiceId)
        assertEquals("vB", ContactVoiceLookup.find(context, num2)?.voiceId)

        // مفتاح المحادثة يحسم بدقة
        val convMatch = ContactVoiceLookup.find(
            context = context,
            keyOrName = "محمد علي",
            conversationKey = "conv_A"
        )
        assertEquals("vA", convMatch?.voiceId)

        // حذف أحدهما يزيل الالتباس
        store.remove(num1)
        store.remove("conv_A")
        val remaining = ContactVoiceLookup.find(context, "محمد علي")
        assertNotNull(remaining)
        assertEquals("vB", remaining?.voiceId)

        store.remove(num2)
    }

    @Test
    fun find_uniqueFirstNameMatching() {
        val store = ContactVoiceStore.create(context)
        store.put("طارق الشريف", VoiceProfile(voiceId = "tariqVoice"))

        val match = ContactVoiceLookup.find(context, "طارق")
        assertNotNull(match)
        assertEquals("tariqVoice", match!!.voiceId)
        store.remove("طارق الشريف")
    }

    @Test
    fun find_ambiguousFirstNameDoesNotMatch() {
        val store = ContactVoiceStore.create(context)
        store.put("سامي علي", VoiceProfile(voiceId = "sami1"))
        store.put("سامي كمال", VoiceProfile(voiceId = "sami2"))

        val match = ContactVoiceLookup.find(context, "سامي")
        assertNull(match)
        store.remove("سامي علي")
        store.remove("سامي كمال")
    }

    @Test
    fun find_unknownReturnsNull() {
        assertNull(ContactVoiceLookup.find(context, "غير_موجود"))
        assertNull(ContactVoiceLookup.find(context, ""))
    }
}
