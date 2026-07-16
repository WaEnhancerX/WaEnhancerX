package com.waenhancer.compatibility;

import com.waenhancer.api.contracts.WaexClientType;
import com.waenhancer.api.contracts.WaexCompatibilityProvider;

public final class WaexCompatibilityProviderImpl implements WaexCompatibilityProvider {

    @Override
    public WaexClientType getActiveClient() {
        return WaexClientType.UNKNOWN;
    }

    @Override
    public boolean isCompatible() {
        return false;
    }
}
