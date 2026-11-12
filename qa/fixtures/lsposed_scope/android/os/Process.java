package android.os;

public final class Process {
    private static int uid = 10191;

    private Process() {}

    public static int myUid() {
        return uid;
    }

    public static void setUidForTest(int nextUid) {
        uid = nextUid;
    }
}
