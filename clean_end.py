#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Clean up the end of the file
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

# Remove the duplicate function at the end
# Find the line with "}," after the safeEngineForAnnouncement function
# The file should end after the safeEngineForAnnouncement function

# Find the last occurrence of "}" that should be the end of the class
# The file should end with the class closing brace

# Find the last occurrence of "}" that is the class end
# The file should end with "}" after the safeEngineForAnnouncement function

# Find the last occurrence of "}" that is at the top level (no indentation)
# Let's find the last "}" that's at column 0 (start of line)
lines = content.split('\n')
last_brace_line = -1
for i in range(len(lines) - 1, -1, -1):
    if lines[i].strip() == '}':
        last_brace_line = i
        break

if last_brace_line != -1:
    # Check if there's content after this brace that shouldn't be there
    # The file should end at this brace
    content = '\n'.join(lines[:last_brace_line + 1])
    print(f"Truncated file at line {last_brace_line + 1}")

write_file(r"core\audio\src\main\java\com\aymankhattab\nateq\core\audio\announcement\AnnouncementSpeaker.kt", content)
print("Cleaned up end of file")