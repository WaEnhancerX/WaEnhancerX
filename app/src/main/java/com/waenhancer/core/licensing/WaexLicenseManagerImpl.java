package com.waenhancer.core.licensing;

import com.waenhancer.api.contracts.WaexLicenseManager;
import com.waenhancer.api.contracts.WaexPreferenceManager;

public class WaexLicenseManagerImpl implements WaexLicenseManager {
    private final WaexPreferenceManager preferenceManager;
    private static final String LICENSE_KEY_PREF = "premium.licenseKey";
    private static final String LICENSE_STATUS_PREF = "premium.isProActivated";

    public WaexLicenseManagerImpl(WaexPreferenceManager preferenceManager) {
        this.preferenceManager = preferenceManager;
    }

    @Override
    public boolean isProActivated() {
        return preferenceManager.getBoolean(LICENSE_STATUS_PREF, false);
    }

    @Override
    public boolean activateLicense(String licenseKey) {
        if (licenseKey != null && !licenseKey.trim().isEmpty()) {
            preferenceManager.putString(LICENSE_KEY_PREF, licenseKey);
            preferenceManager.putBoolean(LICENSE_STATUS_PREF, true);
            return true;
        }
        return false;
    }

    @Override
    public void deactivateLicense() {
        preferenceManager.putString(LICENSE_KEY_PREF, "");
        preferenceManager.putBoolean(LICENSE_STATUS_PREF, false);
    }

    @Override
    public String getLicenseKey() {
        return preferenceManager.getString(LICENSE_KEY_PREF, "");
    }
}
