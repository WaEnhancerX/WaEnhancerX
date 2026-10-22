package com.waenhancer.utils;

/** Android profile identity for commands launched from the manager's own Context. */
public final class AndroidUserScope {
    // AOSP defines app UIDs as 100000 * userId + appId, including work profiles.
    // Do not use the root shell's UID, the foreground user, or a user's serial number.
    private static final int PER_USER_RANGE = 100_000;

    private AndroidUserScope() {}

    public static int userIdForUid(int applicationUid) {
        if (applicationUid < 0) throw new IllegalArgumentException("Invalid application UID");
        return applicationUid / PER_USER_RANGE;
    }

    public static int matchingUserId(int contextUid, int processUid) {
        int userId = userIdForUid(contextUid);
        if (userId != userIdForUid(processUid)) {
            throw new IllegalArgumentException("Context belongs to a different Android user");
        }
        return userId;
    }
}
