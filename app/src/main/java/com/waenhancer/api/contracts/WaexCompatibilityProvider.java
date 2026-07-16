package com.waenhancer.api.contracts;

public interface WaexCompatibilityProvider {
    WaexClientType getActiveClient();
    boolean isCompatible();
}
