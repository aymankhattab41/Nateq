#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Clean up the end of the file properly
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

# Find the last occurrence of "return null\n}" which marks the end of the valid function
matches = list(re.finditer(r'return null\n}', content))
if matches:
    last_match = matches[-1]
    end_pos = last_match.end()  # Position after the '}'
    content = content[:end_pos]
    print(f"Truncated at position {end_pos}")

    with open(speaker_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print("Cleaned up end of file properly")
else:
    print("Pattern not found")