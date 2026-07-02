package com.waenhancer.api.contracts;

import java.util.Set;

public interface WaexVersionProfile {
    ClientType getClientType();
    long getMinVersionCode();
    long getMaxVersionCode();
    Set<Long> getAllowedVersions();
    boolean isExplicitListMode();
}
