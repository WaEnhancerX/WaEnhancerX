# CallsHub explicit opt-out acceptance

## Defect and narrow fix

The official 2.0.6 screen could change an explicitly stored false flag to true on reopen by combining it with a retained mode and persisting derived state. The candidate makes screen entry read-only, honors explicit false, preserves recognized modes0-4, keys state to targetEpoch and only initializes mode1 on explicit enable from mode0.

Callbacks capture the rendered target epoch and ignore actions from a stale screen after a target change. This is a guard at the UI action boundary, not an atomic transaction across arbitrary preference writers.

## Reproduce

    python3 qa/test_call_privacy_optout.py --out /tmp/waex-call-optout --kotlin-home /path/to/kotlinc
    python3 qa/mutate_call_privacy_optout.py --out /tmp/waex-call-mutants --kotlin-home /path/to/kotlinc

Java17+, a complete Kotlin distribution and Python are required. Direct compiler-JVM invocation avoids shell classpath ambiguity. --source-root selects a baseline. Output paths must be new.

## Results and boundaries

The exact production initialization and both toggle bodies are compiled against explicit Compose state and preference doubles. The whole screen, persistent store and real incoming-call hook are not executed here.

36 scenarios /130 checks pass. Official main completes the same36 with36 failed cases. The five compiled mutations are rejected: stale target actions, explicit-false ignored, unkeyed target mode, writes on entry, custom modes replaced on reenable. Cases include the15 mode/flag combinations, repeated visits, both controls, destination changes, stale callbacks and invalid retained modes.

The official failing APK was observed in Android UI and actual redirected preferences in a previous lab pilot. The complete candidate APK has not yet been built or device-validated. No actual inbound call was placed during that reproduction.

## Explicit limitations

The existing WaexPreferenceManager void setters ignore commit failure and the UI remains optimistic on those writes. This patch does not claim durable acknowledged disablement under disk failure, atomicity of the two enable writes, or synchronization of every open sheet when destinations change. Independent per-contact rules are unchanged. Those are separate follow-up contracts, not hidden coverage.

## Device acceptance

Build using the maintainer's authorized path. Disable and reopen for modes1-4; visit a fresh config repeatedly; explicitly enable from0; switch targets and reject stale callbacks; check invalid restored data without implicit consent. Inspect actual stored values and then test authorized incoming voice/video calls with independent per-contact policies accounted for. Review English/es/es-419 layouts and Debug/Release. Do not infer a complete call-blocking repair from a UI switch or fragment test alone.
