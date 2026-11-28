# Hook-report request concurrency

`python3 qa/test_target_hook_sync.py` compiles the complete production
`TargetAppManager.kt` and runs eleven cases in separate JVMs. The existing
`qa/test_runtime_diagnostics.py` entry point invokes this suite, so its current
Source QA workflow invocation also requires these checks.

Requirements: Python 3.11+, Java 17+, and a Kotlin CLI distribution compatible with
the project sources. By default the suite uses that distribution's coroutines and
standard-library JARs and records their SHA-256 hashes. To use the exact dependency
versions resolved for the app, provide them explicitly:

```text
python3 qa/test_target_hook_sync.py --out /new/receipt/directory \
  --kotlin-home /path/to/kotlinc \
  --coroutines-jar /path/to/kotlinx-coroutines-core-jvm-1.10.2.jar \
  --stdlib-jar /path/to/kotlin-stdlib-2.1.0.jar
```

The output directory must not exist. It retains the compiled input copies,
compiler output, per-case output and a JSON receipt. Without `--out`, temporary
files are removed after the run and the result is printed. The suite downloads
nothing. The same overrides are available as `WAEX_QA_KOTLIN_HOME`,
`WAEX_QA_COROUTINES_JAR` and `WAEX_QA_KOTLIN_STDLIB_JAR` when called through the
existing runtime entry point.

## Behavioral coverage

- A single request and two overlapping requests, both with a response and with
  the unchanged 2500 ms production timeout.
- Partial overlap and disjoint batches: both reach the broadcast boundary before
  either is released, and completing one preserves the other owner's claim.
- Timeout followed by retry, preserving another target's timeout state.
- Cancellation followed by retry, duplicate input, empty input, installed-only
  default selection and a broadcast exception.

The overlap fixture supplies valid two-element collections. Both iterators wait
at their end; the test requires both IO jobs to arrive with an empty in-flight
set before release. The old separate filter/assignment then sends two requests
per package and fails with `DUPLICATE_SYNC_BROADCAST`. `--source-root` can point
at a pristine baseline checkout to reproduce that failure. The suite never
converts a compilation error into a successful negative control.

Coroutines, scheduling, delays and the manager source are real JVM execution.
Context, preferences, package discovery and root are explicit counting doubles;
SystemClock delegates to the monotonic JVM clock. Reflection only obtains the
manager's coroutine scope to await jobs and test cancellation. No Android IPC,
root command or external broadcast runs. These checks do not establish Android,
WhatsApp, hook-framework or full-APK acceptance.
