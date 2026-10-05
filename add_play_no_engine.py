#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Add playNoEngineCue function
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

# Add playNoEngineCue function and companion object before doSpeakParts
old_do_speak = '''    }

    /** ينطق المقاطع بالتتابع: النصوص بصوت الإعلان (النص المختلط الكتابات
     *  يُقسَّم إلى مقاطع لغوية فيُنطق كلٌّ بلغته وصوته — بند 17)، وأسماء
     *  الإيموجي بصوت فئتها. */
    private fun doSpeakParts('''

new_do_speak = '''    }

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

content = content.replace(old_do_speak, new_do_speak)

write_file(r"core\audio\src\main\java\com\aymankhattab\nateq\core\audio\announcement\AnnouncementSpeaker.kt", content)
print("playNoEngineCue added successfully")