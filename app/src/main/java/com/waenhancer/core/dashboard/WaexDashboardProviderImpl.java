package com.waenhancer.core.dashboard;

import com.waenhancer.api.contracts.WaexDashboardProvider;
import com.waenhancer.api.contracts.WaexFeature;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class WaexDashboardProviderImpl implements WaexDashboardProvider {

    @Override
    public List<WaexFeature> getVisibleFeatures() {
        return Collections.emptyList();
    }

    @Override
    public Map<String, List<WaexFeature>> getFeaturesByCategory() {
        return Collections.emptyMap();
    }
}
