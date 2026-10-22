#!/usr/bin/env python3
"""Execute the production bounded runtime-report queue with a JDK; no Android benchmark."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='waex-runtime-queue-') as directory:
    subprocess.run(['javac', '--release', '17', '-d', directory,
        str(root/'app/src/main/java/com/waenhancer/xposed/core/RuntimeReportDispatcher.java'),
        str(root/'qa/TestRuntimeReportDispatcher.java')], check=True, timeout=30)
    subprocess.run(['java', '-cp', directory,
        'com.waenhancer.xposed.core.TestRuntimeReportDispatcher'], check=True, timeout=20)
