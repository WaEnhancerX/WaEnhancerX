#!/usr/bin/env python3
"""JDK policy and source integration checks; not Android Binder instrumentation."""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
bridge = root / "app/src/main/java/com/waenhancer/xposed/bridge"
provider = (bridge / "HookProvider.java").read_text(encoding="utf-8")
assert 'ProviderDerivedKeyPolicy.allowsGenericWrite(key)' in provider
assert provider.index('ProviderDerivedKeyPolicy.allowsGenericWrite(key)') < provider.index(
    'SharedPreferences.Editor editor = prefs.edit()', provider.index('private Bundle applyPreferenceChanges('))
# Guard the actual expression and execution order, not its whitespace layout.
# The check must fail closed before any SharedPreferences editor is acquired.
policy_start = provider.index('private Bundle applyPreferenceChanges(')
editor_start = provider.index('SharedPreferences.Editor editor = prefs.edit()', policy_start)
validation = provider[policy_start:editor_start]
clear_guard = re.search(
    r'if\s*\(\s*clear\s*&&\s*!\s*ProviderDerivedKeyPolicy\.mayClearPreferences'
    r'\s*\(\s*callerUid\s*,\s*context\.getApplicationInfo\(\)\.uid'
    r'\s*\)\s*\)\s*return\s+writeResult\(false\)\s*;',
    validation,
)
assert clear_guard, 'Privileged clear check missing, malformed or executed after editor creation'
assert 'return applyPreferenceChanges(context, prefs, callerUid, false, operations)' in provider
assert 'return applyPreferenceChanges(context, prefs, callerUid,' in provider
assert 'callerOwnsPackage(context, callerUid, pkg)' in provider
assert provider.index('isTrustedCaller(context, callerUid)') < provider.index('Binder.clearCallingIdentity()')
assert '"report_runtime_health".equals(method)' in provider
for signal in ('runtime_boot_', 'runtime_pid_', 'runtime_status_', 'runtime_error_', 'bridge_status_'):
    assert signal in provider, f"Current upstream telemetry removed: {signal}"
assert 'PreferenceWriteTypePolicy.accepts(entry.type.name(), type)' in provider
with tempfile.TemporaryDirectory(prefix="waex_provider_policy_") as temp:
    subprocess.run(["javac", "--release", "17", "-d", temp,
                    str(bridge / "ProviderDerivedKeyPolicy.java"),
                    str(root / "qa/java/TestProviderDerivedKeyPolicy.java")], check=True)
    subprocess.run(["java", "-cp", temp,
                    "com.waenhancer.xposed.bridge.TestProviderDerivedKeyPolicy"], check=True)
print("PASS: derived IPC key restrictions, privileged clear, and runtime telemetry preserved")
