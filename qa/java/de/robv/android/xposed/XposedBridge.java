package de.robv.android.xposed;

/** Compile-only test double; never installed or used as a runtime framework. */
public final class XposedBridge {
    private XposedBridge() { }
    public static void log(String value) {
        System.err.println(value);
    }
}
