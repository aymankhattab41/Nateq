package com.aymankhattab.nateq

import android.content.Intent
import android.speech.tts.TextToSpeech
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.settings.CheckTtsDataActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * فحص CHECK_TTS_DATA: جواب النشاط يجب أن يطابق أسماء tts_engine.xml
 * حرفاً بحرف وأسماء onGetVoices (supportedVoices) معاً — فلا تختفي
 * الأصوات في قوائم النظام ولا يضيع voiceId المختار (آلية سامسونج).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CheckTtsDataActivityTest {

    private fun xmlDeclaredNames(): List<String> {
        val xml = File("src/main/res/xml/tts_engine.xml").readText()
        return Regex("""<voice\s+android:name="([^"]+)"\s*/>""")
            .findAll(xml)
            .map { it.groupValues[1] }
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun launch(
        requested: List<String> = emptyList()
    ): org.robolectric.shadows.ShadowActivity {
        val intent = Intent().apply {
            if (requested.isNotEmpty()) {
                putStringArrayListExtra(
                    TextToSpeech.Engine.EXTRA_CHECK_VOICE_DATA_FOR,
                    ArrayList(requested)
                )
            }
        }
        val controller = Robolectric.buildActivity(
            CheckTtsDataActivity::class.java, intent
        )
        return Shadows.shadowOf(controller.setup().get())
    }

    @Test
    fun availableVoices_matchXmlAndOnGetVoices() {
        val shadow = launch()
        assertEquals(
            TextToSpeech.Engine.CHECK_VOICE_DATA_PASS,
            shadow.getResultCode()
        )
        val available = shadow.getResultIntent()
            ?.getStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES
            )
            ?.toList()
            .orEmpty()
        // مطابقة الملف حرفاً بحرف (بنفس الترتيب)
        assertEquals(xmlDeclaredNames(), available)
        // مطابقة onGetVoices (كتالوج بلا اكتشاف = الأصوات المضمونة كاملة)
        val onGetVoices = VoiceCatalog(emptyList())
            .supportedVoices().map { it.name }
        assertEquals(onGetVoices.toSet(), available.toSet())
        // كل الأصوات المعلنة متاحة محلياً — لا قائمة غير متاحة
        assertTrue(
            "لا صوت غير متاح",
            shadow.getResultIntent()
                ?.getStringArrayListExtra(
                    TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES
                )
                ?.isEmpty() == true
        )
    }

    @Test
    fun requestedOnly_limitedToRequestedLanguages() {
        val shadow = launch(listOf("ar-EG", "fr"))
        val available = shadow.getResultIntent()
            ?.getStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES
            )
            ?.toList()
            .orEmpty()
        assertEquals(listOf("ar-EG", "fr"), available)
    }
}