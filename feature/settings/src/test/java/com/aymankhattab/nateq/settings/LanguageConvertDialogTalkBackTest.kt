package com.aymankhattab.nateq.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.R as MaterialR
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * الاختبار الحاسم لإتاحة قارئ الشاشة في حوار «إعداد جميع اللغات»:
 * تعديلُ المستخدم الكفيف لأشرطة السرعة/النبرة/مستوى الصوت عبر أداء
 * الوصول (TalkBack) يمر في [SeekBar.OnSeekBarChangeListener.onProgressChanged]
 * بمعامل fromUser=true حصراً — بلا onStartTrackingTouch/
 * onStopTrackingTouch إطلاقاً لأن لا لمسَ ولا تحريرَ حقيقيين. كان زر
 * «حفظ» يبقى معطَّلاً لمثل هذا التعديل؛ يُستدعى المستمع مباشرةً وتُتحقق
 * تمكينُ الزر بعدها تماماً كما يفعل TalkBack فعلياً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LanguageConvertDialogTalkBackTest {

    private lateinit var context: Context
    private lateinit var view: View
    private lateinit var controller: LanguageConvertDialogController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(MaterialR.style.Theme_MaterialComponents_DayNight)
        val settings = SettingsRepository(context)
        view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_convert_languages, null)
        controller = LanguageConvertDialogController(
            context,
            settings,
            listOf(LanguageRow("ar", "العربية", emptyList()))
        ) { _, _, _, _, _ -> }
        controller.bindTo(view) { }
    }

    private fun seekBar(id: Int): SeekBar = view.findViewById(id)

    private fun saveButton(): MaterialButton =
        view.findViewById(R.id.btn_convert_dialog_save)

    @Test
    fun volumeTalkBackChange_enablesSave() {
        assertFalse(saveButton().isEnabled)
        controller.volumeListener.onProgressChanged(
            seekBar(R.id.seek_convert_dialog_volume), 60, true
        )
        assertTrue(saveButton().isEnabled)
    }

    @Test
    fun pitchTalkBackChange_enablesSave() {
        assertFalse(saveButton().isEnabled)
        controller.pitchListener.onProgressChanged(
            seekBar(R.id.seek_convert_dialog_pitch), 150, true
        )
        assertTrue(saveButton().isEnabled)
    }

    @Test
    fun rateTalkBackChange_enablesSave() {
        assertFalse(saveButton().isEnabled)
        controller.rateListener.onProgressChanged(
            seekBar(R.id.seek_convert_dialog_rate), 150, true
        )
        assertTrue(saveButton().isEnabled)
    }

    @Test
    fun programmaticChange_zeroProgress_doesNotEnableSave() {
        assertFalse(saveButton().isEnabled)
        controller.volumeListener.onProgressChanged(
            seekBar(R.id.seek_convert_dialog_volume), 60, false
        )
        assertFalse(saveButton().isEnabled)
    }
}
