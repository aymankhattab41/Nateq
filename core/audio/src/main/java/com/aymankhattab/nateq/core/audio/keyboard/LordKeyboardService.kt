package com.aymankhattab.nateq.core.audio.keyboard

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyModel
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyType
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyboardLayer
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.AccessibilityUtils
import java.util.Locale

/**
 * خدمة لوحة مفاتيح Lord TTS الميسّرة لذوي الإعاقة البصرية.
 * تدعم وضعي الكفيف وضعيف البصر، التفعيل برفع الإصبع أو النقر المزدوج،
 * التشكيل، الكتابة بالصوت، أدوات الحافظة، مع إلغاء التصحيح التلقائي.
 */
class LordKeyboardService : InputMethodService(),
    LordKeyboardView.OnKeyboardActionListener {

    private lateinit var settingsRepo: SettingsRepository
    internal var keyboardView: LordKeyboardView? = null
    private var currentLayer: KeyboardLayer = KeyboardLayer.ARABIC
    private var isEnglishCaps: Boolean = false
    internal var isPasswordField: Boolean = false

    // تتبع دورة حياة جلسات الإدخال والإملاء الصوتي
    internal data class InputSessionToken(
        val generation: Long,
        val packageName: String,
        val fieldId: Int,
        val inputType: Int
    )

    internal var sessionGeneration: Long = 0L
    internal var activeSessionToken: InputSessionToken? = null

    private var voiceRequestCounter: Long = 0L
    internal var activeVoiceRequestId: Long = 0L
    internal var activeVoiceSessionGeneration: Long = -1L

    internal var inputConnectionOverride: InputConnection? = null
    internal var isRecognitionAvailableOverride: Boolean? = null
    internal var speechRecognizerFactory: ((Context) -> SpeechRecognizer?)? =
        null
    internal var recognitionListener: RecognitionListener? = null

    internal var lastSpokenText: String? = null
    internal var lastAnnouncedAccessibilityText: String? = null

    // أشرطة الأدوات
    private var tashkeelBar: LinearLayout? = null
    private var toolbarStrip: LinearLayout? = null
    private val toolbarButtons = mutableListOf<Button>()

    // محرك النطق الخاص باللوحة للاستجابة اللحظية
    private var tts: TextToSpeech? = null
    private var isTtsReady: Boolean = false

    // الكتابة بالصوت
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningVoice: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository.create(this)
        initTts()
    }

    private fun initTts() {
        val defaultEngine = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.TTS_DEFAULT_SYNTH
        )
        if (defaultEngine == packageName) {
            isTtsReady = false
            return
        }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ar")
                isTtsReady = true
            }
        }
    }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // شريط الإجراءات العلوي
        val toolbar = createToolbar()
        rootLayout.addView(toolbar)

        // شريط التشكيل السريع (مخفي افتراضياً)
        tashkeelBar = createTashkeelBar()
        tashkeelBar?.visibility = View.GONE
        rootLayout.addView(tashkeelBar)


        // مسطح لوحة المفاتيح
        val kbView = LordKeyboardView(this).apply {
            actionListener = this@LordKeyboardService
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        keyboardView = kbView
        rootLayout.addView(kbView)

        applySettingsToKeyboard()
        updateKeyboardLayout()

        return rootLayout
    }

    override fun getCurrentInputConnection(): InputConnection? {
        return inputConnectionOverride ?: super.getCurrentInputConnection()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        updateSession(attribute, restarting)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        updateSession(info, restarting)
        applySettingsToKeyboard()
        updateKeyboardLayout()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelVoiceTyping(announce = false)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        cancelVoiceTyping(announce = false)
        activeSessionToken = null
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        cancelVoiceTyping(announce = false)
    }

    private fun updateSession(info: EditorInfo?, restarting: Boolean) {
        isPasswordField = checkIsPasswordField(info)
        val pkg = info?.packageName ?: ""
        val fieldId = info?.fieldId ?: 0
        val inputType = info?.inputType ?: 0

        val current = activeSessionToken
        val isCosmeticRestart = restarting &&
            current != null &&
            current.packageName == pkg &&
            current.fieldId == fieldId &&
            current.inputType == inputType

        if (!isCosmeticRestart) {
            sessionGeneration++
            activeSessionToken = InputSessionToken(
                generation = sessionGeneration,
                packageName = pkg,
                fieldId = fieldId,
                inputType = inputType
            )
            cancelVoiceTyping(announce = false)
        }
    }

    internal fun checkIsPasswordField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> {
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            InputType.TYPE_CLASS_NUMBER -> {
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            else -> false
        }
    }

    internal fun applySettingsToKeyboard() {
        val kv = keyboardView ?: return
        val mode = settingsRepo.getKeyboardMode()
        val activation = settingsRepo.getKeyboardActivation()
        val announceAfter = settingsRepo.isKeyboardAnnounceAfterLift()
        val haptic = settingsRepo.isKeyboardHapticEnabled()
        val sound = settingsRepo.isKeyboardSoundEnabled()
        val sizeLevel = settingsRepo.getKeyboardSizeLevel()

        kv.keyboardMode = mode
        kv.activationMode = activation
        kv.announceAfterLift = announceAfter
        kv.hapticEnabled = haptic
        kv.soundEnabled = sound
        kv.sizeLevel = sizeLevel

        updateToolbarTheme(mode)
    }

    private fun updateKeyboardLayout() {
        val baseRows = when (currentLayer) {
            KeyboardLayer.ARABIC -> KeyboardLayoutHelper.getArabicRows()
            KeyboardLayer.ENGLISH ->
                KeyboardLayoutHelper.getEnglishRows(isEnglishCaps)
            KeyboardLayer.SYMBOLS -> KeyboardLayoutHelper.getSymbolsRows()
            KeyboardLayer.TASHKEEL -> KeyboardLayoutHelper.getArabicRows()
        }
        val rows = if (settingsRepo.isKeyboardAlwaysShowNumberRow() &&
            currentLayer != KeyboardLayer.SYMBOLS
        ) {
            listOf(KeyboardLayoutHelper.getNumberRow()) + baseRows
        } else {
            baseRows
        }
        keyboardView?.setKeyRows(rows)
    }

    private fun createToolbar(): View {
        val isLowVision =
            settingsRepo.getKeyboardMode() ==
                SettingsRepository.KEYBOARD_MODE_LOW_VISION

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            val heightPx = (46 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightPx
            )
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(
                if (isLowVision) Color.BLACK else Color.rgb(24, 24, 24)
            )
        }
        toolbarStrip = bar
        toolbarButtons.clear()

        val btnLang = createToolbarButton("ع / En", "تبديل اللغة") {
            toggleLanguage()
        }
        val btnTashkeel = createToolbarButton("ً حركات", "شريط التشكيل") {
            toggleTashkeelBar()
        }
        val btnVoice = createToolbarButton("🎤 صوت", "الكتابة بالصوت") {
            startVoiceTyping()
        }
        val btnCopy = createToolbarButton("نسخ", "نسخ النص المحدد") {
            handleCopy()
        }
        val btnPaste = createToolbarButton("لصق", "لصق من الحافظة") {
            handlePaste()
        }
        val btnCut = createToolbarButton("قص", "قص النص المحدد") {
            handleCut()
        }
        val btnSelectAll = createToolbarButton("تحديد الكل", "تحديد الكل") {
            handleSelectAll()
        }
        val btnClose =
            createToolbarButton("⬇ إغلاق", "إغلاق لوحة المفاتيح") {
                cancelVoiceTyping(announce = false)
                requestHideSelf(0)
                speakText("إغلاق لوحة المفاتيح")
            }

        bar.addView(btnLang)
        bar.addView(btnTashkeel)
        bar.addView(btnVoice)
        bar.addView(btnCopy)
        bar.addView(btnPaste)
        bar.addView(btnCut)
        bar.addView(btnSelectAll)
        bar.addView(btnClose)

        scroll.addView(bar)
        return scroll
    }

    private fun createToolbarButton(
        title: String,
        contentDesc: String,
        onClick: () -> Unit
    ): Button {
        val isLowVision =
            settingsRepo.getKeyboardMode() ==
                SettingsRepository.KEYBOARD_MODE_LOW_VISION

        val btn = Button(this).apply {
            text = title
            contentDescription = contentDesc
            isAllCaps = false
            maxLines = 1
            val textSize = if (isLowVision) 14f else 12f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            setTextColor(
                if (isLowVision) Color.rgb(255, 235, 59) else Color.WHITE
            )
            setBackgroundColor(Color.TRANSPARENT)
            val padPx = (10 * resources.displayMetrics.density).toInt()
            setPadding(padPx, 0, padPx, 0)
            minWidth = 0
            minimumWidth = (44 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { onClick() }
        }
        toolbarButtons.add(btn)
        return btn
    }

    private fun updateToolbarTheme(mode: String) {
        val isLowVision = mode == SettingsRepository.KEYBOARD_MODE_LOW_VISION
        val textColor =
            if (isLowVision) Color.rgb(255, 235, 59) else Color.WHITE
        val bgColor =
            if (isLowVision) Color.BLACK else Color.rgb(24, 24, 24)

        tashkeelBar?.setBackgroundColor(bgColor)
        toolbarStrip?.setBackgroundColor(bgColor)
        toolbarButtons.forEach { btn ->
            btn.setTextColor(textColor)
            val textSize = if (isLowVision) 14f else 12f
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        }
    }

    private fun createTashkeelBar(): LinearLayout {
        val scroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (48 * resources.displayMetrics.density).toInt()
            )
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(30, 30, 30))
        }

        val tashkeelKeys = KeyboardLayoutHelper.getTashkeelRow()
        for (item in tashkeelKeys) {
            val btn = Button(this).apply {
                text = item.label
                contentDescription = item.spokenText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener {
                    onKeySelected(item)
                }
            }
            strip.addView(btn)
        }
        scroll.addView(strip)

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll)
        }
        return wrapper
    }

    private fun toggleLanguage() {
        currentLayer = when (currentLayer) {
            KeyboardLayer.ARABIC -> {
                speakText("الإنجليزية")
                KeyboardLayer.ENGLISH
            }
            KeyboardLayer.ENGLISH -> {
                speakText("العربية")
                KeyboardLayer.ARABIC
            }
            KeyboardLayer.SYMBOLS -> {
                speakText("العربية")
                KeyboardLayer.ARABIC
            }
            KeyboardLayer.TASHKEEL -> {
                speakText("العربية")
                KeyboardLayer.ARABIC
            }
        }
        updateKeyboardLayout()
    }

    private fun toggleTashkeelBar() {
        val bar = tashkeelBar ?: return
        if (bar.visibility == View.VISIBLE) {
            bar.visibility = View.GONE
            speakText("إخفاء شريط التشكيل")
        } else {
            bar.visibility = View.VISIBLE
            speakText("إظهار شريط التشكيل")
        }
    }

    override fun onKeyHover(key: KeyModel) {
        if (AccessibilityUtils.isTouchExplorationEnabled(this)) {
            return
        }

        val isPhonetic = settingsRepo.isKeyboardPhoneticEnabled()
        val textToSpeak = if (isPhonetic && !key.phonetic.isNullOrEmpty()) {
            key.phonetic
        } else {
            key.spokenText
        }
        speakText(textToSpeak)
    }

    override fun onKeySelected(key: KeyModel) {
        val ic = currentInputConnection

        when (key.type) {
            KeyType.CHARACTER -> {
                if (key.label == "⇧" || key.label == "⇩") {
                    isEnglishCaps = !isEnglishCaps
                    speakText(if (isEnglishCaps) "أحرف كبيرة" else "أحرف صغيرة")
                    updateKeyboardLayout()
                    return
                }
                ic?.commitText(key.label, 1)
            }
            KeyType.ACTION_BACKSPACE -> {
                ic?.deleteSurroundingText(1, 0)
            }
            KeyType.ACTION_SPACE -> {
                ic?.commitText(" ", 1)
            }
            KeyType.ACTION_ENTER -> {
                sendKeyChar('\n')
            }
            KeyType.ACTION_SWITCH_LANG -> {
                toggleLanguage()
                return
            }
            KeyType.ACTION_SYMBOLS -> {
                currentLayer = if (currentLayer == KeyboardLayer.SYMBOLS) {
                    KeyboardLayer.ARABIC
                } else {
                    KeyboardLayer.SYMBOLS
                }
                speakText(
                    if (currentLayer == KeyboardLayer.SYMBOLS)
                        "الأرقام والرموز" else "الحروف"
                )
                updateKeyboardLayout()
                return
            }
            KeyType.ACTION_TASHKEEL -> {
                toggleTashkeelBar()
                return
            }
            KeyType.ACTION_VOICE -> {
                startVoiceTyping()
                return
            }
            KeyType.ACTION_HIDE -> {
                cancelVoiceTyping(announce = false)
                requestHideSelf(0)
                return
            }
        }

        if (settingsRepo.isKeyboardAnnounceAfterLift()) {
            val confirm = when (key.type) {
                KeyType.CHARACTER -> {
                    if (isPasswordField) "تم إدخال رمز"
                    else "تم إدخال ${key.spokenText}"
                }
                KeyType.ACTION_BACKSPACE -> "حذف"
                KeyType.ACTION_SPACE -> "مسافة"
                KeyType.ACTION_ENTER -> "إدخال"
                else -> key.spokenText
            }
            speakText(confirm)
        }
    }

    override fun onSwipeLeft() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        speakText("حذف")
    }

    override fun onSwipeRight() {
        currentInputConnection?.commitText(" ", 1)
        speakText("مسافة")
    }

    override fun onSwipeDown() {
        cancelVoiceTyping(announce = false)
        requestHideSelf(0)
        speakText("إخفاء لوحة المفاتيح")
    }

    override fun onSwipeUp() {
        toggleLanguage()
    }

    private fun announceInputContent(
        content: String,
        prefix: String,
        genericAnnouncement: String
    ) {
        if (isPasswordField) {
            speakText(genericAnnouncement)
        } else {
            speakText("$prefix: $content")
        }
    }

    internal fun handlePaste() {
        val cm =
            getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            currentInputConnection?.commitText(text, 1)
            announceInputContent(
                content = text,
                prefix = "تم اللصق",
                genericAnnouncement = "تم اللصق"
            )
        } else {
            speakText("الحافظة فارغة")
        }
    }

    internal fun handleCopy() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            val cm =
                getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("LordTTS", selected))
            announceInputContent(
                content = selected,
                prefix = "تم نسخ",
                genericAnnouncement = "تم النسخ"
            )
        } else {
            speakText("لا يوجد نص محدد للنسخ")
        }
    }

    internal fun handleCut() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            val cm =
                getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("LordTTS", selected))
            ic.commitText("", 1)
            announceInputContent(
                content = selected,
                prefix = "تم قص",
                genericAnnouncement = "تم القص"
            )
        } else {
            speakText("لا يوجد نص محدد للقص")
        }
    }

    private fun handleSelectAll() {
        currentInputConnection?.performContextMenuAction(
            android.R.id.selectAll
        )
        speakText("تم تحديد الكل")
    }

    internal fun startVoiceTyping() {
        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            speakText(
                "يرجى منح إذن الميكروفون أولاً. جارٍ فتح الإعدادات"
            )
            try {
                val cls =
                    "com.aymankhattab.nateq.settings.KeyboardSettingsActivity"
                val intent = Intent().apply {
                    setClassName(packageName, cls)
                    putExtra("request_mic", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            return
        }

        if (isListeningVoice) {
            cancelVoiceTyping(announce = true)
            return
        }

        val isAvailable = isRecognitionAvailableOverride
            ?: SpeechRecognizer.isRecognitionAvailable(this)
        if (!isAvailable) {
            speakText("خدمة التعرف على الصوت غير متاحة على جهازك")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = speechRecognizerFactory?.invoke(this)
                ?: SpeechRecognizer.createSpeechRecognizer(this)
            if (speechRecognizer == null) {
                isListeningVoice = false
                speakText("تعذر بدء التعرف على الصوت")
                return
            }

            val reqGen = sessionGeneration
            val reqId = ++voiceRequestCounter
            activeVoiceRequestId = reqId
            activeVoiceSessionGeneration = reqGen

            val langTag = if (currentLayer == KeyboardLayer.ENGLISH) "en-US"
            else "ar-SA"
            val intent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                        langTag
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE,
                        langTag
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }

            val listener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (reqId != activeVoiceRequestId ||
                        reqGen != sessionGeneration
                    ) {
                        return
                    }
                    isListeningVoice = true
                    vibrateFeedback(60L)
                }

                override fun onBeginningOfSpeech() {
                    if (reqId != activeVoiceRequestId ||
                        reqGen != sessionGeneration
                    ) {
                        return
                    }
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    if (reqId != activeVoiceRequestId ||
                        reqGen != sessionGeneration
                    ) {
                        return
                    }
                    isListeningVoice = false
                    vibrateFeedback(40L)
                }

                override fun onError(error: Int) {
                    if (reqId != activeVoiceRequestId ||
                        reqGen != sessionGeneration
                    ) {
                        return
                    }
                    activeVoiceRequestId = 0L
                    isListeningVoice = false
                    vibrateFeedback(40L)
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "يرجى منح إذن الميكروفون من إعدادات التطبيق"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "تعذر الاتصال بالشبكة للتعرف على الصوت"
                        SpeechRecognizer.ERROR_AUDIO ->
                            "خطأ في تسجيل الصوت"
                        SpeechRecognizer.ERROR_SERVER ->
                            "خطأ في خادم التعرف على الصوت"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            "خدمة الصوت مشغولة، حاول ثانية"
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "لم أتمكن من سماع صوتك، حاول ثانية"
                        else ->
                            "تعذر التعرف على الصوت، حاول ثانية"
                    }
                    speakText(errorMsg)
                }

                override fun onResults(results: Bundle?) {
                    if (reqId != activeVoiceRequestId ||
                        reqGen != sessionGeneration
                    ) {
                        return
                    }
                    activeVoiceRequestId = 0L
                    isListeningVoice = false
                    vibrateFeedback(50L)
                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrEmpty()) {
                        currentInputConnection?.commitText(text, 1)
                        announceInputContent(
                            content = text,
                            prefix = "تم إدخال",
                            genericAnnouncement = "تم الإدخال"
                        )
                    } else {
                        speakText("لم يتم التعرف على أي نص")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (reqId != activeVoiceRequestId ||
                        reqGen != sessionGeneration
                    ) {
                        return
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            }
            this.recognitionListener = listener
            speechRecognizer?.setRecognitionListener(listener)

            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            isListeningVoice = false
            activeVoiceRequestId = 0L
            recognitionListener = null
            speakText("تعذر بدء التعرف على الصوت")
        }
    }

    internal fun cancelVoiceTyping(announce: Boolean = false) {
        if (activeVoiceRequestId != 0L || isListeningVoice) {
            activeVoiceRequestId = 0L
            activeVoiceSessionGeneration = -1L
            isListeningVoice = false
            recognitionListener = null
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }
            if (announce) {
                vibrateFeedback(40L)
                speakText("تم إيقاف الاستماع")
            }
        }
    }

    private fun vibrateFeedback(durationMs: Long = 40L) {
        if (!settingsRepo.isKeyboardHapticEnabled()) return
        try {
            val vibrator =
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        durationMs, VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (_: Exception) {
            keyboardView?.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP
            )
        }
    }

    internal fun speakText(text: String) {
        if (text.isEmpty()) return
        lastSpokenText = text
        lastAnnouncedAccessibilityText = text
        keyboardView?.announceForAccessibility(text)
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kbd_tts")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelVoiceTyping(announce = false)
        activeSessionToken = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        recognitionListener = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        toolbarButtons.clear()
        toolbarStrip = null
        tashkeelBar = null
        keyboardView = null
    }
}
