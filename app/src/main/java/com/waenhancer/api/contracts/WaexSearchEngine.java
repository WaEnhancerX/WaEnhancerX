package com.waenhancer.api.contracts;

import java.util.List;

public interface WaexSearchEngine {
    List<WaexFeature> search(String query);
}
