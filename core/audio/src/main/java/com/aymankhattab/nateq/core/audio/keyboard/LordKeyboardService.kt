package com.aymankhattab.nateq.core.audio.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyModel
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyType
import com.aymankhattab.nateq.core.audio.keyboard.KeyboardLayoutHelper.KeyboardLayer
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.Locale

/**
 * خدمة لوحة مفاتيح Lord TTS الميسّرة لذوي الإعاقة البصرية.
 * تدعم وضعي الكفيف وضعيف البصر، التفعيل برفع الإصبع أو النقر المزدوج،
 * التشكيل، الكتابة بالصوت، أدوات الحافظة، مع إلغاء التصحيح التلقائي.
 */
class LordKeyboardService : InputMethodService(),
    LordKeyboardView.OnKeyboardActionListener {

    private lateinit var settingsRepo: SettingsRepository
    private var keyboardView: LordKeyboardView? = null
    private var currentLayer: KeyboardLayer = KeyboardLayer.ARABIC
    private var isEnglishCaps: Boolean = false
    private var isPasswordField: Boolean = false

    // أشرطة الأدوات
    private var tashkeelBar: LinearLayout? = null
    private var clipboardBar: LinearLayout? = null

    // محرك النطق الخاص باللوحة للاستجابة اللحظية
    private var tts: TextToSpeech? = null
    private var isTtsReady: Boolean = false

    // الكتابة بالصوت
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningVoice: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ar")
                isTtsReady = true
            }
        }
    }

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

        // شريط الحافظة السريع (مخفي افتراضياً)
        clipboardBar = createClipboardBar()
        clipboardBar?.visibility = View.GONE
        rootLayout.addView(clipboardBar)

        // مسطح لوحة المفاتيح
        val kbView = LordKeyboardView(this).apply {
            actionListener = this@LordKeyboardService
            val displayMetrics = resources.displayMetrics
            val kbHeightPx = (270 * displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                kbHeightPx
            )
        }
        keyboardView = kbView
        rootLayout.addView(kbView)

        applySettingsToKeyboard()
        updateKeyboardLayout()

        return rootLayout
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isPasswordField = checkIsPasswordField(info)
        applySettingsToKeyboard()
        updateKeyboardLayout()
    }

    private fun checkIsPasswordField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun applySettingsToKeyboard() {
        val kv = keyboardView ?: return
        val mode = settingsRepo.getKeyboardMode()
        val activation = settingsRepo.getKeyboardActivation()
        val announceAfter = settingsRepo.isKeyboardAnnounceAfterLift()
        val haptic = settingsRepo.isKeyboardHapticEnabled()
        val sound = settingsRepo.isKeyboardSoundEnabled()

        kv.keyboardMode = mode
        kv.activationMode = activation
        kv.announceAfterLift = announceAfter
        kv.hapticEnabled = haptic
        kv.soundEnabled = sound

        updateToolbarTheme(mode)
    }

    private fun updateKeyboardLayout() {
        val rows = when (currentLayer) {
            KeyboardLayer.ARABIC -> KeyboardLayoutHelper.getArabicRows()
            KeyboardLayer.ENGLISH ->
                KeyboardLayoutHelper.getEnglishRows(isEnglishCaps)
            KeyboardLayer.SYMBOLS -> KeyboardLayoutHelper.getSymbolsRows()
            KeyboardLayer.TASHKEEL -> KeyboardLayoutHelper.getArabicRows()
        }
        keyboardView?.setKeyRows(rows)
    }

    private fun createToolbar(): View {
        val isLowVision =
            settingsRepo.getKeyboardMode() ==
                SettingsRepository.KEYBOARD_MODE_LOW_VISION

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val heightPx = (46 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightPx
            )
            setBackgroundColor(
                if (isLowVision) Color.BLACK else Color.rgb(24, 24, 24)
            )
        }

        val btnLang = createToolbarButton("ع / En") {
            toggleLanguage()
        }
        val btnTashkeel = createToolbarButton("ً حركات") {
            toggleTashkeelBar()
        }
        val btnVoice = createToolbarButton("🎤 صوت") {
            startVoiceTyping()
        }
        val btnClip = createToolbarButton("📋 حافظة") {
            toggleClipboardBar()
        }
        val btnClose = createToolbarButton("⬇ إغلاق") {
            requestHideSelf(0)
        }

        bar.addView(btnLang)
        bar.addView(btnTashkeel)
        bar.addView(btnVoice)
        bar.addView(btnClip)
        bar.addView(btnClose)

        return bar
    }

    private fun createToolbarButton(
        title: String,
        onClick: () -> Unit
    ): Button {
        val isLowVision =
            settingsRepo.getKeyboardMode() ==
                SettingsRepository.KEYBOARD_MODE_LOW_VISION

        return Button(this).apply {
            text = title
            isAllCaps = false
            val textSize = if (isLowVision) 14f else 12f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            setTextColor(
                if (isLowVision) Color.rgb(255, 235, 59) else Color.WHITE
            )
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1.0f
            )
            setOnClickListener { onClick() }
        }
    }

    private fun updateToolbarTheme(mode: String) {
        val isLowVision = mode == SettingsRepository.KEYBOARD_MODE_LOW_VISION
        val textColor =
            if (isLowVision) Color.rgb(255, 235, 59) else Color.WHITE
        val bgColor =
            if (isLowVision) Color.BLACK else Color.rgb(24, 24, 24)

        tashkeelBar?.setBackgroundColor(bgColor)
        clipboardBar?.setBackgroundColor(bgColor)
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

    private fun createClipboardBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val heightPx = (46 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightPx
            )
            setBackgroundColor(Color.rgb(32, 32, 32))
        }

        val btnPaste = createToolbarButton("لصق") { handlePaste() }
        val btnCopy = createToolbarButton("نسخ") { handleCopy() }
        val btnCut = createToolbarButton("قص") { handleCut() }
        val btnSelectAll = createToolbarButton("تحديد الكل") {
            handleSelectAll()
        }

        bar.addView(btnPaste)
        bar.addView(btnCopy)
        bar.addView(btnCut)
        bar.addView(btnSelectAll)

        return bar
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
            clipboardBar?.visibility = View.GONE
            speakText("إظهار شريط التشكيل")
        }
    }

    private fun toggleClipboardBar() {
        val bar = clipboardBar ?: return
        if (bar.visibility == View.VISIBLE) {
            bar.visibility = View.GONE
            speakText("إخفاء الحافظة")
        } else {
            bar.visibility = View.VISIBLE
            tashkeelBar?.visibility = View.GONE
            speakText("إظهار الحافظة")
        }
    }

    override fun onKeyHover(key: KeyModel) {
        if (isPasswordField) {
            speakText("رمز محمي")
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
            KeyType.ACTION_CLIPBOARD -> {
                toggleClipboardBar()
                return
            }
            KeyType.ACTION_HIDE -> {
                requestHideSelf(0)
                return
            }
        }

        if (settingsRepo.isKeyboardAnnounceAfterLift()) {
            val confirm = if (isPasswordField) "تم إدخال رمز"
            else "تم إدخال ${key.spokenText}"
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
        requestHideSelf(0)
        speakText("إخفاء لوحة المفاتيح")
    }

    override fun onSwipeUp() {
        toggleLanguage()
    }

    private fun handlePaste() {
        val cm =
            getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            currentInputConnection?.commitText(text, 1)
            speakText("تم اللصق: $text")
        } else {
            speakText("الحافظة فارغة")
        }
    }

    private fun handleCopy() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            val cm =
                getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("LordTTS", selected))
            speakText("تم نسخ: $selected")
        } else {
            speakText("لا يوجد نص محدد للنسخ")
        }
    }

    private fun handleCut() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            val cm =
                getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("LordTTS", selected))
            ic.commitText("", 1)
            speakText("تم قص: $selected")
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

    private fun startVoiceTyping() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speakText("خدمة التعرف على الصوت غير متاحة على جهازك")
            return
        }

        if (isListeningVoice) {
            speechRecognizer?.stopListening()
            isListeningVoice = false
            speakText("توقف الاستماع")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            val intent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer?.setRecognitionListener(object :
                RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListeningVoice = true
                    speakText("تحدث الآن...")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListeningVoice = false
                }

                override fun onError(error: Int) {
                    isListeningVoice = false
                    speakText("لم أتمكن من سماع صوتك، حاول ثانية")
                }

                override fun onResults(results: Bundle?) {
                    isListeningVoice = false
                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrEmpty()) {
                        currentInputConnection?.commitText(text, 1)
                        speakText("تمت كتابة: $text")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListeningVoice = false
            speakText("يرجى التأكد من صلاحية الميكروفون")
        }
    }

    private fun speakText(text: String) {
        if (text.isEmpty()) return
        keyboardView?.announceForAccessibility(text)
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kbd_tts")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
