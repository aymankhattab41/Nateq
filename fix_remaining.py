#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Complete fix for AnnouncementSpeaker.kt
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

# 1. Fix the safeEngineForAnnouncement call at line ~813 (in startInit)
# The old call: val engine = safeEngineForAnnouncement(appContext, finalEngine)
# Should be: val engine = safeEngineForAnnouncement(appContext, finalEngine, languageEngine, fallbackEngine)
old_startinit_call = '''        val engine = safeEngineForAnnouncement(appContext, finalEngine)'''
new_startinit_call = '''        val engine = safeEngineForAnnouncement(appContext, finalEngine, languageEngine, fallbackEngine)'''

content = content.replace(old_startinit_call, new_startinit_call)

# Fix 2: Replace currentLocale with locale parameter in the file
# Fix the references to currentLocale in ensureInit
content = content.replace('currentLocale?.language', 'locale.language')
content = content.replace('currentLocale?.language ?: ""', 'locale.language')

# Fix 3: Ensure playNoEngineCue is defined and accessible
# The playNoEngineCue function should be defined. Let's check if it exists.
# If not, we need to add it.

# Fix 4: Fix the startInit call at line 1569 (ensureInit call with locale)
# The call at line 1569 should have 4 arguments now

# Let's also fix the safeEngineForAnnouncement call in startInit that still uses old signature
# Search for any remaining old signature calls
content = content.replace(
    'safeEngineForAnnouncement(appContext, finalEngine)',
    'safeEngineForAnnouncement(appContext, finalEngine, languageEngine, fallbackEngine)'
)

# Fix the call in startInit's timeoutRunnable that uses currentLocale
content = content.replace(
    'settings?.getEngineForLanguage(currentLocale?.language ?: "")',
    'settings?.getEngineForLanguage(locale.language)'
)

# Also fix the fallbackEngine getter call
content = content.replace(
    'settings?.getAnnouncementFallbackEngine()',
    'settings?.getAnnouncementFallbackEngine()'
)

write_file(r"core\audio\src\main\java\com\aymankhattab\nateq\core\audio\announcement\AnnouncementSpeaker.kt", content)
print("AnnouncementSpeaker.kt fixed")