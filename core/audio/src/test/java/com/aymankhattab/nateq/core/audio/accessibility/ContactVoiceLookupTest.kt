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
 * اختبارات مطابقة وتطبيع أسماء وأرقام جهات الاتصال (ع٤٢-11).
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
    }

    @Test
    fun find_matchesPhoneNumberForms() {
        val store = ContactVoiceStore.create(context)
        store.put("+201000000000", VoiceProfile(voiceId = "numVoice"))

        val found = ContactVoiceLookup.find(context, "01000000000")
        assertNotNull(found)
        assertEquals("numVoice", found!!.voiceId)
    }

    @Test
    fun find_unknownReturnsNull() {
        assertNull(ContactVoiceLookup.find(context, "غير_موجود"))
        assertNull(ContactVoiceLookup.find(context, ""))
    }
}
