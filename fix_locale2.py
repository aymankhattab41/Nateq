#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Fix the missing locale parameter in ensureInit call
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

# Fix the ensureInit call
old_call = '''        ensureInit({ ready ->
            if (ready && gen == speechGeneration.get()) {
                sendSpeakUnits(target.units, attempt = 1)
            }
        }, target.engineOverride)'''

new_call = '''        ensureInit({ ready ->
            if (ready && gen == speechGeneration.get()) {
                sendSpeakUnits(target.units, attempt = 1)
            }
        }, target.engineOverride, target.locale)'''

content = content.replace(old_call, new_call)

with open(speaker_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Fixed missing locale parameter")