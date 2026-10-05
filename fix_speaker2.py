#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Script to fix remaining issues in AnnouncementSpeaker.kt
"""

import re

def read_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()

def write_file(path, content):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)

speaker_path = r"core\audio\src\main\java\com\aymankhattab\nateq\core\audio\announcement\AnnouncementSpeaker.kt"
content = read_file(speaker_path)

# Fix 1: Update ensureInit to accept locale parameter
old_ensure_init = '''    private fun ensureInit(
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

new_ensure_init = '''    private fun ensureInit(
        onReady: (Boolean) -> Unit,
        requestedEngine: String? = null,
        locale: Locale
    ) {
        val requested = requestedEngine
            ?.takeIf {
                it in EnginePicker.installedEnginePackages(appContext)
            }
        val languageEngine = runCatching {
            settings?.getEngineForLanguage(locale.language)
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

# Fix startInit call in startInit's completion.hasNext
old_completion = '''if (completion.hasNext) {
                val languageEngine = runCatching {
                    settings?.getEngineForLanguage(currentLocale?.language ?: "")
                }.getOrNull()
                val fallbackEngine = runCatching {
                    settings?.getAnnouncementFallbackEngine()
                }.getOrNull()
                startInit(completion.nextEngine, languageEngine, fallbackEngine)
            }'''

new_completion = '''if (completion.hasNext) {
                val languageEngine = runCatching {
                    settings?.getEngineForLanguage(currentLocale?.language ?: "")
                }.getOrNull()
                val fallbackEngine = runCatching {
                    settings?.getAnnouncementFallbackEngine()
                }.getOrNull()
                startInit(completion.nextEngine, languageEngine, fallbackEngine, currentLocale)
            }'''

content = content.replace(old_completion, new_completion)

# Fix startInit signature to accept locale
old_start_init = '''    private fun startInit(
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

new_start_init = '''    /** يبدأ تهيئة TextToSpeech لمحركٍ محسوم؛ عند الاكتمال تُصفّى البوابة
     *  خارجها (تخدم النداءات المطابقة وتُسلسل إعادة تهيئةٍ للمحرك
     *  المتبقي بمحركٍ مختلف).
     *
     * @param finalEngine المحرك الصريح المطلوب لهذه الفئة (قد يكون null)
     * @param languageEngine محرك اللغة المضبوط للُغة الحالية
     * @param fallbackEngine محرك TTS الاحتياطي للإعلانات (إعداد announcement_fallback_engine)
     * @param locale لغة النص الحالي لتحديد محرك اللغة
     */
    private fun startInit(
        finalEngine: String?,
        languageEngine: String?,
        fallbackEngine: String?,
        locale: Locale
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
                val languageEngine = runCatching {
                    settings?.getEngineForLanguage(locale.language)
                }.getOrNull()
                val fallbackEngine = runCatching {
                    settings?.getAnnouncementFallbackEngine()
                }.getOrNull()
                startInit(completion.nextEngine, languageEngine, fallbackEngine, locale)
            }
        }'''

content = content.replace(old_start_init, new_start_init)

# Fix the call sites in ensureInit
old_ensure_init_call = '''            InitGate.Decision.START -> startInit(requested, languageEngine, fallbackEngine)'''
new_ensure_init_call = '''            InitGate.Decision.START -> startInit(requested, languageEngine, fallbackEngine, locale)'''

content = content.replace(old_ensure_init_call, new_ensure_init_call)

# Fix the call site in startInit's completion.hasNext (second occurrence)
old_completion2 = '''if (completion.hasNext) {
                val languageEngine = runCatching {
                    settings?.getEngineForLanguage(currentLocale?.language ?: "")
                }.getOrNull()
                val fallbackEngine = runCatching {
                    settings?.getAnnouncementFallbackEngine()
                }.getOrNull()
                startInit(completion.nextEngine, languageEngine, fallbackEngine)
            }'''

new_completion2 = '''if (completion.hasNext) {
                val languageEngine = runCatching {
                    settings?.getEngineForLanguage(locale.language)
                }.getOrNull()
                val fallbackEngine = runCatching {
                    settings?.getAnnouncementFallbackEngine()
                }.getOrNull()
                startInit(completion.nextEngine, languageEngine, fallbackEngine, locale)
            }'''

content = content.replace(old_completion2, new_completion2)

# Fix the call sites in ensureInit
old_ensure_init_call2 = '''            InitGate.Decision.START -> startInit(requested, languageEngine, fallbackEngine)'''
new_ensure_init_call2 = '''            InitGate.Decision.START -> startInit(requested, languageEngine, fallbackEngine, locale)'''

content = content.replace(old_ensure_init_call2, new_ensure_init_call2)

# Fix the call site in ensureInit (line 784 in original)
# Find and replace the call in ensureInit
old_ensure_init_call3 = '''            InitGate.Decision.START -> startInit(requested, languageEngine, fallbackEngine)'''

# The call in ensureInit should already be fixed by the previous replace
# Let's check if there are multiple occurrences

# Fix the call in startSpeech at line 1390
old_speech_call = '''        ensureInit({ ready ->
            if (ready) {
                executeSpeech()
            } else {
                // فشل التهيئة — لا تراجع تلقائي؛ شغّل نغمة خطأ وعرض تحذير
                Log.w(
                    TAG,
                    "[Speaker] فشل تهيئة $engineOverride — لا تراجع تلقائي (بند D1)"
                )
                playNoEngineCue()
                releaseAudioFocus()
                notifySpeechComplete()
            }
        }, engineOverride)'''

new_speech_call = '''        ensureInit({ ready ->
            if (ready) {
                executeSpeech()
            } else {
                // فشل التهيئة — لا تراجع تلقائي؛ شغّل نغمة خطأ وعرض تحذير
                Log.w(
                    TAG,
                    "[Speaker] فشل تهيئة $engineOverride — لا تراجع تلقائي (بند D1)"
                )
                playNoEngineCue()
                releaseAudioFocus()
                notifySpeechComplete()
            }
        }, engineOverride, currentLocale)'''

content = content.replace(old_speech_call, new_speech_call)

# Fix the launchWithCue call
old_launch_call = '''        ensureInit({ _ -> }, engineOverride)'''

new_launch_call = '''        ensureInit({ _ -> }, engineOverride, currentLocale)'''

content = content.replace(old_launch_call, new_launch_call)

# Fix the call in startSpeech
old_startSpeech_call = '''        ensureInit({ ready ->
            if (ready) {
                executeSpeech()
            } else {
                // فشل التهيئة — لا تراجع تلقائي؛ شغّل نغمة خطأ وعرض تحذير
                Log.w(
                    TAG,
                    "[Speaker] فشل تهيئة $engineOverride — لا تراجع تلقائي (بند D1)"
                )
                playNoEngineCue()
                releaseAudioFocus()
                notifySpeechComplete()
            }
        }, engineOverride)'''

# This is already replaced above

# Write the modified content
write_file(speaker_path, content)
print("AnnouncementSpeaker.kt updated successfully")