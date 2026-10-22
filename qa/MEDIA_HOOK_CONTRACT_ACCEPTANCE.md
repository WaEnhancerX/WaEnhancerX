# Media, view-once and descriptor cache acceptance

## What the patch establishes

The complete production `AntiViewOnceHook` now binds a unique state getter/setter
interface reached from a view-once SQL caller. It resolves the exact setter name,
including inherited implementations, and registers each concrete message setter
once. Discovery examines all matching SQL callers. A direct `view_once` match is
accepted only as a unique concrete instance setter per verified message type.

An immutable primary message key, read by the exact sender-method anchor,
establishes direction. State `1` changes to `0` only for an incoming message.
Outgoing media, null or ambiguous keys, other state values, unrelated integer
setters and non-message interface implementations retain host behavior. The
supported identity shapes use the `com.whatsapp.infra.core.jid` or legacy
`com.whatsapp.jid` namespace; actual target-build discovery still requires device
acceptance. No message content or identifier is logged by these hooks.

`MediaQualityBypassHook` accepts a unique valid method for each old image/FPS
anchor, and starts a new cache-key generation so a previous first-result choice
cannot bypass the new selection rule. JPEG quality changes only inside `0..100`.
An FPS result changes to `60` only if the host successfully returned a positive
integer below `60`; exceptions, nulls, zero, negative sentinels and higher values
remain intact. This hook adjusts a hint; it cannot create frames absent from the
input or establish codec, bitrate or resolution support.

For the separately identified public host family, the HD editor predicate must
contain exactly `enable_media_quality_tool` or `show_media_quality_toggle`, return
primitive `boolean`, and identify one concrete method. Ambiguous and absent
contracts are skipped. Its successful boolean result may be enabled while either
existing quality preference is on. The host still owns the editor and selection.
This adds no overlay and does not infer editor availability from a gallery image.

Installation retains the existing preference-change contract: callbacks recheck
the settings, including activation after installation with settings initially off.
This patch does not defer installation or silently require a new restart for
these controls. Tests use mutable SharedPreferences doubles; bridge delivery and
host process reload are separate acceptance concerns.

When either media-quality setting or either view-once preference alias is enabled,
the corresponding hook must report an unavailable contract if it has registered
zero callbacks. The exception has a fixed generic message. The complete registry
then records `FAILED` instead of accepting a successful return as `ACTIVE`. A
missing bridge, absent/ambiguous contracts, discovery errors and unsuccessful
Xposed registrations follow this rule. No failure is forced while the settings
are off, and registration still occurs while off when a valid method is available.

A nonempty installed-method set remains usable when some contracts are missing or
later discovery fails. `ACTIVE` therefore confirms the feature-level installation
result, not every media suboption or message subtype. In particular, a working
image callback does not prove the FPS callback exists. Live preferences continue
to affect the callbacks that were registered; an enabled preference cannot create
an absent host contract. This diagnostic change does not adjust selectors, codecs,
cache generations or the preference delivery mechanism.

`DexCacheManager` now admits persisted descriptors only for the current host
version and compiled module version code/name plus a descriptor schema epoch.
This uses the module's generated `BuildConfig`, so validating the module does not
query its package from WhatsApp or another Android profile. A changed generation
invalidates once; unchanged launches reuse persisted descriptors. If host identity
cannot be validated, fresh results remain in memory and unvalidated disk entries
are neither reused nor overwritten. The bounded negative cooldown is retained.

Increase `DESCRIPTOR_SCHEMA_VERSION` when incompatible cache/selector semantics
ship without a distinct module version. Generation metadata is not a timestamp:
normal launches must not invalidate it.

## Reproduction and evidence boundaries

| Gate | What executes | What it does not establish |
| --- | --- | --- |
| `test_media_hook_contracts.py` | Complete production media/view-once hooks, ownership helper and FeatureRegistry; reflection on fixture host classes, real fixture method invocation between Xposed callbacks; unrelated hooks/private install entry points are explicit doubles | Native DexKit matching, WhatsApp, codec output, Android UI, private feature implementations or LSPosed |
| `compile_media_dexkit_api.py` | Complete production media/view-once classes type-checked against unmodified DexKit 2.2.0 classes.jar and Kotlin stdlib | Native scanning or a full Android module build |
| `test_dex_negative_cache.py` | Complete production cache classes, Android/Xposed doubles, separate JVM processes sharing a real temporary preferences file; module versions are compiled final constants | Android process injection, kernel Binder, device storage fault behavior |
| Existing Media Hub/return-type/log gates | Production preference policy and manager, declared schema, independent signature and logging controls | Successful hook injection or output media quality |

The initial media hook baseline is commit `66559468acc0b2f44a1c319ee98f9ed0be27157a`.
The original 60 behavioral assertions produced 30 baseline failures and zero candidate
failures for both independently generated JID namespace families; ten focused
production mutants were rejected. Those historical runs remain separate from the
subsequent availability-diagnostic follow-up.

