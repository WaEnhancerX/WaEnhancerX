# Phone or WhatsApp call selection

The public `call_type_controller` Boolean option means **phone or WhatsApp**.
Its existing description is “Show an option to select whether you want a call
via phone or WhatsApp”. Voice/video selection remains the host's own choice.

## Installed behavior

Enable the existing free option and restart WhatsApp. The existing preference
restart tracker already covers this catalog/public setting. A host process that
starts with the option disabled performs no selector discovery or registration.
Once installed, disabling it cancels a pending lookup or visible selector.

The selector intercepts the two outgoing `void(boolean, boolean, boolean)`
conversation operations reached by both the classic menu and the newer native
call popup. WhatsApp's own routing decides voice/video and all three original
arguments before the selector takes ownership. Choosing **WhatsApp** invokes
that exact original operation once. The original call menus and their return
values are unchanged.

Choosing **Phone** opens an `ACTION_DIAL` intent containing the verified phone
number. The user still confirms a phone call in the dialer. Choosing **Cancel**,
back, or dismiss never invokes either destination. If a dialer cannot be opened,
the selector leaves WhatsApp and Cancel available; it never changes a failed
Phone choice into an automatic WhatsApp call.

The recipient comes from the live conversation owner's typed `getChatJid()`.
An explicit `@s.whatsapp.net` peer supplies a phone number. An explicit `@lid`
requires the existing read-only, current-package `jid_map` lookup to return one
unambiguous phone identity. The lookup runs outside the main thread with one
worker, one queue slot and a 1.5 second deadline. Missing, ambiguous, invalid,
failed or timed-out mappings continue the original WhatsApp operation only while
the same owner, Activity, resumed lifecycle and peer are still current. A late
lookup cannot reopen a canceled selector or call a different chat.

Groups and unsupported identities use WhatsApp's original behavior. The host's
“Add to call”, call-link and scheduling operations remain outside these two
interception sites.

## Host contract checked before installation

The two exact executable-string anchors must resolve one contact-menu owner and
one popup dispatcher. Their outgoing interface calls must be the same two
`void(boolean, boolean, boolean)` methods, and the classic menu must open the
same popup owner. Both the named Conversation Activity and the named
ConversationFragment's unique View adapter must implement those operations.

Installation additionally requires the named current-peer getter, a concrete
typed JID string getter, a unique lifecycle state getter with the five standard
enum states, and authoritative Fragment ownership. The adapter is usable only
while it is the Fragment's current adapter and is attached, visible and resumed.
Retaining an Activity context after Fragment detachment does not preserve a call
request. Every asynchronous choice rechecks ownership and peer identity.

All four sites install together. A partial registration failure rolls back the
registered sites and listeners. Any callback retained by a failed framework
cleanup stays disabled, and that installation cannot be reactivated by retrying.
Missing or ambiguous host contracts leave the original behavior intact.

The static preflight used the locally supplied
`01-WhatsApp_2.26.40.76_antisplit.apk`, SHA-256
`593d1216e8f3f24303c42bb1de88ab40060128a9030c1e0e44315c9909e258a4`.
All 13 DEX files and 108,326 class definitions were inspected for the two anchors.
The unique owners were `X.8eO` (contact menu) and `X.8eP` (popup dispatcher);
both invoke `X.1mo.Bbe(ZZZ)V` and `X.1mo.Bbh(ZZZ)V`. The concrete owners are
`com.whatsapp.Conversation` and `X.2Se`. The named ConversationFragment directly
constructs `X.2Se` and stores it in its adapter field. These are audit identities,
not hardcoded obfuscated names in the implementation. No host APK or method
body is included in this repository.

## Reproducible Android fixture

Requirements: JDK 17+, a Kotlin compiler, Python 3 and Maven. The first run
downloads public pinned dependencies; no Android SDK, private licensing checkout,
signing material or host APK is needed for this fixture.

```sh
python3 qa/android_call_type_test.py --out /tmp/waex-call-type-qa \
  --maven-repo /tmp/waex-qa-maven
```

DexKit 2.2.0 is downloaded from Maven Central with a 30 second request timeout,
4 MiB archive cap and pinned AAR/JAR SHA-256 checks. Only `classes.jar` is
extracted. Native libraries are not extracted or loaded by the bootstrap.
For an offline run, provide `--dexkit /path/to/dexkit-2.2.0.jar`, an existing
`--deps` directory and `--sdk-jars` containing the instrumented Android 24/28/34
JARs. The runner records source and dependency identities in its output.

The 29 fixture cases run on each of Android 24, 28 and 34: **87 executions**.
They compile the entire production selector, host contract, DexSearchEngine,
DexCacheManager, exact phone mapper and their source dependencies. They use
real Robolectric Android Activity/View, dialog, Looper, Intent and SQLite APIs.
The host class shapes and Xposed registration/original invocation are explicit
fixtures. The fixture Activity records phone intents instead of launching an
external Activity or making a call.

Coverage includes all voice argument combinations, both concrete owners and
both original operations, direct phone and exact/ambiguous/missing LID mappings,
wrong preference types, disabled startup, canceled and stale choices, Activity
pause, Fragment detachment with a retained Activity, adapter replacement,
repeated events, a slow lookup and late completion, a bounded queue, failed
original operations and partial installation rollback. Three production mutants
(ACTION_CALL, removed peer comparison, and an armed failed installation) each
compile but fail the relevant case on all three Android versions.

Validate the resource table separately:

```sh
python3 qa/test_es419_localization.py
python3 qa/verify_es419_resources.py --aapt2 /path/to/real/aapt2 \
  --out /tmp/waex-call-type-resources
```

The five dialog strings exist in default, Spanish and Latin American Spanish
resources. AAPT2 links their actual module resource IDs. The Android fixture
uses generated compile-only R IDs and exercises UiText's fallback; it does not
claim to exercise installed module-resource lookup.

## Remaining device acceptance

These checks establish source/API compatibility and fixture behavior. A native
DexKit match with the host ClassLoader, full module build, LSPosed injection,
preference IPC and the actual WhatsApp UI have not been executed by this fixture.
The modeled Fragment uses Android's platform Fragment; the inspected host uses
AndroidX and its own lifecycle classes. Static host inspection and the reflection
contract support that boundary, but do not replace an injected device run.

On a supported device, verify classic and modern outgoing call menus with a
test contact; both host voice/video selections; an explicit phone JID; a LID
with and without a verified mapping; group and active-call native behavior;
Phone/WhatsApp/Cancel and missing-dialer behavior; navigation, rotation and
backgrounding while a selector or lookup is pending; and disabling/re-enabling
the setting with the documented restart behavior. Repeat with the supported
release and debug module artifacts. Do not infer acceptance for a different
WhatsApp version, Business build or clone from this one static APK contract.
