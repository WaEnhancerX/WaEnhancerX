#!/usr/bin/env python3
"""Regression for explicit Hide Updates selection versus IGStatus preferences.

The reported combination is hidetabs=[300], igstatus=true. The production
Java policy is compiled and run; source guards cover the hook's integration.
This does not simulate WhatsApp method resolution or certify Android 16.
"""

from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/waenhancer/xposed/features/customization"
HOOK = SOURCE / "HideHomeTabsHook.java"
POLICY = SOURCE / "TypedStyleHookPolicy.java"
FIXTURE = ROOT / "qa/TestHideHomeTabsPolicy.java"


def check_hook_contract(source):
    assert 'prefs.getStringSet("hidetabs", null)' in source, "wrong preference source"
    assert re.search(r'Set<Integer>\s+hidden\s*=\s*TypedStyleHookPolicy\.parseHiddenTabIds\(stored\)',
                     source), "selected tabs are not passed to the production policy"
    assert 'TypedStyleHookPolicy.filterTabIds(tabs, hidden)' in source, "model filter disconnected"
    assert 'hidden.contains(item.getItemId())' in source, "menu visibility disconnected"
    assert 'prefs.getBoolean("igstatus"' not in source, "IGStatus must not cancel a hide selection"
    assert not re.search(r'\bhidden\s*\.\s*(?:remove|clear|removeAll|retainAll)\s*\(', source), \
        "selected tabs were removed after parsing"


def run_policy(policy_code, expected_success):
    with tempfile.TemporaryDirectory(prefix="waex_hide_tabs_") as directory:
        temporary = Path(directory)
        source = temporary / "TypedStyleHookPolicy.java"
        source.write_text(policy_code, encoding="utf-8")
        compile_result = subprocess.run(
            ["javac", "--release", "17", "-d", str(temporary), str(source), str(FIXTURE)],
            capture_output=True, text=True, timeout=60, check=False,
        )
        assert compile_result.returncode == 0, compile_result.stderr
        result = subprocess.run(
            ["java", "-cp", str(temporary),
             "com.waenhancer.xposed.features.customization.TestHideHomeTabsPolicy"],
            capture_output=True, text=True, timeout=30, check=False,
        )
        assert (result.returncode == 0) is expected_success, result.stdout + result.stderr
        if expected_success:
            assert "PASS: 10 production tab-ID parsing/filtering checks" in result.stdout


def main():
    hook = HOOK.read_text(encoding="utf-8")
    policy = POLICY.read_text(encoding="utf-8")
    check_hook_contract(hook)
    run_policy(policy, True)

    removed_updates = hook.replace("if (hidden.isEmpty()) return;",
        'if (prefs.getBoolean("igstatus", false)) hidden.remove(300);\n'
        "        if (hidden.isEmpty()) return;", 1)
    assert removed_updates != hook
    try:
        check_hook_contract(removed_updates)
    except AssertionError:
        pass
    else:
        raise AssertionError("Original IGStatus suppression mutant was accepted")

    lost_filter = policy.replace(
        "tabs.removeIf(value -> value instanceof Integer && hidden.contains(value));",
        "tabs.removeIf(value -> false);", 1)
    assert lost_filter != policy
    run_policy(lost_filter, False)

    lost_update = policy.replace(
        "return hidden;\n    }\n\n    public static void filterTabIds",
        "hidden.remove(300);\n        return hidden;\n    }\n\n    public static void filterTabIds", 1)
    assert lost_update != policy
    run_policy(lost_update, False)

    print("PASS: 10 executable production policy checks; 3 regression mutants rejected; "
          "feature wiring and explicit selection verified (not an in-WhatsApp test)")


if __name__ == "__main__":
    main()
