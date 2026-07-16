package com.waenhancer.api.contracts;

public interface WaexClientDetector {
    WaexClientType detectActiveClient();
    String getActiveClientPackageName();
}
