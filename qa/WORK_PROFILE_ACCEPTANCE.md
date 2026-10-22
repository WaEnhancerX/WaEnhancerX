# Manager operations in Android work profiles

## Confirmed failure and correction

The manager previously used an unqualified "am force-stop" followed by Monkey.
AOSP's ActivityManager shell initializes an unqualified force-stop to USER_ALL.
Monkey discovers launcher activities for the foreground user. A work profile
has a separate Android user ID even while its parent remains in the foreground.
The old stop and launch operations therefore did not name the same installation.
The root updater also hardcoded "--user 0" independently of the manager's profile.

AndroidUserScope derives the user ID from the manager Context's application UID,
using AOSP's documented "uid = 100000 * userId + appId" relationship. The process
UID must belong to the same user. Negative UIDs and Android's special all/current
user selectors are rejected.

Before stopping a supported package, AppRestartHelper resolves its launcher with
that same Context's PackageManager and checks the component package, installed
package name and application UID. Both privileged restart commands name that
user explicitly. The launch names the validated component through "am start -W";
only a complete "Status: ok" line on a successful command counts as an acknowledged
launch. Missing packages, foreign components/UIDs and invalid component names
fail before force-stop. Root denial and launch failure keep the existing local
Context fallback; an already stopped app does not receive a subsequent self-kill
broadcast. Bulk restart follows the same target validation and checks the launch
acknowledgement. A denied root stop uses a target-scoped restart broadcast and the
verified normal launcher Intent. If root already stopped the target but its
launch fails or is unacknowledged, bulk restart uses the normal Intent without
sending another kill broadcast. A broadcast failure does not prevent that
target's normal launch or the next verified target. Rejected main-thread
scheduling releases the shared pending-request guard.

The root updater chooses that same explicit user instead of user 0. This selects
the installation user; it does not create independent APK versions across users
or override Android's package-update semantics. Package code updates may also
affect users where the same package is already installed. Existing APK identity,
version, copy-size and temporary-file checks remain active.

This change concerns the destination of manager operations. It does not prove
that the entire reported 1.7-to-2.0 work-profile injection regression is fixed.

## Framework regression runner

    python3 qa/android_work_profile_test.py \
      --out /tmp/waex-work-profile-framework \
      --maven-repo /tmp/waex-work-profile-m2

Use a new output directory and provide JDK 17 or later, javac, Kotlin and Maven.
The runner pins Robolectric 4.17, JUnit 4.13.2 and Android compilation classes
from android-all:14-robolectric-10818077. It uses the project's pinned OkHttp and
Kotlin versions. Dependency resolution uses the shared bounded Maven helper.
CI runs this as a non-optional job and uploads JSON identities/results and compiler,
dependency and JUnit logs even if the test command fails.

For an existing dependency cache and flat offline SDK directory:

    python3 qa/android_work_profile_test.py \
      --out /tmp/waex-work-profile-offline \
      --deps /path/to/robolectric-and-android-jars \
      --extra-deps /path/to/okhttp-and-okio-jars \
      --sdk-jars /path/to/android-all-instrumented

The complete production AppRestartHelper, RestartTargetPolicy, UpdateDownloader,
UpdateTransferPolicy and candidate AndroidUserScope files are compiled unchanged.
The runner copies only the exact one-line normalizeVersion function from
ReleaseRepository, recording that source file's identity separately. It does
not claim to compile the complete release repository.

Twenty-nine scenarios run on API 24, 28 and 34: exactly 87 executions. They cover
owner user 0, work-profile user 10, secondary user 11, a supported clone, a launcher
class from another Java namespace, unavailable packages, invalid target names and
components, negative/foreign Context UIDs, foreign target UIDs, root denial,
rejected/timed-out/malformed launch results, duplicate requests, bulk restart,
three updater users, invalid APK/version, foreign updater Context and failed copy.
The four additional bulk scenarios cover denied root stop, failed launch after a
successful stop, an unacknowledged launch, and an isolated restart-broadcast
failure while subsequent verified targets continue.

Android Context, Activity, Intent, PackageManager, Handler and Toast run under
Robolectric. Profile UIDs and package/archive metadata are controlled fixtures.
Two invalid-component scenarios override only getLaunchIntentForPackage; other
framework calls delegate directly. Negative installer assertions join the actual
production worker thread before inspecting its commands. RootCommandRunner is
an explicit endpoint fixture that records arguments and never invokes its
subprocess starter. The test's ZIP is not a generated or signed Android APK.
The fixture's install response is a deliberate failure so no self-termination
path runs. Bulk manager termination is also excluded from the main-looper drain.
Display localization, scope lookup and the normal external installer are adapters.
Real OkHttp classes are used, but no HTTP request is made.

