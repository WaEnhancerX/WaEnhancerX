package com.waenhancer.core.loader;

import com.waenhancer.api.contracts.WaexFeature;
import com.waenhancer.api.contracts.WaexFeatureLoader;
import com.waenhancer.api.contracts.WaexFeatureRegistry;

import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class WaexFeatureLoaderImpl implements WaexFeatureLoader {
    private final Set<WaexFeature> features;

    @Inject
    public WaexFeatureLoaderImpl(Set<WaexFeature> features) {
        this.features = features;
    }

    @Override
    public void loadFeatures(WaexFeatureRegistry registry) {
        for (WaexFeature feature : features) {
            registry.registerFeature(feature);
        }
    }
}
