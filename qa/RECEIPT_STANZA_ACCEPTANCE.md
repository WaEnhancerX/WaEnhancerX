# Receipt privacy, protocol controls, and explicit consent — current-main reconciliation

Base reviewed: 4750124e36223dd21d8535c14f2fb658025d8480. This updates PR #105 without replacing upstream's newer recipe, typed-JID or lifecycle implementations.

## Production contract

Only an exact read receipt and the ordinary no-type delivery receipt enter their respective privacy transformations. Preserve retry, sender, played, read-self, empty and unknown explicit types unchanged. Never null the protocol-tree object or change unrelated attributes/payload. Status is the exact status@broadcast identity, not a numeric broadcast-list JID.

Viewed/handled database history is not explicit read consent. Current BlueOnReplyHook can mark a recipe handled when a bounded queue evicts it, without sending it. The stanza path therefore no longer grants a read exception from that history. Only the bounded exact-recipient/message-ID allowance can authorize a read, and not while Hide Delivered or Ghost Mode applies.

The actual BlueOnReply sender attaches an immutable action-time snapshot of its exact recipient and authorized constructor message IDs before enqueue. At verified execution, a tagged job refreshes only that original scope, requires matching serialized recipient and policy target, and respects current delivery/Ghost privacy. Arbitrary String[] fields, a different participant, later job-array mutations and a bare manual marker cannot enlarge consent. Missing IDs are not blanket permission. Manual status handling remains independent and unchanged. An enqueue acknowledgement is not proof of delivery or reading on another phone.

Read execution uses the current exact receipt literal scoped to the resolved job or its direct superclass, requires a unique concrete public instance void() method, revalidates cached results and checks callback identity/receiver/arguments. Selector key v2 separates the stricter contract from old descriptors. Do not restore blanket zero-argument hooks or the old additional status literal without real-host evidence.

## Reproduce

    python3 qa/test_receipt_stanza_runtime.py --out /tmp/waex-stanzas
    python3 qa/mutate_receipt_stanzas.py --out /tmp/waex-stanza-mutants
    python3 qa/test_manual_receipt_grant.py --out /tmp/waex-manual-grant
    python3 qa/mutate_manual_receipt_grant.py --out /tmp/waex-manual-grant-mutants
    python3 qa/test_receipt_job_execution_contract.py
    python3 qa/test_played_receipt_dispatch_contract.py
    python3 qa/test_blue_receipt_recipient_isolation.py
    python3 qa/test_receipt_observation.py

Use JDK 17+ and a complete Kotlin distribution; --kotlinc accepts its launcher. Compilation invokes the compiler JVM, not a fragile batch classpath. DexKit 2.2.0 classes.jar and its downloaded AAR are SHA256-checked. WAEX_QA_DEXKIT_JAR may select an already verified local cache; its bytes are still checked.

The full HideReceiptsHook, BaseFeature, HookRegistrar and ViewHookDispatcher plus policy classes compile. Host classes, Android, Xposed dispatch, DEX search results and four unrelated services are explicit test boundaries. Real DexKit API types compile, but native matching is not executed. HideSeenStore and BlueOnReply service seams do not prove actual SQLite or host queue behavior.

## Current measured QA

- Final full-hook suite: 101 scenarios, 699 checks, 0 failures. The runner requires all 101 completed scenarios; a truncated or missing summary is not PASS.
- Before the final immutable-grant additions, the same 98-case suite on completely unchanged official current main produced 69 failed scenarios after compilation, not missing dependencies. Do not conflate this historical comparison with the later 101-case matrix.
- Intermediate reconciliation before the consent fix produced 5 failing consent scenarios in that 98-case oracle. That partial experiment is retained, not presented as the final candidate.
- Seven compiling production mutations are rejected at their named assertions: history treated as consent, manual chat bypassing delivery, control-stanza rewrite, delivery timestamp stripped by read-only privacy, broadcast-list misclassification, disabled read privacy, and recipient-grant precedence.
- The exact production manual sender and immutable grant/store policies pass 26 additional checks with explicit host queue/Xposed/initial-allowance boundaries. Four compiling mutations are rejected: omitted token, participant widening, an unapproved ID, and missing delivery veto. This complements the full-hook fixture rather than replacing the real sender with its seam.
- Current installer/resolver and real policy:65 checks, including direct inheritance, malformed cache entries, unrelated methods and typed constructor capture.
- Played dispatcher:51 checks. Receipt observation:824 checks plus 16 resource checks.
- Queue tests use snapshot followed by per-recipe enqueue acknowledgement, not the removed production drain API. 26 routing/capacity checks plus 6,000 concurrent insertions; deterministic 100,000 serial and 100,000 concurrent logical operations test retained capacity/isolation. These are not network messages or independent end-to-end features.

