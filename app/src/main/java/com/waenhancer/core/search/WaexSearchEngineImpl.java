package com.waenhancer.core.search;

import com.waenhancer.api.contracts.WaexFeature;
import com.waenhancer.api.contracts.WaexFeatureRegistry;
import com.waenhancer.api.contracts.WaexSearchEngine;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

public final class WaexSearchEngineImpl implements WaexSearchEngine {
    private final WaexFeatureRegistry registry;

    @Inject
    public WaexSearchEngineImpl(WaexFeatureRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<WaexFeature> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return registry.getFeatures();
        }
        String lowerQuery = query.toLowerCase().trim();
        List<WaexFeature> results = new ArrayList<>();
        for (WaexFeature feature : registry.getFeatures()) {
            if (feature.getMetadata().getFeatureId().toLowerCase().contains(lowerQuery) ||
                feature.getMetadata().getTitle().toLowerCase().contains(lowerQuery) ||
                feature.getMetadata().getDescription().toLowerCase().contains(lowerQuery) ||
                feature.getMetadata().getCategory().name().toLowerCase().contains(lowerQuery)) {
                results.add(feature);
            }
        }
        return results;
    }
}
