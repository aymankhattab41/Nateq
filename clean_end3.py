#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Clean up the end of the file - remove duplicate function properly
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
# Find the position of the last "return null" in the function

# Find the last occurrence of the pattern "return null\n}"
# This marks the end of the valid function
pattern = r'return null\n}'
match = list(re.finditer(r'return null\n}', content))
if match:
    last_match = match[-1]
    end_pos = last_match.end()  # Position after the '}'
    # The content should end here (after the closing brace of the function)
    content = content[:end_pos]
    print(f"Truncated at position {end_pos}")

write_file(r"core\audio\src\main\java\com\aymankhattab\nateq\core\audio\announcement\AnnouncementSpeaker.kt", content)
print("Cleaned up end of file properly")