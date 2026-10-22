# Runtime process observations within the manager's Android user

## Production contract

`XposedRuntimeInspector` derives the expected Android user from the manager Context
and `Process.myUid()`, then reads each target package's `ApplicationInfo` using that
Context's PackageManager. A target must belong to the same user. The root probe
filters each reported PID by the exact expected UID, and the parser checks that UID
again before publishing a complete observation. A failed new observation invalidates
cached evidence. A complete empty PID record represents a successful observation of
no running process; an unavailable or partial probe remains unavailable.

## Framework test

Run from the repository root with Java, Kotlin and Maven on PATH:

```sh
python3 qa/android_runtime_profile_test.py --out /tmp/waex-runtime-profile
```

The harness resolves Robolectric 4.17, Android-all 14-robolectric-10818077, JUnit
4.13.2 and the project's pinned Kotlin runtime through `qa/maven_dependencies.py`.
Dependency resolution uses the helper's bounded retries and preserves every attempt.
Compilation and JUnit failures are immediate failures. `--maven` selects a Maven
executable and `--maven-repo` selects a dependency cache. AAR class jars are extracted
for the standalone classpath.

For an offline run, provide an existing dependency directory containing its jar
dependencies and extracted AAR class jars, plus the cached instrumented Android SDK
jars. The SDK path can contain version subdirectories:

```sh
python3 qa/android_runtime_profile_test.py \
  --deps /path/to/robolectric-deps \
  --sdk-jars /path/to/android-all-instrumented \
  --out /tmp/waex-runtime-profile-offline
```

The harness compiles these complete production sources against Android-all and the
Kotlin runtime, with no skipped metadata or API checks:

- `AndroidUserScope.java`
- `InjectionEvidence.kt`
- `XposedRuntimeInspector.kt`

The existing `app/src/test/.../WhatsAppPackageDetectorTest.kt` is also compiled
in full against real JUnit, with an exact `hasCurrentInjection` production
delegate extracted from `WhatsAppPackageDetector.kt`. This is an API-consumer
gate: it catches a unit test left on the old parser signature or PID-only
protocol. Its separate compiler log and source/fragment hashes are retained.
The full detector, Gradle app test task and those unit-test methods are not
executed by this harness; the 30 runtime cases below remain the executed gate.

`AppRestartHelper.runRootCommand` is the only controlled production endpoint. The
test uses Robolectric's Android Application, Context, PackageManager,
ApplicationInfo, Process and SystemClock classes. UID values and installed-package
metadata are supplied through standard Robolectric shadows. There are no custom
Context or PackageManager implementations in this fixture.

## Cases and results

Ten JUnit scenarios execute on each of API 24, 28 and 34, for 30 executions:

| Scenario | Expected result |
| --- | --- |
| Primary-user Context and target metadata agree | Complete expected PID set |
| Work-profile UID values agree across Context, Process and target | Complete expected PID set using that UID |
| Context/target agree with each other but belong to another user than Process | Rejected before the root endpoint |
| Target package metadata belongs to another user | Rejected before the root endpoint |
| Negative Context UID | Rejected before the root endpoint |
| Target package is missing after a successful observation | Cached observation invalidated |
| Returned UID differs from PackageManager metadata | Observation rejected |
| Complete empty PID record | Empty PID set |
| Missing end marker | Observation unavailable |
| Root endpoint fails after a successful observation | Cached observation invalidated |

Recorded candidate result: **30 tests, zero failures**. Two isolated mutation
controls also compiled and ran. Removing the Context/process user guard caused
three assertion failures, one on each API. Removing the target-package user guard
caused three assertion failures, one on each API. All remaining scenarios passed.
Use `--source-root` for an isolated source tree and `--expect-assertion-failures 3`
only for these intentional controls; ordinary candidate execution requires zero.

The output directory preserves Java/Kotlin compiler logs, JUnit logs, complete
staged production sources, SHA-256 identities, dependency/SDK identities and a
machine-readable `result.json`. A non-assertion setup failure never qualifies as an
expected mutation result.

## Evidence boundaries

The test demonstrates compilation against real Android API classes and execution
through Robolectric on API 24/28/34. A managed profile is represented by controlled
UID/package metadata; the fixture does not create an Android user or work profile.
Root output is supplied by the endpoint fixture. No `su`, `pidof`, `/proc`, Android
kernel, Binder profile boundary, WhatsApp process, LSPosed injection or physical
device acceptance is exercised here.

The generated shell's UID filtering, process exits, invalid records, missing status
files and glob rejection have their separate local-shell checks in
`qa/test_runtime_diagnostics.py`. Those checks use fake pidof and redirected status
files; they complement the framework identity checks.

Device acceptance still needs a manager and target installed in primary/work
profiles, actual process UIDs, successful and unavailable root probes, and checks
that the UI distinguishes unavailable evidence from a verified empty process set.
