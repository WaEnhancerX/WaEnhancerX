package com.waenhancer.compatibility;

import com.waenhancer.api.contracts.WaexClientType;
import com.waenhancer.api.contracts.WaexSupportedVersionConfig;
import com.waenhancer.api.contracts.WaexVersionManager;
import com.waenhancer.api.contracts.WaexVersionProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WaexVersionManagerImpl implements WaexVersionManager {
    private WaexSupportedVersionConfig config;

    public WaexVersionManagerImpl() {
        // Initialize default configuration
        Map<WaexClientType, List<String>> defaults = new HashMap<>();
        defaults.put(WaexClientType.WHATSAPP, Arrays.asList("2.26.24.x", "2.26.25.x"));
        defaults.put(WaexClientType.WHATSAPP_BUSINESS, Arrays.asList("2.26.24.x", "2.26.25.x"));
        
        List<String> overrides = Arrays.asList("allow-future-beta=false");
        
        this.config = new WaexSupportedVersionConfig(defaults, overrides);
    }

    @Override
    public boolean isVersionSupported(WaexClientType clientType, String versionCode) {
        if (config == null || versionCode == null) {
            return false;
        }
        List<String> supported = config.getSupportedVersions().get(clientType);
        if (supported == null) {
            return false;
        }
        for (String pattern : supported) {
            if (pattern.endsWith(".x")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                if (versionCode.startsWith(prefix)) {
                    return true;
                }
            } else if (pattern.equals(versionCode)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public WaexVersionProfile getVersionProfile(WaexClientType clientType) {
        if (config == null) {
            return new WaexVersionProfileImpl(clientType, Collections.emptyList(), Collections.emptyList());
        }
        List<String> versions = config.getSupportedVersions().get(clientType);
        if (versions == null) {
            versions = Collections.emptyList();
        }
        return new WaexVersionProfileImpl(clientType, versions, config.getOverrideRules());
    }

    @Override
    public void updateConfig(WaexSupportedVersionConfig config) {
        this.config = config;
    }
}
