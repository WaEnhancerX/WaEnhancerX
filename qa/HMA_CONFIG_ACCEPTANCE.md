# HMA configuration repair: scope and acceptance

The public-source runner compiles the complete `HmaDiagnostics`, `HmaConfigPolicy`,
`HmaConfigFile`, `AndroidUserScope` and `RootCommandRunner`, together with the actual
declared-target set and current `HookErrors`. It runs 70 diagnostics/repair
scenarios and 15 policy/snapshot scenarios on each of Android API 24, 28 and 34
using Robolectric 4.17: **255 framework test executions**. These are 85 scenarios
repeated on three framework versions, not 255 devices. A separate runner executes
**20 process/output checks**.

## Current nightly reconciliation (2026-11-21)

Original PR head `4f0bfa7b3d30d2b99f6a3944d650685b11c0ab1d` was reconciled with
nightly `9972e49fac9bfb650c25ee31fde0da9425b67175`. On Linux with Temurin
21.0.13.1+2, Kotlin 2.0.20 and verified Robolectric SDK JARs, staged input tree
`3b43726b7be22564e94bcd0c6248834de5ae89db` passed 255/255 framework cases and
20/20 bounded-process checks in 76.63 seconds including compilation. Production
and framework-fixture inputs did not change afterward; subsequent edits adapted
separate JDK/display fixtures and this documentation. The 13 startup-deadline
scenarios, 12 shell-transaction cases and 33 current dashboard-state checks also
passed. The 12 transaction cases were executed again by the upstream-hardening
entry point; that is repetition, not 12 additional unique cases.

Current native path/package facts and the `NativeDiagPaths.available` guard are
retained. The existing live-scope snapshot API remains; an empty scope never
expands to every declared target, scope changes invalidate a cached target set,
and only a freshly read live scope can authorize a repair. Any discovered config
that cannot be read/parsed makes the diagnostic unavailable instead of clean.
Six new flow scenarios cover those boundaries and all 23 current native paths.

The fixture extracts native constants from the actual C source and copies the
exact public `LsposedScopeSnapshot` declaration. JNI execution and LSPosed DB
access are explicit controlled endpoints, not reimplemented private services.
Hashes identify those source and generated boundaries. The display fixture also
retains current module-active and DenyList critical-error precedence. Its initial
missing-current-model compile failure was retained before adapting the boundary.
The unrelated receipt guard had drifted from a one-line veto to nightly's
manual-grant/retry-preserving branch; the structural check now checks that real
branch and the existing 65-check compiled receipt installer suite passed unchanged.
No receipt implementation was reverted or altered.

```bash
python3 qa/android_hma_test.py \
  --out /tmp/waex-hma-qa \
  --maven-repo /tmp/waex-hma-maven-cache
```

The output directory must not already exist. JDK 17 or newer, Python, Kotlin CLI
with its standard/coroutines runtime JARs, and Maven are required. The optional
`--deps` and `--sdk-jars` arguments reuse an already populated flat dependency
directory and the exact Robolectric instrumented SDK JARs. `--source-root` and
`--suite flow` can compile a historical diagnostics implementation without the
new helper classes. `--expect-assertion-failures N` accepts only the explicitly
specified number of assertion failures; setup, linkage or compilation errors do
not become successful baseline evidence.

## What is executed

- Real Android framework `Context`, package-manager interfaces, JSON and Base64
  behavior under Robolectric on the three requested API versions.
- Complete public diagnostics and repair entry points, including their IO
  dispatcher, target validation, manager discovery, result cache invalidation,
  pre/post repair manager passes, and final Boolean result.
- Calling-user identity checks before root access, explicit package-list and
  force-stop user arguments, profile-specific private file candidates, and cache
  keys covering the application UID, module package, normalized scoped target set
  and live-versus-cached scope provenance.
  Invalid or mismatched Context/UID requests return unavailable and never issue a
  root command. Checks and repairs share a lock so an old check cannot overwrite
  repair's cache invalidation.
- The complete production process runner. A test-owned executable accepts the
  runner's command protocol; it does not invoke a root manager or elevate
  privileges. Logical Android paths map to temporary files owned by this test.
- Generated config read/backup/write/restore shell logic, including size/hash checks, temporary
  file creation, owner/mode preservation, rename, cleanup and acknowledgement,
  through local `sh` and filesystem tools. The evidence records the executor UID
  without claiming that the host process is an ordinary Android app.
- Exact manager package matching, shared and unapplied template preservation,
  unrelated/default-scope template-name reservations, OSS list/preset precedence,
  complete large JSON preservation, 64 KiB limits, partial repair and injected
  filesystem/command failures.

