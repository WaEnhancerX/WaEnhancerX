# Call privacy: explicit contact choices, membership and exact Context paths

## Problem and resulting behavior

The V38 incoming-call gate did not evaluate per-contact BlockCall rules when the
global mode was its default value, "0". The same policy treated a LID-to-phone
result as a complete JID, although ContactNameResolver returns phone digits.
Unknown-caller checks could also stop after a missing legacy table, skip the
system address-book fallback after a database error, or accept a push/business
display name as evidence that a number was saved.

CallPrivacyHook now considers the enabled custom-privacy option in its existing
call gate. In global mode "0" with call_privacy_enabled false, it additionally
requires the boolean BlockCallConfirmed marker on a BlockCall rule. The existing
Custom Privacy dialog in WhatsApp writes this marker only when the user saves
its visible choices, including Block Incoming Calls. Reading or cancelling the
dialog does not confirm a rule. Saving an explicit allow choice keeps calls
allowed. Rules already admitted by a non-default global mode or the existing
enable flag preserve their prior behavior.

This boundary matters because older Navigation saves could copy FreezeLastSeen
into BlockCall. Existing rules without a confirmation marker are preserved and
are not silently activated under the newly supported default mode. The manager
writer also preserves both call fields while editing unrelated contact privacy.
It neither creates a marker nor infers intent from historical values.

The call policy canonicalizes the raw caller identity, adds the verified phone
namespace when using a LID mapping, and preserves the existing raw-contact rule
precedence. Block-all returns before contact/database work.

Saved-contact queries use wa_contacts and wa_address_book independently. An
unmapped LID is never supplied as a phone number and missing identities are never
turned into empty query keys. Each membership query stops at one row. The
fallback asks the existing exact system-contact lookup instead of the generic
display-name resolver.

The database comes only from context.getDatabasePath("wa.db"). The decision no
longer probes fixed /data/data/com.whatsapp or /data/user/0 paths or constructs a
relative database path from a null filesDir. This establishes current-Context
routing; it does not independently establish WhatsApp's internal account-switch
contract.

## Executable checks

Run from the repository with Python 3, JDK 17+ and Kotlin 2 available:

    python3 qa/test_call_privacy_contacts.py
    python3 qa/test_call_privacy_logs.py
    python3 qa/test_contact_identity.py
    python3 qa/test_selected_upstream_hardening.py

To preserve compilation logs, scenario results, generated doubles and SQLite
fixtures, provide a new output directory:

    python3 qa/test_call_privacy_contacts.py --out /tmp/waex-call-contact-candidate

The fixture compiles the complete production CallPrivacyHook.java and
ContactIdentityPolicy.java. It also extracts and compiles the exact
CustomPrivacyHook.getContactPrivacy implementation and the existing dialog's
Save and Cancel callbacks in Kotlin. Those callbacks write to the same in-memory
preferences used by the complete call hook. The UI construction and selected
switch inputs are explicit adapters; the complete dialog is not compiled or
rendered by this fixture. JSON uses the hash-pinned AOSP Android 14 sources from
the existing bootstrap. Use --json-source-dir or WAEX_AOSP_JSON_SOURCE_DIR for an
offline source directory; absent default-cache entries use bounded, hash-checked
source downloads.

The incoming-call test installs the production
callback, passes synthetic CallInfo, and observes the intercepted result.
For an intercepted call, it also waits for that Context's notification endpoint
with a bounded latch. This lets the real asynchronous notification finish its
contact-name lookups before the next case resets shared counters. The production
executor remains unchanged, and the block-all decision still must perform zero
contact or database lookups. No arbitrary sleep or ignored assertion is used.
Production SQL is executed read-only against real file-backed Python SQLite.

The 40 scenarios cover explicit Save versus Cancel/read, ambiguous historical
BlockCall/FreezeLastSeen data, missing/false/non-boolean confirmation markers,
legacy enable/mode behavior, explicit allow, custom-privacy disable, outgoing
calls, block-all without lookups, invalid JIDs,
PN/LID namespace boundaries, explicit raw-rule precedence, legacy/new contact
tables independently, a corrupt or unavailable database, exact system-contact
fallback, false-positive display names, null filesDir, and distinct Context roots.
The fixture's HTTP boundary throws if unexpectedly invoked.

The existing identity suite separately compiles the complete production Kotlin
resolver/store and Java identity policy. It includes actual SQLite predicate
checks as well as controlled Android/provider boundaries.

## Negative controls

Run the same fixture against an immutable V38 checkout:

    python3 qa/test_call_privacy_contacts.py --source-root /path/to/v38 --out /tmp/waex-call-contact-v38 --allow-test-failure

--allow-test-failure is for negative controls only. Compilation and completion
of the scenario set are still required. Normal CI must omit this flag.

On the reviewed V38 snapshot, all 40 scenarios completed and 14 failed. The
candidate passed all 40. Nine compiling mutations were detected: dropping the
custom-privacy gate, using unqualified mapped digits as the contact JID, treating
a generic display name as saved-contact membership, and aborting on a missing
legacy table before checking the new one; removing the confirmation boundary,
imposing confirmation on previously enabled legacy rules, accepting a string
marker as boolean confirmation, omitting the marker from the real Save callback,
and performing an unnecessary contact lookup before the block-all return.
Compilation and all 40 scenarios completed for every mutation.

During final integration, the previous fixture exposed a race between a pending
notification and the next case's counter reset. A controlled notification pause
reproduced the race with unchanged production code. The per-Context completion
signal described above fixes that isolation defect. The same compiled candidate
then passed all 40 cases in six fresh JVM runs: two with default scheduling, two
with common-pool parallelism one (CompletableFuture's thread-per-task fallback),
and two with common-pool parallelism two. The new block-all mutation fails the
original zero-lookup assertion after this isolation fix.

## Runtime acceptance still required

These are controlled JVM/SQLite checks. Android Context, Xposed registration,
DexKit discovery, contact resolution, preference storage, dialog inputs,
notifications and app HTTP use explicit doubles. The per-contact reader and
dialog callback bodies are production fragments and JSONObject is the actual
Android implementation. The fixture does not execute native LSPosed injection,
real VoIP interception, Binder/profile routing, an app network request or a
signed APK.

On a supported WhatsApp build and an authorized test account, verify:

1. With global call privacy at Default and custom privacy enabled, save Block
   Incoming Calls from the existing Custom Privacy menu/tile in WhatsApp. The
   explicitly confirmed contact is blocked; an unconfigured contact and outgoing
   call retain their normal behavior. Historical unconfirmed rules stay dormant
   in this default mode until the user saves that explicit choice. Verify that
   a manager presence edit preserves both call fields and does not confirm them.
2. In Block unknown mode, a saved phone contact and its verified LID mapping are
   accepted. An unsaved contact with a visible business/push name stays unknown.
3. Exercise the observed legacy and new contact schemas separately. Simulate
   denied Contacts permission or unavailable wa.db and confirm that the remaining
   permitted source is considered.
4. Repeat on WhatsApp, Business and a secondary Android profile, with distinct
   address books. Confirm the database belongs to the selected Context.
5. Verify all existing rejection actions and notifications on device. Their host
   selectors and interception contracts were preserved, not newly validated by
   this change.

No end-to-end compatibility or measured latency improvement is claimed.
