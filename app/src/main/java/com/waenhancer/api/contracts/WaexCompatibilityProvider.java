package com.waenhancer.api.contracts;

public interface WaexCompatibilityProvider {
    ClientType getActiveClient();
    boolean isFeatureAvailable(String featureId);
    boolean hasCapability(WaexCapability capability);
    WaexHookAdapter getActiveAdapter();
}
