package com.aymankhattab.nateq.core.audio.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.concurrent.atomic.AtomicReference

/**
 * موجّه اختصارات المفاتيح القابل للتهيئة (ع٢٩-13، ع٤١-ب1).
 * - يقرأ جدول الربط من الإعدادات عند كل حدث (ShortcutBindingTable).
 * - يستهلك المفتاح فقط عند تحقّق إيماءةٍ مربوطة (النمط المعتمد في ب1).
 * - استمرارُ المفتاحين معاً يتولّاه مؤقّت الطوارئ في الخدمة التي تنادي
 *   executeGesture(BOTH_HOLD).
 * - أثناء الإيقاف المؤقت لا يُستهلك ولا يُنفَّذ غير BOTH_HOLD (الاستئناف)،
 *   لأنّ isActive تعود false والمؤقّت يبقى يعمل.
 */
class KeyShortcutRouter(
    private val service: AccessibilityService,
    private val settings: SettingsRepository,
    private val stateStore: A11yStateStore?
) {

    private val bindingsRef = AtomicReference(
        ShortcutBindingTable.decode(settings.getA11yShortcutBindings())
    )

    private val detector = ShortcutGestureDetector(
        isGestureBound = { bindingsRef.get().isBound(it) }
    )

    /** جدول الربط الحالي (نسخةٌ للقراءة). */
    fun bindings(): ShortcutBindingTable = bindingsRef.get()

    /** إعادة تحميل جدول الربط بعد تغييره من الإعدادات. */
    fun reloadBindings() {
        bindingsRef.set(
            ShortcutBindingTable.decode(settings.getA11yShortcutBindings())
        )
    }

    /** حفظ جدول ربطٍ جديد في الإعدادات وتطبيقه فوراً. */
    fun setBindings(table: ShortcutBindingTable) {
        bindingsRef.set(table)
        settings.setA11yShortcutBindings(table.encode())
    }

    /** @return هل تم استهلاك الحدث؟ */
    fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        val key = volumeKeyOf(event.keyCode) ?: return false

        val active = FeatureFlags.isActive(
            FeatureFlags.FEATURE_KEY_SHORTCUTS,
            context = service.applicationContext,
            settings = settings,
            stateStore = stateStore
        )
        if (!active) {
            detector.reset()
            return false
        }

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val detection = detector.onKeyDown(
                    key = key,
                    eventTimeMs = event.eventTime,
                    isLongPress = event.isLongPress,
                    repeatCount = event.repeatCount
                )
                detection.gesture?.let { executeGesture(it) }
                detection.consume
            }
            KeyEvent.ACTION_UP -> {
                val detection = detector.onKeyUp(key, event.eventTime)
                detection.gesture?.let { executeGesture(it) }
                detection.consume
            }
            else -> false
        }
    }

    /** تنفيذ الفعل المربوط بإيماءةٍ (يُستدعى من اختصار المفاتيح والطوارئ). */
    fun executeGesture(gesture: ShortcutGesture) {
        executeAction(bindingsRef.get().actionFor(gesture))
    }

    /** تنفيذ فعلٍ مباشرةً. */
    fun executeAction(action: ShortcutAction) {
        val context = service.applicationContext
        when (action) {
            ShortcutAction.READ_SCREEN -> readScreenNow(context)
            ShortcutAction.READ_CLIPBOARD -> readClipboard(context)
            ShortcutAction.REPEAT_LAST ->
                RepeatSpellController.repeatLast(context)
            ShortcutAction.SPELL_LAST ->
                RepeatSpellController.spellLast(context)
            ShortcutAction.WHERE_AM_I ->
                WhereAmIController.announceCurrentLocation(context)
            ShortcutAction.TOGGLE_PAUSE -> togglePause(context)
            ShortcutAction.TOGGLE_CONTINUOUS -> toggleContinuous(context)
            ShortcutAction.OCR_FOCUS -> readImage(context)
            ShortcutAction.READ_FULL_URL -> readFullLinks(context)
            ShortcutAction.REPEAT_PREVIOUS ->
                RepeatSpellController.repeatPrevious(context)
            ShortcutAction.SPEECH_HISTORY ->
                RepeatSpellController.announceHistory(context)
            ShortcutAction.SHOW_QUICK_MENU -> A11yQuickMenu.toggle(service) {
                executeAction(it)
            }
            ShortcutAction.EXPAND_READ_MORE -> expandReadMoreExplicit(context)
            ShortcutAction.NONE ->
                Log.i(TAG, "no action bound for gesture")
        }
    }

    /** ع٤٣-1: تنفيذ صريح لإيماءة «قراءة المزيد» وتوسيع الرسالة المفوكسة. */
    private fun expandReadMoreExplicit(context: Context) {
        if (!isFeatureActive(
                FeatureFlags.FEATURE_READ_MORE_EXPAND,
                context
            )
        ) {
            announce(context, "ميزة قراءة المزيد غير مفعّلة")
            return
        }
        val svc = service as? NateqAccessibilityService
        if (svc == null) {
            announce(context, "خدمة إمكانية الوصول غير متصلة")
            return
        }
        svc.launchExpandReadMoreExplicit { result ->
            if (result != null) {
                announce(context, result)
            } else {
                announce(context, "لا يوجد نص مقتطع لتوسيعه")
            }
        }
    }

    /** ع٤١-ب6: يبدّل القراءة المستمرة (مشروطة بمفتاح ميزتها). */
    private fun toggleContinuous(context: Context) {
        val reading = ContinuousReader.isReading()
        if (!reading && !isFeatureActive(
                FeatureFlags.FEATURE_CONTINUOUS_READING, context
            )
        ) {
            announce(context, "القراءة المستمرة غير مفعّلة")
            return
        }
        ContinuousReader.toggle(continuousSource())
    }

    /** ع٤١-ب12: يقرأ العنصر المركّز بالتصوير (مشروط بمفتاح ميزته). */
    private fun readImage(context: Context) {
        if (!isFeatureActive(FeatureFlags.FEATURE_OCR_READING, context)) {
            announce(context, "قراءة الصور غير مفعّلة")
            return
        }
        OcrScreenReader.readFocusedElement(service)
    }

    private fun isFeatureActive(feature: String, context: Context): Boolean =
        FeatureFlags.isActive(
            feature,
            context = context,
            settings = settings,
            stateStore = stateStore
        )

    private fun announce(context: Context, text: String) {
        // ع٤١-ب11: التبديل التلقائي حسب التطبيق الأمامي (ملف التطبيق).
        val voice = A11yVoiceResolver.resolve(
            context = context,
            settings = settings,
            packageName = AccessibilityBridge.foregroundPackage(),
            senderName = null
        )
        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = text,
            isInterruptAllowed = true,
            voiceId = voice?.voiceId,
            engineOverride = voice?.engine,
            speechRate = voice?.speechRate,
            pitch = voice?.pitch,
            volume = voice?.volume
        )
    }

    private fun continuousSource(): ContinuousReader.Source =
        object : ContinuousReader.Source {
            override fun readTexts(): List<String> =
                AccessibilityBridge.readScreenTexts()

            override fun scrollForward(): Boolean =
                AccessibilityBridge.scrollForward()

            override fun currentPackage(): String? =
                AccessibilityBridge.foregroundPackage()

            override fun announce(text: String) {
                this@KeyShortcutRouter.announce(
                    service.applicationContext,
                    text
                )
            }
        }

    private fun readScreenNow(context: Context) {
        val texts = AccessibilityBridge.readScreenTexts()
            .filter { it.isNotBlank() }
        val focus = AccessibilityBridge.focusSnapshot()
            ?.readableText()
            ?.takeIf { it.isNotBlank() }
        val message = when {
            texts.isNotEmpty() -> texts.joinToString(". ")
            focus != null -> focus
            else -> "لا يوجد نصّ على الشاشة"
        }
        announce(context, message)
    }

    /** ع٤١-ب7: ينطق الروابط كاملةً من الشاشة (إيماءة «اقرأ الرابط
     *  كاملاً») — بديلُ الطلب عن النطق المختصر؛ مشروطٌ بمفتاح الميزة. */
    private fun readFullLinks(context: Context) {
        if (!isFeatureActive(FeatureFlags.FEATURE_URL_SHORTENING, context)) {
            announce(context, "اختصار الروابط غير مفعّل")
            return
        }
        val texts = buildList {
            AccessibilityBridge.focusSnapshot()?.readableText()
                ?.let { add(it) }
            addAll(AccessibilityBridge.readScreenTexts())
        }
        val urls = texts
            .flatMap { UrlShortenerSpoken.findUrlsForSpeech(it) }
            .distinct()
        val message = if (urls.isEmpty()) {
            "لا يوجد رابط على الشاشة"
        } else {
            urls.joinToString(". ") { "رابط $it" }
        }
        announce(context, message)
    }

    private fun readClipboard(context: Context) {
        val manager = context.getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as? ClipboardManager
        val clip = manager?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(context).toString()
        } else {
            null
        }
        val message = text?.takeIf { it.isNotBlank() } ?: "الحافظة فارغة"
        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = message,
            isInterruptAllowed = true
        )
    }

    private fun togglePause(context: Context) {
        val store = stateStore ?: return
        val newPaused = !store.isPaused()
        store.setPaused(newPaused)
        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = if (newPaused) "الخدمة موقوفة" else "الخدمة تعمل",
            isInterruptAllowed = true
        )
    }

    private fun volumeKeyOf(keyCode: Int): VolumeKey? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> VolumeKey.UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeKey.DOWN
        else -> null
    }

    companion object {
        private const val TAG = "NATEQ_TTS"

        /** إجراء اختبار إيماءةٍ يُرسَل من شاشة الإعدادات (ع٤١-ب1.6). */
        const val ACTION_TEST_SHORTCUT =
            "com.aymankhattab.nateq.action.TEST_SHORTCUT"

        /** مفتاح الإيماءة المطلوب اختبارها في بثّ الاختبار. */
        const val EXTRA_GESTURE = "gesture"
    }
}
