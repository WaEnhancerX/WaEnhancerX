# WAEX provider IPC — Android cached-app freezer

## Failure observed before this change

Android 14, emulator `emulator-5560`, WhatsApp 2.26.43.76, WAEX 2.0.6, Vector 2.2:

- The Android system log repeatedly reports `com.waenhancer` killed for
  `Sync transaction while in frozen state` (e.g. 15:44, 15:51, 15:56,
  16:03, 16:24, 16:29 on 2026-11-11).
- At 16:29:38, Android also killed personal `com.whatsapp` PID5034 because
  it `depends on provider com.waenhancer/.xposed.bridge.HookProvider in dying proc`.
  This is a system-issued process termination, not evidence of a Java crash.
- Do not infer an installed APK is built from the branch under test. This
  branch is a **candidate** pending an Android build and A/B instrumented test.

## Contract and change

AOSP explains that synchronous Binder calls into frozen processes terminate
the receiver. Android's `ContentResolver.acquireUnstableContentProviderClient`
documentation explicitly says it disables the mechanism that also cleans up
processes depending on a dead provider. Consequently, hook-side calls to the
WAEX manager must not keep **stable** provider dependencies on WhatsApp.

All 14 source call sites now use `HookProviderIpc.call`. Each call:

1. Acquires a fresh **unstable** provider client for the same WAEX authority.
2. Sends the original method, argument and Bundle without altering identity,
   permissions, feature entitlement or user/profile resolution.
3. Closes the client even when the provider returns null or throws.
4. Returns null on `RemoteException` (like `ContentResolver.call`), but lets
   `SecurityException` and programming errors propagate to existing callers.
5. Does **not** retry ambiguous operations, especially `apply_preferences`,
   `record_preserved_message`, call-recording creation, or transcription.

An unstable reference protects **the client** from being killed as a dependent
process; it does **not** prevent the frozen provider itself from being killed,
guarantee response delivery, or replace user-10 scope configuration.

Primary references:

- https://source.android.com/docs/core/perf/cached-apps-freezer
- https://developer.android.com/reference/android/content/ContentResolver#acquireUnstableContentProviderClient(android.net.Uri)
- https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/content/ContentProviderClient.java

## Reproducible JVM contract probe

```bash
python3 qa/provider_ipc_freezer/verify.py
python3 -O qa/provider_ipc_freezer/verify.py
```

The runner compiles the production `HookProviderIpc.java` against deterministic
Android API stubs. If the `javac` binary is missing but a full JDK `java`
module exists, it uses `java com.sun.tools.javac.Main`. It verifies single
unstable acquisition, no stable/legacy calls, exact arguments/reply, closure
on all paths, Binder death handling without replay, security exceptions, URI
and null contracts, separate calling contexts, all 14 migrated sites. Five
negative mutations must be rejected by the unchanged oracle.

The stubs only exercise client code; they do not simulate Android's real
process freezer or prove Kotlin compilation, APK integration or WhatsApp
behavior. The device acceptance experiment must compare (same host/WAEX/
WhatsApp/Vector/settings/users) baseline vs candidate after the provider is
observed frozen, with `dumpsys activity providers` stable/unstable counts and
ActivityManager process exit causes. Do not send messages/calls/receipts.
Confirm that retries do not duplicate provider writes and that runtime health
still reaches the manager when unfrozen. Use isolated data and recovery.
