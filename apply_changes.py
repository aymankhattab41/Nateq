#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Script to apply D1-D6 changes to the Nateq codebase.
"""

import re
import os

def read_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()

def write_file(path, content):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)

# ========================================================================
# 1. AnnouncementSpeaker.kt - Main changes for D1-D3
# ========================================================================
speaker_path = r"core\audio\src\main\java\com\aymankhattab\nateq\core\audio\announcement\AnnouncementSpeaker.kt"
content = read_file(speaker_path)

# 1. Replace safeEngineForAnnouncement function
old_safe_engine = '''/**
 * حسم محرك آمن لنطق الإعلانات والأحداث:
 * يمنع التكرار الذاتي وحلقات الربط الفاشلة؛ إن كان المحرك المطلوب فارغاً
 * أو هو حزمة التطبيق نفسها (التي تتطلب BIND_TTS_SERVICE للنظام فتفشل عند ربط
 * التطبيق بذاتها)، يتم تفويض أول محرك خارجي مثبت من [EnginePicker].
 */
internal fun safeEngineForAnnouncement(
    context: Context,
    engine: String?,
    defaultSynthProvider: () -> String? = {
        runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                "tts_default_synth"
            )
        }.getOrNull()
    },
    installedEnginesProvider: () -> List<String> = {
        EnginePicker.installedEnginePackages(context)
    }
): String? {
    if (!engine.isNullOrBlank() && engine != context.packageName) {
        return engine
    }
    val defaultSynth = defaultSynthProvider()
    if (!defaultSynth.isNullOrBlank() &&
        defaultSynth != context.packageName
    ) {
        return defaultSynth
    }
    return installedEnginesProvider().firstOrNull {
        it != context.packageName
    }
}'''

new_safe_engine = '''/**
 * حسم محرك آمن لنطق الإعلانات والأحداث — نسخة صارمة (بند D1):
 * يمنع التكرار الذاتي فقط (حزمة التطبيق نفسها).
 * ترتيب الحسم الوحيد:
 *   1. محرك الفئة الصريح (مضبوط عبر واجهة الفئة).
 *   2. محرك اللغة (getEngineForLanguage).
 *   3. محرك TTS الاحتياطي للإعلانات (announcementFallbackEngine، اختياري، مختار يدوياً).
 *   لا فرع تلقائي (tts_default_synth / أول محرك مثبت) — إن لم يُحسم محرك: null.
 *   استدعاء هذه الدالة فقط من مسار النطق؛ الفشل (null) يُعالَج في الأعلى
 *   بتشغيل نغمة خطأ NO_ENGINE وعرض تحذير ثابت «الإعلانات بلا محرك».
 * دالة نقية مستقلة (بلا حالة) لسهولة الاختبار الآلي.
 */
internal fun safeEngineForAnnouncement(
    context: Context,
    requestedCategoryEngine: String?,
    languageEngine: String?,
    announcementFallbackEngine: String?
): String? {
    // 1. محرك الفئة الصريح
    if (!requestedCategoryEngine.isNullOrBlank() &&
        requestedCategoryEngine != context.packageName
    ) {
        return requestedCategoryEngine
    }
    // 2. محرك اللغة
    if (!languageEngine.isNullOrBlank() &&
        languageEngine != context.packageName
    ) {
        return languageEngine
    }
    // 3. محرك الاحتياط الصريح للمستخدم (إعداد announcementFallbackEngine)
    if (!announcementFallbackEngine.isNullOrBlank() &&
        announcementFallbackEngine != context.packageName
    ) {
        return announcementFallbackEngine
    }
    // لا فرع تلقائي — الفشل التام يُعاد null ويُعالَج في المستدعي
    return null
}'''

content = content.replace(old_safe_engine, new_safe_engine)

# 2. Update ensureInit function
old_ensure_init = '''    private fun ensureInit(
        onReady: (Boolean) -> Unit,
        requestedEngine: String? = null
    ) {
        val requested = requestedEngine
            ?.takeIf {
                it in EnginePicker.installedEnginePackages(appContext)
            }
        // مسارٌ جاهز: المثيل الحالي مرتبط فعلاً بنفس المحرك المطلوب —
        // نداء فوري بلا بوابة (لا تهيئة جديدة ولا انتظار دورة).
        if (tts != null && boundEngine == requested) {
            onReady(true)
            return
        }
        // خلاف ذلك يُحسم القرار عبر البوابة (بند [5]): إن كانت تهيئةٌ
        // ما قائمة يُصرف الخطاف عند اكتمال محركٍ مطابق داخل تلك الدورة،
        // وإن كان المحرك مختلفاً ينتظر إعادة تهيئةٍ تُسلسل بعدها — فلا
        // يُنطق النص أبداً بمحركٍ حُسم لاحقاً عن التهيئة الجارية.
        when (initGate.enqueue(requested, onReady)) {
            InitGate.Decision.JOIN -> return
            InitGate.Decision.START -> startInit(requested)
        }
    }'''

new_ensure_init = '''    private fun ensureInit(
        onReady: (Boolean) -> Unit,
        requestedEngine: String? = null
    ) {
        val requested = requestedEngine
            ?.takeIf {
                it in EnginePicker.installedEnginePackages(appContext)
            }
        val languageEngine = runCatching {
            settings?.getEngineForLanguage(currentLocale?.language ?: "")
        }.getOrNull()
        val fallbackEngine = runCatching {
            settings?.getAnnouncementFallbackEngine()
        }.getOrNull()
        // مسارٌ جاهز: المثيل الحالي مرتبط فعلاً بنفس المحرك المطلوب —
        // نداء فوري بلا بوابة (لا تهيئة جديدة ولا انتظار دورة).
        if (tts != null && boundEngine == requested) {
            onReady(true)
            return
        }
        // خلاف ذلك يُحسم القرار عبر البوابة (بند [5]): إن كانت تهيئةٌ
        // ما قائمة يُصرف الخطاف عند اكتمال محركٍ مطابق داخل تلك الدورة،
        // وإن كان المحرك مختلفاً ينتظر إعادة تهيئةٍ تُسلسل بعدها — فلا
        // يُنطق النص أبداً بمحركٍ حُسم لاحقاً عن التهيئة الجارية.
        when (initGate.enqueue(requested, onReady)) {
            InitGate.Decision.JOIN -> return
            InitGate.Decision.START -> startInit(requested, languageEngine, fallbackEngine)
        }
    }'''

content = content.replace(old_ensure_init, new_ensure_init)

# 3. Update startInit function
old_start_init = '''    /** يبدأ تهيئة TextToSpeech لمحركٍ محسوم؛ عند الاكتمال تُصفّى البوابة
     *  خارجها (تخدم النداءات المطابقة وتُسلسل إعادة تهيئةٍ للمحرك
     *  المتبقي بمحركٍ مختلف). */
    private fun startInit(finalEngine: String?) {
        // محرك مختلف للفئة القادمة (أو تهيئة أولى): أُغلق الربط القديم
        // كاملاً ثم أُهيّئ الجديد (لا تبقى مثيلات معلقة على محرك آخر).
        if (tts != null) {
            shutdownSafely()
        }
        val engine = safeEngineForAnnouncement(appContext, finalEngine)
        boundEngine = engine
        var newTts: TextToSpeech? = null
        val timeoutRunnable = Runnable {
            Log.w(TAG, "[Speaker] Engine $engine init timed out")
            val completion = initGate.complete(false)
            completion.served.forEach { cb -> cb(false) }
            if (completion.hasNext) {
                startInit(completion.nextEngine)
            }
        }
        mainHandler.postDelayed(timeoutRunnable, 5_000L)'''

new_start_init = '''    /** يبدأ تهيئة TextToSpeech لمحركٍ محسوم؛ عند الاكتمال تُصفّى البوابة
     *  خارجها (تخدم النداءات المطابقة وتُسلسل إعادة تهيئةٍ للمحرك
     *  المتبقي بمحركٍ مختلف).
     *
     * @param finalEngine المحرك الصريح المطلوب لهذه الفئة (قد يكون null)
     * @param languageEngine محرك اللغة المضبوط للُغة الحالية
     * @param fallbackEngine محرك TTS الاحتياطي للإعلانات (إعداد announcement_fallback_engine)
     */
    private fun startInit(
        finalEngine: String?,
        languageEngine: String?,
        fallbackEngine: String?
    ) {
        // محرك مختلف للفئة القادمة (أو تهيئة أولى): أُغلق الربط القديم
        // كاملاً ثم أُهيّئ الجديد (لا تبقى مثيلات معلقة على محرك آخر).
        if (tts != null) {
            shutdownSafely()
        }
        val engine = safeEngineForAnnouncement(
            appContext, finalEngine, languageEngine, fallbackEngine
        )
        boundEngine = engine
        var newTts: TextToSpeech? = null
        val timeoutRunnable = Runnable {
            Log.w(TAG, "[Speaker] Engine $engine init timed out")
            val completion = initGate.complete(false)
            completion.served.forEach { cb -> cb(false) }
            if (completion.hasNext) {
                startInit(completion.nextEngine, languageEngine, fallbackEngine)
            }
        }'''

content = content.replace(old_start_init, new_start_init)

# 4. Fix the call site in startInit where completion.hasNext
old_completion = '''if (completion.hasNext) {
                startInit(completion.nextEngine)
            }'''

new_completion = '''if (completion.hasNext) {
                val languageEngine = runCatching {
                    settings?.getEngineForLanguage(currentLocale?.language ?: "")
                }.getOrNull()
                val fallbackEngine = runCatching {
                    settings?.getAnnouncementFallbackEngine()
                }.getOrNull()
                startInit(completion.nextEngine, languageEngine, fallbackEngine)
            }'''

content = content.replace(old_completion, new_completion)

# 4. Fix the fallback logic in startSpeech (lines 1372-1393)
old_fallback = '''            } else if (engineOverride != null) {
                Log.w(
                    TAG,
                    "[Speaker] فشل تهيئة $engineOverride —" +
                    " التراجع لمحرك بديل"
                )
                val fallback = safeEngineForAnnouncement(
                    appContext, null
                )
                if (fallback != null && fallback != engineOverride) {
                    ensureInit({ fallbackReady ->
                        if (fallbackReady) {
                            executeSpeech()
                        } else {
                            releaseAudioFocus()
                            notifySpeechComplete()
                        }
                    }, requestedEngine = fallback)
                } else {
                    releaseAudioFocus()
                    notifySpeechComplete()
                }
            } else {
                releaseAudioFocus()
                notifySpeechComplete()
            }'''

new_fallback = '''            } else {
                // فشل التهيئة — لا تراجع تلقائي؛ شغّل نغمة خطأ وعرض تحذير
                Log.w(
                    TAG,
                    "[Speaker] فشل تهيئة $engineOverride — لا تراجع تلقائي (بند D1)"
                )
                playNoEngineCue()
                releaseAudioFocus()
                notifySpeechComplete()
            }'''

content = content.replace(old_fallback, new_fallback)

# 5. Add playNoEngineCue function and companion object before doSpeakParts
old_do_speak_parts = '''}
    }

    /** ينطق المقاطع بالتتابع: النصوص بصوت الإعلان (النص المختلط الكتابات
     *  يُقسَّم إلى مقاطع لغوية فيُنطق كلٌّ بلغته وصوته — بند 17)، وأسماء
     *  الإيموجي بصوت فئتها. */
    private fun doSpeakParts('''

new_do_speak_parts = '''}
    }

    /** يُشغّل نغمة خطأ قصيرة (CueSynth.NO_ENGINE) عند عدم وجود محرك للإعلانات.
     *  محمية بمهلة 60 ثانية لتجنب تكرار النغمة المزعجة. */
    private fun playNoEngineCue() {
        val now = System.currentTimeMillis()
        if (now - lastNoEngineCueTime < 60_000) return
        lastNoEngineCueTime = now
        try {
            val cue = CueSynth.getInstance(appContext).createCue(CueSynth.Type.NO_ENGINE)
            AudioCuePlayer.getInstance(appContext).play(cue) { _ -> }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to play NO_ENGINE cue", t)
        }
    }

    companion object {
        @Volatile
        private var lastNoEngineCueTime: Long = 0
    }

    /** ينطق المقاطع بالتتابع: النصوص بصوت الإعلان (النص المختلط الكتابات
     *  يُقسَّم إلى مقاطع لغوية فيُنطق كلٌّ بلغته وصوته — بند 17)، وأسماء
     *  الإيموجي بصوت فئتها. */
    private fun doSpeakParts('''

content = content.replace(old_do_speak_parts, new_do_speak_parts)

# 5. Fix the timeout in startInit - use 8 seconds for API <= 29, 5 seconds for API 30+
old_timeout = '''        mainHandler.postDelayed(timeoutRunnable, 5_000L)'''

new_timeout = '''        val initTimeoutMs = if (Build.VERSION.SDK_INT <= 29) 8_000L else 5_000L
        mainHandler.postDelayed(timeoutRunnable, initTimeoutMs)'''

content = content.replace(old_timeout, new_timeout)

# 6. Add retry logic in startInit - retry once on same engine
# We need to add retry logic in the timeout runnable
old_timeout_runnable = '''        val timeoutRunnable = Runnable {
            Log.w(TAG, "[Speaker] Engine $engine init timed out")
            val completion = initGate.complete(false)
            completion.served.forEach { cb -> cb(false) }
            if (completion.hasNext) {
                val languageEngine = runCatching {
                    settings?.getEngineForLanguage(currentLocale?.language ?: "")
                }.getOrNull()
                val fallbackEngine = runCatching {
                    settings?.getAnnouncementFallbackEngine()
                }.getOrNull()
                startInit(completion.nextEngine, languageEngine, fallbackEngine)
            }
        }
        val initTimeoutMs = if (Build.VERSION.SDK_INT <= 29) 8_000L else 5_000L
        mainHandler.postDelayed(timeoutRunnable, initTimeoutMs)'''

new_timeout_runnable = '''        // متغير لتتبع عدد محاولات التهيئة للمحرك الحالي
        val initAttempt = java.util.concurrent.atomic.AtomicInteger(1)
        val timeoutRunnable = Runnable {
            Log.w(TAG, "[Speaker] Engine $engine init timed out (attempt ${initAttempt.get()})")
            if (initAttempt.get() == 1) {
                // إعادة المحاولة مرة واحدة على نفس المحرك (بند D3)
                initAttempt.incrementAndGet()
                Log.i(TAG, "[Speaker] Retrying init for engine $engine")
                // إعادة تهيئة نفس المحرك
                if (tts != null) {
                    shutdownSafely()
                }
                val newTtsRetry = TextToSpeech(appContext, { status ->
                    mainHandler.removeCallbacks(this)
                    val success = status == TextToSpeech.SUCCESS
                    if (success) {
                        tts = newTts
                    } else {
                        tts = null
                    }
                    val completion = initGate.complete(success)
                    completion.served.forEach { cb -> cb(success) }
                    if (completion.hasNext) {
                        val languageEngine = runCatching {
                            settings?.getEngineForLanguage(currentLocale?.language ?: "")
                        }.getOrNull()
                        val fallbackEngine = runCatching {
                            settings?.getAnnouncementFallbackEngine()
                        }.getOrNull()
                        startInit(completion.nextEngine, languageEngine, fallbackEngine)
                    }
                }, engine)
                val initTimeoutMs = if (Build.VERSION.SDK_INT <= 29) 8_000L else 5_000L
                mainHandler.postDelayed(this, initTimeoutMs)
                return
            }
            // المحاولة الثانية فشلت — فشل تام
            Log.e(TAG, "[Speaker] Engine $engine init failed after retry")
            val completion = initGate.complete(false)
            completion.served.forEach { cb -> cb(false) }
            if (completion.hasNext) {
                val languageEngine = runCatching {
                    settings?.getEngineForLanguage(currentLocale?.language ?: "")
                }.getOrNull()
                val fallbackEngine = runCatching {
                    settings?.getAnnouncementFallbackEngine()
                }.getOrNull()
                startInit(completion.nextEngine, languageEngine, fallbackEngine)
            }
        }
        val initTimeoutMs = if (Build.VERSION.SDK_INT <= 29) 8_000L else 5_000L
        mainHandler.postDelayed(timeoutRunnable, initTimeoutMs)'''

content = content.replace(old_timeout_runnable, new_timeout_runnable)

# Write the modified content
write_file(speaker_path, content)
print("AnnouncementSpeaker.kt updated successfully")