package com.waenhancer.xposed.bridge.client;

/**
 * Strict compile-only stub for the public provider authority. The production
 * source must declare the same constant; qa/test_target_exit_android_api.py
 * checks that independently before compiling the Android adapter.
 * No methods, preference reads, provider calls or permissive behavior are faked.
 */
public final class PreferenceBridgeClient {
    public static final String AUTHORITY = "com.waenhancer.hookprovider";
    private PreferenceBridgeClient() {}
}
