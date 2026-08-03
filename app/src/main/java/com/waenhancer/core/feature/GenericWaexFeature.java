package com.waenhancer.core.feature;

import com.waenhancer.api.contracts.WaexFeature;
import com.waenhancer.api.contracts.WaexFeatureMetadata;
import com.waenhancer.api.contracts.WaexPreferenceManager;

public class GenericWaexFeature implements WaexFeature {
    private final WaexFeatureMetadata metadata;
    private final WaexPreferenceManager preferenceManager;

    public GenericWaexFeature(WaexFeatureMetadata metadata, WaexPreferenceManager preferenceManager) {
        this.metadata = metadata;
        this.preferenceManager = preferenceManager;
    }

    @Override
    public WaexFeatureMetadata getMetadata() {
        return metadata;
    }

    @Override
    public boolean isEnabled() {
        return preferenceManager.getBoolean(metadata.getFeatureId(), false);
    }

    @Override
    public void setEnabled(boolean enabled) {
        preferenceManager.putBoolean(metadata.getFeatureId(), enabled);
    }
}
