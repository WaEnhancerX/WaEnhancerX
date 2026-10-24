# Receipt stanza preservation and exact status routing

## Verified defect and bounded correction

The V39 receipt generator callback applied delivery privacy to every non-status
receipt node. A `retry`, `sender`, `read-self`, `hist_sync`, `peer_msg`,
`server-error`, played or unknown receipt could therefore acquire `type=inactive`
and lose `sts`. Read privacy also removed `sts` from ordinary delivery and control
receipts, even when their type was not `read`.

The callback now classifies the type before parsing recipient preferences. Only
an untyped ordinary delivery receipt or an explicit `read` receipt enters these
two privacy controls. Read privacy strips `type=read` and `sts` only from a read
receipt. Other types keep the original node, attribute array, values, children
and binary payload. Already-inactive and unknown types are preserved as well.
These early returns also avoid contact-privacy lookups and replacement attribute
arrays for unrelated receipt traffic; no device timing improvement is asserted.

Status routing now recognizes the exact `status@broadcast` address in either
recipient position. A numeric broadcast list remains an ordinary conversation:
stealth status viewing no longer suppresses its incoming receipt method or
marks its read job as a status job. Existing group routing, per-contact settings,
Ghost Mode precedence and exact recipient/message-ID grants remain covered.

The existing writer policy for message code 419 is unchanged. Code 89 and other
writer message codes retain their original fields and execution path. This
change does not infer an ACK contract from a numeric dispatcher code, alter
message sends, fabricate successful delivery, or modify injection/root policy.

## Executable evidence

```sh
# Requires JDK 17+ and the Kotlin compiler in PATH (or supply --kotlinc).
python3 qa/test_receipt_stanza_runtime.py --out /tmp/waex-receipt-stanzas
python3 qa/mutate_receipt_stanzas.py --out /tmp/waex-receipt-mutations
python3 qa/test_receipt_job_execution_contract.py
python3 qa/test_blue_receipt_recipient_isolation.py
```

`test_receipt_stanza_runtime.py` compiles the complete production
`HideReceiptsHook.java`, `BaseFeature.kt` and six receipt policies. It executes
the production reflection bootstrap, hook registration, callbacks, attribute
transformations, status predicates and explicit allowance decisions. The fixture
supplies explicit Android, Xposed, positive DEX cache, contact preference and
BlueOnReply capture boundaries. Native DEX matching and WhatsApp are not run.

The same final fixture produces the following results:

| Source | Scenarios | Checks reached | Failed scenarios |
| --- | ---: | ---: | ---: |
| V39 baseline (`66559468`, hook SHA-256 `e7c5c7bc00b3b4335a9a668928b7125092a3d8d1b92bc8cc6a9d4d7430a441a8`) | 88 | 358 | 61 |
| Corrected callback | 88 | 595 | 0 |

Checks stop within a failed scenario, explaining the different check totals.
The baseline failures comprise 55 control-type/preference combinations, two
ordinary delivery timestamp cases and four status/broadcast classification cases.
This is a reproducible transformation/routing defect; it does not identify the
cause of a particular device's pending-message report.

Five isolated production mutations must fail at their intended runtime invariant:

1. Re-enable transformation of unrelated receipt types.
2. Remove the read-type condition from timestamp stripping.
3. Treat the broadcast namespace as status again.
4. Disable requested read privacy.
5. Let an explicit read grant override delivery privacy and Ghost Mode.

All five are rejected by runtime assertions. Compilation failure is not accepted
as detection. The existing exact-recipient/property suite and verified read-job
execution suite also pass; the latter reaches 56 assertions with its extracted
resolver/installer and the production execution policy.

## Primary implementation references and limits

The public Baileys implementation independently distinguishes these receipt
roles. Its [retry sender](https://github.com/WhiskeySockets/Baileys/blob/master/src/Socket/messages-recv.ts)
constructs a `retry` receipt during recovery; its
[media retry builder](https://github.com/WhiskeySockets/Baileys/blob/master/src/Utils/messages-media.ts)
constructs `server-error` for requesting media re-upload. Its
[generic receipt sender](https://github.com/WhiskeySockets/Baileys/blob/master/src/Socket/messages-send.ts)
routes `sender` separately and distinguishes `read` from `read-self`;
the [receipt type declaration](https://github.com/WhiskeySockets/Baileys/blob/master/src/Types/Message.ts)
also names synchronization and played types. These sources were consulted on
2026-10-20 as corroborating implementation evidence, not as an Android host API
or proof that a specific obfuscated method builds every one of those types.

The fixture assumes the cached ProtocolTreeNode/KeyValue reflection mapping is
valid, as the existing production resolver expects. A host APK, native matching,
real Android/LSPosed injection, peer delivery, encrypted recovery and media
transfers remain outside this JVM result. No improvement in WhatsApp cold-start
latency, bitrate, HD quality or real message-queue completion is claimed here.

## Device acceptance still required

Use two consenting test accounts with a verified target APK/version and an
injected runtime report. Exercise ordinary text, image and video sends with
read hiding, delivery hiding, their combination and Ghost Mode. Check the
receiving account's actual delivery/read state and confirm later outgoing text
continues to leave the pending state. Repeat while the recipient is offline and
after reconnecting, and across a numeric broadcast list, group and ordinary chat.

Verify exact manual-read grants disclose only the selected recipient/message
IDs, do not override delivery hiding or Ghost Mode, and are revoked after failed
enqueue. Verify status privacy against actual status views, including per-contact
exceptions. Preserve the real job/ACK outcome when inspecting failures; a visual
tick or absent exception alone is insufficient acceptance evidence.
