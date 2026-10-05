#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Clean up the end of the file - remove duplicate function
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

# Find the end of the valid safeEngineForAnnouncement function
# The function should end at "}" after "return null"
# Find the last occurrence of "return null" followed by "}" and then the class ends

# Find the last occurrence of "return null" in the safeEngineForAnnouncement function
# The function ends with:
#     return null
# }
# Then the class should end

# Find the position of the last "}" that closes the class
# The file should end right after the class closing brace

# Find all positions of "}" at the start of a line (class-level)
lines = content.split('\n')
last_brace_line = -1
for i in range(len(lines) - 1, -1, -1):
    if lines[i].strip() == '}':
        last_brace_line = i
        break

if last_brace_line != -1:
    # Truncate the file at the last brace
    content = '\n'.join(lines[:last_brace_line + 1])
    print(f"Truncated file at line {last_brace_line + 1}")

write_file(r"core\audio\src\main\java\com\aymankhattab\nateq\core\audio\announcement\AnnouncementSpeaker.kt", content)
print("Cleaned up end of file")