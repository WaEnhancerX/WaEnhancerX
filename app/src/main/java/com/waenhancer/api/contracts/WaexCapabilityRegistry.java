package com.waenhancer.api.contracts;

import java.util.Set;

public interface WaexCapabilityRegistry {
    Set<WaexCapability> getCapabilities(WaexClientType clientType);
}
