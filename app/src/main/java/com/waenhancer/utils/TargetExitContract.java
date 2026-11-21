package com.waenhancer.utils;

/**
 * Pure-data contract for opt-in reports of Android's own-app process-exit history.
 * No exception text, process name, trace, JID, account or arbitrary host strings cross IPC.
 */
public final class TargetExitContract {
    public static final int MAX_EVENTS = 16;
    public static final int MAX_SEEN_KEYS = 64;
    public static final long RETENTION_MS = 7L * 86_400_000L;
    private static final long MAX_MEMORY_KIB = 1L << 34;

    private TargetExitContract() {}

    public static boolean isValid(String target, long now, long consentAt, long exitAt,
                                  int pid, int reason, long pssKiB, long rssKiB) {
        return DeclaredWhatsAppTargets.contains(target)
                && consentAt > 0 && consentAt <= now
                && exitAt >= consentAt && exitAt <= now
                && now - exitAt <= RETENTION_MS
                && pid > 0 && reason >= 0 && reason <= 100
                && pssKiB >= 0 && pssKiB <= MAX_MEMORY_KIB
                && rssKiB >= 0 && rssKiB <= MAX_MEMORY_KIB;
    }

    /** Named constants are from Android's ApplicationExitInfo (API 30+). */
    public static String reasonName(int reason) {
        switch (reason) {
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "JAVA_CRASH";
            case 5: return "NATIVE_CRASH";
            case 6: return "ANR_KILLED";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            case 17: return "MEMORY_LIMITER";
            case 18: return "ANOMALY";
            default: return "UNKNOWN";
        }
    }

    /** A bounded diagnostic-only key; the associated seen-set is private to WAEX's Android user. */
    public static String identity(long exitAt, int pid, int reason, int status) {
        return exitAt + ":" + pid + ":" + reason + ":" + status;
    }
}
