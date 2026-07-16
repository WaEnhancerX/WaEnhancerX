package com.waenhancer.compatibility;

import com.waenhancer.api.contracts.WaexClientType;
import com.waenhancer.api.contracts.WaexSupportedVersionConfig;
import com.waenhancer.api.contracts.WaexVersionManager;
import com.waenhancer.api.contracts.WaexVersionProfile;

public final class WaexVersionManagerImpl implements WaexVersionManager {

    @Override
    public boolean isVersionSupported(WaexClientType clientType, String versionCode) {
        return false;
    }

    @Override
    public WaexVersionProfile getVersionProfile(WaexClientType clientType) {
        return null;
    }

    @Override
    public void updateConfig(WaexSupportedVersionConfig config) {
    }
}
