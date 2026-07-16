package com.waenhancer.api.contracts;

import java.util.List;

public interface WaexVersionProfile {
    WaexClientType getClientType();
    List<String> getSupportedVersions();
    List<String> getOverrideRules();
}
