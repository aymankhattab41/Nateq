package com.aymankhattab.nateq.core.audio.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyModel
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyType
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LordKeyboardPrivacyAndSessionTest {

    private lateinit var controller: ServiceController<LordKeyboardService>
    private lateinit var service: LordKeyboardService
    private lateinit var fakeInputConnection: FakeInputConnection
    private lateinit var clipboardManager: ClipboardManager

    class FakeInputConnection(targetView: View) :
        BaseInputConnection(targetView, false) {

        val committedTexts = mutableListOf<String>()
        val deletedBeforeCounts = mutableListOf<Int>()
        var selection: String? = null

        override fun commitText(
            text: CharSequence?,
            newCursorPosition: Int
        ): Boolean {
            text?.toString()?.let { committedTexts.add(it) }
            return true
        }

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int
        ): Boolean {
            deletedBeforeCounts.add(beforeLength)
            return true
        }

        override fun getSelectedText(flags: Int): CharSequence? {
            return selection
        }
    }

    @Before
    fun setUp() {
        controller = Robolectric.buildService(LordKeyboardService::class.java)
        service = controller.create().get()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as android.app.Application
        org.robolectric.Shadows.shadowOf(app)
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)

        val dummyView = View(context)
        fakeInputConnection = FakeInputConnection(dummyView)
        service.inputConnectionOverride = fakeInputConnection

        clipboardManager = context.getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager
        clipboardManager.clearPrimaryClip()
        service.isRecognitionAvailableOverride = true
    }

    @After
    fun tearDown() {
        clipboardManager.clearPrimaryClip()
        controller.destroy()
    }

    private fun createEditorInfo(
        inputType: Int,
        packageName: String = "com.test.app",
        fieldId: Int = 100
    ): EditorInfo {
        return EditorInfo().apply {
            this.inputType = inputType
            this.packageName = packageName
            this.fieldId = fieldId
        }
    }

    @Test
    fun testCheckIsPasswordField_masksProperlyAndAvoidsUriCollision() {
        // حقول عادية
        val normalText = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        )
        assertFalse(service.checkIsPasswordField(normalText))

        val emailText = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        )
        assertFalse(service.checkIsPasswordField(emailText))

        // حقل URI قيمته 0x10 وكان يخلط مع PIN في النظام القديم
        val uriText = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        assertFalse(
            "حقل URI لا يجوز تصنيفه ككلمة مرور",
            service.checkIsPasswordField(uriText)
        )

        // حقول كلمات المرور والـ PIN الصريحة
        val passText = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        assertTrue(service.checkIsPasswordField(passText))

        val webPass = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
        assertTrue(service.checkIsPasswordField(webPass))

        val visiblePass = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        )
        assertTrue(service.checkIsPasswordField(visiblePass))

        val numPin = createEditorInfo(
            InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
        assertTrue(service.checkIsPasswordField(numPin))

        val normalNum = createEditorInfo(
            InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_NORMAL
        )
        assertFalse(service.checkIsPasswordField(normalNum))

        assertFalse(service.checkIsPasswordField(null))
    }

    @Test
    fun testPaste_inNormalField_speaksContent() {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText("test", "نص تجريبي 123")
        )
        val normalInfo = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        )
        service.onStartInput(normalInfo, false)

        service.handlePaste()

        assertEquals(1, fakeInputConnection.committedTexts.size)
        assertEquals(
            "نص تجريبي 123",
            fakeInputConnection.committedTexts[0]
        )
        assertEquals(
            "تم اللصق: نص تجريبي 123",
            service.lastSpokenText
        )
        assertEquals(
            "تم اللصق: نص تجريبي 123",
            service.lastAnnouncedAccessibilityText
        )
    }

    @Test
    fun testPaste_inPasswordField_commitsTextButDoesNotLeakSecret() {
        val secret = "SecretP@ssw0rd!"
        clipboardManager.setPrimaryClip(ClipData.newPlainText("test", secret))

        val passInfo = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        service.onStartInput(passInfo, false)

        service.handlePaste()

        // يتم إدخال النص في الحقل بأمان
        assertEquals(1, fakeInputConnection.committedTexts.size)
        assertEquals(secret, fakeInputConnection.committedTexts[0])

        // لكن النطق وإعلان الإتاحة لا يتضمنان كلمة المرور
        assertEquals("تم اللصق", service.lastSpokenText)
        assertEquals("تم اللصق", service.lastAnnouncedAccessibilityText)
        assertFalse(service.lastSpokenText!!.contains(secret))
        assertFalse(service.lastAnnouncedAccessibilityText!!.contains(secret))
    }

    @Test
    fun testPaste_inNumericPinAndWebPassword_doesNotLeakSecret() {
        val pin = "987654"
        clipboardManager.setPrimaryClip(ClipData.newPlainText("pin", pin))

        val pinInfo = createEditorInfo(
            InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
        service.onStartInput(pinInfo, false)

        service.handlePaste()

        assertEquals(pin, fakeInputConnection.committedTexts[0])
        assertEquals("تم اللصق", service.lastSpokenText)
        assertFalse(service.lastSpokenText!!.contains(pin))

        // وبالمثل في كلمة مرور الويب
        val webSecret = "WebSecretPass"
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText("web", webSecret)
        )
        val webInfo = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
        service.onStartInput(webInfo, false)

        service.handlePaste()

        assertEquals(webSecret, fakeInputConnection.committedTexts[1])
        assertEquals("تم اللصق", service.lastSpokenText)
        assertFalse(service.lastSpokenText!!.contains(webSecret))
    }

    @Test
    fun testPaste_emptyClipboard_speaksEmptyMessage() {
        val normalInfo = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        )
        service.onStartInput(normalInfo, false)

        service.handlePaste()

        assertEquals("الحافظة فارغة", service.lastSpokenText)
        assertEquals(0, fakeInputConnection.committedTexts.size)
    }

    @Test
    fun testCopyAndCut_inNormalField_speaksContent() {
        val normalInfo = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        )
        service.onStartInput(normalInfo, false)

        fakeInputConnection.selection = "نص محدد"
        service.handleCopy()

        assertEquals("تم نسخ: نص محدد", service.lastSpokenText)
        val clipItem = clipboardManager.primaryClip?.getItemAt(0)
        assertEquals("نص محدد", clipItem?.text?.toString())

        service.handleCut()
        assertEquals("تم قص: نص محدد", service.lastSpokenText)
        assertEquals(1, fakeInputConnection.committedTexts.size)
        assertEquals("", fakeInputConnection.committedTexts[0])
    }

    @Test
    fun testCopyAndCut_inPasswordField_doesNotLeakSecret() {
        val passInfo = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        service.onStartInput(passInfo, false)

        val sensitive = "TopSecretCode"
        fakeInputConnection.selection = sensitive

        service.handleCopy()
        // الحافظة لم تُمسح بصمت والنص نسخ بالفعل
        val clipItem = clipboardManager.primaryClip?.getItemAt(0)
        assertEquals(sensitive, clipItem?.text?.toString())
        // لكن النطق والإتاحة لا ينطقان المحتوى
        assertEquals("تم النسخ", service.lastSpokenText)
        assertFalse(service.lastSpokenText!!.contains(sensitive))

        service.handleCut()
        assertEquals("تم القص", service.lastSpokenText)
        assertFalse(service.lastSpokenText!!.contains(sensitive))
    }

    @Test
    fun testCopyAndCut_withEmptySelection_announcesNoSelectionProperly() {
        val passInfo = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        service.onStartInput(passInfo, false)
        fakeInputConnection.selection = null

        service.handleCopy()
        assertEquals(
            "لا يوجد نص محدد للنسخ",
            service.lastSpokenText
        )

        service.handleCut()
        assertEquals(
            "لا يوجد نص محدد للقص",
            service.lastSpokenText
        )
    }

    @Test
    fun testKeyHoverInPasswordField_preservesKeyNamesForAccessibility() {
        val passInfo = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        service.onStartInput(passInfo, false)

        val keyA = KeyModel(label = "أ", spokenText = "ألف")
        service.onKeyHover(keyA)

        // نطق اسم المفتاح لتمكين الكفيف من الاستكشاف
        assertEquals("ألف", service.lastSpokenText)
        assertFalse(
            "لا يجوز حجب أسماء المفاتيح أثناء الاستكشاف",
            service.lastSpokenText == "رمز محمي"
        )
    }

    @Test
    fun testKeyCommitInPasswordField_speaksMaskedConfirmation() {
        val settings = SettingsRepository.create(
            ApplicationProvider.getApplicationContext()
        )
        settings.setKeyboardAnnounceAfterLift(true)

        val passInfo = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        service.onStartInput(passInfo, false)

        val keyB = KeyModel(label = "ب", spokenText = "باء")
        service.onKeySelected(keyB)

        // تأكيد الإدخال في كلمة المرور يقول رمز محمي
        assertEquals("تم إدخال رمز", service.lastSpokenText)
        assertFalse(service.lastSpokenText!!.contains("باء"))

        // المفاتيح الوظيفية كالحذف تنطق وظيفتها
        val backspace = KeyModel(
            label = "⌫",
            spokenText = "حذف",
            type = KeyType.ACTION_BACKSPACE
        )
        service.onKeySelected(backspace)
        assertEquals("حذف", service.lastSpokenText)
    }

    @Test
    fun testDictation_inNormalField_commitsAndSpeaksContent() {
        val normalInfo = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        )
        service.onStartInput(normalInfo, false)

        service.startVoiceTyping()
        val listener = service.recognitionListener
        assertTrue(listener != null)

        val results = Bundle().apply {
            putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("سلام عليكم")
            )
        }
        listener?.onResults(results)

        assertEquals(
            "سلام عليكم",
            fakeInputConnection.committedTexts[0]
        )
        assertEquals(
            "تم إدخال: سلام عليكم",
            service.lastSpokenText
        )
    }

    @Test
    fun testDictation_inPasswordField_doesNotLeakSpeechContent() {
        val passInfo = createEditorInfo(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        service.onStartInput(passInfo, false)

        service.startVoiceTyping()
        val listener = service.recognitionListener
        assertTrue(listener != null)

        val secretSpoken = "MyDictatedPassword2026"
        val results = Bundle().apply {
            putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf(secretSpoken)
            )
        }
        listener?.onResults(results)

        // يتم الإدخال في الحقل
        assertEquals(secretSpoken, fakeInputConnection.committedTexts[0])
        // ولكن النطق والإتاحة لا يكشفان السر
        assertEquals("تم الإدخال", service.lastSpokenText)
        assertFalse(service.lastSpokenText!!.contains(secretSpoken))
    }

    @Test
    fun testDictation_switchFieldBeforeResults_discardsResultsCompletely() {
        // حقل أ
        val infoA = createEditorInfo(
            InputType.TYPE_CLASS_TEXT,
            fieldId = 201
        )
        service.onStartInput(infoA, false)

        service.startVoiceTyping()
        val listenerFieldA = service.recognitionListener
        assertTrue(listenerFieldA != null)

        // المستخدم يبدل إلى حقل ب قبل وصول النتيجة
        val infoB = createEditorInfo(
            InputType.TYPE_CLASS_TEXT,
            fieldId = 202
        )
        service.onStartInput(infoB, false)

        // الآن تصل نتيجة الإملاء المتأخرة من حقل أ
        val resultsA = Bundle().apply {
            putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("نص خاص بحقل أ القديم")
            )
        }
        listenerFieldA?.onResults(resultsA)

        // يجب رفض النتيجة تماماً وعدم كتابتها في حقل ب
        assertEquals(0, fakeInputConnection.committedTexts.size)
        assertNull(service.lastSpokenText)

        // يجب رفض النتيجة تماماً وعدم كتابتها في حقل ب وعدم نطقها
        assertEquals(0, fakeInputConnection.committedTexts.size)
        assertNull(service.lastSpokenText)
    }

    @Test
    fun testDictation_finishInputViewAndFinishInput_cancelsAndDiscards() {
        val info = createEditorInfo(InputType.TYPE_CLASS_TEXT)
        service.onStartInput(info, false)
        service.startVoiceTyping()
        val listener = service.recognitionListener

        // إغلاق لوحة المفاتيح
        service.onFinishInputView(true)

        val results = Bundle().apply {
            putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("نص بعد الإغلاق")
            )
        }
        listener?.onResults(results)

        assertEquals(0, fakeInputConnection.committedTexts.size)

        // وبالمثل عند onFinishInput
        service.onStartInput(info, false)
        service.startVoiceTyping()
        val listener2 = service.recognitionListener
        service.onFinishInput()
        listener2?.onResults(results)

        assertEquals(0, fakeInputConnection.committedTexts.size)
    }

    @Test
    fun testDictation_cosmeticRestart_preservesActiveRequest() {
        val info = createEditorInfo(
            InputType.TYPE_CLASS_TEXT,
            packageName = "com.test.app",
            fieldId = 300
        )
        service.onStartInput(info, false)
        val initialGen = service.sessionGeneration

        service.startVoiceTyping()
        val reqId = service.activeVoiceRequestId
        assertTrue(reqId > 0L)

        // إعادة تشغيل شكلية (restarting = true مع نفس الحقل والحزمة)
        service.onStartInput(info, true)
        assertEquals(
            "إعادة التهيئة الشكلية يجب ألا ترفع جيل الجلسة",
            initialGen,
            service.sessionGeneration
        )
        assertEquals(
            "إعادة التهيئة الشكلية يجب ألا تلغي طلب التعرف القائم",
            reqId,
            service.activeVoiceRequestId
        )

        val results = Bundle().apply {
            putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("نص الإملاء السليم")
            )
        }
        service.recognitionListener?.onResults(results)

        assertEquals(1, fakeInputConnection.committedTexts.size)
        assertEquals(
            "نص الإملاء السليم",
            fakeInputConnection.committedTexts[0]
        )
    }

    @Test
    fun testDictation_staleCallbackFromOldRequest_doesNotInterfereWithNew() {
        val info = createEditorInfo(InputType.TYPE_CLASS_TEXT)
        service.onStartInput(info, false)

        service.startVoiceTyping()
        val oldReqId = service.activeVoiceRequestId
        val oldListener = service.recognitionListener

        // إيقاف يدوي للطلب وبدء طلب جديد
        service.startVoiceTyping() // toggle off
        service.startVoiceTyping() // start new
        val newReqId = service.activeVoiceRequestId
        val newListener = service.recognitionListener

        assertTrue(newReqId > oldReqId)

        // وصول رد خطأ أو نتائج من الطلب القديم
        oldListener?.onError(SpeechRecognizer.ERROR_NETWORK)
        oldListener?.onResults(Bundle().apply {
            putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("قديم ملغي")
            )
        })

        // لم يُلغ الطلب الجديد ولم يُنطق خطأ الشبكة القديم
        assertEquals(0, fakeInputConnection.committedTexts.size)
        assertEquals(newReqId, service.activeVoiceRequestId)

        // الطلب الجديد يكمل بنجاح
        newListener?.onResults(Bundle().apply {
            putStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("جديد معتمد")
            )
        })
        assertEquals(
            "جديد معتمد",
            fakeInputConnection.committedTexts[0]
        )
    }

    @Test
    fun testDictation_serviceUnavailable_speaksUnavailableMessage() {
        service.isRecognitionAvailableOverride = false
        val info = createEditorInfo(InputType.TYPE_CLASS_TEXT)
        service.onStartInput(info, false)

        service.startVoiceTyping()

        assertEquals(
            "خدمة التعرف على الصوت غير متاحة على جهازك",
            service.lastSpokenText
        )
    }

    @Test
    fun testDictation_permissionDenied_speaksPermissionMessage() {
        val app = ApplicationProvider
            .getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(app)
            .denyPermissions(android.Manifest.permission.RECORD_AUDIO)

        val info = createEditorInfo(InputType.TYPE_CLASS_TEXT)
        service.onStartInput(info, false)

        service.startVoiceTyping()

        val spoken = service.lastSpokenText.orEmpty()
        assertTrue(spoken.contains("يرجى منح إذن الميكروفون أولاً"))
    }
}
