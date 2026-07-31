package com.waenhancer.compatibility;

import com.waenhancer.api.contracts.WaexClientType;
import com.waenhancer.api.contracts.WaexVersionProfile;

import java.util.List;

public final class WaexVersionProfileImpl implements WaexVersionProfile {
    private final WaexClientType clientType;
    private final List<String> supportedVersions;
    private final List<String> overrideRules;

    public WaexVersionProfileImpl(
            WaexClientType clientType,
            List<String> supportedVersions,
            List<String> overrideRules) {
        this.clientType = clientType;
        this.supportedVersions = supportedVersions;
        this.overrideRules = overrideRules;
    }

    @Override
    public WaexClientType getClientType() {
        return clientType;
    }

    @Override
    public List<String> getSupportedVersions() {
        return supportedVersions;
    }

    @Override
    public List<String> getOverrideRules() {
        return overrideRules;
    }
}
