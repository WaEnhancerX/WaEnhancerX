# Runtime diagnostics without configuration feedback

## Verified behavior

Runtime observations and hook settings share the public preference file. Before
this correction, every changed telemetry key reached `WaexApp`'s preference
listener, notified `/preferences` and broadcast a configuration refresh to every
supported/registered target. The provider added another `/preferences`
notification for each successful diagnostic report. This made telemetry itself
trigger configuration work.

`PreferenceSyncPolicy` excludes the explicit runtime package, injected-hook,
heartbeat, process, boot, status, error and bridge keys from that listener path.
The three validated diagnostic provider routes notify `/runtime_health`, and
injected hook registration also notifies `/injected_hooks`. Ordinary settings,
operational switches, entitlements, unknown future configuration keys and the
null key used for a whole-store clear still notify hook configuration.

Both `register_hooked_package` and `register_injected_hooks` now propagate the
actual `commit()` result. They notify diagnostic observers only after successful
persistence. `report_runtime_health` already honored that result. Binder scope
validation still precedes identity clearing, package ownership is still checked
using the original complete UID, and identity restoration remains in `finally`.

## Full-class executable fixture

```sh
# JDK 17+ and Kotlin compiler in PATH; --kotlinc is also supported.
python3 qa/test_preference_sync_runtime.py --out /tmp/waex-preference-sync
python3 qa/mutate_preference_sync.py --out /tmp/waex-preference-sync-mutations
python3 qa/test_provider_scope.py
python3 qa/test_provider_derived_keys.py
```

The fixture compiles the complete `WaexApp.kt`, `HookProvider.java`, preference
schema, stores, restart tracker, synchronization policy and three provider write/
authorization policies. Platform services, catalog lookup, localization,
licensing, updates, keybox synchronization and the preserved-message store are
explicit boundaries. No private licensing implementation or external side effect
is substituted into the deliverable.

The listener executes both synchronously and through a deterministic deferred
queue. The preference double updates memory and invokes change listeners even
when disk persistence fails. It also delivers null for clear, modeling the
Android R+ callback contract. The complete application registers its own real
listener during `onCreate`; the fixture does not copy or extract that listener.

| Source | Scenarios | Checks reached | Failed scenarios |
| --- | ---: | ---: | ---: |
| Original V39 app/provider | 86 | 1,517 | 42 |
| Telemetry filtering/routing, before registration commit fix | 86 | 1,697 | 4 |
| Corrected candidate | 86 | 1,721 | 0 |

Checks stop within each failing scenario. The original app/provider are supplied
as verbatim baseline overrides; unchanged policies and the newly introduced
classifier use the same source set for both runs. The original app never calls
that new classifier. Every source and the fixture is SHA-256 recorded in the
result JSON.

For a successful health report changing seven keys, the original full classes
emit eight `/preferences` notifications. The candidate emits zero configuration
notifications/broadcasts and one `/runtime_health` notification. The fixture's
package detector uses all 48 configured scope entries, producing 336 original
broadcasts in that synthetic case. This is a fixture fan-out, not a measured
number of packages or broadcasts on a user's phone.

The fixture also exercises 100 successive health reports, every declared public
setting against the classifier, configuration and entitlement updates, null
clear, generic writes, private/derived-key and declared-type restrictions,
entitlement denial/allowance, original caller PID preservation, ownership
rejection, complete work-profile UIDs and untrusted Binder callers. A failed
generic setting commit retains its real memory-listener signal while the
provider returns failure; a failed telemetry commit causes no configuration or
explicit diagnostic notification.

The mutation runner requires seven deliberate defects to fail at specific
runtime assertions: unfiltered listener feedback, provider feedback, lost clear
refresh, false success in each registration route, lost entitlement refresh and
unscoped Binder trust. A compilation failure does not count as detection.

## Platform contract and remaining acceptance

The modeled persistence and notification ordering follows
[AOSP SharedPreferencesImpl](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/SharedPreferencesImpl.java):
memory is updated before the disk result is returned, and listeners can run or
be posted to the main handler independently of that disk result. The
[Android listener reference](https://developer.android.com/reference/android/content/SharedPreferences.OnSharedPreferenceChangeListener)
documents the null callback on clear for apps targeting Android R+ on Android
R+ devices. Consulted on 2026-10-20; this fixture does not emulate older clear
callback behavior or disk recovery after process death.

No Android Binder integration, native hooks, real work-profile cross-user IPC,
private licensing engine, target cold-start timing or frame latency is certified
by this JVM result. The fixed source removes a reproducible source of redundant
configuration work; device acceptance must still confirm live settings reload,
diagnostic freshness, target injection and observed startup behavior in the
supported framework/profile configuration.
