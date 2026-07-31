package com.waenhancer.compatibility;

import com.waenhancer.api.contracts.WaexClientDetector;
import com.waenhancer.api.contracts.WaexClientType;

public final class WaexClientDetectorImpl implements WaexClientDetector {

    @Override
    public WaexClientType detectActiveClient() {
        return WaexClientType.WHATSAPP;
    }

    @Override
    public String getActiveClientPackageName() {
        return "com.whatsapp";
    }
}
