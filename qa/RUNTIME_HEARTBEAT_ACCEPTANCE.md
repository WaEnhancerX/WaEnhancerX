# Runtime health heartbeat: WhatsApp UI-thread isolation

## Observable baseline and scope

- Device: Android 14 / API 34, `emulator-5560`; WhatsApp 2.26.43.76; WAEX
  2.0.6; Vector 2.2; Android users 0 and 10, with WhatsApp and WAEX
  processes running in both users.
- The Vector module log has WAEX initialization entries for user 10
  (Android UID 1010197), so injection is **observed** there. This does not
  establish that every hook works in a work profile.
- Android logcat observed repeated `ActivityManager: freezing 7579
  com.waenhancer` and `sync unfroze 7579 com.waenhancer for 7` messages
  for the work-profile manager process. A short timestamp sequence repeated
  roughly every 20 seconds. These logs establish freezer churn; they do
  not individually identify which call initiated each unfreeze.
- `dumpsys activity exit-info com.waenhancer` reported multiple
  `reason=14 (FREEZER)`, `subreason=20 (FREEZER BINDER TRANSACTION)`
  historical exits for the manager UID in the personal profile.
- In the exact public main source, `MainHook.reportTargetRuntime` posts a
  report every 10 seconds using `Handler(Looper.getMainLooper())`. The
  report performs synchronous `ContentResolver.call()` to the WAEX
  manager and the manager synchronously commits preference changes.
- The classifier requires a timestamp no older than **25 seconds** to
  treat injection evidence as current. This patch does not change the
  10-second heartbeat interval or the 25-second validation window.

The main-thread scheduling defect is established by source. The exact
latency of that IPC on a production WhatsApp UI thread is **not measured**;
the freezer logs identify an important risk, not a measured frame-rate
improvement.

## Focused change

Use a single lazy-started daemon `ScheduledExecutorService` to dispatch
recurring health reports. It schedules each report 10 seconds after the
previous one completes, avoiding overlap and avoiding execution from the
WhatsApp main Looper. The initial status report remains synchronous to
retain immediate initialization visibility. The existing at-most-once
startup guard and volatile status fields remain. A failure in one
recurring report is logged by exception class only and cannot permanently
cancel later reports.

This is deliberately independent of the provider-lifetime fix in upstream
PR #137. Moving calls off the UI thread **does not** stop the manager
from being frozen/killed by synchronous Binder calls, nor does it prevent
stable-provider dependency termination. It addresses the distinct UI
thread exposure and should be integrated alongside #137, not used as a
replacement.

## Reproducible isolated contract

```bash
python3 qa/test_runtime_heartbeat_thread.py \
  --source-root /absolute/path/to/candidate \
  --baseline-root /absolute/path/to/reference \
  --out /new/empty/output-directory
```

The test extracts the exact production executor declaration and complete
`reportTargetRuntime` method into a tiny standalone Java harness. It
only scales the interval from 10 seconds to 40 milliseconds. All
other scheduling logic is production text. The harness tests:

1. Immediate initial status report is preserved.
2. Recurring calls start after the initial delay.
3. Recurring reports use one named daemon worker, never the test/UI caller.
4. Repeated status updates retain the at-most-once scheduler.
5. Volatile status changes reach the next heartbeat.
6. A callback failure is recorded by class and subsequent heartbeats run.
7. A slow provider never overlaps with another heartbeat, and the next
   report waits after completion.
8. The baseline with UI-Looper scheduling is rejected; non-daemon and
   fixed-rate mutations are rejected by contract guards.

The source and generated fixture SHA-256, compile/runtime logs and JSON
result are retained. A deliberately defective reference must fail. The
test relies on explicit Java doubles; it does **not** call an Android
ContentProvider or test WhatsApp rendering.

### Executed results, 12 November 2026

- **PASS:** Windows 11 / Temurin 17.0.20.1, `javac --release 17`:
  21 runtime checks, one named daemon worker, status propagation, failure
  recovery, serial IPC and three rejected baseline/mutation controls.
  Final candidate evidence:
  `C:\WAEX-Lab\evidence\waex-heartbeat-background-20261112-jdk17-2`.
- **PASS:** Android 14 / API 34 / x86_64: the exact standalone Java harness
  compiled to DEX with D8 Build Tools 36.0.0 (`--min-api 24`) and
  executed by `app_process` as an isolated shell process. 21 checks passed
  with exit code 0. DEX SHA-256:
  `97895B043C038998CA5336330D7600AED9AFC918925B7803A7DC1F7121A74009`.
  The temporary DEX was removed from the device afterward; host evidence
  remains at `C:\WAEX-Lab\evidence\waex-heartbeat-background-20261112-android14-r2`.
- **PASS:** `qa/check_syntax.py` with JDK 17, UTF-8 and Kotlin 2.0.20
  parsed all 129 production Java and 128 Kotlin files.
- **PASS:** Existing bridge contract and provider-scope tests on Windows.
- **FAIL in both baseline and candidate:** `qa/test_startup_reconciliation.py`
  asserts an old Instrumentation fallback. The candidate did not modify
  that path.
- **BLOCKED:** The broader runtime diagnostics script requires a `sh`
  executable that was not on the Windows PATH for that invocation.
- **NOT VERIFIED:** The complete candidate APK, real WAEX/WhatsApp hooks,
  manager-freezer behavior after installing the change, and any improvement
  in measured frame-time, latency, memory or battery usage.

On a minimal Java runtime without `ct.sym`, the runner falls back from
`--release 17` to `-source 17 -target 17`. This only checks Java 17
bytecode syntax and must not be reported as API-signature validation.
Use a complete JDK for canonical `--release 17` acceptance.

## Remaining device acceptance

- Build the **exact candidate commit** with legitimate compatible
  dependencies. Record its APK bytes, signature, package and user.
- Confirm in user 0 and user 10 that the recurring WAEX report is no
  longer executed on `com.whatsapp`'s main thread. Use method tracing
  or deliberate bounded provider latency under a controlled probe.
- Verify injection evidence remains current when WhatsApp is active;
  check boot/PID, READY vs failure states, cold start and provider death.
- Under the same baseline/candidate settings, measure frame-time and
  main-thread blocking with enough repetitions and warm-up control.
- Inspect `dumpsys activity providers`, ActivityManager exits and
  freezer logs. Verify PR #137 eliminates stable-client dependency
  when it is integrated. Do not attribute freezer recovery to this patch.
- Recheck the manager dashboard, notification refresh and changes in
  feature status. No real messages or calls are necessary.
- Check at least Android API 24 and 34 when devices are available;
  other Android and WhatsApp versions remain **NOT_VERIFIED**.

## Recovery and compatibility

The change touches the runtime scheduler and its QA only. It changes no
WhatsApp database, preference key, declared package, provider authority,
license check or permission. One daemon worker exists per hooked process
after first runtime reporting. Revert through an ordinary commit if
device acceptance identifies a regression. No force push or user-data
restore is required.

Primary Android references:
- https://source.android.com/docs/core/perf/cached-apps-freezer
- https://source.android.com/docs/core/architecture/ipc/binder-freezer
- https://developer.android.com/reference/android/os/StrictMode
