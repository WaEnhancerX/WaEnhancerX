# Contributing to WAEX

Contributions to code, translations, documentation and reproducible test cases are
welcome. Follow the [Code of Conduct](CODE_OF_CONDUCT.md), inspect existing issues
and pull requests, and keep each contribution focused.

## Getting started

Fork the project, clone your fork, and create a topic branch based on current
upstream `main`:

```bash
git clone https://github.com/YOUR_GITHUB_USER/WaEnhancerX.git
cd WaEnhancerX
git remote add upstream https://github.com/WaEnhancerX/WaEnhancerX.git
git fetch upstream
git switch -c fix/my-change upstream/main
```

The public source and its standalone QA can be reviewed without access to the
private licensing module. Full Android builds have additional prerequisites.

## Development setup

The following values are declared by the checked-in build configuration:

| Component | Current declaration | Source |
|---|---|---|
| Android SDK | `compileSdk 36`, `targetSdk 36`, `minSdk 24` | [app/build.gradle.kts](app/build.gradle.kts) |
| Java | Source/target and Kotlin JVM toolchain 17 | [app/build.gradle.kts](app/build.gradle.kts) |
| Gradle distribution | 9.1.0 declared by the wrapper properties | [gradle-wrapper.properties](gradle/wrapper/gradle-wrapper.properties) |
| Android Gradle Plugin | 9.0.1 | [version catalog](gradle/libs.versions.toml) |
| Kotlin | Catalog entry 2.0.20 | [version catalog](gradle/libs.versions.toml) |
| Project modules | `:app`, `:licensing` | [settings.gradle.kts](settings.gradle.kts) |

