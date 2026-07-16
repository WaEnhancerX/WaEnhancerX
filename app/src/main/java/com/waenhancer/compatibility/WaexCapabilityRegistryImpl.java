package com.waenhancer.compatibility;

import com.waenhancer.api.contracts.WaexCapability;
import com.waenhancer.api.contracts.WaexCapabilityRegistry;
import com.waenhancer.api.contracts.WaexClientType;

import java.util.Collections;
import java.util.Set;

public final class WaexCapabilityRegistryImpl implements WaexCapabilityRegistry {

    @Override
    public Set<WaexCapability> getCapabilities(WaexClientType clientType) {
        return Collections.emptySet();
    }
}
