#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Fix remaining issues in AnnouncementSpeaker.kt
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

# Fix 1: Remove duplicate safeEngineForAnnouncement function (keep only the new one)
# Find the old safeEngineForAnnouncement with Function0 parameters and remove it
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

# Remove the old safeEngineForAnnouncement function
content = content.replace(old_safe_engine, '')

# Fix 2: Fix the startInit call at line 815 - it's using old signature
content = content.replace(
    'val engine = safeEngineForAnnouncement(\n            appContext, finalEngine, languageEngine, fallbackEngine\n        )',
    'val engine = safeEngineForAnnouncement(\n            appContext, finalEngine, languageEngine, fallbackEngine\n        )'
)

# Fix the playNoEngineCue function - remove duplicate companion object
# The companion object was added but there might already be one
# Let's check if there's already a companion object and merge

# Fix the playNoEngineCue function - remove the companion object from inside the function
old_play_cue = '''    private fun playNoEngineCue() {
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
    }'''

new_play_cue = '''    private fun playNoEngineCue() {
        val now = System.currentTimeMillis()
        if (now - AnnouncementSpeaker.lastNoEngineCueTime < 60_000) return
        AnnouncementSpeaker.lastNoEngineCueTime = now
        try {
            val cue = CueSynth.getInstance(appContext).createCue(CueSynth.Type.NO_ENGINE)
            AudioCuePlayer.getInstance(appContext).play(cue) { _ -> }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to play NO_ENGINE cue", t)
        }
    }'''

content = content.replace(old_play_cue, new_play_cue)

# Add the companion object field at the class level (top level)
# Find the class declaration and add the field there
class_start = '''class AnnouncementSpeaker(
    appContext: Context
) : AudioCuePlayer.Callback {'''

new_class_start = '''class AnnouncementSpeaker(
    appContext: Context
) : AudioCuePlayer.Callback {

    companion object {
        @Volatile
        private var lastNoEngineCueTime: Long = 0
    }'''

content = content.replace(class_start, new_class_start)

# Fix the playNoEngineCue to use AnnouncementSpeaker.lastNoEngineCueTime
old_play_cue2 = '''    private fun playNoEngineCue() {
        val now = System.currentTimeMillis()
        if (now - AnnouncementSpeaker.lastNoEngineCueTime < 60_000) return
        AnnouncementSpeaker.lastNoEngineCueTime = now
        try {
            val cue = CueSynth.getInstance(appContext).createCue(CueSynth.Type.NO_ENGINE)
            AudioCuePlayer.getInstance(appContext).play(cue) { _ -> }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to play NO_ENGINE cue", t)
        }
    }'''

# The playNoEngineCue should already be fixed from the previous replacement
# Let's just make sure the companion object is at class level

write_file(speaker_path, content)
print("Fixed duplicate safeEngineForAnnouncement and playNoEngineCue issues")