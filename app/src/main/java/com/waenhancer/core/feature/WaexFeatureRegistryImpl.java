package com.waenhancer.core.feature;

import com.waenhancer.api.contracts.WaexFeature;
import com.waenhancer.api.contracts.WaexFeatureRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class WaexFeatureRegistryImpl implements WaexFeatureRegistry {
    private final Map<String, WaexFeature> featuresMap = new ConcurrentHashMap<>();

    @Override
    public void registerFeature(WaexFeature feature) {
        if (feature != null && feature.getMetadata() != null) {
            featuresMap.put(feature.getMetadata().getId(), feature);
        }
    }

    @Override
    public void unregisterFeature(String featureId) {
        if (featureId != null) {
            featuresMap.remove(featureId);
        }
    }

    @Override
    public WaexFeature lookupFeature(String featureId) {
        if (featureId == null) {
            return null;
        }
        return featuresMap.get(featureId);
    }

    @Override
    public List<WaexFeature> getFeatures() {
        return new ArrayList<>(featuresMap.values());
    }

    @Override
    public Map<String, List<WaexFeature>> getFeaturesByCategory() {
        return featuresMap.values().stream()
                .collect(Collectors.groupingBy(f -> f.getMetadata().getCategory()));
    }
}
