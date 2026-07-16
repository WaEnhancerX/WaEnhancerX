package com.waenhancer.api.contracts;

public interface WaexVersionManager {
    boolean isVersionSupported(WaexClientType clientType, String versionCode);
    WaexVersionProfile getVersionProfile(WaexClientType clientType);
    void updateConfig(WaexSupportedVersionConfig config);
}
