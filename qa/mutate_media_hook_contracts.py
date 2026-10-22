#!/usr/bin/env python3
"""Require behavioral failures for focused media/view-once production mutants.

Uses the unchanged full-hook harness in isolated temporary source trees. Compilation
errors do not count as killed mutants. No proprietary host or Android runtime runs.
"""
from pathlib import Path
import argparse
import json
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MEDIA = "app/src/main/java/com/waenhancer/xposed/features/media/MediaQualityBypassHook.java"
VIEW = "app/src/main/java/com/waenhancer/xposed/features/privacy/AntiViewOnceHook.java"
CONTRACT = "app/src/main/java/com/waenhancer/xposed/features/privacy/ViewOnceMessageContract.java"
GUARD = "app/src/main/java/com/waenhancer/xposed/core/devkit/HookReturnTypeGuard.java"

MUTANTS = [
    ("outgoing_instead_of_incoming", CONTRACT, "&& !own.getBoolean(key)", "&& own.getBoolean(key)", "viewonce"),
    ("ignore_message_ownership", VIEW, "if (messages.isIncoming(param.thisObject))", "if (true)", "viewonce"),
    ("allow_ambiguous_state_interface", CONTRACT,
     "if (setter != null && !setter.equals(method)) return null;", "/* accept an ambiguous setter */", "viewonce"),
    ("repeat_viewonce_registration", VIEW, "if (installed.contains(target)) continue;", "/* register twice */", "viewonce"),
    ("allow_ambiguous_direct_setters", VIEW,
     "if (candidates.size() == 1) targets.add(candidates.iterator().next());",
     "targets.addAll(candidates);", "viewonce"),
    ("overwrite_unknown_image_integer", MEDIA,
     "if (original >= 0 && original <= 100) param.args[0] = 100;",
     "param.args[0] = 100;", "media"),
    ("restore_unconditional_fps", MEDIA,
     "if (!isVideo60FpsEnabled() || param.hasThrowable() || !(param.getResult() instanceof Integer)) return;",
     "if (isVideo60FpsEnabled()) { param.setResult(60); return; }\n"
     "                    if (!(param.getResult() instanceof Integer)) return;", "media"),
    ("accept_last_ambiguous_media_method", MEDIA,
     "if (result != null && !result.equals(candidate)) return null;",
     "/* overwrite a previous candidate */", "media"),
    ("relax_hd_literal", MEDIA,
     "uniqueMethod(key, anchor, StringMatchType.Equals,",
     "uniqueMethod(key, anchor, StringMatchType.Contains,", "media"),
    ("ignore_live_hd_preferences", MEDIA,
     "if (isImageQualityMaxEnabled() || isVideo60FpsEnabled()) param.setResult(true);",
     "param.setResult(true);", "media"),
    ("media_zero_callbacks_still_active", MEDIA,
     'throw new IllegalStateException("Media quality contract unavailable");',
     '/* omit the unavailable-contract signal */', "status"),
    ("viewonce_zero_callbacks_still_active", VIEW,
     'throw new IllegalStateException("View-once contract unavailable");',
     '/* omit the unavailable-contract signal */', "status"),
    ("media_reports_failure_while_off", MEDIA,
     'if (installed.isEmpty() && (isImageQualityMaxEnabled() || isVideo60FpsEnabled())) {',
     'if (installed.isEmpty()) {', "status"),
    ("viewonce_reports_failure_while_off", VIEW,
     'if (installed.isEmpty() && (isEnabled("anti_view_once", false) || isEnabled("viewonce", false))) {',
     'if (installed.isEmpty()) {', "status"),
    ("media_rejects_working_partial_hooks", MEDIA,
     'if (installed.isEmpty() && (isImageQualityMaxEnabled() || isVideo60FpsEnabled())) {',
     'if (installed.size() < 2 && (isImageQualityMaxEnabled() || isVideo60FpsEnabled())) {', "status"),
    ("viewonce_rejects_working_partial_hooks", VIEW,
     'if (installed.isEmpty() && (isEnabled("anti_view_once", false) || isEnabled("viewonce", false))) {',
     'if (installed.size() < 2 && (isEnabled("anti_view_once", false) || isEnabled("viewonce", false))) {', "status"),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()
    if args.evidence:
        args.evidence.mkdir(parents=True, exist_ok=True)
    original = {path: (ROOT / path).read_text() for path in [MEDIA, VIEW, CONTRACT, GUARD]}
    results = []
    for name, path, old, new, suite in MUTANTS:
        if original[path].count(old) != 1:
            raise AssertionError(f"{name}: mutation anchor must occur exactly once")
        with tempfile.TemporaryDirectory(prefix="waex_media_mutant_") as directory:
            dest = Path(directory)
            for relative, content in original.items():
                target = dest / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content.replace(old, new, 1) if relative == path else content)
            run = subprocess.run([sys.executable, str(ROOT / "qa/test_media_hook_contracts.py"),
                                  "--source-root", str(dest), "--suite", suite],
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=90)
            result = re.search(r"RESULT checks=(\d+) failures=(\d+) suite=(\w+)", run.stdout)
            killed = (run.returncode != 0 and result is not None and int(result[2]) > 0
                      and "java.lang.AssertionError" in run.stdout and "FAIL " in run.stdout)
            if args.evidence:
                (args.evidence / f"{name}.log").write_text(run.stdout)
            failures = [line for line in run.stdout.splitlines() if line.startswith("FAIL ")]
            results.append({"name": name, "exit": run.returncode, "behaviorally_killed": killed,
                            "failed_assertions": failures})
            print(f"{'KILLED' if killed else 'INVALID/SURVIVED'} {name}: {len(failures)} assertions")
            if not killed:
                print(run.stdout)
    if args.evidence:
        (args.evidence / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    if not all(item["behaviorally_killed"] for item in results):
        raise AssertionError("A media/view-once mutant survived or did not compile")
    print(f"PASS: {len(results)} production mutants killed by unchanged behavioral assertions")


if __name__ == "__main__":
    main()