Old fixture compilation failures and the original FAIL evidence are retained. Passing a mutation requires a completed compiling suite and the intended assertion; setup failure is not accepted.

## Acceptance still required

With legitimate contributor APKs and two authorized test accounts, observe both endpoints while testing plain text, images, videos, reconnect/retry, Hide Read, Hide Delivered, Ghost, manual read, Blue on Reply, status and broadcast lists. Test a delayed explicit job and a changed privacy setting before execution. Check that other chat IDs remain hidden and that control traffic still progresses. Repeat exact supported host versions, profiles and Debug/Release independently.

No candidate APK or actual recipient/network observation was performed in this reconciliation. This does not certify every selector, all WhatsApp versions, real Binder delivery, fixes for account logout, or a cause for every pending-message report. Existing metadata/history semantics are not rewritten; stored rows are preserved. Private licensing remains outside these source fixtures.

## Integrated manual-receipt veto and explicit retry (2026-11-10)

The final action-time grant remains immutable. A per-job attempt now coordinates enqueue acknowledgement and a synchronous/delayed local privacy veto. It restores only a copy of the original recipe in the existing bounded pending store, at most once per attempt. Nothing automatically sends the restored recipe. A second explicit action is required. Enqueue exceptions leave the original recipe pending, and repeated/late callbacks do not duplicate or resurrect a consumed retry. The delivery-only path also preserves retry when Hide Read was disabled while the job waited.

`test_manual_receipt_retry.py` compiles the real HideReceipts hook, lifecycle, policies and PendingReadReceiptStore, plus exact extracted BlueOnReply sender/constructor-staging/attempt bodies. Its synthetic constructor explicitly dispatches the registered Xposed constructor callback: reflection alone is not instrumentation. Android, Xposed transport, prefs and host job remain declared JVM boundaries. No WhatsApp messages or network are involved.

The 14 scenarios / 400 checks cover both callback orders, Ghost/delivery/contact vetoes, rejected/ambiguous enqueue, mutated host arrays, disabling Hide Read mid-queue, unrelated automatic recipient/ID probes, duplicate old callbacks, and 20 actual two-thread races between acknowledgement and veto. This is per-attempt coordination, not a guarantee for arbitrary simultaneous user actions or server-side replays. The unchanged pre-retry candidate completes the same 14 with 11 assertion failures. Five independently compiled mutations must complete all 14 and fail behavioral assertions; compilation failures do not count.

Run the normal 101-case matrix first to obtain the pinned DexKit jar, then:

```sh
export WAEX_QA_DEXKIT_JAR=/path/to/verified/dexkit-2.2.0.jar
python3 qa/test_manual_receipt_retry.py --out /tmp/manual-retry --kotlinc /path/to/kotlinc
python3 qa/mutate_manual_receipt_retry.py --out /tmp/manual-retry-mutants --kotlinc /path/to/kotlinc
```

The regular 101-case stanza/manual-consent suite, grant tests/mutations, receipt execution, played receipts, bounded recipient queue and observation tests must remain green. The official-source 101-case negative includes the newly introduced pure grant policy solely to compile the new oracle; it is not an unchanged-whole-release test. The new 14-case baseline is the intact local pre-retry commit 08307f32fdc474807cc5db01b470b0106d6e4176.

The retry does not promise durable storage across process death, delivery confirmation from the server, prevention of repeated host execution after privacy is disabled, status-user-action equivalence, or device-level compatibility. Verify those separately with legitimate candidate APKs and authorized accounts. Existing bounded-store capacity and eviction semantics are unchanged.
