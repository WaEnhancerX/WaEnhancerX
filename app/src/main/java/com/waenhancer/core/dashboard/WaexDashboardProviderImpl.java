package com.waenhancer.core.dashboard;

import com.waenhancer.api.contracts.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;

public final class WaexDashboardProviderImpl implements WaexDashboardProvider {
    private final WaexFeatureRegistry registry;

    @Inject
    public WaexDashboardProviderImpl(WaexFeatureRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<WaexFeature> getVisibleFeatures() {
        List<WaexFeature> visible = new ArrayList<>();
        for (WaexFeature feature : registry.getFeatures()) {
            if (!feature.getMetadata().isHidden()) {
                visible.add(feature);
            }
        }
        return visible;
    }

    @Override
    public Map<WaexFeatureCategory, List<WaexFeature>> getFeaturesByCategory() {
        return registry.getFeaturesByCategory();
    }
}
