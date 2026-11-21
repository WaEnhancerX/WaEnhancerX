# Status and property override acceptance

The public `status_style` setting remains a string with four choices verified on
current WhatsApp builds: `1` (Default), `0` (Vertical Rows), `3` (Circular
Avatars), and `9` (Square Cards). Previously published values `4` and `5` now
fail closed to Default when restored from an older backup. The default remains `1`.
Whitespace around a valid value remains accepted. Missing, malformed, unsupported
and incorrectly typed values use the default without failing hook initialization.

Status customization is applied only to an existing integer result. A failed host
call keeps the exact original exception. An absent or incompatible cached result
is retained. The central property override callback also preserves host exceptions.
Successful typed property values keep their existing override behavior and priority.
The existing `novaconfig` boolean continues to enable New Settings independently;
its properties coexist with the selected status style.

## Required compiled regression

```sh
python3 qa/test_status_style_hook_contract.py --out /tmp/status-style-hook-contract
```

The runner requires `javac`/`java` and the Kotlin standard library (located through
`kotlinc`, or supplied with `--kotlin-stdlib`). It downloads the public DexKit 2.2.0
AAR, verifies both its SHA-256 and the extracted API JAR SHA-256, and never extracts
or loads native libraries. An already available API JAR may be passed through
`--dexkit-jar`; its identity is still required to match.

All three production classes are compiled without extraction or rewriting:
`StatusStyleHook`, `PropsOverrideHook` and `NewSettingsStyleHook`. The runner executes
83 JVM assertions with explicit Android, SharedPreferences, DexSearchEngine cache,
and Xposed transport fixtures. DexKit query and result classes are the real API.
The callback fixture implements the documented Xposed result/exception transition:
`setResult` clears the previous exception, so a missing exception guard fails the
test instead of being hidden by the fixture.

The suite covers the four choices, trimmed values, obsolete values, invalid integers and strings,
wrong preference types, exception identity, absent/incompatible results, matching
and nonmatching property types, unregistered IDs, `novaconfig` off/on, simultaneous
status/settings overrides, callback priority, and properties registered after
the central callback is installed. `status_style_values` in the public resource
table must still match the independently specified choice matrix.

Source/dependency/fixture hashes, exact source snapshots, compile logs and behavior
results are written to the output directory. The source QA workflow requires the
runner and uploads its evidence even if a later suite fails. It does not add a
separate Android framework job or replace any existing source suites.

## Device acceptance still required

These are complete Java hook bodies tested on the JVM, not WhatsApp or LSPosed
execution. DEX declaration/instruction observations on an exact host APK can confirm
the current candidate shapes, but do not establish successful native DexKit matching,
rendering, server behavior, or compatibility with another WhatsApp version.

On a device, check each published style after restart, New Settings off and on with
the style retained, and restoration of a backup containing a malformed `status_style`.
Verify native status layouts and any per-account server configuration separately.
This change does not alter resolver selection or introduce another preference key.
