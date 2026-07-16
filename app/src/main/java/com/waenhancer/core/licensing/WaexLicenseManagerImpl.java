package com.waenhancer.core.licensing;

import com.waenhancer.api.contracts.WaexLicenseManager;

public final class WaexLicenseManagerImpl implements WaexLicenseManager {

    @Override
    public boolean isProActivated() {
        return false;
    }

    @Override
    public String getLicenseKey() {
        return "";
    }

    @Override
    public boolean activateLicense(String key) {
        return false;
    }

    @Override
    public void deactivateLicense() {
    }
}