The quick `test_hma_backup_commands.py` gate separately compiles the complete
command builder and executes 12 filesystem contracts using a JDK Base64 adapter,
an unused manager-policy link that throws if called, and an explicit SELinux
endpoint. It validates shell transactions without downloading an Android SDK or
substituting the full Android framework gate. `test_selected_upstream_hardening.py`
calls this compiled gate in place of HMA source-literal assertions and preserves
the receipt, props and call checks from upstream. `HMA_QA_COMMAND_OUT` optionally
retains its exact generated commands, logs and source/fixture identities.

## Recoverable transactions

The backup introduced in upstream `a565e1a1a50ebfe5d633e8cd0d43c6dc6025a8ac`
is preserved as `<config>.waex.bak`. Backup creation checks the live snapshot,
copies complete original bytes and metadata to a unique temporary file, verifies
the digest, and atomically replaces a regular backup destination. A symlink or
directory at the backup path is rejected. The live replacement cannot start
without the exact backup acknowledgement and independently verified backup hash.

The replacement preserves owner, mode and available SELinux label before rename.
Success requires the exact write acknowledgement and a fresh bounded snapshot
matching the whole intended document. If either check fails, guarded rollback
may copy the verified backup into another temporary file and atomically restore
it, preserving the backup itself. It only replaces a live file whose digest still
equals this attempt's exact replacement; a concurrent writer's different content
is left alone. An already unchanged original needs no rewrite. Rollback checks
its own exact acknowledgement and a fresh snapshot, and the repair still returns
false after a failed write, even when restoration succeeds.

Tests cover copied bytes/permissions, backup and live symlinks, a backup directory,
empty/suffixed/fabricated acknowledgements, failed copy or rename, stale input,
tampered backups, concurrent changes before/after replacement and before rollback,
and retained recovery copies after failed restoration. An older regular backup
is replaced by this attempt's verified pre-edit snapshot.

The command service fails on commands outside its enumerated protocol. Package
listing and force-stop are explicit service fixtures; no actual Android app is
stopped. SELinux operations are explicit fixture boundaries. The direct process
checks use local `sh` with the runner's package-private starter and never call its
public root endpoint.

## Supported path owners

| Owner/source | Accepted direct config location |
| --- | --- |
| Inspected HMA/OSS service storage | `/data/misc/hide_my_applist*/config.json` |
| Inspected legacy migration source | `/data/system/hide_my_applist*/config.json` |
| Exact known manager's own file, owner-user alias only | `/data/data/<manager>/files/config.json` |
| Private manager candidate for the calling user's verified UID | `/data/user/<userId>/<manager>/files/config.json` |
| Current native manager candidates, same verified user | `/data/user_de/<userId>/<manager>/files/config.json` and manager `files/temp_config.json` |
| Current native per-user service candidate | `/data/system/users/<userId>/hide_my_applist*/config.json` |
| Current native global module candidates | `/data/adb/hma/config.json`, `/data/adb/hma_oss/config.json`, and `config.json` immediately under `/data/adb/modules/{hma_oss_zygisk,hma_oss,hide_my_applist}` |

`<manager>` must be exactly `com.tsng.hidemyapplist`, `org.frknkrc44.hma_oss`, or
`com.google.android.hmal`. Descendants of another app/service, additional nested
directories, another user's private files, noncanonical user numbers, unsupported
roots, traversal components, controls and oversized components are rejected.
Discovery checks the current native direct candidates mapped to the initiating
user, plus the current native service-directory glob,
without a recursive scan of unrelated application directories. It canonicalizes
paths and rechecks their user ownership; read/write
commands recheck canonical identity and reject a symlinked file. Additional forks
or storage layouts require their own confirmed contracts before being accepted.

The additional module/private paths above are facts from current nightly's
`app/src/main/cpp/waex_diag.c`, not evidence that every HMA fork uses them. The
path policy admits those exact roots while still rejecting similarly named
foreign module directories, nested paths and global `temp_config.json` files.
Per-user system and device-encrypted candidates cannot cross the caller's user.

The global service configurations remain shared by package name across Android
users. Selecting a target repairs that package's rules in those global stores; it
does not create a profile-specific HMA rule. A private manager file is only a
candidate. Its existence does not establish that the service imported or uses it.
In particular, the inspected OSS importer names owner-user locations, so discovery
of a profile-10 manager file is not evidence that the running service imported
that file. Root package discovery and manager force-stop use the calling app's
verified user even when repairing a global service store.

