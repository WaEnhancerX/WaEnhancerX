package com.waenhancer.compatibility;

import com.waenhancer.api.contracts.WaexClientType;
import com.waenhancer.api.contracts.WaexVersionProfile;

import java.util.Collections;
import java.util.List;

public final class WaexVersionProfileImpl implements WaexVersionProfile {

    @Override
    public WaexClientType getClientType() {
        return WaexClientType.UNKNOWN;
    }

    @Override
    public List<String> getSupportedVersions() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getOverrideRules() {
        return Collections.emptyList();
    }
}
