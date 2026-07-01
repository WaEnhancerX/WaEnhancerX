package com.waenhancer.core.search;

import com.waenhancer.api.contracts.WaexFeature;
import com.waenhancer.api.contracts.WaexFeatureRegistry;
import com.waenhancer.api.contracts.WaexSearchEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class WaexSearchEngineImpl implements WaexSearchEngine {
    private final WaexFeatureRegistry featureRegistry;

    public WaexSearchEngineImpl(WaexFeatureRegistry featureRegistry) {
        this.featureRegistry = featureRegistry;
    }

    @Override
    public List<WaexFeature> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>(featureRegistry.getFeatures());
        }
        
        String lowerQuery = query.toLowerCase().trim();
        return featureRegistry.getFeatures().stream()
                .filter(f -> {
                    if (f.getMetadata() == null) return false;
                    String title = f.getMetadata().getTitle() != null ? f.getMetadata().getTitle().toLowerCase() : "";
                    String desc = f.getMetadata().getDescription() != null ? f.getMetadata().getDescription().toLowerCase() : "";
                    String cat = f.getMetadata().getCategory() != null ? f.getMetadata().getCategory().toLowerCase() : "";
                    return title.contains(lowerQuery) || desc.contains(lowerQuery) || cat.contains(lowerQuery);
                })
                .collect(Collectors.toList());
    }
}