## Source basis

The rule review uses these immutable public implementations:

- [Legacy config schema, bd5466b](https://github.com/Dr-TSNG/Hide-My-Applist/blob/bd5466b37bae15e3ea2a66694e7624ce3d33479d/common/src/main/java/icu/nullptr/hidemyapplist/common/JsonConfig.kt).
- [Legacy service, bd5466b](https://github.com/Dr-TSNG/Hide-My-Applist/blob/bd5466b37bae15e3ea2a66694e7624ce3d33479d/xposed/src/main/java/icu/nullptr/hidemyapplist/xposed/HMAService.kt).
- [OSS config schema, 1fe86ce](https://github.com/frknkrc44/HMA-OSS/blob/1fe86ce2b4a6f21197e6e92d15e054d609b7d77c/common/src/main/java/icu/nullptr/hidemyapplist/common/JsonConfig.kt).
- [OSS service, 1fe86ce](https://github.com/frknkrc44/HMA-OSS/blob/1fe86ce2b4a6f21197e6e92d15e054d609b7d77c/zygote/src/main/java/org/frknkrc44/hma_oss/zygote/service/HMAService.kt).
- [AOSP multi-user testing](https://source.android.com/docs/devices/admin/multi-user-testing),
  including the owner-user default for `pm list packages` and the need to identify
  the intended Android user explicitly.

Both services apply `extraAppList` before template rules and use the target's
whitelist mode when evaluating template membership. The template's `isWhitelist`
field is not that runtime decision. OSS evaluates `extraOppositeAppList` after the
extra list and before templates/presets, and honors the global preset exclusion
list. OSS can omit a missing template, so creating a new global name must avoid
references in other scopes/defaultConfig as well as existing template keys.

The inspector covers these JSON visibility rules for the running module package.
It does not reconstruct every service exception, dynamically populated preset,
system-app, WebView/browser, Google service, disabled-hook or per-user decision.
Legacy's missing-template behavior differs from OSS; the tests do not describe
legacy as ignoring such a reference.

`python3 qa/test_hma_dashboard_state.py` compiles the dashboard's exact HMA value,
health and loading expressions against the production state enum. It checks all
states, including an unavailable result that remains unconfirmed and raises the
overall warning indication. This is a display-policy check, not Compose rendering.

## Historical paired profile regression evidence

The earlier profile candidate passed **237/237** framework executions and **20/20** bounded
process checks. The same 237 fixtures compiled against the immutable integration
baseline `5cd69d8e7fbad0379f417134cbb2ce289dfa3baa` produce **39 assertion failures**,
with no setup or compilation failures, while retaining 20/20 bounded checks. The
failures expose unscoped manager stops, owner-user package discovery, rejection
of the caller's work-profile candidate, invalid-context access and stale cache
reuse. They are intentional baseline failures, not a passing baseline run.
That historical baseline comparison was not rerun against the current native and
live-scope contracts. It must not be attributed to the reconciled input tree or
added to its 255 executions.

## Device acceptance still required

`UNAVAILABLE` covers an invalid/mismatched caller identity and a discovered
configuration set that cannot be read completely. Other diagnostic failures retain
the current classification: inaccessible root discovery can still yield
`NOT_ACTIVE`, and no discovered config with a detected manager can yield
`NOT_CONFIGURED`. Native-library unavailability retains nightly's `NOT_ACTIVE`
report with an explicit unavailable-architecture message. This does not make
permission denial distinguishable from absence throughout the pipeline.

Successful replacement means that the script returned the exact acknowledgement
and a subsequent bounded snapshot matched the intended document. It does not
establish that an HMA process reloaded the file or that WhatsApp hooks injected.
An atomic rename prevents readers seeing an incomplete replacement. Snapshot
hash checks detect observed concurrent changes; they are not an absolute
compare-and-swap against another writer in the final check/rename window.

On a disposable device with an authorized supported HMA setup, verify the
manager/service version and actual config path, original ownership/mode/SELinux
label, selected target and current module package. Confirm that the repair
preserves unrelated scopes/templates, that the service reloads the result, and
that the module's actual injection evidence changes after restarting the target.
Check failure behavior when access is denied, the file changes concurrently or
one of several files fails. A false result can accompany a partially completed
multi-file repair; this contribution reports that failure and does not promise
cross-file rollback.

No Gradle APK build, private licensing module, Android root-manager integration,
real SELinux transition, HMA Binder/service reload, native hook installation or
WhatsApp behavior is established by this public-source suite.
