package com.waenhancer.api.contracts;

import java.util.Set;

public interface WaexHookAdapter {
    ClientType getSupportedClientType();
    Set<WaexCapability> getCapabilities();
    boolean isCompatible(long versionCode);
    void initializeHooks();
}
