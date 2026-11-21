#!/usr/bin/env python3
"""Reject executable production-manager regressions with the repaired media QA gate.

The Java production manager is compiled from each mutated source, and every mutant
must compile successfully and fail a specific behavioral assertion. A broken
fixture or compilation error is never accepted as a detected product regression.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MANAGER = ROOT / "app/src/main/java/com/waenhancer/core/preferences/WaexPreferenceManager.java"
TEST = ROOT / "qa/test_media_preference_sync.py"


def replace_exactly_once(source: str, before: str, after: str) -> str:
    count = source.count(before)
    if count != 1:
        raise AssertionError(f"Mutation target occurs {count} times, expected one: {before[:70]}")
    return source.replace(before, after, 1)


def main() -> None:
    source = MANAGER.read_text(encoding="utf-8")
    restart_loop = (
        "for (Map.Entry<String, Boolean> change : batch.entrySet()) {\n"
        "                PreferenceRestartTracker.onPreferenceChanged(\n"
        "                        PreferenceStores.publicStore(context), resolvedKey(change.getKey()));\n"
        "            }"
    )
    schema_guard = (
        "if (definition != null && (definition.type != PreferenceSchema.Type.BOOLEAN\n"
        "                        || definition.store != PreferenceSchema.Store.PUBLIC)) return false;"
    )
    candidates = [
        ("missing_restart_reporting",
         replace_exactly_once(source, restart_loop, "/* mutant: no restart reports */"),
         "HD batch did not report all three exact keys in order"),
        ("ignored_commit_failure",
         replace_exactly_once(source, "if (!editor.commit()) return false;", "editor.commit();"),
         "failed commit reported success"),
        ("accepted_private_or_wrong_type",
         replace_exactly_once(source, schema_guard, "if (false) return false;"),
         "non-Boolean schema entry was accepted"),
        ("false_legacy_commit_still_notifies",
         replace_exactly_once(source,
              "if (!getStore(key).edit().putBoolean(key(key), value).commit()) return;",
              "getStore(key).edit().putBoolean(key(key), value).commit();"),
         "failed legacy commits must not notify the hook provider"),
        ("removed_setting_never_reports_restart",
         replace_exactly_once(source,
              "if (isPublicStore(key)) {\n"
              "            PreferenceRestartTracker.onPreferenceChanged(PreferenceStores.publicStore(context), key(key));\n"
              "            notifyChange();\n"
              "        }\n"
              "    }\n\n    public Map<String, ?> getAll()",
              "if (isPublicStore(key)) {\n"
              "            notifyChange();\n"
              "        }\n"
              "    }\n\n    public Map<String, ?> getAll()"),
         "successful legacy writes and removal did not report exact restart keys"),
    ]
    with tempfile.TemporaryDirectory(prefix="waex-media-negative-") as folder:
        for name, mutation, expected in candidates:
            path = Path(folder) / name / "WaexPreferenceManager.java"
            path.parent.mkdir(parents=True)
            path.write_text(mutation, encoding="utf-8")
            command = [sys.executable, str(TEST), "--manager-source", str(path)]
            result = subprocess.run(command, cwd=ROOT, env=os.environ.copy(),
                                    text=True, capture_output=True, timeout=45)
            output = result.stdout + result.stderr
            if (result.returncode == 0 or "error:" in output or
                    "java.lang.AssertionError: " + expected not in output):
                raise AssertionError(
                    f"Control {name} not detected by its intended runtime assertion: "
                    f"exit={result.returncode}\n{output[-2500:]}")
            if "PASS: 6083 Media Hub mapping/compatibility checks" not in output:
                raise AssertionError(f"Control {name} did not finish policy fixture")
            print(f"PASS negative_control {name}: behavioral failure {expected}")
    print(f"TOTAL NEGATIVE CONTROLS {len(candidates)}/{len(candidates)}")


if __name__ == "__main__":
    main()
