# Call-blocking opt-out: state, migration and target-switch acceptance

## Reproduced defect

The official 2.0.6 CallsHub screen derives enablement with `explicitFlag || retainedMode != "0"`, then writes that result in an entry-time `LaunchedEffect`. Consequently an explicit false flag is overwritten merely by revisiting the screen. A first visit with no call settings writes mode `1`; a subsequent visit can enable Block All Calls without a user opt-in. Modes `3` and `4` were also rewritten to `1`, and the remembered mode was not keyed to target changes.

This was reproduced in the unmodified, officially signed 2.0.6 Release APK on a rooted Android 14/API34 x86_64 AVD. A read-only observation of the actual Vector-redirected public preference store found `call_privacy=1` and `call_privacy_enabled=false` before navigation; after Back and reopening Calls & Recording, the flag was true and the UI showed Block All Calls. No incoming call was placed during this reproduction. This proves unintended enablement, not the origin of every reported call failure.

## Candidate behavior

- Treat an explicit false flag as authoritative.
- Use a recognized nonzero mode only as the fallback for installations without an explicit flag.
- Keep opening/reopening read-only for call settings; remove the implicit migration effect.
- Preserve recognized modes 0–4, including existing blocklist/allowlist values.
- Refresh both local states when the target epoch changes.
- Initialize mode 1 only on explicit enable from mode 0, not on screen entry or when restoring modes 3/4.
- Label retained custom modes as Custom Privacy rather than Block All Calls, using existing localized resources.

The call hook, incoming callback, notification policy, per-contact rules, preference key names, private licensing and recording implementation are unchanged. This does not add the contact-list editor currently labelled Coming Soon.

## Executable source-fragment test

Run with JDK 17+ and a complete Kotlin 2.0.20 distribution available:

```sh
python3 qa/test_call_privacy_optout.py --out /tmp/waex-call-optout
python3 qa/mutate_call_privacy_optout.py --out /tmp/waex-call-optout-mutants
```

On Windows, supply executable/distribution paths rather than modifying global PATH:

```powershell
python qa/test_call_privacy_optout.py --out C:\path\to\fresh-evidence `
  --java C:\WAEX-Lab\tools\jdk17\bin\java.exe `
  --kotlin-home C:\WAEX-Lab\tools\kotlin-2.0.20\kotlinc
```

`--source-root` runs the same test against another complete source checkout. Each output directory must be new. The harness extracts the actual state/effect block and the two actual toggle callbacks; it does not reimplement their decision logic. Kotlin compiles and executes those fragments with explicit in-memory preference and composition-state boundaries.

The 31 cases exercise mode 0–4 with absent/false/true flags, repeated visits, both disable controls, explicit first opt-in, re-enabling retained custom modes, and switching from one target configuration to another. Results record the source SHA-256, compilation outcome, executed cases, reached checks and failures. Failed assertions can stop individual cases earlier, so baseline and candidate reached-check totals differ.

Observed comparison with identical 31-case fixture:

| Production source | Cases | Reached checks | Failed cases |
|---|---:|---:|---:|
| Official 2.0.6 (same CallsHub source as investigated main) | 31 | 61 | 31 |
| Candidate | 31 | 122 | 0 |

Four compiling mutations are rejected for their intended behavior: ignore explicit false (17 failed cases); drop the mode's target epoch (1); write mode 1 on entry (27); replace custom mode on explicit enable (4). A compiler or setup failure never qualifies as a detected behavioral regression.

## Manual acceptance of an authorized candidate APK

1. Record Android/WAEX/WhatsApp versions, APK certificate/hash, root/framework versions and selected target. Preserve existing preferences.
2. With mode 1 and the explicit switch false, open Calls & Recording, navigate back and reopen; confirm the stored flag and UI remain false.
3. Repeat with mode 2, and with legacy modes 3/4. Confirm no mode is silently rewritten.
4. In a fresh isolated configuration, visit twice without toggling; confirm calls remain allowed and no new call settings are persisted just by visiting.
5. Explicitly enable from mode 0; confirm valid mode 1 is initialized. Disable and reopen; confirm it stays off.
6. Switch between two target configurations with different modes and flags; confirm the displayed state follows the selected target without writes to the other target.
7. Verify an actual incoming voice/video call using only authorized test accounts. Keep separate per-contact rules in the expected outcome; this change does not disable intentional independent contact blocks.
8. Check localized labels and accessibility in a legitimate signed build; run full CI without suppressing unrelated failures.

## Boundaries

The automated suite compiles exact production fragments, not the entire CallsHub screen. Its composition scheduler and preference manager are explicit doubles, not Android/Compose runtime. It does not simulate provider commit failures, cross-profile IPC, native VoIP, remote audio, or private Premium behavior. The official baseline was observed on an actual AVD; the patched full APK still requires the maintainer's authorized build path and device acceptance. No throughput, startup or frame-time improvement is claimed.
