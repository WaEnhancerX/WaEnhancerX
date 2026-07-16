package com.waenhancer.core.feature;

import com.waenhancer.api.contracts.WaexFeature;
import com.waenhancer.api.contracts.WaexFeatureRegistry;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class WaexFeatureRegistryImpl implements WaexFeatureRegistry {

    @Override
    public void registerFeature(WaexFeature feature) {
    }

    @Override
    public void unregisterFeature(String featureId) {
    }

    @Override
    public WaexFeature lookupFeature(String featureId) {
        return null;
    }

    @Override
    public List<WaexFeature> getFeatures() {
        return Collections.emptyList();
    }

    @Override
    public Map<String, List<WaexFeature>> getFeaturesByCategory() {
        return Collections.emptyMap();
    }
}
