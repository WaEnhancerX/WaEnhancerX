# Lightweight verification scripts

These standalone tools do not need an Android device or full Gradle build. They are regression checks for selected invariants, **not** a substitute for Android/LSPosed integration testing.

| Script | Prerequisites | What it checks |
| --- | --- | --- |
| `test_bridge_contract.py` | Python 3, JDK 17+ | Compiles the real `PreferenceBridgeClient.java` against minimal Android/Xposed doubles; tests snapshot reads, type conversion, atomic edits, clear/remove and observer updates |
| `test_separate_groups_policy.py` | Python 3, JDK 17+ | Compiles the real pure-Java tab policy and checks 1,277 cases for order, idempotence, replacement and capacity fallback |
| `check_syntax.py` | Python 3, JDK 17+, Kotlin compiler JARs (`kotlinc` or `KOTLIN_HOME`) | Parses all Java/Kotlin sources for syntax errors, without resolving Android classes or linking |
| `audit_apks.py` | Python 3, locally supplied APKs | ZIP CRC and manifest checks; samples 18 literal DexKit anchors (not method or runtime verification) |

Run:

```sh
python3 qa/test_bridge_contract.py
python3 qa/test_separate_groups_policy.py
python3 qa/check_syntax.py
python3 qa/audit_apks.py /path/to/WAEX.apk /path/to/WhatsApp.apk
```

The APK scripts do not redistribute the user-provided or proprietary packages. To confirm behavior, also run Gradle, instrumentation and real-device tests in the supported environment.
