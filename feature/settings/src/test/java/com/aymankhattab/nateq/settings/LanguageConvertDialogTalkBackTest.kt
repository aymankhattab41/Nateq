package com.aymankhattab.nateq.settings

import android.content.Context
import android.content.res.Configuration
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
import java.util.Locale

/**
 * الاختبار الحاسم لإتاحة قارئ الشاشة في حوار «إعداد جميع اللغات»:
 * تعديلُ المستخدم الكفيف لأشرطة السرعة/النبرة/مستوى الصوت عبر أداة
 * الوصول (TalkBack) يمر في
 * [SeekBar.OnSeekBarChangeListener.onProgressChanged] بمعامل
 * fromUser=true حصراً — بلا onStartTrackingTouch أو onStopTrackingTouch
 * إطلاقاً لأن لا لمسَ ولا تحريرَ حقيقيين. كان زر «حفظ» يبقى معطَّلاً لمثل
 * هذا التعديل؛ يُستدعى المستمع مباشرةً وتُتحقق تمكينُ الزر بعدها تماماً
 * كما يفعل TalkBack فعلياً.
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

    @Test
    fun seekBars_keepMinimumTouchTarget_48dp() {
        val min = 48 * context.resources.displayMetrics.density
        for (id in intArrayOf(
            R.id.seek_convert_dialog_volume,
            R.id.seek_convert_dialog_pitch,
            R.id.seek_convert_dialog_rate
        )) {
            assertTrue(
                "seekbar $id أصغر من 48dp",
                seekBar(id).minimumHeight >= min.toInt()
            )
        }
    }

    @Test
    fun ltrParentheticalStrings_areBidiIsolated() {
        val lre = "\u202a"
        val pdf = "\u202c"
        val arabic = context.createConfigurationContext(
            Configuration().apply {
                setLocale(Locale.forLanguageTag("ar"))
            }
        )
        assertTrue(
            "time_chime_at_0=[${arabic.getString(R.string.time_chime_at_0)}]",
            arabic.getString(R.string.time_chime_at_0).contains(lre)
        )
        assertTrue(
            arabic.getString(R.string.time_chime_at_0).endsWith("$pdf)")
        )
        assertTrue(
            arabic.getString(R.string.time_chime_custom_quarter_15)
                .contains(lre)
        )
        assertTrue(
            arabic.getString(R.string.rate_value_format).startsWith(lre)
        )
        assertTrue(arabic.getString(R.string.rate_value_format).endsWith(pdf))
        assertFalse(
            context.getString(R.string.time_chime_at_0).contains(lre)
        )
    }
}
