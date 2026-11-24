package com.waenhancer.xposed.features.media;

/** Pure Java policy checks. Does not represent Android/Xposed lifecycle execution. */
public final class TestProximityWakeLockPolicy {
    private static void equal(int expected, int actual, String behavior) {
        if (expected != actual) throw new AssertionError(behavior + ": expected 0x"
                + Integer.toHexString(expected) + ", got 0x" + Integer.toHexString(actual));
    }

    public static void main(String[] args) {
        equal(0x20, ProximityWakeLockPolicy.forAcquire(0x20, false), "disabled option preserves proximity");
        equal(0x01, ProximityWakeLockPolicy.forAcquire(0x20, true), "enabled proximity acquires CPU-only lock");
        equal(0x40000001, ProximityWakeLockPolicy.forAcquire(0x40000020, true), "retain logging-importance flag");
        equal(0x80000001, ProximityWakeLockPolicy.forAcquire(0x80000020, true), "retain unrelated sign-bit flag");
        equal(0xc0000020, ProximityWakeLockPolicy.forAcquire(0xc0000020, false), "disabled preserves all flags");

        for (int level : new int[]{0x00, 0x01, 0x06, 0x0a, 0x1a, 0x40, 0x80, 0x21, 0x120, 0xffff}) {
            equal(level, ProximityWakeLockPolicy.forAcquire(level, true), "leave other/unknown level unchanged");
            equal(0x40000000 | level, ProximityWakeLockPolicy.forAcquire(0x40000000 | level, true),
                    "leave other level and flags unchanged");
        }
        equal(0x10000020, ProximityWakeLockPolicy.forAcquire(0x10000020, true),
                "do not introduce PARTIAL plus ACQUIRE_CAUSES_WAKEUP");
        equal(0x20000020, ProximityWakeLockPolicy.forAcquire(0x20000020, true),
                "do not introduce PARTIAL plus ON_AFTER_RELEASE");
        equal(0x70000020, ProximityWakeLockPolicy.forAcquire(0x70000020, true),
                "unsupported screen flags stay unchanged together with other flags");

        equal(0x20, ProximityWakeLockPolicy.restoreAfterAcquire(0x20, 0x01), "restore original proximity level");
        equal(0x40000020, ProximityWakeLockPolicy.restoreAfterAcquire(0x20, 0x40000001),
                "retain logging-importance added during acquire");
        equal(0x20, ProximityWakeLockPolicy.restoreAfterAcquire(0x40000020, 0x01),
                "retain logging-importance removal during acquire");
        equal(0x80000020, ProximityWakeLockPolicy.restoreAfterAcquire(0x40000020, 0x80000001),
                "restore only owned low bits");
        equal(0x0a, ProximityWakeLockPolicy.restoreAfterAcquire(0x20, 0x0a),
                "do not overwrite a different level installed by another interceptor");
        equal(0x01, ProximityWakeLockPolicy.restoreAfterAcquire(0x06, 0x01),
                "do not restore an unrelated original level");

        int reused = 0x40000020; // Object already existed while the feature was disabled.
        equal(0x40000020, ProximityWakeLockPolicy.forAcquire(reused, false), "existing object before enable");
        reused = ProximityWakeLockPolicy.restoreAfterAcquire(reused,
                ProximityWakeLockPolicy.forAcquire(reused, true));
        equal(0x40000020, reused, "enabled acquisition does not permanently change existing object");
        equal(0x40000020, ProximityWakeLockPolicy.forAcquire(reused, false), "same object after disable");
        System.out.println("PASS: proximity-only conversion; other levels/flags preserved; invalid PARTIAL combinations refused; restoration and per-acquisition toggle policy");
    }
}
