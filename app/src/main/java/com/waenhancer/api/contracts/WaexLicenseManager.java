package com.waenhancer.api.contracts;

public interface WaexLicenseManager {
    boolean isProActivated();
    boolean activateLicense(String licenseKey);
    void deactivateLicense();
    String getLicenseKey();
}
