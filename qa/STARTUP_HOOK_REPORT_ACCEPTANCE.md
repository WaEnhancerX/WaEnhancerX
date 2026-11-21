# Startup catalogue reporting: bounded work and truthful transitions

## Contract

MainHook publishes the terminal catalogue after initializeAll. STARTUP entries do not publish a full catalogue individually. This removes intermediate whole-catalogue snapshots, not hooks or version/licensing checks.

Lazy entries may install inside initializeAll when an Activity is already present, or after a later lifecycle event. Both must publish their changes. A retry that succeeds clears its former error. A WAITING lazy entry disabled before installation publishes DISABLED once; an unchanged disabled entry does not create repeated reports.

## Reproduce

    python3 qa/test_startup_hook_reporting.py --out /tmp/waex-startup-report
    python3 qa/mutate_startup_hook_reporting.py --out /tmp/waex-startup-report-mutants

Java 17+ and Python are required. Output directories must be new. The first runner accepts --source-root for a baseline checkout.

## Verification boundaries

The runner extracts eight exact production blocks. IPC, JSON serialization, feature implementations, Dex leases and lifecycle events are explicit counting doubles. It is not a complete registry build, native Xposed run or Binder latency benchmark.

The source guard ignores comments and string/character literals; two negative guard cases reject a commented or quoted terminal sequence. The real MainHook source SHA256 is recorded. This is still a structural call-site guard, not execution of MainHook's entire startup path.

The fixture separates publication attempts from accepted snapshots. Provider failure must leave no accepted snapshot. It also covers Activity-present early lazy installation rather than forcing ActivityTracker to return null in every case.

## Results

13 scenarios / 241 reached checks pass on the reviewed candidate. The unchanged main completes 13 scenarios with eight failed cases. Six compiled mutations are rejected: redundant startup publication, missing lazy publication, failed hooks marked active, all states marked active, stale retry errors and missing lazy opt-out publication.

For an all-STARTUP catalogue of N enabled entries, attempts fall from N+1 to one. An early lazy installation can add its own report before the terminal report; do not claim exactly one IPC for every possible configuration.

## Tradeoffs and pending acceptance

If initialization exits exceptionally before the terminal call or the terminal transport fails, there may be no startup catalogue from this attempt. No eventual delivery/retry guarantee is introduced. The earlier intermediate snapshots are intentionally removed. Later reinstallation of STARTUP entries is not an established production path in the inspected source; changing that invariant requires revisiting batching.

Official APK ON/OFF measurements demonstrate an injection-associated startup difference only. They do not measure a candidate speedup. Repeat a balanced launch experiment with legitimate matching baseline/candidate APKs, record Displayed and READY separately, count actual provider calls, and test lazy success/failure/opt-out before claiming runtime improvement.
