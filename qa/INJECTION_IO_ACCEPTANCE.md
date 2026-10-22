# Injection diagnostics, bounded delivery and live preference refresh

## Contract

Optional package reports, hook inventories, runtime health and update requests must not block
`Application.onCreate`, the heartbeat callback or the preference-change broadcast receiver.
The original authoritative initial preference read still precedes version checks and feature
installation. Hook installation remains serial and runs only once per process.
If the startup waiter is interrupted before that initial read completes, it preserves the
thread's interrupt flag and aborts feature initialization. It does not cancel the provider read.
A provider read that finishes with an error retains the established `DISK_FALLBACK` behavior.

Runtime delivery has one worker and at most one pending task per kind (four kinds). New health
observations replace pending ones, and replacement moves the task to its new chronological
position so READY cannot overtake an already queued hook inventory. A report carries the time
of observation, not a new timestamp created when a blocked delivery finally resumes.

Runtime metadata in the manager's shared preference file must not trigger configuration
reloads or preference broadcasts to every target. Settings, entitlement changes and whole-store
clears retain their existing notification path. Runtime health and inventory notifications
remain on their own URIs. Binder scope, ownership checks and license decisions remain enforced.

## Local verification

```sh
python3 qa/test_runtime_report_queue.py
python3 qa/test_injection_io_runtime.py --out /tmp/waex-injection-io
python3 qa/mutate_injection_io.py --out /tmp/waex-injection-io-mutants
python3 qa/test_bridge_contract.py
python3 qa/test_bridge_ordering.py
python3 qa/test_startup_reconciliation.py
```

The complete production `MainHook`, `PreferenceBridgeClient` and dispatcher are compiled.
Xposed callback registration, Android/Binder/looper types, detector transport, the feature
registry and private visual components are explicit substitutes. Latches represent slow I/O;
the timeouts detect blocking relationships and are not Android startup measurements.

The fixtures cover slow registration/inventory/update delivery, heartbeat and live configuration
refresh, required initial synchronization, interrupted startup and the completed provider-error
disk fallback, unsupported versions, initialization failures,
Application/Instrumentation duplication, the missing-attach fallback and optional-report
failure isolation. Mutations restore inline I/O, incorrect pending order, delivery timestamps,
synchronous refresh, premature initialization, a lost interrupt flag and continuation after
interrupted synchronization. Only the intended runtime assertion counts
as rejection; compilation failures and missing events are infrastructure failures.

## Device acceptance still required

Test cold and warm starts on the supported Android/WhatsApp/framework combinations, in the
personal and work profiles. Record startup duration, main-thread traces, frame timing, provider
latency and configuration propagation before and after the change. Verify caller UID/PID and
boot evidence under delayed or denied provider access. A blocked Binder transaction cannot be
forcibly cancelled by this queue: it may delay diagnostics while the host remains responsive.
Expired observations must remain unconfirmed, and a missing report must not become proof of
injection. This change does not bypass root-manager scope or perform late parallel installation
of hooks. It does not remove the initial preference read or promise a measured millisecond gain.
