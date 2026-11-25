package com.aymankhattab.nateq.core.audio.keyboard

import android.content.Context
import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyModel
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyType
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
 * اختبارات انحدار لحذف النصوص المحددة وحذف الإيموجي والعلامات (الأمر 16):
 * 1. حذف النص المحدد وحده دون مساس بالحرف قبله (ABCDE مع BCD يصبح AE).
 * 2. عدم كسر أزواج UTF-16 (Surrogate pairs) مثل A😀.
 * 3. حذف الإيموجي المركب والأعلام بأمان.
 * 4. الحروف مع علامات التشكيل.
 * 5. أمان حدود النص عند بداية الحقل.
 * 6. تطابق السلوك بين زر المسح وإيماءة السحب لليسار.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardBackspaceAndEmojiTest {

    private lateinit var context: Context
    private lateinit var service: LordKeyboardService

    class MockBufferInputConnection(
        initialText: String = "",
        var selStart: Int = initialText.length,
        var selEnd: Int = initialText.length
    ) : BaseInputConnection(
        View(ApplicationProvider.getApplicationContext()),
        false
    ) {
        val buffer = StringBuilder(initialText)
        var batchEditDepth = 0

        override fun beginBatchEdit(): Boolean {
            batchEditDepth++
            return true
        }

        override fun endBatchEdit(): Boolean {
            batchEditDepth--
            return true
        }

        override fun getSelectedText(flags: Int): CharSequence? {
            val start = minOf(selStart, selEnd)
            val end = maxOf(selStart, selEnd)
            return if (start != end && start >= 0 && end <= buffer.length) {
                buffer.substring(start, end)
            } else {
                null
            }
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            val cursor = minOf(selStart, selEnd)
            val start = maxOf(0, cursor - n)
            return buffer.substring(start, cursor)
        }

        override fun commitText(
            text: CharSequence?,
            newCursorPosition: Int
        ): Boolean {
            val str = text?.toString() ?: ""
            val start = minOf(selStart, selEnd)
            val end = maxOf(selStart, selEnd)
            buffer.replace(start, end, str)
            val newCursor = start + str.length
            selStart = newCursor
            selEnd = newCursor
            return true
        }

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int
        ): Boolean {
            // عقد InputConnection الصريح: الحذف حول التحديد وليس التحديد نفسه
            val start = minOf(selStart, selEnd)
            val deleteStart = maxOf(0, start - beforeLength)
            buffer.delete(deleteStart, start)
            val shift = start - deleteStart
            selStart -= shift
            selEnd -= shift
            return true
        }

        override fun deleteSurroundingTextInCodePoints(
            beforeLength: Int,
            afterLength: Int
        ): Boolean {
            return deleteSurroundingText(beforeLength, afterLength)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val controller = Robolectric.buildService(
            LordKeyboardService::class.java
        )
        service = controller.create().get()
    }

    @Test
    fun selection_ABCDE_withSelectionBCD_becomes_AE() {
        // نص ABCDE والتحديد على BCD (من 1 إلى 4)
        val mockIc = MockBufferInputConnection(
            "ABCDE",
            selStart = 1,
            selEnd = 4
        )
        service.inputConnectionOverride = mockIc

        service.performBackspace()

        assertEquals("AE", mockIc.buffer.toString())
        assertEquals(1, mockIc.selStart)
        assertEquals(1, mockIc.selEnd)
        assertEquals(0, mockIc.batchEditDepth)
    }

    @Test
    fun backspaceKey_deletesSelectedText_whenSelectionPresent() {
        val mockIc = MockBufferInputConnection("Hello World", 0, 5)
        service.inputConnectionOverride = mockIc

        val backspaceKey = KeyModel(
            label = "⌫",
            spokenText = "حذف",
            type = KeyType.ACTION_BACKSPACE
        )
        service.onKeySelected(backspaceKey)

        assertEquals(" World", mockIc.buffer.toString())
    }

    @Test
    fun swipeLeft_deletesSelectedText_whenSelectionPresent() {
        val mockIc = MockBufferInputConnection("Hello World", 5, 11)
        service.inputConnectionOverride = mockIc

        service.onSwipeLeft()

        assertEquals("Hello", mockIc.buffer.toString())
    }

    @Test
    fun emoji_AD_doesNotBreakSurrogatePair() {
        // A متبوعة بإيموجي 😀 (U+1F600 = \uD83D\uDE00)
        val text = "A\uD83D\uDE00"
        val mockIc = MockBufferInputConnection(text)
        service.inputConnectionOverride = mockIc

        service.performBackspace()

        // يجب أن يتبقى الحرف A فقط دون أي نصف surrogate مكسور
        val remaining = mockIc.buffer.toString()
        assertEquals("A", remaining)
        assertFalse(
            remaining.any {
                Character.isSurrogate(it)
            }
        )
    }

    @Test
    fun compositeEmoji_flag_deletesWithoutBreakingRegionalIndicators() {
        // علم مصر 🇪🇬 مكون من زوج مؤشرات إقليمية (4 وحدات UTF-16)
        val egyptFlag = "\uD83C\uDDEA\uD83C\uDDEC"
        val mockIc = MockBufferInputConnection("Flag:$egyptFlag")
        service.inputConnectionOverride = mockIc

        service.performBackspace()

        assertEquals("Flag:", mockIc.buffer.toString())
    }

    @Test
    fun compositeEmoji_zwjSequence_deletesCompositeUnit() {
        // 👨‍🚀 رجل \uD83D\uDC68 + \u200D + صاروخ \uD83D\uDE80
        val astronaut = "\uD83D\uDC68\u200D\uD83D\uDE80"
        val mockIc = MockBufferInputConnection("Go $astronaut")
        service.inputConnectionOverride = mockIc

        service.performBackspace()

        val remaining = mockIc.buffer.toString()
        assertEquals("Go ", remaining)
    }

    @Test
    fun combiningMark_deletesTashkeelSignSafely() {
        // كلمة مع تشكيل: "عَ" (ع + فتحة \u064E)
        val text = "ع\u064E"
        val mockIc = MockBufferInputConnection(text)
        service.inputConnectionOverride = mockIc

        service.performBackspace()

        // حذف الفتحة أولاً ويبقى الحرف ع
        assertEquals("ع", mockIc.buffer.toString())

        // حذف ثانٍ يحذف الحرف ع
        service.performBackspace()
        assertEquals("", mockIc.buffer.toString())
    }

    @Test
    fun textBoundary_atBeginning_doesNotCrash() {
        val mockIc = MockBufferInputConnection("")
        service.inputConnectionOverride = mockIc

        // لا استثناء عند استدعاء الحذف والنص فارغ
        service.performBackspace()
        assertEquals("", mockIc.buffer.toString())
        assertEquals(0, mockIc.batchEditDepth)
    }
}
