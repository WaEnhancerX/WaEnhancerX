# Opt-in support diagnostics

WAEX adds **Record support diagnostics** to **System Diagnostics**, disabled by
default. Consent is stored as the non-exportable, device-local Boolean
`diagnostic_capture_enabled`. A settings restore does not silently enable it
on another installation or Android user/profile.

**Advanced WhatsApp crash and ANR history** is a separate opt-in toggle,
available on Android 11+ (API 30+). `diagnostic_target_exit_enabled` and the
consent timestamp `diagnostic_target_exit_since_ms` are local runtime preferences
excluded from settings exports. Disabling the main capture switch also revokes
advanced consent; advanced capture cannot be enabled while basic consent is off.

## What is recorded

- WAEX manager process start and the instant capture was enabled.
- Uncaught crashes in the **WAEX manager process**: exception *type* plus at
  most three WAEX class/method names. Exception messages, file paths, source
  line numbers and external stack frames are omitted.
- Existing **authenticated** HookProvider startup reports from declared
  WhatsApp targets after their preference update is committed: target package,
  a fixed-vocabulary runtime status, a fixed-vocabulary bridge status, and a
  Boolean indicating whether an error was reported. The existing hook
  registration payload also yields counts of active, failed, disabled and
  waiting features, plus at most eight validated failed *class names*.
  Raw JSON, user-facing names and exception messages are never exported.
  The existing runtime report is repeated by a 10-second heartbeat. A
  per-target, per-PID transition gate stores only changes (or a fresh process
  PID), not identical heartbeats. A failed journal write is retried at the
  next heartbeat without claiming that the write succeeded.
- **Optional WhatsApp process exit history** when *both* diagnostic switches
  have been explicitly enabled: on the next WhatsApp process startup, a
  low-priority, one-shot worker asks Android's `ActivityManager` for at most 16
  recent `ApplicationExitInfo` records **belonging to the calling WhatsApp
  package/UID**, never WAEX or another UID. This detects OS-classified Java and
  native crashes, killed ANRs, memory pressure, dependency/freezer deaths and
  other exits where the OS still retains them. Only events **after the explicit
  advanced-consent timestamp** and within seven days are sent as bounded typed
  fields (time, PID, reason/status codes, PSS/RSS). The existing HookProvider
  authenticates the caller UID and package again, verifies both consent flags,
  validates all array sizes and values, and appends the filtered metadata to
  the same private journal. A bounded, manager-private seen-set avoids writing
  the same OS ring-buffer entries again on repeated WhatsApp starts. No raw
  exception messages, stack traces, ANR traces or tombstones cross the bridge.
  Android may evict ring-buffer records; recovered (non-killed) ANRs, failures
  before module initialization, and other unreported events are **not** detected.
  Every successful advanced query delivered to the provider produces a small
  `TARGET_EXIT_SCAN` receipt, even when the OS returned no post-consent events;
  this distinguishes an empty history from missing delivery, although a scan
  receipt does not guarantee that Android retained every earlier crash.
  On API 29 and below, the advanced toggle cannot collect this OS history.
  This feature performs no recurring reads, no crash-thread IPC and no active
  interception of host exceptions; it only queries once after an opted-in launch.
- A chronological breadcrumb for the **static WAEX screen class**, not its
  dynamic route or arguments. For settings changed through WaexPreferenceManager,
  record only an exportable schema key, universal/target scope and PUT/REMOVE;
  never the changed value or actual target-specific key prefix. Also report
  successful settings import/export counts.

**Only when a user explicitly generates a report**, an on-demand
`environment.txt` is assembled containing:

- Device manufacturer/model, Android security-patch level, first supported ABI,
  module and installed reported WhatsApp target versions where visible.
- A memory/storage/battery snapshot and recorded hook framework/API label.
- Actual Boolean settings that the exportable public preference schema allows,
  including universal/target scope; **no string, list, path or secret values**.
- On Android 11+ (API 30+), up to 16 own-package OS process termination
  reason codes, exit statuses, timestamp and PSS/RSS; no free-form description,
  process identifiers, stack contents or ANR traces. Android does not permit
  reading other apps' exit history without privileged DUMP access.

This wider snapshot may identify device models and reveal which WAEX privacy
features are enabled. The UI discloses this before export; users should inspect
the resulting ZIP before choosing a recipient, especially in public groups.
It has **no sampling/collection cost during normal WhatsApp use**, because
all system and package queries run only when exporting.

There is deliberately **no** WhatsApp chat content, attachments, contacts,
message IDs, raw Android logcat, private WhatsApp databases, full exception
messages, credentials, serial/Android IDs, continuous performance tracing,
or host-side crash interception. A green hook status also does not prove
correct end-user behavior. Android's isolation prevents a normal application
from silently collecting every event/crash from another application's process.
Reading OS exit metadata *from the WhatsApp UID itself* avoids that permission
boundary without trying to obtain `DUMP`, root privileges or other users' data.
The manager cannot reliably capture every WhatsApp crash: collection requires
the target to restart with injection, the OS to retain exit history and the
authenticated provider to accept the report. The manager may also be absent.

## Storage and export

- App-private `noBackupFilesDir/waex-diagnostics`. No background service,
  timer, logcat polling, remote endpoint or automatic Telegram upload.
- Seven calendar-day buckets, at most two 128-KiB files per day:
  **1.75 MiB maximum** of journal parts. Expired/clock-future parts are
  pruned on the next record or export; an unused app does not run a cleanup
  worker solely for the journal.
- **Share diagnostic ZIP to Telegram** builds a ZIP under the app's private
  cache and opens Android's chooser with a read-only, temporary FileProvider
  grant. The user chooses Telegram, the developers' group, and confirms the
  send. WAEX itself does not know or impersonate a group/bot identity.
- **Delete saved diagnostics** removes journal parts and local ZIP copies
  created by this feature, after confirmation. It cannot revoke an already
  sent copy in Telegram.
- Turning capture off stops subsequent records; retained files can still be
  reviewed/exported or manually deleted. Turning it back on records a new
  consent marker.
- Enabling advanced capture after WhatsApp is already open requires reopening
  the target once for the startup-only OS history query; only subsequent exit
  records qualify. This is explicitly described next to the UI toggle.

## Verification boundaries

Run `python qa/test_diagnostics_journal.py --negative-control` with JDK 17+
and `python qa/test_diagnostics_integration_contract.py`. The first compiles
production journal, crash-handler, preference schema and declared target
classes and environment snapshot, and tests rotation, seven-day retention,
ZIP, structured breadcrumbs, failed hook summaries, data minimization,
concurrency, consent-backup isolation, and fatal-handler delegation. The
negative control intentionally permits forged target names and must FAIL.
The second verifies Android source/manifest/FileProvider wiring statically.

An Android Java adapter compile against the matching SDK and AAPT2 resource
compile are additional checks, **not** a full WAEX APK build. Until the
private `:licensing` module and compatible build inputs are available, the
full Gradle build is BLOCKED. The exact APK installation, Telegram share
sheet, multi-user support, crash callback and observable WhatsApp effects
remain **NOT_VERIFIED** on device. There is no claim of literally zero
overhead, universal diagnostic coverage, increased FPS or battery savings.
