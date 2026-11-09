# Startup hook-report batching: acceptance and boundaries

## Contract

`MainHook` publishes the complete hook catalogue after `initializeAll()` returns. Startup entries must not serialize/transmit that same complete catalogue once per feature. Deferred installations must still publish their new ACTIVE/FAILED state immediately. Failures remain visible; disabled/waiting states, hook execution order, Dex leases and licensing decisions are unchanged.

This intentionally removes intermediate per-startup-entry snapshots. During startup, the runtime health remains INITIALIZING; the terminal catalogue and READY/READY_WITH_ERRORS reports remain the authority. This is not permission to mark disabled or failed features ACTIVE.

## Automated checks

```
python3 qa/test_startup_hook_reporting.py --out /tmp/waex-startup-report
python3 qa/mutate_startup_hook_reporting.py --out /tmp/waex-startup-report-mutants
```

Requires a JDK supporting `--release 17`. Use `--source-root` on the primary runner to compare the unchanged official source. Outputs must be new directories. Tests compile eight exact source blocks from FeatureRegistry, including initialization, installation, failure handling and reporting. MainHook's terminal publication is independently required by a source guard.

The fixture models Android IPC/JSON, the lifecycle scheduler, feature bodies and Dex leasing explicitly. It counts serialized catalogue rows and provider calls; it does not emulate Binder latency or execute native Xposed. The complete FeatureRegistry constructor and private policy/licensing dependencies are not compiled or replaced by this test.

Ten scenarios verify one terminal startup report at 1/8/28/65 enabled entries, complete error/status snapshots, disabled and waiting entries, successful and failed lazy updates, provider-failure isolation, duplicate lazy installation and balanced Dex leases. Four mutants restore redundant reporting, drop deferred reports, mislabel failures or falsify snapshot states; compiler failures do not qualify as detected mutants.

## Device acceptance still required

1. Build authorized baseline/candidate APKs with identical signing/configuration and dependency versions.
2. Reproduce cold launches on the same WhatsApp build, account, Android profile and framework; preserve data and scope.
3. Record initial draw and runtime READY separately. A timeout from `am start -W` must not be called a completed startup.
4. Compare repeated launches in balanced order, without concurrent stress tasks. Trace/count actual register_injected_hooks calls and attribute time before claiming a speed gain.
5. Verify the final complete catalogue includes successful, failed, disabled and waiting features accurately.
6. Enter a deferred-feature screen, exercise that feature, and verify its report still updates. Repeat with an induced legitimate feature failure.
7. Restore the original scope/settings after each experiment. Use only authorized recipients for functional tests.

The November 9 laboratory scope-on/off experiment establishes overhead associated with WAEX in that environment; it does not measure this unbuilt candidate or prove that reporting accounts for all startup delay. Full APK, Linux CI and device acceptance remain separate checks.
