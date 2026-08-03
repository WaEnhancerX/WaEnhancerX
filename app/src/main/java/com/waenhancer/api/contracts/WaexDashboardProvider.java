package com.waenhancer.api.contracts;

import java.util.List;
import java.util.Map;

public interface WaexDashboardProvider {
    List<WaexFeature> getVisibleFeatures();
    Map<WaexFeatureCategory, List<WaexFeature>> getFeaturesByCategory();
}
