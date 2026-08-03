package com.waenhancer.api.contracts;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface WaexFeatureRegistry {
    void registerFeature(WaexFeature feature);
    void unregisterFeature(String featureId);
    WaexFeature lookupFeature(String featureId);
    List<WaexFeature> getFeatures();
    
    // Updated category mapping
    Map<WaexFeatureCategory, List<WaexFeature>> getFeaturesByCategory();
    
    // State query & filtering methods
    List<WaexFeature> getFeaturesByState(WaexFeatureState state);
    WaexFeatureState getFeatureState(String featureId);
    Set<WaexFeatureState> getFeatureStates(String featureId);
}
