package com.waenhancer.api.contracts;

import java.util.List;
import java.util.Map;

public interface WaexFeatureRegistry {
    void registerFeature(WaexFeature feature);
    void unregisterFeature(String featureId);
    WaexFeature lookupFeature(String featureId);
    List<WaexFeature> getFeatures();
    Map<String, List<WaexFeature>> getFeaturesByCategory();
}
