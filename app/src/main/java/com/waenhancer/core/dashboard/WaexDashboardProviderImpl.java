package com.waenhancer.core.dashboard;

import com.waenhancer.api.contracts.WaexDashboardProvider;
import com.waenhancer.api.contracts.WaexFeature;
import com.waenhancer.api.contracts.WaexFeatureRegistry;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class WaexDashboardProviderImpl implements WaexDashboardProvider {
    private final WaexFeatureRegistry featureRegistry;

    public WaexDashboardProviderImpl(WaexFeatureRegistry featureRegistry) {
        this.featureRegistry = featureRegistry;
    }

    @Override
    public List<WaexFeature> getVisibleFeatures() {
        return featureRegistry.getFeatures().stream()
                .filter(f -> f.getMetadata() != null && "VISIBLE".equalsIgnoreCase(f.getMetadata().getVisibility()))
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, List<WaexFeature>> getFeaturesByCategory() {
        return getVisibleFeatures().stream()
                .collect(Collectors.groupingBy(f -> f.getMetadata().getCategory()));
    }
}
