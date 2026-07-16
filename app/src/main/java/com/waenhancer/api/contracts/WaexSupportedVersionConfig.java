package com.waenhancer.api.contracts;

import java.util.List;
import java.util.Map;

public final class WaexSupportedVersionConfig {
    private final Map<WaexClientType, List<String>> supportedVersions;
    private final List<String> overrideRules;

    public WaexSupportedVersionConfig(
            Map<WaexClientType, List<String>> supportedVersions,
            List<String> overrideRules) {
        this.supportedVersions = supportedVersions;
        this.overrideRules = overrideRules;
    }

    public Map<WaexClientType, List<String>> getSupportedVersions() {
        return supportedVersions;
    }

    public List<String> getOverrideRules() {
        return overrideRules;
    }
}
