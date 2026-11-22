#!/usr/bin/env python3
"""Host-only regression: production provider call-site checks and real Java policy."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
bridge = root / "app/src/main/java/com/waenhancer/xposed/bridge"
provider = (bridge / "HookProvider.java").read_text(encoding="utf-8")
get_all = provider[provider.index('if ("get_all_preferences".equals(method))'):
                   provider.index('if ("get_preference".equals(method)')]
get_one = provider[provider.index('if ("get_preference".equals(method)'):
                   provider.index('if ("put_preference".equals(method)')]
write = provider[provider.index("private Bundle applyPreferenceChanges("):
                 provider.index("private String targetPackageForCaller(")]
assert "ProviderPreferenceScopePolicy.filterTargetNamespaces(" in get_all, \
    "Bulk IPC response must strip target-prefixed values before encoding"
assert get_all.index("ProviderPreferenceScopePolicy.filterTargetNamespaces") < get_all.index("return encodePreferences(all)")
assert "ProviderPreferenceScopePolicy.mayReadKey(" in get_one, \
    "Single-key reads must reject namespaced keys from target apps"
assert "!PreferenceSchema.isGlobalOnly(key)" in get_one, \
    "Global-only values must ignore stale target overrides"
assert "ProviderPreferenceScopePolicy.mayWriteKey(" in write, \
    "Target writes must reject global-only and namespaced keys before committing"
assert write.index("ProviderPreferenceScopePolicy.mayWriteKey") < write.index(
    "SharedPreferences.Editor editor = prefs.edit()")
assert "if (scoped && !PreferenceSchema.isGlobalOnly(key))" in write, \
    "Global-only keys must not be stored with a target prefix"

with tempfile.TemporaryDirectory(prefix="waex_provider_scope_") as target:
    subprocess.run(["javac", "--release", "17", "-d", target,
                    str(root / "app/src/main/java/com/waenhancer/config/PreferenceSchema.java"),
                    str(bridge / "ProviderPreferenceScopePolicy.java"),
                    str(root / "qa/java/TestProviderPreferenceScopePolicy.java")], check=True)
    subprocess.run(["java", "-cp", target,
                    "com.waenhancer.xposed.bridge.TestProviderPreferenceScopePolicy"], check=True)
print("PASS: provider source wiring and pure-Java target isolation")

# Negative controls: each deliberately broken production policy must fail this harness.
policy_source = (bridge / "ProviderPreferenceScopePolicy.java").read_text(encoding="utf-8")
mutants = (
    ("target-namespace-leak", "supportedPackages.contains(key.substring(0, delimiter))", "false"),
    ("direct-namespace-read", "key.indexOf(':') < 0", "true"),
    ("global-write-bypass", "!PreferenceSchema.isGlobalOnly(key)", "true"),
)
for name, original, replacement in mutants:
    assert policy_source.count(original) == 1, f"Mutant anchor changed: {name}"
    with tempfile.TemporaryDirectory(prefix="waex_provider_mutant_") as target:
        mutated_file = Path(target) / "ProviderPreferenceScopePolicy.java"
        mutated_file.write_text(policy_source.replace(original, replacement), encoding="utf-8")
        subprocess.run(["javac", "--release", "17", "-d", target,
                        str(root / "app/src/main/java/com/waenhancer/config/PreferenceSchema.java"),
                        str(mutated_file), str(root / "qa/java/TestProviderPreferenceScopePolicy.java")],
                       check=True, capture_output=True, text=True)
        negative = subprocess.run(["java", "-cp", target,
                                   "com.waenhancer.xposed.bridge.TestProviderPreferenceScopePolicy"],
                                  capture_output=True, text=True)
        assert negative.returncode != 0 and "AssertionError" in negative.stderr, \
            f"Regression control did not detect {name}: {negative.stdout} {negative.stderr}"
print("PASS: 3 controlled policy mutations rejected")
