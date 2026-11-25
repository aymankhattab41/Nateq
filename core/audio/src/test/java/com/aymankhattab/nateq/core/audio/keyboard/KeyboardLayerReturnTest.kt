package com.aymankhattab.nateq.core.audio.keyboard

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyModel
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyType
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyboardLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات انحدار لحفظ طبقة الحروف السابقة عند دخول الرموز والرجوع (الأمر 18):
 * 1. English -> Symbols -> Letters يعود إلى English.
 * 2. Arabic -> Symbols -> Letters يعود إلى Arabic.
 * 3. الحفاظ على حالة الأحرف الكبيرة isEnglishCaps عند العودة.
 * 4. التبديل المتكرر بين الرموز والحروف.
 * 5. زر تبديل اللغة أثناء التواجد في طبقة الرموز.
 * 6. بدء حقل إدخال جديد يعيد اللوحة إلى طبقة الحروف السابقة دون خلط التشكيل.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardLayerReturnTest {

    private lateinit var context: Context
    private lateinit var service: LordKeyboardService

    private val symbolsKey = KeyModel(
        label = "?123",
        spokenText = "الأرقام والرموز",
        type = KeyType.ACTION_SYMBOLS
    )

    private val shiftKey = KeyModel(
        label = "⇧",
        spokenText = "تبديل حالة الأحرف",
        type = KeyType.CHARACTER
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val controller = Robolectric.buildService(
            LordKeyboardService::class.java
        )
        service = controller.create().get()
    }

    @Test
    fun defaultState_isArabic() {
        assertEquals(KeyboardLayer.ARABIC, service.currentLayer)
        assertEquals(KeyboardLayer.ARABIC, service.previousLetterLayer)
    }

    @Test
    fun arabic_to_symbols_and_back_returns_arabic() {
        assertEquals(KeyboardLayer.ARABIC, service.currentLayer)

        // الانتقال للرموز
        service.onKeySelected(symbolsKey)
        assertEquals(KeyboardLayer.SYMBOLS, service.currentLayer)
        assertEquals(KeyboardLayer.ARABIC, service.previousLetterLayer)

        // الرجوع للحروف
        service.onKeySelected(symbolsKey)
        assertEquals(KeyboardLayer.ARABIC, service.currentLayer)
    }

    @Test
    fun english_to_symbols_and_back_returns_english_preserving_caps() {
        // التحويل إلى الإنجليزية
        service.toggleLanguage()
        assertEquals(KeyboardLayer.ENGLISH, service.currentLayer)
        assertEquals(KeyboardLayer.ENGLISH, service.previousLetterLayer)

        // تفعيل الأحرف الكبيرة Caps
        assertFalse(service.isEnglishCaps)
        service.onKeySelected(shiftKey)
        assertTrue(service.isEnglishCaps)

        // الانتقال إلى الرموز
        service.onKeySelected(symbolsKey)
        assertEquals(KeyboardLayer.SYMBOLS, service.currentLayer)
        assertEquals(KeyboardLayer.ENGLISH, service.previousLetterLayer)

        // العودة إلى الحروف: يجب أن تعود إلى الإنجليزية وتحتفظ بـ isEnglishCaps
        service.onKeySelected(symbolsKey)
        assertEquals(KeyboardLayer.ENGLISH, service.currentLayer)
        assertTrue(service.isEnglishCaps)
    }

    @Test
    fun multiple_toggles_between_symbols_and_letters() {
        // تبديل للإنجليزية
        service.toggleLanguage()
        assertEquals(KeyboardLayer.ENGLISH, service.currentLayer)

        for (i in 1..5) {
            service.onKeySelected(symbolsKey)
            assertEquals(KeyboardLayer.SYMBOLS, service.currentLayer)
            assertEquals(KeyboardLayer.ENGLISH, service.previousLetterLayer)

            service.onKeySelected(symbolsKey)
            assertEquals(KeyboardLayer.ENGLISH, service.currentLayer)
        }

        // تبديل للعربية
        service.toggleLanguage()
        assertEquals(KeyboardLayer.ARABIC, service.currentLayer)

        for (i in 1..5) {
            service.onKeySelected(symbolsKey)
            assertEquals(KeyboardLayer.SYMBOLS, service.currentLayer)
            assertEquals(KeyboardLayer.ARABIC, service.previousLetterLayer)

            service.onKeySelected(symbolsKey)
            assertEquals(KeyboardLayer.ARABIC, service.currentLayer)
        }
    }

    @Test
    fun toggleLanguage_while_in_symbols_switches_layer_to_other_language() {
        // البدء من الإنجليزية
        service.toggleLanguage()
        assertEquals(KeyboardLayer.ENGLISH, service.currentLayer)

        // دخول الرموز
        service.onKeySelected(symbolsKey)
        assertEquals(KeyboardLayer.SYMBOLS, service.currentLayer)
        assertEquals(KeyboardLayer.ENGLISH, service.previousLetterLayer)

        // ضغط زر تبديل اللغة أثناء التواجد في الرموز: ينتقل إلى العربية
        service.toggleLanguage()
        assertEquals(KeyboardLayer.ARABIC, service.currentLayer)
        assertEquals(KeyboardLayer.ARABIC, service.previousLetterLayer)

        // دخول الرموز من العربية
        service.onKeySelected(symbolsKey)
        assertEquals(KeyboardLayer.SYMBOLS, service.currentLayer)

        // ضغط زر تبديل اللغة أثناء التواجد في الرموز: ينتقل إلى الإنجليزية
        service.toggleLanguage()
        assertEquals(KeyboardLayer.ENGLISH, service.currentLayer)
        assertEquals(KeyboardLayer.ENGLISH, service.previousLetterLayer)
    }

    @Test
    fun newSession_resets_symbols_to_previous_letter_layer() {
        // تحويل للإنجليزية ثم الرموز
        service.toggleLanguage()
        service.onKeySelected(symbolsKey)
        assertEquals(KeyboardLayer.SYMBOLS, service.currentLayer)
        assertEquals(KeyboardLayer.ENGLISH, service.previousLetterLayer)

        // بدء حقل إدخال جديد
        val info = EditorInfo().apply {
            packageName = "com.test.newapp"
            fieldId = 123
            inputType = InputType.TYPE_CLASS_TEXT
        }
        service.onStartInput(info, false)

        // يجب أن يخرج من الرموز ويعود للإنجليزية السابقة
        assertEquals(KeyboardLayer.ENGLISH, service.currentLayer)
    }

    @Test
    fun newSession_arabic_symbols_resets_to_arabic() {
        // من العربية إلى الرموز
        service.onKeySelected(symbolsKey)
        assertEquals(KeyboardLayer.SYMBOLS, service.currentLayer)
        assertEquals(KeyboardLayer.ARABIC, service.previousLetterLayer)

        // بدء حقل إدخال جديد
        val info = EditorInfo().apply {
            packageName = "com.test.newapp2"
            fieldId = 456
            inputType = InputType.TYPE_CLASS_TEXT
        }
        service.onStartInput(info, false)

        // يجب أن يخرج من الرموز ويعود للعربية
        assertEquals(KeyboardLayer.ARABIC, service.currentLayer)
    }
}
