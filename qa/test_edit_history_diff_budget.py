#!/usr/bin/env python3
"""Execute the production diff under a bounded heap; test exact reconstruction.

--source can compile the previous hook's verbatim pure diff methods as a
negative control. This is a JVM allocation/work bound, not Android FPS.
"""
import argparse
import subprocess
import shutil
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, default=root / (
    'app/src/main/java/com/waenhancer/xposed/features/conversation/EditHistoryDiff.kt'))
parser.add_argument('--scenario', choices=['all', 'large'], default='all')
args = parser.parse_args()
source = args.source.read_text()
if 'private fun computeDiff(' in source:
    enums = source[source.index('    private enum class DiffType'):source.index('    private fun buildDiffSpannable')]
    functions = source[source.index('    private fun computeDiff('):source.index('    private fun createWdsSwitch(')]
    enums = enums.replace('private enum class DiffType', 'enum class Type').replace('private data class DiffChunk', 'data class Chunk')
    functions = functions.replace('private fun computeDiff(', 'fun compute(').replace('DiffType', 'Type').replace('DiffChunk', 'Chunk')
    source = 'package com.waenhancer.xposed.features.conversation\ninternal object EditHistoryDiff {\n' + enums.replace('DiffType', 'Type') + functions + '\n}\n'
fixture = root / 'qa/fixtures/EditHistoryDiffChecks.kt'
with tempfile.TemporaryDirectory() as folder:
    temp = Path(folder)
    implementation = temp / 'EditHistoryDiff.kt'
    implementation.write_text(source)
    subprocess.run([shutil.which('kotlinc.bat') or shutil.which('kotlinc') or 'kotlinc', str(implementation), str(fixture), '-jvm-target', '17', '-include-runtime', '-d', str(temp / 'checks.jar')], check=True)
    subprocess.run(['java', '-Xmx64m', '-jar', str(temp / 'checks.jar'), args.scenario], check=True, timeout=35)

hook = root / 'app/src/main/java/com/waenhancer/xposed/features/conversation/AntiEditMessagesHook.kt'
assert 'val chunks = EditHistoryDiff.compute(oldStr, newStr)' in hook.read_text(), 'live UI must use the bounded diff'
