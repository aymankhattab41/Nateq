#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Fix the playNoEngineCue function
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

# Fix the playNoEngineCue function
old_cue = '''    private fun playNoEngineCue() {
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

new_cue = '''    private fun playNoEngineCue() {
        val now = System.currentTimeMillis()
        if (now - AnnouncementSpeaker.lastNoEngineCueTime < 60_000) return
        AnnouncementSpeaker.lastNoEngineCueTime = now
        try {
            val cue = AudioCue(type = CueType.NO_ENGINE)
            AudioCuePlayer.getInstance(appContext).play(cue) { _ -> }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to play NO_ENGINE cue", t)
        }
    }'''

content = content.replace(old_cue, new_cue)

write_file(r"core\audio\src\main\java\com\aymankhattab\nateq\core\audio\announcement\AnnouncementSpeaker.kt", content)
print("Fixed playNoEngineCue function")