Use Android Studio compatible with AGP 9.0.1; consult the
[official Studio/AGP compatibility table](https://developer.android.com/build/releases/about-agp).
Install SDK Platform 36 and the Build Tools required by
[AGP 9.0.1](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
(36.0.0). The distribution URL does not identify the bootstrap JAR version; wrapper artifact
validation is a separate build check.

The project opts out of AGP's new DSL and built-in Kotlin defaults in
[gradle.properties](gradle.properties). AGP has its own Kotlin Gradle plugin
runtime dependency, so the catalog value alone is not proof of the compiler
version resolved by a full build. Record the resolved toolchain in build evidence;
do not change those compatibility flags as an incidental documentation fix.

### Full APK builds

The `:licensing` project requires a separate, authorized checkout of
[`mubashardev/waex-licensing`](https://github.com/mubashardev/waex-licensing)
at the repository's `licensing/` path. Although [.gitmodules](.gitmodules)
declares that path and URL, the public revision documented here has no Git
submodule entry (gitlink) and therefore pins no licensing commit.
`git submodule update --init --recursive` alone does not obtain this module.
Builders who already have access can obtain its repository with:

```bash
git clone https://github.com/mubashardev/waex-licensing.git licensing
```

Before building, check out a licensing revision confirmed compatible with the
selected public app revision by the maintainers. Record both commit SHAs in the
build evidence; the private repository's latest default branch is not an
established compatibility pin. If a later public revision adds a gitlink, use and
record that pinned revision. The current public
[source QA workflow](.github/workflows/source-qa.yml) does not fetch or build
`licensing`.

Both Debug and Release use `releaseConfig`, which requires a keystore whose
certificate matches the fingerprint checked in `app/build.gradle.kts`. The
configuration accepts `androidStoreFile`, `androidStorePassword`,
`androidKeyAlias` and `androidKeyPassword` as Gradle properties or entries in local
`local.properties`. Configure those authorized values and the local Android SDK
without committing credentials. A missing module or signing key is a build
prerequisite failure; the public QA path below remains available.

With those prerequisites present:

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

The configured artifacts are:

- `app/build/outputs/apk/debug/WaEnhancerX-v<VERSION_NAME>_debug.apk`
- `app/build/outputs/apk/release/WaEnhancerX-v<VERSION_NAME>_release.apk`

`VERSION_NAME` and `VERSION_CODE` come from
[gradle.properties](gradle.properties). Release enables code minification and
resource shrinking using [proguard-rules.pro](app/proguard-rules.pro); Debug does
not enable those release settings. Verify dynamically resolved classes, methods
and resources in the optimized APK as well as Debug.

### Public-source QA

The [source QA workflow](.github/workflows/source-qa.yml) checks out public code
without submodules and uses Python 3.11, JDK 21 and a Kotlin CLI/compiler available
on the runner. Install `java`, `javac`, `python3` and `kotlinc` locally as needed by
the selected suite. Examples that do not require a full Gradle app build are:

```bash
python3 qa/check_syntax.py --root app/src/main/java
python3 qa/test_catalog_source.py
python3 qa/test_provider_scope.py
```

The workflow is the current inventory of required suites. Individual files in
`qa/` document their dependencies and scope. Some framework suites download real
Android/Compose/Robolectric dependencies; others compile selected production code
against explicit doubles. Report what executed and preserve failures. Source
parsing, a test with doubles and a framework fixture are distinct from compiling
or installing the complete app.

### Gradle unit and device tests

With the full build prerequisites present, run local unit tests using:

```bash
./gradlew :app:testDebugUnitTest
```

Tests currently checked into [app/src/test](app/src/test) are local unit tests.
`./gradlew test` does not execute device instrumentation tests. The device-task
form is `./gradlew :app:connectedDebugAndroidTest`, which requires suitable tests
and a connected emulator/device; this public tree does not currently contain an
`app/src/androidTest` test suite. See Android's
[command-line testing guide](https://developer.android.com/studio/test/command-line).
Standalone `qa/` Android framework fixtures are a separate path and do not establish
LSPosed/WhatsApp acceptance.

## Reporting issues

Search existing issues first. Include steps to reproduce, the affected setting,
WAEX version/commit and Debug or Release variant, exact target package/version,
Android version, and the loaded root/hook framework. State whether the problem
survives a target-process restart and whether the manager shows a preference,
compatibility or injection error. Share relevant redacted diagnostics; omit
private conversations, tokens and personal identifiers.

For a feature request, describe the intended user behavior and current limitation.
Discuss large changes before beginning work, especially when they require private
module changes, storage migrations or new host method selectors.

## Pull requests

1. Base the branch on current upstream `main` and inspect overlapping open PRs.
2. Explain the concrete problem, the resulting behavior and related issue numbers.
3. Identify the actual production paths changed and preserve existing settings and
   data formats unless the change includes a reviewed migration.
4. Run the relevant checks. Describe whether each check uses source inspection,
   compiled production code, doubles, real Android framework classes, an APK or a
   device. Include a failing baseline or focused negative control for a behavioral
   regression when practical.
5. Report remaining prerequisites and device acceptance honestly. A passing job
   whose feature step was skipped is not evidence that the feature ran.

Pull requests that target `nightly` are also checked by an automated gate and review.
Read [docs/NIGHTLY_CONTRIBUTOR_RULES.md](docs/NIGHTLY_CONTRIBUTOR_RULES.md) first; it
lists the rules (R1-R14) and explains how to resubmit a rejected PR.

Do not commit signing keys, account credentials, private licensing sources or
proprietary host APKs. Public reports can describe a verified host method contract
and sample identity without redistributing the host binary.

## Architecture and code guidelines

The implemented source map is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
`app` contains the manager UI, configuration, preferences, update code, bridge and
Xposed features; `licensing` is the required, separately obtained private module. There are no separate
`core-ui`, `feature-*` or `plugin-host` Gradle modules in this tree.

Use existing Compose/Material components for manager UI and the existing native
view helpers where host hooks require Android views. Keep persisted keys and types
consistent across the UI, schema, provider, bridge and hook. Confirm host method
arguments, return types and meaning before intercepting them. Keep setup failures
isolated and avoid work on hot UI paths that does not need to run there.

Follow the surrounding Java/Kotlin conventions. Any new architectural boundary or
plugin API should be proposed and implemented explicitly instead of being assumed
to exist because an earlier design document described it.

## Commit messages and security disclosures

Use descriptive conventional prefixes such as `fix:`, `feat:`, `test:`, `docs:`,
`refactor:` and `chore:`. For example:

```text
fix: preserve caller message flags during forwarding metadata updates
```

For a security vulnerability, follow [SECURITY.md](SECURITY.md) and report through
its confidential channel rather than including sensitive exploitation details in
a public issue.
