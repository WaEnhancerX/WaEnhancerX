# Live observation time between invalidation pulses

> Historical acceptance for the removed `RuntimeObservationClock.kt`.
> Current versions refresh through `TargetAppManager` events and PID/boot evidence;
> see [CURRENT_RUNTIME_EVIDENCE_ACCEPTANCE.md](CURRENT_RUNTIME_EVIDENCE_ACCEPTANCE.md).
> Do not rerun these deleted-file test commands against the current revision.

The lifecycle-STARTED pulse still invalidates the display every five seconds, but
each composition obtains current wall/elapsed time rather than reusing a timestamp
from the previous pulse. SystemHealth reads the target reports before that clock.
Future timestamps and the existing heartbeat expiry remain rejected; no window is
widened and no root or package enumeration is added per timer tick.

Run test_runtime_observation_clock.py, test_runtime_diagnostics.py and
verify_stability_framework.py --mode clock. The latter uses real Composition,
Recomposer, frame clock and LifecycleRegistry to test between-pulse recompositions,
periodic invalidation, stop/resume and disposal. Test scheduling/wall time is
controlled; complete dashboard rendering, atomic stored report snapshots, actual
HMA/Binder/Vector and LSPosed acceptance remain open.
