package com.waenhancer.xposed.features.media;

/** Android-independent flag policy; acquisition/release accounting stays in PowerManager. */
final class ProximityWakeLockPolicy {
    private static final int LEVEL_MASK = 0x0000ffff;
    private static final int PROXIMITY_SCREEN_OFF = 0x00000020;
    private static final int PARTIAL = 0x00000001;
    // Android documents both flags as incompatible with PARTIAL_WAKE_LOCK.
    private static final int SCREEN_ONLY_FLAGS = 0x30000000;

    private ProximityWakeLockPolicy() {}

    static boolean isProximity(int flags) {
        return (flags & LEVEL_MASK) == PROXIMITY_SCREEN_OFF;
    }

    static int forAcquire(int flags, boolean disabled) {
        return disabled && isProximity(flags) && (flags & SCREEN_ONLY_FLAGS) == 0
                ? (flags & ~LEVEL_MASK) | PARTIAL : flags;
    }

    static int restoreAfterAcquire(int originalFlags, int currentFlags) {
        // Restore only the level we temporarily replaced. AOSP's logging-importance setter
        // can change an unrelated high bit while acquisition is in progress.
        if (!isProximity(originalFlags) || (currentFlags & LEVEL_MASK) != PARTIAL) {
            return currentFlags;
        }
        return (currentFlags & ~LEVEL_MASK) | PROXIMITY_SCREEN_OFF;
    }
}
