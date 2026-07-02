package com.waenhancer.api.contracts;

public interface WaexClientDetector {
    ClientType detectActiveClient();
    String getActiveClientPackageName();
}
