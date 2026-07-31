package com.waenhancer.core.licensing;

import com.waenhancer.api.contracts.WaexLicenseManager;

public final class WaexLicenseManagerImpl implements WaexLicenseManager {
    private boolean activated = false;
    private String licenseKey = "";

    @Override
    public boolean isProActivated() {
        return activated;
    }

    @Override
    public String getLicenseKey() {
        return licenseKey;
    }

    @Override
    public boolean activateLicense(String key) {
        if (key != null && !key.trim().isEmpty()) {
            this.activated = true;
            this.licenseKey = key;
            return true;
        }
        return false;
    }

    @Override
    public void deactivateLicense() {
        this.activated = false;
        this.licenseKey = "";
    }
}
