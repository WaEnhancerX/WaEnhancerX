# Opt-in support diagnostics

WAEX adds **Record support diagnostics** to **System Diagnostics**, disabled by
default. Consent is stored as the non-exportable, device-local Boolean
`diagnostic_capture_enabled`. A settings restore does not silently enable it
on another installation or Android user/profile.

## What is recorded

- WAEX manager process start and the instant capture was enabled.
- Uncaught crashes in the **WAEX manager process**: exception *type* plus at
  most three WAEX class/method names. Exception messages, file paths, source
  line numbers and external stack frames are omitted.
- Existing **authenticated** HookProvider startup reports from declared
  WhatsApp targets after their preference update is committed: target package,
  a fixed-vocabulary runtime status, a fixed-vocabulary bridge status, and a
  Boolean indicating whether an error was reported. Recording adds no new
  IPC calls to WhatsApp and no new per-hook callbacks.

There is deliberately **no** WhatsApp chat content, attachments, contacts,
message IDs, raw Android logcat, private WhatsApp databases, full exception
messages, credentials, device identifiers, continuous performance tracing,
or host-side crash interception. A green hook status also does not prove
correct end-user behavior. Android's isolation prevents a normal application
from silently collecting every event/crash from another application's process.

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

## Verification boundaries

Run `python qa/test_diagnostics_journal.py --negative-control` with JDK 17+
and `python qa/test_diagnostics_integration_contract.py`. The first compiles
production journal, crash-handler, preference schema and declared target
classes and tests rotation, seven-day retention, ZIP, data minimization,
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