The current V39 candidate passes 87/87. The identical 87-case fixture against
integration V38 (`5cd69d8e7fbad0379f417134cbb2ce289dfa3baa`) produces 12 assertion
failures from the four added bulk scenarios. That negative control uses
"--expect-assertion-failures 12"; the default requires all 87 cases to pass.
Compilation errors, unexpected exceptions, zero tests and different failure
counts cannot satisfy that control.

The earlier profile-scoping comparison used 25 scenarios across the three SDKs,
before those four bulk scenarios were added. Its candidate passed 75/75; the
same earlier fixture against a565e1a1a50ebfe5d633e8cd0d43c6dc6025a8ac completed
75 cases with 39 assertion failures. The corresponding expected count of 39
belongs to that historical fixture, not the current 87-case gate.

On that earlier 75-case fixture, six isolated negative controls reintroduced one
regression each: hardcoded stop
user 0 (12 failures), hardcoded launch user 0 (12), hardcoded installer user 0 (6),
removed target-UID check (3), removed Context/process profile check (3) and loose
launch acknowledgement (3). These are repeated executions of the same scenarios,
not additional feature-coverage claims. Source, fixture and dependency hashes
are retained with the audit results.

## Device acceptance still required

Use an authorized test device with an enabled, unlocked work profile. Record
Android/OEM version, profile ID, host APK identity, WAEX APK identity and LSPosed/
Vector version. Confirm that the module and its provider are available and enabled
for the relevant profile and that the selected WhatsApp package is in that
profile's module scope. Do not assume that enabling the personal instance proves
injection or provider availability for the work instance.

1. With the same WhatsApp package installed in personal and work profiles, record
   both processes with their UIDs. Restart from the work manager and verify only
   the work instance is stopped and relaunched. Repeat from the personal manager.
   A process name or an unscoped pidof result alone does not identify a profile.
2. Repeat the bulk restart with supported WhatsApp and Business/clone packages
   installed in different combinations. Only valid targets present in the
   initiating manager's profile may be stopped. Verify the manager relaunch too.
3. Deny root, disable or remove the launcher in a disposable test installation,
   and make the work profile unavailable. Verify a failed target lookup does not
   redirect to another profile and that a later valid retry works.
4. For a normal authorized update, verify the package install operation selects
   the manager's user and the package installer still enforces its signature and
   policy checks. Observe both profiles for Android's shared package-code update
   behavior. Do not interpret "--user" as version isolation.
5. Separately verify provider IPC, preference persistence, runtime-health reports,
   hook installation and actual feature behavior in each profile. Include both
   release and debug artifacts; successful process launch is not hook verification.

No work profile was created, no real APK was installed, no su/am/pm command was
executed and no LSPosed or physical-device test was performed by this runner.
No startup-time, FPS, memory or universal feature-performance gain is claimed.

## Primary platform contracts

- [AOSP managed profiles: UID construction, separate data and profile boundaries](https://source.android.com/docs/devices/admin/managed-profiles)
- [AOSP ActivityManager shell: force-stop user default and explicit activity start](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/services/core/java/com/android/server/am/ActivityManagerShellCommand.java)
- [AOSP Monkey: launcher discovery uses the foreground user](https://github.com/aosp-mirror/platform_development/blob/master/cmds/monkey/src/com/android/commands/monkey/Monkey.java)
- [AOSP multi-user behavior](https://source.android.com/docs/devices/admin/multi-user)
- [AOSP multi-user testing: user-scoped installation and process storage paths](https://source.android.com/docs/devices/admin/multi-user-testing)

## V39: complete the bulk restart fallback

Bulk restart now checks the activity-launch acknowledgement after a successful
root stop. A denied stop uses the target-scoped restart broadcast followed by a
normal verified launcher Intent; a failed or unacknowledged root launch uses the
normal Intent without sending a second kill broadcast. Failure to broadcast to
one target does not prevent its normal launch or the next verified target.
Rejected main-thread scheduling releases the shared pending-request guard.

The complete helper and root-update sources pass 87 Robolectric executions on
API 24, 28 and 34. The identical fixture against V38 executes all 87 and detects
12 assertion failures from the four new bulk cases across the three SDKs.
`qa/test_restart_safety.py` additionally covers rejected scheduling and release
of the duplicate-request guard. Context/UID/package metadata and privileged
endpoints are controlled. The framework does not create an Android work profile,
run `su`/`am`/`pm`, install an APK or terminate the manager process.
