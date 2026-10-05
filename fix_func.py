#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Fix the safeEngineForAnnouncement function at the end of the file
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

# Find and replace the old safeEngineForAnnouncement function at the end
# The old function starts with "internal fun safeEngineForAnnouncement(" and ends with "}"
# We need to replace from "internal fun safeEngineForAnnouncement(" to the end of the file

# Find the last occurrence of "internal fun safeEngineForAnnouncement("
old_func_start = content.rfind("internal fun safeEngineForAnnouncement(")
if old_func_start == -1:
    print("Function not found!")
else:
    # Replace from that point to the end of the file
    new_func = '''internal fun safeEngineForAnnouncement(
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
}
'''

    # Find the start of the function
    start = content.rfind("internal fun safeEngineForAnnouncement(")
    if start != -1:
        # Find the end of the function (matching braces)
        brace_count = 0
        end = start
        for i in range(start, len(content)):
            if content[i] == '{':
                brace_count += 1
            elif content[i] == '}':
                brace_count -= 1
                if brace_count == 0:
                    end = i + 1
                    break
        
        # Replace
        content = content[:start] + new_func + content[end:]
        print("Replaced safeEngineForAnnouncement function")

write_file(r"core\audio\src\main\java\com\aymankhattab\nateq\core\audio\announcement\AnnouncementSpeaker.kt", content)
print("Fixed safeEngineForAnnouncement function")