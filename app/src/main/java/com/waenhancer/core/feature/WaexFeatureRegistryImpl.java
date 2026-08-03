package com.waenhancer.core.feature;

import com.waenhancer.api.contracts.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class WaexFeatureRegistryImpl implements WaexFeatureRegistry {
    private final Map<String, WaexFeature> features = new ConcurrentHashMap<>();

    @Override
    public void registerFeature(WaexFeature feature) {
        if (feature != null && feature.getMetadata() != null) {
            features.put(feature.getMetadata().getFeatureId(), feature);
        }
    }

    @Override
    public void unregisterFeature(String featureId) {
        if (featureId != null) {
            features.remove(featureId);
        }
    }

    @Override
    public WaexFeature lookupFeature(String featureId) {
        if (featureId == null) {
            return null;
        }
        return features.get(featureId);
    }

    @Override
    public List<WaexFeature> getFeatures() {
        return new ArrayList<>(features.values());
    }

    @Override
    public Map<WaexFeatureCategory, List<WaexFeature>> getFeaturesByCategory() {
        Map<WaexFeatureCategory, List<WaexFeature>> map = new EnumMap<>(WaexFeatureCategory.class);
        for (WaexFeatureCategory category : WaexFeatureCategory.values()) {
            map.put(category, new ArrayList<>());
        }
        for (WaexFeature feature : features.values()) {
            map.get(feature.getMetadata().getCategory()).add(feature);
        }
        return map;
    }

    @Override
    public List<WaexFeature> getFeaturesByState(WaexFeatureState state) {
        List<WaexFeature> result = new ArrayList<>();
        for (WaexFeature feature : features.values()) {
            if (getFeatureStates(feature.getMetadata().getFeatureId()).contains(state)) {
                result.add(feature);
            }
        }
        return result;
    }

    @Override
    public WaexFeatureState getFeatureState(String featureId) {
        WaexFeature feature = lookupFeature(featureId);
        if (feature == null) {
            return WaexFeatureState.DISABLED;
        }
        
        if (feature.getMetadata().isHidden()) {
            return WaexFeatureState.HIDDEN;
        }
        if (feature.getMetadata().isPremium()) {
            return WaexFeatureState.PREMIUM;
        }
        if (feature.getMetadata().isExperimental()) {
            return WaexFeatureState.EXPERIMENTAL;
        }
        if (feature.isEnabled()) {
            return WaexFeatureState.ENABLED;
        }
        return WaexFeatureState.DISABLED;
    }

    @Override
    public Set<WaexFeatureState> getFeatureStates(String featureId) {
        WaexFeature feature = lookupFeature(featureId);
        Set<WaexFeatureState> states = EnumSet.noneOf(WaexFeatureState.class);
        if (feature == null) {
            return states;
        }

        if (feature.getMetadata().isHidden()) {
            states.add(WaexFeatureState.HIDDEN);
        }
        if (feature.getMetadata().isPremium()) {
            states.add(WaexFeatureState.PREMIUM);
        }
        if (feature.getMetadata().isExperimental()) {
            states.add(WaexFeatureState.EXPERIMENTAL);
        }
        if (feature.isEnabled()) {
            states.add(WaexFeatureState.ENABLED);
        } else {
            states.add(WaexFeatureState.DISABLED);
        }
        return states;
    }
}
