package com.waenhancer.api.contracts;

public interface WaexFeature {
    WaexFeatureMetadata getMetadata();
    boolean isEnabled();
    void setEnabled(boolean enabled);
}
