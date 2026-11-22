# Preserve restart requests in resolved preference snapshots

## Regression and expected behavior

Provider scope isolation at `a4a5d96d41fa97806e8c32669068f04f091a4f9e`
correctly resolves this caller's per-target preferences and removes all raw
target namespaces from the IPC response. The existing client then performed
per-target resolution a second time. It read `package:need_restart` from a
response that intentionally no longer contained that key, overwrote the resolved
`need_restart=true` with `false`, and removed `pending_restart_changes`.

`RestartPromptController` requires the resulting bare restart flag before it can
show the request. A manager setting change could therefore reach the provider
but lose its restart notification inside the client cache in Per Target App mode.
This source-level regression does not establish that a released APK contained
the development provider-scope branch.

The client now normalizes only raw disk snapshots before storing them in its
confirmed cache. Provider snapshots are already resolved. Optimistic edits are
applied to the resolved cache without reapplying old raw disk keys. The provider
namespace filter and its global-only and direct-key restrictions remain intact.

## Executable acceptance

```bash
python qa/test_bridge_resolved_snapshot.py --negative-controls
```

The default command requires a JDK supporting `javac --release 17`. It compiles:

- the complete production `PreferenceBridgeClient`, `PreferenceSchema`,
  `DeclaredWhatsAppTargets`, namespace policy and `HookErrors`;
- the provider's actual preference-resolution block, target initialization and
  bundle serializer, extracted verbatim into a narrow fixture;
- strict JVM substitutes for Android transport and preference storage.

Expected values are explicit fixture inputs, independent of the implementation:

1. Personal target retains the resolved restart flag and pending set; raw target
   keys remain absent from the provider response.
2. Refresh delivers a new restart notification; consuming it persists only this
   target's restart state and leaves the universal state alone.
3. Business does not inherit the personal restart request or feature override.
4. A declared clone with no override does not inherit universal restart state.
5. Disk fallback selects its own overrides; provider recovery replaces stale
   disk restart state and reports the recovered transport source.
6. Disk fallback without a target override does not inherit universal restart
   state.
7. Raw disk keys cannot overwrite optimistic restart consumption while the write
   is held at a deterministic gate.
8. Universal mode retains universal restart and feature values.

The same suite checks defensive set copies and rejects stale per-target copies
of global-only version rules. Two compiled negative controls restore double
resolution or omit disk normalization; they must fail for their specific
behavioral reasons. A compile error or timeout is not a successful negative
control.

The original `qa/test_bridge_contract.py` double is also aligned with the
provider's resolved, namespace-filtered wire contract. Its prior raw-map response
could conceal this regression. No original contract scenario was removed.

## Validation dependencies and limits

The standalone bridge contract and ordering runners in the base revision omit
production schema/error-logger dependencies. That separate repair already exists
in `fix/qa-bridge-schema-diagnostics-20261117` at
`c04fd0603500a94a8fbae41be70b522eaf6f0739`. Do not duplicate that repair here.
For combined validation, apply that QA branch and this PR's fake-provider delta;
record the exact integration inputs. The new resolved-snapshot runner is
self-contained and supplies the real dependencies directly.

A reduced runtime that has the Java compiler module but lacks `ct.sym` can use
`--runtime-api` explicitly. This compiles with source/target 17 against the runtime
APIs and **does not** establish Java 17 API compatibility. CI uses the default
`--release 17` path; it does not fall back silently.

This is JVM source/contract evidence. It does not compile or install a full WAEX
APK, provide private licensing components, validate Binder across Android users,
or prove that a restart sheet appears inside a particular WhatsApp version.

The production client with SHA-256
`9b527cce1a4b8deaa9ecb6d4f3b142e55609f817f5099c890023de15dd683037`
was also exercised on Windows 11 with Temurin JDK 17.0.20.1 using the default
`--release 17` path: the reference compiled and failed because the restart
request was discarded; the candidate passed all eight scenarios and both
behavioral negative controls. Provider namespace, caller, derived-key,
preference-type and backup-alias suites also passed in that environment.
The first evidence collector failed after preserving the reference failure
log while locating `java`; that collector error is retained separately from
the actual assertion and was corrected before candidate collection.

## Required Android acceptance after a genuine integrated build

Use the exact source/dependency/build/APK identity and an authorized test profile.
In Per Target App mode, keep personal and Business restart state distinct. With
WhatsApp running, change one restart-requiring preference in the manager and
verify the prompt names that feature. Consume the request, return to the target,
and verify it does not recur without another change. Repeat with the option off,
with no target overrides, after process restart, and in the work profile when
that profile is configured for testing. Verify universal mode separately.

Exercise provider loss/recovery without modifying the user's original data.
Confirm that disk fallback keeps target isolation, the provider is selected when
available again, and raw `package:key` entries do not return to IPC. Record actual
observations rather than interpreting a registered hook as a visible effect.

## Recovery

Revert the focused client/test change if validation exposes a regression. Keep
the provider namespace-isolation change in place. No preference migration,
persisted-data deletion, credential change, or database rewrite is required.