For that follow-up, a snapshot immediately before the new diagnostic checks and
the final candidate use the same expanded 200 assertions per JID family. The
snapshot fails 56 checks; the candidate passes all 200. All original host-behavior
assertions remain, including live preference changes. The additional scenarios
exercise the complete registry's real factory, `initializeAll` and `hasFailures`
for missing contracts, both preference aliases, absent bridges, private discovery
errors, rejected registrations, settings off, and working partial registrations.
The fixture filters unrelated registry entries before initialization; their hook
doubles throw if accidentally executed. Native dialogs and private static install
entry points are declared dependencies, not tested implementations.

The mutant runner includes six further controls: omit each unavailable-contract
signal, report failure while off, or reject a working partial registration. A
completed fixture RESULT plus behavioral assertion failures is required; compiler
errors and incomplete runs do not count as rejected mutants.

In the initial duplicate-SQL fixture, registrations fall
from 12 to 2 and callbacks per photo setter from 4 to 1. Both initial fixture runs
issue three discovery queries. These are operation counts, not device startup or
frame-time measurements.

The cache baseline preserves old class, method and field descriptors that still
resolve after a module update and skips the new resolver. The candidate resolves
each kind once after a module version change and resolves none on an unchanged
restart. API 24/25/27 fixtures omit `getLongVersionCode` entirely; API 28 uses a
long version with nonzero upper bits. Failed identity lookup checks also verify
that fresh memory results do not poison old disk metadata. Four focused cache
mutants cover ignoring module identity, ignoring version-name changes, clearing
on every launch and admitting unvalidated disk entries.

## Commands

Run with JDK 17 or newer on `PATH`:

```sh
python3 qa/test_media_hook_contracts.py
python3 qa/test_media_hook_contracts.py --jid-family legacy
python3 qa/test_media_hook_contracts.py --suite status
python3 qa/mutate_media_hook_contracts.py --evidence /tmp/waex-media-mutants
python3 qa/test_media_preference_sync.py
python3 qa/test_hook_return_types.py
python3 qa/test_private_hook_logs.py
python3 qa/test_dex_negative_cache.py
python3 qa/mutate_dex_cache_generation.py --evidence /tmp/waex-cache-mutants
python3 qa/compile_media_dexkit_api.py \
  --dexkit-jar /absolute/path/to/dexkit-2.2.0.jar \
  --kotlin-stdlib /absolute/path/to/kotlin-stdlib.jar
```

The hook runner accepts `--source-root` for an unchanged checkout. The cache
runner accepts `--baseline-manager /absolute/path/to/DexCacheManager.java` and
`--generation-only`; baseline source must remain unchanged. Three framework
runners that compile the complete cache also declare a compile-time BuildConfig
fixture: call type, IG status view and preserved-message dispatch. Their existing
Android behavior and test expectations are unchanged.

## Reports still requiring actual-host acceptance

The supplied gallery screenshot is the preselection **Add status / Recents**
screen. It cannot demonstrate whether an HD action exists after media selection.
The supplied Media Hub screen shows the aggregate HD control on; an enabled
preference does not prove injection or a matching host quality pipeline.

The current public implementation has no verified video-resolution, bitrate,
single-transcode or size-limit contract. It therefore cannot claim a fix for
blurry encoded videos or restore a 90 MB control from these tests. A public
upstream implementation supplies additional property IDs and ordered field
mapping, but copying those without a verified host mapping can change unrelated
fields. No such writes, global bitmap safety bypass, upload-size guess or private
feature implementation is added here.

Device acceptance should inspect the editor after selecting authorized test
media, verify the actual quality settings and output dimensions/bitrate, and test
incoming/outgoing view-once state on the exact supported WhatsApp build. No
actual message, media upload, call, reaction or private attachment transmission
was performed by the JVM/API gates. In particular, these results do not certify
WhatsApp `2.26.40.76` or the user's current installation.

## Public reference contracts

The public Dev4Mod/WaEnhancer source at immutable commit
`f4a8da1eb4bb5cf9672983909efdf337269369ec` was inspected for the original ownership
guard and named HD-predicate family:

- `app/src/main/java/com/wmods/wppenhacer/xposed/features/privacy/ViewOnce.kt`
- `app/src/main/java/com/wmods/wppenhacer/xposed/features/media/MediaQuality.kt`
- `app/src/main/java/com/wmods/wppenhacer/xposed/core/devkit/Unobfuscator.kt`,
  `loadViewOnceMethod`, `loadMediaQualitySelectionMethod` and quality/field helpers.

The reference itself is not host-build evidence. The candidate deliberately adds
uniqueness, direction and failure-preservation controls beyond those reference
selectors, with independently declared fixture metadata and real API compilation.
