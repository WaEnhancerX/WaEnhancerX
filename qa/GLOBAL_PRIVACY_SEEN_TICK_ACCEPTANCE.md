# Global Privacy manual seen-tick persistence

## Problem and scope

At upstream `main` revision `0aaffab0270a3d0832bef2895f6457f3792fd74a`,
`GlobalPrivacySettingsScreen` confirms the `seentick` switch and placement before
calling the void `WaexPreferenceManager.putString()` setter. The placement selector
and the dependent reset when Hide Read Receipts is disabled have the same issue.
That setter does not report a failed `SharedPreferences.Editor.commit()` and does
not catch an editor or commit exception. The switch also writes `"1"` for every
accepted enable action, even if `"2"` was already selected.

The three paths now share a small screen-local helper. It calls the existing
`putPublicString()` method, confirms both display values only on success, and uses
the existing localized `waex_ui_unable_to_save_setting` feedback on failure. The
existing `ManualSeenTickPreferencePolicy` preserves the current overflow placement
when an enable action is accepted. An explicit disable still writes `"0"` according
to that policy. No preference type, key, default, migration, manager API, hook,
permission, resource, dialog, or navigation flow is added or replaced.

Related prior changes are [#35](https://github.com/WaEnhancerX/WaEnhancerX/pull/35)
(manual seen-tick policy and Styles wiring),
[#39](https://github.com/WaEnhancerX/WaEnhancerX/pull/39) (checked public string
writers and typed Styles editors), and
[#36](https://github.com/WaEnhancerX/WaEnhancerX/pull/36) (the unrelated archive
setting type on this screen). The source and changed-file lists checked on
2026-11-17 leave these three Global Privacy writes unchanged. A read-only review of
all 29 then-open upstream PRs and the one then-open fork PR found no proposal
changing this screen, its manual seen-tick policy, or its preference manager. This
is a bounded duplicate check at that revision and time, not a claim about future
branches or every unreferenced commit.

## Executable regression

Use a full JDK supporting `javac --release 17`, Python 3, and a Kotlin CLI, or pass
`--java`, `--javac`, and `--kotlin-home` for an existing isolated compiler. Every
output directory must be new; failed evidence is never overwritten.

```sh
python3 qa/test_global_privacy_seen_tick_persistence.py \
  --out /tmp/waex-global-privacy-positive
```

The runner extracts and compiles the actual complete `updatePreference` lambda,
the actual placement click body, and the new helper when present. It also compiles
the complete production preference manager, schema, stores, exclusive preference
wrapper, and existing manual seen-tick and media policies. Android preferences,
resource lookup, Toast, notifications, navigation, and unrelated dependencies are
explicit JVM doubles. Unexpected fixture accesses fail visibly. The error resource
must exist and be nonempty in the production default XML resources.

Expected values are independently specified by the test matrix rather than read
from the production policy. Memory and durable storage are separate in the
preference double, including a failed disk write that has already changed memory.
Every later successful commit writes the entire current memory map, not only its
own pending keys. This follows `commitToMemory()` and `writeMapXml()` in
[AOSP Android 14 SharedPreferencesImpl](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/app/SharedPreferencesImpl.java).
The screen must retain its last confirmed display without rereading that failed
memory state. Each failed action also receives a successful direct callback retry;
this does not prove that the same retry is immediately reachable from the UI.

| Cases | Matrix | Acceptance |
| --- | --- | --- |
| 30 | Initial `0/1/2` × enable/disable × five outcomes | Correct value and display on success; unchanged confirmed display and one localized error on failure |
| 20 | Initial `1/2` × placement `1/2` × five outcomes | Requested placement persists only on success; failed input can be retried |
| 8 | Initial `1/2` × four commit outcomes during dependent reset | The `seentick` reset obeys the same confirmation contract |
| 3 | Initial `0/1/2`, no accepted callback | Construction alone makes no preference write or save feedback |

The five outcomes are successful commit, false commit, commit exception, memory
changed before false commit, and editor exception. The dependent-reset cases omit
editor exceptions because unrelated Boolean setters run first. The 61 cases are
one matrix; retries, internal assertions, repeated runs, and mutants are not extra
functional cases. A separate fixture-fidelity control verifies that a successful
write of another key persists an earlier failed write's memory value. It is not a
62nd callback case or an additional WAEX feature. The three no-action cases do not
simulate a Compose cancellation or lifecycle transition.

Three deliberately defective variants must compile and then return behavioral
`FAIL` (exit 1). A compilation failure is `BLOCKED` (exit 2) and cannot validate a
negative control. Each variant must fail its specific expected case and assertion.

```sh
python3 qa/test_global_privacy_seen_tick_persistence.py \
  --out /tmp/waex-global-privacy-optimistic --mutant optimistic-confirmation
python3 qa/test_global_privacy_seen_tick_persistence.py \
  --out /tmp/waex-global-privacy-unchecked --mutant unchecked-placement
python3 qa/test_global_privacy_seen_tick_persistence.py \
  --out /tmp/waex-global-privacy-toolbar --mutant force-toolbar
```

The additional `--mutant pending-only-double` restores the incomplete preference
double. It must compile and fail the separate fixture-fidelity control even when
all 61 callback cases pass. It cannot be counted as a production regression.

The source QA workflow runs the positive matrix, the three compiled production
negative controls, and the separate double-fidelity mutant. It preserves per-stage
commands, exit codes, duration, logs, input hashes,
the extracted Kotlin fixture, and the case results. The runner can also execute
the original source tree with `--source-root` to retain the defective reference.

## Recorded host JVM comparisons

The initial Linux image contains OpenJDK 21.0.13 and its Java compiler module but
lacks `ct.sym`. Strict `--release 17` and a diagnostic `--release 21` attempt were
`BLOCKED`; neither is a product failure. An explicit `--java-source-target 17`
repeat used Kotlin 2.0.20 and the same production inputs. That mode checks Java 17
syntax and bytecode only, not the Java 17 API surface.

The corrected runner was then repeated on Windows 11 build 26200 with Temurin
17.0.20.1+1, Kotlin 2.0.20 and the laboratory's Python 3.15.0a5 launcher. All six
executions used `javac --release 17` with no source/target override or fallback.
The native comparison passed with the same callback outcomes below and the
separate fixture-fidelity result described afterwards. Run durations were
5.680–5.853 seconds; these are harness durations, not application performance.

The runner SHA-256 is
`16279ed158c0039986f539413b130cc32066963ff85621caaef494f95540dcbf`.
All 19 source/resource inputs for each of the candidate and baseline roots were
matched by SHA-256 to the previously retained package. Documentation changes do
not alter those inputs. The native six-run evidence is identified by
`JDK17_MATRIX.json`, SHA-256
`f605490da82259da79d3f66095edff4f522209048868c5d6c088026fb738034b`;
it records separate result identities for every run. Earlier Linux failures and
the incomplete-double results remain retained under their own identities.

| Revision or variant | Result | Matrix outcome |
| --- | --- | --- |
| Original `0aaffab` Global Privacy callbacks | `FAIL` | 47 of 61 cases fail |
| Candidate Global Privacy callbacks | `PASS` | 61 of 61 cases pass |
| Candidate with optimistic confirmation restored | Expected `FAIL` | 26 cases fail; required switch confirmation defect detected |
| Candidate with unchecked placement restored | Expected `FAIL` | 16 cases fail; required placement confirmation defect detected |
| Candidate forcing toolbar on every enable | Expected `FAIL` | 5 cases fail; required overflow placement loss detected |

The separate complete-map fidelity control passed with both the original and
candidate production callbacks. Its deliberate pending-keys-only variant compiled
and failed that control while all 61 candidate callback cases still passed. This
demonstrates why the fidelity control is needed separately. Earlier evidence using
the incomplete double is retained as an earlier fixture revision and is not
substituted for the corrected runner's runs.

All six native runs compiled both Java and Kotlin successfully. This closes the
full JDK 17 compiler/runtime check for these identified inputs. These remain host
JVM results: Android compilation, actual Compose interaction, package overrides,
profile isolation, process restart persistence, and observable WhatsApp receipt
behavior require their own evidence and are not established by this matrix.

## Limits, device acceptance, and recovery

A false Android preferences commit can already have changed in-memory state. This
patch prevents a false UI confirmation and allows retry; it does not roll back
that memory, guarantee disk recovery, or change cross-process cache behavior. The
Hide Read Receipts action still uses the existing separate Boolean writes before
the dependent `seentick` reset. The whole cascade is not a new atomic transaction.
After a failed dependent reset, the parent setting may already be off while the
last confirmed manual seen-tick value remains on; the error is surfaced. The
existing UI dependency disables the manual switch and row while the parent is
off. Retrying the reset through that UI therefore requires enabling Hide Read
Receipts first, then disabling it again after storage succeeds. A direct callback
retry in the matrix is not a one-click UI recovery test. The placement selector's
existing visibility condition depends only on the manual value; its dependency
gating is unchanged by this patch.

A later successful write of another preference in the same store can persist the
earlier failed memory value before the manual control is retried. The displayed
last-confirmed value can therefore differ from storage after that later write.
The helper preserves its confirmed UI state for the immediate callback; it does
not provide cross-setting rollback, a durable-state observer, or persistence of
that confirmation across screen reconstruction or target changes. Changing that
broader transaction and lifecycle behavior is outside this patch. The source
provides a recovery route through the parent; actual Compose interaction remains
unverified.

On an actual declared Android build and profile, separately verify successful
enable/disable and toolbar/overflow selection, reopen/restart persistence, and
the existing navigation or dialog cancellation paths. Inject a checked preference
failure in an isolated test build to verify visible error and unchanged confirmed
control, then remove the injection and retry. Do not treat the JVM doubles as that
Android acceptance. Receipt transmission and hook effects require a separate
authorized WhatsApp scenario with exact host, module, profile, and build identity.

No data migration is introduced. Recovery is a normal revert of the screen change
and its accompanying tests/workflow/documentation. Existing preference values
remain strings with the same meanings and can be read by the reference version.
